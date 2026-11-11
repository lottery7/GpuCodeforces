package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.contest.ContestProblemRepository
import org.eclipse.jgit.api.Git
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

@ConfigurationProperties("platform.problems")
data class ProblemsProperties(
    /** Problem repository checkout. Empty: the bank stays empty. */
    val directory: String = "",
    /** If set, [directory] is a clone of this repository, updated before every sync. */
    val gitUrl: String = "",
    val gitBranch: String = "master",
    /** Sub-directory of the repository with one directory per problem package (`problems` in GpuCodeforcesProblems). */
    val path: String = "",
)

data class SyncResult(val revision: String?, val imported: Int, val unchanged: Int, val removed: Int, val failed: Map<String, String>)

/**
 * Imports the problem repository into the bank on startup and periodically. A package that fails validation keeps
 * its previous version in the bank; a package that disappeared from the repository leaves the bank.
 */
@Service
class ProblemSyncService(
    private val properties: ProblemsProperties,
    private val reader: ProblemPackageReader,
    private val problemRepository: ProblemRepository,
    private val versionRepository: ProblemVersionRepository,
    private val contestProblemRepository: ContestProblemRepository,
    private val transactionTemplate: TransactionTemplate,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun syncOnStartup() {
        syncLoggingFailures()
    }

    @Scheduled(initialDelayString = "\${platform.problems.sync-interval:PT1M}", fixedDelayString = "\${platform.problems.sync-interval:PT1M}")
    fun syncPeriodically() {
        syncLoggingFailures()
    }

    @Synchronized
    fun sync(): SyncResult {
        if (properties.directory.isBlank()) {
            log.warn("platform.problems.directory is not set, the problem bank is empty")
            return SyncResult(null, 0, 0, 0, emptyMap())
        }
        val checkoutDirectory = Path.of(properties.directory).toAbsolutePath().normalize()
        val revision = if (properties.gitUrl.isNotBlank()) checkout(checkoutDirectory) else null
        val root = checkoutDirectory.resolve(properties.path).normalize()
        check(root.isDirectory()) { "Problem directory $root does not exist" }

        val packages = mutableListOf<ProblemPackage>()
        val failed = mutableMapOf<String, String>()
        root.listDirectoryEntries()
            // Every directory is a package: one in a wrong format is reported, not silently skipped.
            .filter { it.isDirectory() && !it.fileName.toString().startsWith(".") }
            .sortedBy { it.fileName.toString() }
            .forEach { directory ->
                try {
                    packages += reader.read(directory)
                } catch (exception: ProblemPackageException) {
                    failed[directory.fileName.toString()] = exception.message!!
                    log.error("Problem package {} is invalid: {}", directory.fileName, exception.message)
                }
            }
        val result = transactionTemplate.execute { store(packages, failed.keys, revision) }!!
        log.info("Problem sync at {}: {} new versions, {} unchanged, {} removed, {} invalid",
            revision ?: root, result.imported, result.unchanged, result.removed, result.failed.size)
        return result.copy(failed = failed)
    }

    private fun syncLoggingFailures() {
        try {
            sync()
        } catch (exception: Exception) {
            log.error("Problem sync failed", exception)
        }
    }

    private fun store(packages: List<ProblemPackage>, failedIds: Set<String>, revision: String?): SyncResult {
        var imported = 0
        var unchanged = 0
        for (problemPackage in packages) {
            val problem = problemRepository.findById(problemPackage.id).orElseGet { problemRepository.save(Problem(problemPackage.id)) }
            val existing = versionRepository.findByProblemIdAndContentHash(problemPackage.id, problemPackage.contentHash)
            val version = existing ?: versionRepository.save(problemPackage.toVersion(revision)).also { imported++ }
            if (existing != null && problem.currentVersionId == existing.id) unchanged++
            if (problem.currentVersionId != version.id) {
                problem.currentVersionId = version.id
                contestProblemRepository.repinUpcoming(problem.id, version.id)
            }
        }
        val present = packages.map { it.id }.toSet() + failedIds
        val removed = problemRepository.findActivePackageIds().filter { it !in present }
        if (removed.isNotEmpty()) problemRepository.unpublish(removed)
        return SyncResult(revision, imported, unchanged, removed.size, emptyMap())
    }

    private fun ProblemPackage.toVersion(revision: String?) = ProblemVersion(
        id = UUID.randomUUID(),
        problemId = id,
        code = frontMatter.code,
        title = frontMatter.title,
        difficulty = frontMatter.difficulty,
        tags = frontMatter.tags,
        authors = frontMatter.authors,
        draft = frontMatter.draft,
        statementMarkdown = statementMarkdown,
        processors = spec.declaredProcessors().map { it.id },
        starters = starters,
        judgeSpec = jsonMapper.writeValueAsString(spec),
        contentHash = contentHash,
        judgeHash = judgeHash,
        revision = revision,
        format = format.format,
        formatData = jsonMapper.writeValueAsString(format),
    )

    /** Clones or fast-forwards [root] to the configured branch and returns the commit id. */
    private fun checkout(root: Path): String {
        val git = if (Files.isDirectory(root.resolve(".git"))) {
            Git.open(root.toFile()).also { it.fetch().setRemote("origin").call() }
        } else {
            Git.cloneRepository().setURI(properties.gitUrl).setBranch(properties.gitBranch).setDirectory(root.toFile()).call()
        }
        return git.use {
            it.checkout().setForced(true).setName("origin/${properties.gitBranch}").call()
            it.repository.resolve("HEAD").name
        }
    }
}
