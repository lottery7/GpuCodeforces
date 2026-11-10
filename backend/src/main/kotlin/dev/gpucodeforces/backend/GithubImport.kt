package dev.gpucodeforces.backend

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class ProblemManifest(
    val code: String,
    val title: String,
    val difficulty: String,
    val tags: List<String?>,
    val statement: String,
    val starter: String,
    val generator: String,
    val referenceSolution: String,
    val testCount: Int,
    val timeLimitSeconds: Double,
    val memoryLimitMb: Int,
    val referenceTimeMs: Double,
    val environment: String,
    val environmentId: String = "default",
    val absoluteTolerance: Double = 1e-6,
    val relativeTolerance: Double = 1e-6,
)

class ImportRejected(message: String) : RuntimeException(message)
data class RepositoryIndex(val tasks: List<IndexedTask?>)
data class IndexedTask(val id: String, val manifest: String)

@Component
class GithubApi(
    private val platform: PlatformConfig,
    private val json: JsonMapper,
    private val db: Database,
    private val live: RuntimeSettings,
) {
    private val config = platform.github
    private val http =
        HttpClient.newBuilder().connectTimeout(config.httpTimeout).followRedirects(HttpClient.Redirect.NEVER).build()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val key by lazy {
        val pem = Files.readString(Path.of(config.privateKeyFile))
        require(pem.startsWith("-----BEGIN PRIVATE KEY-----")) { "GitHub key must be unencrypted PKCS#8 PEM" }
        val encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }

    fun appToken(): String {
        val now = Instant.now().epochSecond
        val header = encoder.encodeToString(json.writeValueAsBytes(mapOf("alg" to "RS256", "typ" to "JWT")))
        val payload = encoder.encodeToString(
            json.writeValueAsBytes(
                mapOf(
                    "iat" to now - 60,
                    "exp" to now + 540,
                    "iss" to config.appId
                )
            )
        )
        val data = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(key); update(data.toByteArray()); sign() }
        return "$data.${encoder.encodeToString(signature)}"
    }

    fun installationToken(id: Long): String =
        request("/app/installations/$id/access_tokens", appToken(), "POST").path("token").asString().also {
            if (it.isBlank()) throw ImportRejected("GitHub did not return an installation token")
        }

    @Synchronized
    fun request(path: String, token: String, method: String = "GET"): JsonNode {
        val config = live.current().values.github(platform.github)
        val now = Instant.now()
        val allowed = db.update(
            """
                UPDATE github_budget SET used = CASE WHEN window_start <= now() - interval '1 minute' THEN 1 ELSE used +
                1 END,
                window_start = CASE WHEN window_start <= now() - interval '1 minute' THEN now() ELSE window_start END
                WHERE singleton AND blocked_until <= now() AND (window_start <= now() - interval '1 minute' OR used < ?)
            """.trimIndent(),
            config.maxRequestsPerMinute,
        )
        if (allowed == 0) throw ImportRejected("GitHub request budget or cooldown exceeded")
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains(".."))
        val request = HttpRequest.newBuilder(URI("https://api.github.com$path")).timeout(config.httpTimeout)
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .method(
                method,
                if (method == "POST") HttpRequest.BodyPublishers.ofString("{}") else HttpRequest.BodyPublishers.noBody()
            )
            .build()
        val response = boundedHttp(http, request, config.maxApiResponseBytes, config.httpTimeout)
        if (response.statusCode() in setOf(403, 429)) {
            val retry =
                response.headers().firstValue("Retry-After").orElse("60").toLongOrNull()?.coerceIn(60, 3600) ?: 60
            val reset = response.headers().firstValue("X-RateLimit-Reset").orElse("").toLongOrNull()
                ?.let(Instant::ofEpochSecond)
            val blockedUntil = maxOf(now.plusSeconds(retry), reset ?: now).coerceAtMost(now.plusSeconds(3600))
            db.update(
                """
                    UPDATE github_budget SET blocked_until = greatest(blocked_until, ?) WHERE singleton
                """.trimIndent(), Timestamp.from(blockedUntil)
            )
        }
        if (response.statusCode() !in 200..299) throw ImportRejected("GitHub HTTP ${response.statusCode()}")
        return json.readTree(response.body())
    }

    fun list(path: String, token: String, max: Int, field: String? = null): List<JsonNode> {
        val result = mutableListOf<JsonNode>()
        var page = 1
        val count = minOf(100, max + 1)
        do {
            val response = request("$path?per_page=$count&page=$page", token)
            val items = if (field == null) response else response.path(field)
            if (!items.isArray) throw ImportRejected("Invalid GitHub list response")
            result += items.toList()
            if (result.size > max) throw ImportRejected("GitHub installation/repository limit exceeded")
            if (items.size() < count) return result
            page++
        } while (true)
    }
}

