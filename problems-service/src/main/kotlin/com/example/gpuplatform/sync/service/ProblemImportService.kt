package com.example.gpuplatform.sync.service

import com.example.gpuplatform.problem.domain.Problem
import com.example.gpuplatform.problem.repository.ProblemRepository
import com.example.gpuplatform.sync.domain.ImportSummary
import com.example.gpuplatform.sync.domain.RepositorySnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Coordinates parsing and database upserts for a repository snapshot.
 * Runs in a single transaction so a failure does not leave partially imported
 * data behind.
 */
@Service
class ProblemImportService(
    private val problemRepository: ProblemRepository,
    private val parser: ProblemParser,
) {

    private val log = LoggerFactory.getLogger(ProblemImportService::class.java)

    @Transactional
    fun importAll(snapshot: RepositorySnapshot): ImportSummary {
        var imported = 0
        var updated = 0
        var unchanged = 0

        val total = snapshot.problemSources.size
        log.info("Importing problems from commit {}: {} file(s) found", snapshot.commitSha, total)

        for ((index, source) in snapshot.problemSources.withIndex()) {
            val progress = "[${index + 1}/$total]"
            val problemMd = Path.of(source.absolutePath)
            val content = Files.readString(problemMd)
            val solutionPy = readSibling(problemMd, "solution.py")
            val testsPy = readSibling(problemMd, "tests.py")
            val hash = parser.contentHash(content, solutionPy, testsPy)

            val existing = problemRepository.findBySlug(source.slug)
            if (existing != null && existing.contentHash == hash) {
                unchanged++
                log.info("{} Problem '{}': unchanged (skipping)", progress, source.slug)
                continue
            }

            log.info("{} Problem '{}': parsing and writing to database", progress, source.slug)
            val parsed = parser.parse(content, source.slug, source.absolutePath, solutionPy, testsPy)
            if (existing == null) {
                problemRepository.save(
                    Problem(
                        slug = parsed.slug,
                        title = parsed.title,
                        tags = parsed.tags,
                        author = parsed.author,
                        metadata = parsed.metadata,
                        statement = parsed.statement,
                        solutionPy = parsed.solutionPy,
                        testsPy = parsed.testsPy,
                        gitCommitSha = snapshot.commitSha,
                        contentHash = hash,
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                    )
                )
                imported++
                log.info("{} Problem '{}': inserted", progress, source.slug)
            } else {
                existing.title = parsed.title
                existing.tags = parsed.tags
                existing.author = parsed.author
                existing.metadata = parsed.metadata
                existing.statement = parsed.statement
                existing.solutionPy = parsed.solutionPy
                existing.testsPy = parsed.testsPy
                existing.gitCommitSha = snapshot.commitSha
                existing.contentHash = hash
                existing.updatedAt = Instant.now()
                problemRepository.save(existing)
                updated++
                log.info("{} Problem '{}': updated", progress, source.slug)
            }
        }

        log.info(
            "Import finished for commit {}: imported={}, updated={}, unchanged={}",
            snapshot.commitSha, imported, updated, unchanged,
        )
        return ImportSummary(
            totalDiscovered = snapshot.problemSources.size,
            imported = imported,
            updated = updated,
            unchanged = unchanged,
        )
    }

    /** Reads an optional sibling file (e.g. `solution.py`) next to [problemMd]. */
    private fun readSibling(problemMd: Path, filename: String): String? {
        val file = problemMd.resolveSibling(filename)
        return if (Files.isRegularFile(file)) Files.readString(file) else null
    }
}