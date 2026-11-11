package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.judge.api.Processors
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service

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
    val contextKey: String = contestId?.toString() ?: PRACTICE_CONTEXT,
    val problemVersionId: UUID? = null,
    val finishedAt: Instant? = null,
)

/** Submission header for "last submission" summaries. */
class SubmissionHead(
    val id: UUID,
    val problemId: String,
    val contestId: UUID?,
    val status: SubmissionStatus,
    val createdAt: Instant,
    val contextKey: String = contestId?.toString() ?: PRACTICE_CONTEXT,
)

interface SubmissionStatsRepository : Repository<Submission, UUID> {
    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.AcceptedSubmission(
            s.id, s.userId, s.problemId, s.contestId, s.judgeHash, s.processor, s.score, s.createdAt, s.benchmarkEnvironmentKey, s.contextKey, s.problemVersionId, s.finishedAt)
        FROM Submission s WHERE s.status = :status AND s.problemId IN :problemIds
        """,
    )
    fun findWithStatus(status: SubmissionStatus, problemIds: Collection<String>): List<AcceptedSubmission>

    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.AcceptedSubmission(
            s.id, s.userId, s.problemId, s.contestId, s.judgeHash, s.processor, s.score, s.createdAt, s.benchmarkEnvironmentKey, s.contextKey, s.problemVersionId, s.finishedAt)
        FROM Submission s WHERE s.status = :status AND s.contextKey = :contextKey
        """,
    )
    fun findWithStatusInContext(status: SubmissionStatus, contextKey: String): List<AcceptedSubmission>

    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.SubmissionHead(s.id, s.problemId, s.contestId, s.status, s.createdAt, s.contextKey)
        FROM Submission s WHERE s.userId = :userId AND s.problemId IN :problemIds
        """,
    )
    fun findHeads(userId: UUID, problemIds: Collection<String>): List<SubmissionHead>

    @Query("SELECT DISTINCT s.userId FROM Submission s WHERE s.contextKey = :contextKey")
    fun findContextParticipants(contextKey: String): List<UUID>

    @Query("SELECT count(s) FROM Submission s WHERE s.contestId = :contestId AND s.contextKey = CAST(:contestId AS string) AND s.status IN :statuses " +
        "AND s.createdAt >= :startAt AND s.createdAt < :endAt")
    fun countInContestWithStatusDuring(contestId: UUID, statuses: Collection<SubmissionStatus>, startAt: Instant, endAt: Instant): Long

    @Query("SELECT count(s) FROM Submission s WHERE s.contextKey = :contextKey AND s.status IN :statuses")
    fun countInContextWithStatus(contextKey: String, statuses: Collection<SubmissionStatus>): Long

    @Query("SELECT count(DISTINCT s.problemId) FROM Submission s WHERE s.userId = :userId AND s.status = :status")
    fun countProblemsWithStatus(userId: UUID, status: SubmissionStatus): Long

    @Query("SELECT s.createdAt FROM Submission s WHERE s.userId = :userId AND s.status = :status")
    fun findAcceptedAt(userId: UUID, status: SubmissionStatus): List<Instant>
}

/**
 * Where results count: practice and every contest have their own records. Accepted scores are compared only within
 * one judge configuration - the problem version's judge hash - and one processor.
 */
data class ResultScope(
    val contestId: UUID?,
    val judgeHashes: Map<String, String>,
    val gpuEnvironmentKey: String? = null,
    val contextKey: String = contestId?.toString() ?: PRACTICE_CONTEXT,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val finishedBefore: Instant? = null,
) {
    fun counts(submission: AcceptedSubmission, processor: String): Boolean =
        submission.processor == processor && submission.contextKey == contextKey && judgeHashes[submission.problemId] == submission.judgeHash &&
            (processor != Processors.GPU_CUDA || gpuEnvironmentKey == null || submission.benchmarkEnvironmentKey == gpuEnvironmentKey) &&
            (startAt == null || !submission.createdAt.isBefore(startAt)) && (endAt == null || submission.createdAt.isBefore(endAt)) &&
            (finishedBefore == null || submission.finishedAt == null || !submission.finishedAt.isAfter(finishedBefore))
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
        contextKey: String = contestId?.toString() ?: PRACTICE_CONTEXT,
    ): BigDecimal? {
        val scope = ResultScope(contestId, mapOf(problemId to judgeHash), gpuEnvironmentKey, contextKey)
        return bestScore(userId, problemId, scope, processor)
    }

    fun bestScore(userId: UUID, problemId: String, scope: ResultScope, processor: String): BigDecimal? {
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
                solved = userId != null && all.any {
                    it.userId == userId && (scope.contextKey == PRACTICE_CONTEXT || it.contextKey == scope.contextKey) &&
                        (scope.startAt == null && scope.endAt == null && scope.finishedBefore == null || processors.any { p -> scope.counts(it, p) })
                },
                solvedCount = all.map { it.userId }.distinct().size,
                last = heads[problemId].orEmpty().filter { it.contextKey == scope.contextKey }.maxByOrNull { it.createdAt },
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

    /** When each participant sent the first accepted official submission of each problem of a contest. */
    fun firstAcceptedInContest(contestId: UUID): Map<UUID, Map<String, Instant>> = firstAcceptedIn(contestId.toString())

    /** When each user sent the first accepted submission of each problem in a context (submission time, not verdict time). */
    fun firstAcceptedIn(contextKey: String): Map<UUID, Map<String, Instant>> =
        repository.findWithStatusInContext(SubmissionStatus.ACCEPTED, contextKey)
            .groupBy { it.userId }
            .mapValues { (_, submissions) -> submissions.groupBy { it.problemId }.mapValues { (_, it) -> it.minOf { s -> s.createdAt } } }

    fun duelBestScores(scope: ResultScope, versionId: UUID, processor: String, startAt: Instant, endAt: Instant): Map<UUID, BigDecimal> =
        repository.findWithStatusInContext(SubmissionStatus.ACCEPTED, scope.contextKey)
            .filter { submission ->
                submission.problemVersionId == versionId && submission.score != null && scope.counts(submission, processor) &&
                    !submission.createdAt.isBefore(startAt) && submission.createdAt.isBefore(endAt)
            }
            .groupBy { it.userId }.mapValues { (_, submissions) -> submissions.minOf { it.score!! } }

    fun contestParticipants(contestId: UUID): Set<UUID> = repository.findContextParticipants(contestId.toString()).toSet()

    fun pendingInContest(contestId: UUID): Long = repository.countInContextWithStatus(contestId.toString(), SubmissionStatus.PENDING)

    fun pendingInContest(contestId: UUID, startAt: Instant, endAt: Instant): Long =
        repository.countInContestWithStatusDuring(contestId, SubmissionStatus.PENDING, startAt, endAt)

    fun solvedProblemCount(userId: UUID): Int = repository.countProblemsWithStatus(userId, SubmissionStatus.ACCEPTED).toInt()

    /**
     * Current streak in whole UTC days: how many consecutive days up to today (or yesterday, if today has no
     * accepted submission yet) the user has at least one accepted submission. Days are counted in UTC.
     */
    fun streak(userId: UUID): Int {
        val today = LocalDate.now(ZoneOffset.UTC)
        val activeDays = repository.findAcceptedAt(userId, SubmissionStatus.ACCEPTED)
            .map { it.atOffset(ZoneOffset.UTC).toLocalDate() }
            .toSet()
        if (activeDays.isEmpty()) return 0
        var day = if (today in activeDays) today else today.minusDays(1)
        var streak = 0
        while (day in activeDays) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    private fun accepted(problemIds: Collection<String>): List<AcceptedSubmission> =
        if (problemIds.isEmpty()) emptyList() else repository.findWithStatus(SubmissionStatus.ACCEPTED, problemIds)

    /** The smallest score, the earliest on a tie; answers to a guess have none. */
    private fun List<AcceptedSubmission>.best(): AcceptedSubmission? =
        filter { it.score != null }.minWithOrNull(compareBy<AcceptedSubmission> { it.score }.thenBy { it.createdAt })
}