@Component
class PackageReader(private val platform: PlatformConfig, private val json: JsonMapper) {
    private val config = platform.github
    fun read(tree: JsonNode, blob: (String) -> JsonNode): Map<String, ProblemPackage> {
        if (tree.path("truncated").asBoolean() || !tree.path("tree").isArray || tree.path("tree")
                .size() > config.maxTreeEntries
        ) throw ImportRejected("Repository tree exceeds limit")
        val entries = tree.path("tree").toList()
        var total = 0L
        entries.filter { it.path("type").asString() == "blob" }.forEach {
            val size = it.path("size").asLong(-1)
            if (size < 0 || size > config.maxRepositoryBytes - total) {
                throw ImportRejected("Repository exceeds size limit")
            }
            total += size
        }
        val byPath = entries.associateBy { it.path("path").asString() }
        var packageBytes = 0
        var importedBytes = 0
        val cache = mutableMapOf<String, String>()
        fun file(path: String): String {
            if (path.length > 200 ||
                !path.matches(Regex("[A-Za-z0-9_./-]+")) ||
                path.startsWith('/') ||
                path.split('/').any { it.isEmpty() || it == "." || it == ".." }
            ) throw ImportRejected("Invalid package file path")
            val entry = byPath[path] ?: throw ImportRejected("Package file is missing: $path")
            if (entry.path("type").asString() != "blob" ||
                entry.path("mode").asString() !in setOf("100644", "100755")
            ) throw ImportRejected("Package files must be regular files")
            val size = entry.path("size").asInt(-1)
            if (size !in 0..config.maxFileBytes ||
                packageBytes.toLong() + size > config.maxPackageBytes
            ) throw ImportRejected("Package file exceeds size limit")
            if (importedBytes.toLong() + size > config.maxImportedBytesPerRepository) {
                throw ImportRejected("Repository import exceeds size limit")
            }
            packageBytes += size
            importedBytes += size
            cache[path]?.let { return it }
            val sha = entry.path("sha").asString()
            if (!sha.matches(Regex("[0-9a-f]{40}"))) throw ImportRejected("Invalid blob SHA")
            val response = blob(sha)
            if (response.path("encoding").asString() != "base64" ||
                response.path("size").asInt(-1) != size
            ) throw ImportRejected("Invalid GitHub blob")
            val bytes = try {
                Base64.getMimeDecoder().decode(response.path("content").asString())
            } catch (_: IllegalArgumentException) {
                throw ImportRejected("Invalid blob encoding")
            }
            if (bytes.size != size) throw ImportRejected("Blob size mismatch")
            val actualSha = MessageDigest.getInstance("SHA-1").run {
                update("blob ${bytes.size}\u0000".toByteArray()); digest(bytes).joinToString("") { "%02x".format(it) }
            }
            if (actualSha != sha) throw ImportRejected("Blob hash mismatch")
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw ImportRejected("Package files must use UTF-8")
            }
            cache[path] = text
            return text
        }

