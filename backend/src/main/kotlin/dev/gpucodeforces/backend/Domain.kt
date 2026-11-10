package dev.gpucodeforces.backend

import org.intellij.lang.annotations.Language
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ConfigurationProperties("platform")
data class PlatformConfig(
    val schedulingEnabled: Boolean = true,
    val publicOrigin: String = "http://localhost:5173",
    val secureCookies: Boolean = true,
    val sessionTtl: Duration = Duration.ofDays(7),
    val maxBodyBytes: Int = 262144,
    val requestsPerMinute: Int = 300,
    val authRequestsPerMinute: Int = 15,
    val maxRateLimitKeys: Int = 10000,
    val maxQueuedSubmissions: Int = 10000,
    val maxPendingPerUser: Int = 10,
    val maxCodeBytes: Int = 131072,
    val judge: JudgeConfig = JudgeConfig(),
    val github: GithubConfig = GithubConfig(),
    val admin: AdminConfig = AdminConfig(),
)

data class AdminConfig(
    val bootstrapToken: String = "",
    val consoleTimeout: Duration = Duration.ofSeconds(30),
    val maxScriptBytes: Int = 65536,
    val maxOutputCharacters: Int = 65536,
)

data class JudgeConfig(
    val workers: List<WorkerConfig> = emptyList(),
    val maxInFlight: Int = 2,
    val timeout: Duration = Duration.ofMinutes(5),
    val pingInterval: Duration = Duration.ofSeconds(30),
    val pingTimeout: Duration = Duration.ofSeconds(10),
    val httpTimeout: Duration = Duration.ofSeconds(5),
    val maxAttempts: Int = 5,
    val inboxCapacity: Int = 10000,
    val exampleWait: Duration = Duration.ofSeconds(10),
    val callbackRequestsPerMinute: Int = 30000,
)

data class WorkerConfig(val id: String, val url: String, val token: String, val environmentId: String = "default")

data class GithubConfig(
    val appId: String = "",
    val privateKeyFile: String = "",
    val discoveryInterval: Duration = Duration.ofMinutes(10),
    val repositoryInterval: Duration = Duration.ofMinutes(2),
    val maxInstallations: Int = 20,
    val maxRepositories: Int = 100,
    val maxRepositoriesPerInstallation: Int = 20,
    val maxRepositoryBytes: Long = 20 * 1024 * 1024,
    val maxTreeEntries: Int = 2000,
    val maxFileBytes: Int = 131072,
    val maxPackageBytes: Int = 524288,
    val maxTasksPerRepository: Int = 100,
    val maxStoredTasksPerRepository: Int = 500,
    val maxImportedBytesPerRepository: Int = 4194304,
    val maxStoredBytesPerRepository: Long = 64 * 1024 * 1024,
    val maxVersionsPerRepository: Int = 100,
    val maxApiResponseBytes: Int = 1048576,
    val maxRequestsPerMinute: Int = 120,
    val httpTimeout: Duration = Duration.ofSeconds(10),
)

