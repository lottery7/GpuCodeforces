package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.judge.api.Processors
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Accepted submission without its code. */
class AcceptedSubmission(
    val id: UUID,
    val userId: UUID,
    val problemId: String,
    val contestId: UUID?,
    val judgeHash: String,
    val processor: String,
    /** Null for an answer to a guess: accepted, yet nothing ran. */
    val score: BigDecimal?,
    val createdAt: Instant,
    val benchmarkEnvironmentKey: String? = null,
)

/** Submission header for "last submission" summaries. */
class SubmissionHead(val id: UUID, val problemId: String, val contestId: UUID?, val status: SubmissionStatus, val createdAt: Instant)

interface SubmissionStatsRepository : Repository<Submission, UUID> {
    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.AcceptedSubmission(
            s.id, s.userId, s.problemId, s.contestId, s.judgeHash, s.processor, s.score, s.createdAt, s.benchmarkEnvironmentKey)
        FROM Submission s WHERE s.status = :status AND s.problemId IN :problemIds
        """,
    )
    fun findWithStatus(status: SubmissionStatus, problemIds: Collection<String>): List<AcceptedSubmission>

    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.AcceptedSubmission(
            s.id, s.userId, s.problemId, s.contestId, s.judgeHash, s.processor, s.score, s.createdAt, s.benchmarkEnvironmentKey)
        FROM Submission s WHERE s.status = :status AND s.contestId = :contestId
        """,
    )
    fun findWithStatusInContest(status: SubmissionStatus, contestId: UUID): List<AcceptedSubmission>

    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.SubmissionHead(s.id, s.problemId, s.contestId, s.status, s.createdAt)
        FROM Submission s WHERE s.userId = :userId AND s.problemId IN :problemIds
        """,
    )
    fun findHeads(userId: UUID, problemIds: Collection<String>): List<SubmissionHead>

    @Query("SELECT DISTINCT s.userId FROM Submission s WHERE s.contestId = :contestId")
    fun findContestParticipants(contestId: UUID): List<UUID>

    @Query("SELECT count(s) FROM Submission s WHERE s.contestId = :contestId AND s.status IN :statuses")
    fun countInContestWithStatus(contestId: UUID, statuses: Collection<SubmissionStatus>): Long

    @Query("SELECT count(DISTINCT s.problemId) FROM Submission s WHERE s.userId = :userId AND s.status = :status")
    fun countProblemsWithStatus(userId: UUID, status: SubmissionStatus): Long
}

/**
 * Where results count: practice and every contest have their own records. Accepted scores are compared only within
 * one judge configuration - the problem version's judge hash - and one processor.
 */
data class ResultScope(val contestId: UUID?, val judgeHashes: Map<String, String>, val gpuEnvironmentKey: String? = null) {
    /** On the real GPU a result counts only from the current environment ([gpuEnvironmentKey]); emulators are deterministic. */
    fun counts(submission: AcceptedSubmission, processor: String): Boolean =
        submission.processor == processor && submission.contestId == contestId && judgeHashes[submission.problemId] == submission.judgeHash &&
            (processor != Processors.GPU_CUDA || gpuEnvironmentKey == null || submission.benchmarkEnvironmentKey == gpuEnvironmentKey)
}

class ProblemResult(
    val solved: Boolean,
    val solvedCount: Int,
    val last: SubmissionHead?,
    /** Personal best per processor. */
    val best: Map<String, AcceptedSubmission>,
    /** Record per processor. */
    val record: Map<String, AcceptedSubmission>,
)

class Leader(val rank: Int, val submission: AcceptedSubmission)

@Service
class SubmissionStats(private val repository: SubmissionStatsRepository) {
    fun bestScore(
        userId: UUID, problemId: String, contestId: UUID?, judgeHash: String, processor: String, gpuEnvironmentKey: String? = null,
    ): BigDecimal? {
        val scope = ResultScope(contestId, mapOf(problemId to judgeHash), gpuEnvironmentKey)
        return accepted(listOf(problemId)).filter { it.userId == userId && scope.counts(it, processor) }.mapNotNull { it.score }.minOrNull()
    }

    /** Personal results of [userId] (if any) and shared counters for the problems of one context, on [processors]. */
    fun problemResults(scope: ResultScope, userId: UUID?, processors: Collection<String>): Map<String, ProblemResult> {
        val problemIds = scope.judgeHashes.keys
        if (problemIds.isEmpty()) return emptyMap()
        val accepted = accepted(problemIds).groupBy { it.problemId }
        val heads = userId?.let { repository.findHeads(it, problemIds) }.orEmpty().groupBy { it.problemId }
        return problemIds.associateWith { problemId ->
            val all = accepted[problemId].orEmpty()
            ProblemResult(
                // Practice counts a solution in any context, a contest only its own.
                solved = userId != null && all.any { it.userId == userId && (scope.contestId == null || it.contestId == scope.contestId) },
                solvedCount = all.map { it.userId }.distinct().size,
                last = heads[problemId].orEmpty().filter { it.contestId == scope.contestId }.maxByOrNull { it.createdAt },
                best = processors.mapNotNull { p -> all.filter { it.userId == userId && scope.counts(it, p) }.best()?.let { p to it } }.toMap(),
                record = processors.mapNotNull { p -> all.filter { scope.counts(it, p) }.best()?.let { p to it } }.toMap(),
            )
        }
    }

    /** Best accepted submission of every user on [processor], smallest score first; equal scores share a rank. */
    fun leaders(problemId: String, scope: ResultScope, processor: String, limit: Int): List<Leader> {
        val best = accepted(listOf(problemId)).filter { scope.counts(it, processor) && it.score != null }
            .groupBy { it.userId }.values.map { it.best()!! }
            .sortedWith(compareBy<AcceptedSubmission> { it.score }.thenBy { it.createdAt })
            .take(limit)
        return best.map { submission -> Leader(1 + best.count { it.score!! < submission.score!! }, submission) }
    }

    /** When each participant sent the first accepted submission of each problem of a contest (submission time, not verdict time). */
    fun firstAcceptedInContest(contestId: UUID): Map<UUID, Map<String, Instant>> =
        repository.findWithStatusInContest(SubmissionStatus.ACCEPTED, contestId)
            .groupBy { it.userId }
            .mapValues { (_, submissions) -> submissions.groupBy { it.problemId }.mapValues { (_, it) -> it.minOf { s -> s.createdAt } } }

    /** Users who sent at least one submission to the contest: only they are rated. */
    fun contestParticipants(contestId: UUID): Set<UUID> = repository.findContestParticipants(contestId).toSet()

    /** Contest submissions still waiting for their verdict. */
    fun pendingInContest(contestId: UUID): Long = repository.countInContestWithStatus(contestId, SubmissionStatus.PENDING)

    fun solvedProblemCount(userId: UUID): Int = repository.countProblemsWithStatus(userId, SubmissionStatus.ACCEPTED).toInt()

    private fun accepted(problemIds: Collection<String>): List<AcceptedSubmission> =
        if (problemIds.isEmpty()) emptyList() else repository.findWithStatus(SubmissionStatus.ACCEPTED, problemIds)

    /** The smallest score, the earliest on a tie; answers to a guess have none. */
    private fun List<AcceptedSubmission>.best(): AcceptedSubmission? =
        filter { it.score != null }.minWithOrNull(compareBy<AcceptedSubmission> { it.score }.thenBy { it.createdAt })
}