        val index = try {
            json.readValue(file("index.json"), RepositoryIndex::class.java)
        } catch (error: JacksonException) {
            throw ImportRejected("Invalid index.json: ${error.javaClass.simpleName}")
        }
        val tasks = index.tasks.map { it ?: throw ImportRejected("Null task in index.json") }
        if (tasks.size > config.maxTasksPerRepository ||
            tasks.map { it.id }.distinct().size != tasks.size ||
            tasks.any { !it.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        ) throw ImportRejected("Invalid task keys or task count in index.json")
        return tasks.associate { task ->
            packageBytes = 0
            val manifest = try {
                json.readValue(file(task.manifest), ProblemManifest::class.java)
            } catch (error: JacksonException) {
                throw ImportRejected("Invalid manifest for ${task.id}: ${error.javaClass.simpleName}")
            }
            task.id to readProblem(manifest, ::file)
        }
    }

    private fun readProblem(manifest: ProblemManifest, file: (String) -> String): ProblemPackage {
        if (manifest.code.isBlank() ||
            manifest.code.length > 32 ||
            manifest.title.isBlank() ||
            manifest.title.length > 200 ||
            manifest.difficulty !in setOf("easy", "medium", "hard") ||
            manifest.tags.size > 20 ||
            manifest.tags.any { it == null || it.isBlank() || it.length > 64 }
        ) throw ImportRejected("Invalid problem metadata")
        if (manifest.testCount !in 1..10000 ||
            !manifest.timeLimitSeconds.isFinite() ||
            manifest.timeLimitSeconds !in 0.001..600.0 ||
            manifest.memoryLimitMb !in 1..1048576 ||
            !manifest.referenceTimeMs.isFinite() ||
            manifest.referenceTimeMs !in 1e-9..1e12
        ) throw ImportRejected("Invalid test limits")
        if (!manifest.environmentId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
            !manifest.absoluteTolerance.isFinite() ||
            manifest.absoluteTolerance < 0 ||
            !manifest.relativeTolerance.isFinite() ||
            manifest.relativeTolerance < 0
        ) throw ImportRejected("Invalid environment or tolerances")
        return ProblemPackage(
            manifest.code,
            manifest.title,
            manifest.difficulty,
            manifest.tags.map { it ?: throw ImportRejected("Null task tag") }.distinct(),
            file(manifest.statement),
            file(manifest.starter),
            file(manifest.generator),
            file(manifest.referenceSolution),
            manifest.testCount,
            manifest.timeLimitSeconds,
            manifest.memoryLimitMb,
            manifest.referenceTimeMs,
            file(manifest.environment),
            manifest.environmentId,
            manifest.absoluteTolerance,
            manifest.relativeTolerance,
        )
    }
}