@Configuration
@EnableConfigurationProperties(PlatformConfig::class)
class PlatformConfiguration

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["platform.scheduling-enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration

data class ProblemPackage(
    val code: String,
    val title: String,
    val difficulty: String,
    val tags: List<String>,
    val statementMarkdown: String,
    val starterCode: String,
    val generator: String,
    val referenceSolution: String,
    val testCount: Int,
    val timeLimitSeconds: Double,
    val memoryLimitMb: Int,
    val referenceTimeMs: Double,
    val environmentMarkdown: String,
    val environmentId: String = "default",
    val absoluteTolerance: Double = 1e-6,
    val relativeTolerance: Double = 1e-6,
)

data class Revision(val id: UUID, val problemId: String, val sha: String, val problem: ProblemPackage)
data class ProblemSummary(
    val code: String,
    val title: String,
    val difficulty: String,
    val tags: List<String>,
    val testCount: Int,
)

data class RevisionSummary(val id: UUID, val problemId: String, val sha: String, val problem: ProblemSummary)
data class StoredSubmission(
    val id: UUID,
    val userId: String,
    val problemId: String,
    val revisionId: UUID,
    val contestId: UUID?,
    val code: String,
    val example: Boolean,
    val status: String,
    val createdAt: Instant,
    val workerId: String?,
    val attemptId: UUID?,
    val attempts: Int,
    val timeMs: Double?,
    val diagnostic: String,
)

data class JudgeState(
    val id: UUID,
    val workerId: String?,
    val attemptId: UUID?,
    val status: String,
    val testCount: Int,
    val contestId: UUID?,
    val deadline: Instant?,
)

val terminalVerdicts =
    setOf(
        "accepted",
        "compile_error",
        "wrong_answer",
        "runtime_error",
        "time_limit",
        "memory_limit",
        "system_error",
        "measurement_error",
    )
val pendingStatuses = setOf("queued", "compiling", "checking", "measuring")

fun fail(status: Int, message: String): Nothing = throw ResponseStatusException(HttpStatus.valueOf(status), message)
fun uuid(value: String): UUID = try {
    UUID.fromString(value)
} catch (_: IllegalArgumentException) {
    fail(400, "Некорректный идентификатор.")
}

@Component
class Database(val jdbc: JdbcTemplate, private val transaction: TransactionTemplate, val json: JsonMapper) {
    fun <T : Any> tx(block: () -> T): T = transaction.execute { block() }
    fun lock(key: Int) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", key)
    }

    fun rows(@Language("SQL") sql: String, vararg args: Any?): List<Map<String, Any?>> = jdbc.queryForList(sql, *args)
    fun row(@Language("SQL") sql: String, vararg args: Any?): Map<String, Any?>? = rows(sql, *args).firstOrNull()
    fun count(@Language("SQL") sql: String, vararg args: Any?): Long =
        jdbc.queryForObject(sql, Long::class.java, *args)!!

    fun update(@Language("SQL") sql: String, vararg args: Any?): Int = jdbc.update(sql, *args)
    fun revision(id: UUID): Revision {
        val row = row("SELECT * FROM problem_revision WHERE id = ?", id) ?: fail(404, "Версия задачи не найдена.")
        return Revision(
            id,
            row["problem_id"].toString(),
            row["commit_sha"].toString(),
            json.readValue(row["document"].toString(), ProblemPackage::class.java),
        )
    }

    fun summary(id: UUID): RevisionSummary {
        val row = row(
            """
                SELECT problem_id, commit_sha, jsonb_build_object(
                'code', metadata->'code', 'title', metadata->'title', 'difficulty', metadata->'difficulty',
                'tags', metadata->'tags', 'testCount', metadata->'testCount') AS metadata FROM problem_revision
                WHERE id = ?
            """.trimIndent(),
            id,
        ) ?: fail(404, "Версия задачи не найдена.")
        return RevisionSummary(
            id,
            row["problem_id"].toString(),
            row["commit_sha"].toString(),
            json.readValue(row["metadata"].toString(), ProblemSummary::class.java),
        )
    }

    fun submission(id: UUID): StoredSubmission {
        val row = row("SELECT * FROM submission WHERE id = ?", id) ?: fail(404, "Посылка не найдена.")
        return StoredSubmission(
            id, row["user_id"].toString(), row["problem_id"].toString(), row["revision_id"] as UUID,
            row["contest_id"] as UUID?, row["code"].toString(), row["example"] as Boolean, row["status"].toString(),
            (row["created_at"] as Timestamp).toInstant(), row["worker_id"] as String?, row["attempt_id"] as UUID?,
            row["attempts"] as Int, (row["time_ms"] as Number?)?.toDouble(), row["diagnostic"].toString(),
        )
    }

    fun judgeState(id: UUID): JudgeState {
        val row = row(
            """
                SELECT id, worker_id, attempt_id, status, test_count, contest_id, deadline FROM submission WHERE id = ?
            """.trimIndent(),
            id,
        ) ?: fail(404, "Посылка не найдена.")
        return JudgeState(
            id,
            row["worker_id"] as String?,
            row["attempt_id"] as UUID?,
            row["status"].toString(),
            row["test_count"] as Int,
            row["contest_id"] as UUID?,
            (row["deadline"] as Timestamp?)?.toInstant(),
        )
    }
}