@Component
class GithubMonitor(
    private val db: Database,
    private val platform: PlatformConfig,
    private val api: GithubApi,
    private val reader: PackageReader,
    private val live: RuntimeSettings,
) {
    private val config = platform.github
    private val owner = UUID.randomUUID()
    private val log = LoggerFactory.getLogger(GithubMonitor::class.java)

    init {
        require(
            config.maxInstallations in 1..100 &&
                config.maxRepositories in 1..10000 &&
                config.maxRepositoriesPerInstallation in 1..1000
        )
        require(config.maxVersionsPerRepository > 0 && config.maxApiResponseBytes in 1024..16777216)
        require(config.maxTasksPerRepository in 1..1000)
        require(config.maxStoredTasksPerRepository >= config.maxTasksPerRepository)
        require(config.maxImportedBytesPerRepository > 0 && config.maxStoredBytesPerRepository > 0)
        require(
            config.maxFileBytes in 1..config.maxApiResponseBytes &&
                config.maxPackageBytes > 0 &&
                config.maxTreeEntries > 0
        )
        require(
            config.repositoryInterval.seconds >= 10 &&
                config.discoveryInterval.seconds >= 10 &&
                config.maxRequestsPerMinute > 0
        )
    }

    @Scheduled(fixedDelay = 1000)
    fun tick() {
        val config = live.current().values.github(platform.github)
        if (config.appId.isBlank()) return
        val acquired = db.update(
            """
                UPDATE platform_lease SET owner = ?, expires_at = now() + interval '10 minutes'
                WHERE name = 'github' AND (expires_at < now() OR owner = ?)
            """.trimIndent(),
            owner, owner,
        ) > 0
        if (!acquired) return
        try {
            val discoveryDue = db.update(
                "UPDATE platform_lease SET next_discovery_at = ? WHERE name = 'github' AND next_discovery_at <= now()",
                Timestamp.from(Instant.now().plus(config.discoveryInterval)),
            ) > 0
            if (discoveryDue) {
                discover()
            }
            val source = db.row(
                """
                    SELECT * FROM source_repository
                    WHERE enabled AND coalesce(checked_at, '-infinity'::timestamptz) < ?
                    ORDER BY coalesce(checked_at, '-infinity'::timestamptz), id LIMIT 1
                """.trimIndent(),
                Timestamp.from(Instant.now().minus(config.repositoryInterval)),
            ) ?: return
            val id = source["id"] as Long
            db.update("UPDATE source_repository SET checked_at = now() WHERE id = ?", id)
            try {
                import(source)
                db.update("UPDATE source_repository SET error = NULL WHERE id = ?", id)
            } catch (error: ImportRejected) {
                db.update("UPDATE source_repository SET error = ? WHERE id = ?", error.message, id)
                log.warn("Repository {} was not imported: {}", id, error.message)
            } catch (error: IOException) {
                db.update(
                    "UPDATE source_repository SET error = ? WHERE id = ?",
                    "GitHub transport failure: ${error.javaClass.simpleName}",
                    id,
                )
                log.warn("Repository {} transport failed: {}", id, error.javaClass.simpleName)
            }
        } catch (error: IOException) {
            log.warn("GitHub is unavailable: {}", error.javaClass.simpleName)
        } catch (error: ImportRejected) {
            log.warn("GitHub discovery/import rejected: {}", error.message)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } finally {
            db.update("UPDATE platform_lease SET expires_at = now() WHERE name = 'github' AND owner = ?", owner)
        }
    }

    private fun discover() {
        val installations = api.list("/app/installations", api.appToken(), config.maxInstallations)
        val repositories = mutableListOf<Pair<Long, JsonNode>>()
        installations.filter { it.path("suspended_at").isNull }.forEach { installation ->
            renewLease()
            val id = installation.path("id").asLong()
            val token = api.installationToken(id)
            api.list("/installation/repositories", token, config.maxRepositoriesPerInstallation, "repositories")
                .forEach {
                    if (repositories.size >= config.maxRepositories) {
                        throw ImportRejected("Global repository limit exceeded")
                    }
                    repositories += id to it
                }
        }
        renewLease()
        db.tx {
            db.lock(4)
            val existing = db.rows("/* full-scan:repository-reconciliation */ SELECT id FROM source_repository")
                .map { it["id"] as Long }.toSet()
            if ((existing + repositories.map {
                    it.second.path("id").asLong()
                }).size > config.maxRepositories) throw ImportRejected("Stored repository limit exceeded")
            db.update("/* full-scan:repository-reconciliation */ UPDATE source_repository SET enabled = false")
            repositories.forEach { (installation, repo) ->
                val id = repo.path("id").asLong()
                val fullName = repo.path("full_name").asString()
                if (!fullName.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) ||
                    fullName.contains("..")
                ) throw ImportRejected("Invalid repository name")
                val allowed = repo.path("size").asLong(Long.MAX_VALUE) <= config.maxRepositoryBytes / 1024
                db.update(
                    """
                        INSERT INTO source_repository(id, installation_id, full_name, enabled) VALUES (?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET installation_id = excluded.installation_id,
                        full_name = excluded.full_name, enabled = excluded.enabled
                    """.trimIndent(),
                    id, installation, fullName, allowed,
                )
            }
            true
        }
    }

    private fun import(source: Map<String, Any?>) {
        renewLease()
        val repositoryId = source["id"] as Long
        val token = api.installationToken(source["installation_id"] as Long)
        val base = "/repos/${source["full_name"]}"
        val commit = api.request("$base/commits/master", token)
        val sha = commit.path("sha").asString()
        val treeSha = commit.path("commit").path("tree").path("sha").asString()
        if (!sha.matches(Regex("[0-9a-f]{40}")) ||
            !treeSha.matches(Regex("[0-9a-f]{40}"))
        ) throw ImportRejected("Invalid commit SHA")
        val old = db.row(
            "SELECT * FROM repository_revision WHERE repository_id = ? AND commit_sha = ?", repositoryId, sha,
        )
        if (old != null) {
            db.tx { publish(repositoryId, sha); true }
            return
        }
        // Never evict revisions referenced by old contests or submissions to admit untrusted updates.
        if (db.count(
                "SELECT count(*) FROM repository_revision WHERE repository_id = ?",
                repositoryId,
            ) >= config.maxVersionsPerRepository
        ) throw ImportRejected("Stored revision limit exceeded")
        val tree = api.request("$base/git/trees/$treeSha?recursive=1", token)
        val problems = reader.read(tree) { blob -> api.request("$base/git/blobs/$blob", token) }
        val documents = problems.mapValues { db.json.writeValueAsString(it.value) }
        val incomingBytes = documents.values.sumOf { db.count("SELECT octet_length(?::jsonb::text)", it) }
        renewLease()
        db.tx {
            db.lock(4)
            if (db.count(
                    "SELECT count(*) FROM repository_revision WHERE repository_id = ?",
                    repositoryId,
                ) >= config.maxVersionsPerRepository
            ) throw ImportRejected("Stored revision limit exceeded")
            val known = db.rows("SELECT task_key FROM source_problem WHERE repository_id = ?", repositoryId)
                .map { it["task_key"].toString() }.toSet()
            if ((known + problems.keys).size > config.maxStoredTasksPerRepository) {
                throw ImportRejected("Stored task count exceeds limit")
            }
            val storedBytes = db.count(
                """
                    SELECT coalesce(sum(octet_length(document::text)), 0)
                    FROM problem_revision WHERE repository_id = ?
                """.trimIndent(), repositoryId,
            )
            if (incomingBytes > config.maxStoredBytesPerRepository - storedBytes
            ) throw ImportRejected("Stored repository data exceeds size limit")
            db.update("INSERT INTO repository_revision VALUES (?, ?, now())", repositoryId, sha)
            documents.forEach { (key, document) ->
                db.update(
                    """
                        INSERT INTO source_problem(id, repository_id, task_key) VALUES (?, ?, ?)
                        ON CONFLICT(repository_id, task_key) DO NOTHING
                    """.trimIndent(), "$repositoryId:$key", repositoryId, key,
                )
                val problemId = db.row(
                    "SELECT id FROM source_problem WHERE repository_id = ? AND task_key = ?", repositoryId, key,
                )!!["id"].toString()
                db.update(
                    """
                        INSERT INTO problem_revision(id, repository_id, problem_id, commit_sha, document)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                    """.trimIndent(), UUID.randomUUID(), repositoryId, problemId, sha,
                    document,
                )
            }
            publish(repositoryId, sha)
            true
        }
    }

    private fun publish(repositoryId: Long, sha: String) {
        db.lock(4)
        db.update("UPDATE source_problem SET enabled = false WHERE repository_id = ?", repositoryId)
        db.update(
            """
                UPDATE source_problem p SET enabled = true, current_revision = r.id FROM problem_revision r
                WHERE r.repository_id = ? AND r.commit_sha = ? AND r.problem_id = p.id
            """.trimIndent(), repositoryId, sha,
        )
    }

    private fun renewLease() {
        val renewed = db.update(
            """
                UPDATE platform_lease SET expires_at = now() + interval '10 minutes' WHERE name = 'github' AND owner = ?
                AND expires_at > now()
            """.trimIndent(),
            owner,
        )
        if (renewed == 0) throw ImportRejected("GitHub import lease expired")
    }
}
