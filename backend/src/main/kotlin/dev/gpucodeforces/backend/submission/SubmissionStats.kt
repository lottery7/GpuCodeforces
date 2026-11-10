package dev.gpucodeforces.backend.submission

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
    val gpuProfile: String,
    val timeMs: BigDecimal,
    val createdAt: Instant,
)

/** Submission header for "last submission" summaries. */
class SubmissionHead(val id: UUID, val problemId: String, val contestId: UUID?, val status: SubmissionStatus, val createdAt: Instant)

interface SubmissionStatsRepository : Repository<Submission, UUID> {
    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.AcceptedSubmission(s.id, s.userId, s.problemId, s.contestId, s.judgeHash, s.gpuProfile, s.timeMs, s.createdAt)
        FROM Submission s WHERE s.status = :status AND s.problemId IN :problemIds
        """,
    )
    fun findWithStatus(status: SubmissionStatus, problemIds: Collection<String>): List<AcceptedSubmission>

    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.AcceptedSubmission(s.id, s.userId, s.problemId, s.contestId, s.judgeHash, s.gpuProfile, s.timeMs, s.createdAt)
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
 * Where results count: practice and every contest have their own records. Accepted times are compared only within
 * one judge configuration - the problem version's judge hash on the platform GPU.
 */
data class ResultScope(val contestId: UUID?, val judgeHashes: Map<String, String>, val gpuProfile: String) {
    fun counts(submission: AcceptedSubmission): Boolean =
        submission.gpuProfile == gpuProfile && submission.contestId == contestId && judgeHashes[submission.problemId] == submission.judgeHash
}

class ProblemResult(
    val solved: Boolean,
    val solvedCount: Int,
    val last: SubmissionHead?,
    val best: AcceptedSubmission?,
    val record: AcceptedSubmission?,
)

class Leader(val rank: Int, val submission: AcceptedSubmission)

@Service
class SubmissionStats(private val repository: SubmissionStatsRepository) {
    fun bestTime(userId: UUID, problemId: String, contestId: UUID?, judgeHash: String, gpuProfile: String): BigDecimal? {
        val scope = ResultScope(contestId, mapOf(problemId to judgeHash), gpuProfile)
        return accepted(listOf(problemId)).filter { it.userId == userId && scope.counts(it) }.minOfOrNull { it.timeMs }
    }

    /** Personal results of [userId] (if any) and shared counters for the problems of one context. */
    fun problemResults(scope: ResultScope, userId: UUID?): Map<String, ProblemResult> {
        val problemIds = scope.judgeHashes.keys
        if (problemIds.isEmpty()) return emptyMap()
        val accepted = accepted(problemIds).groupBy { it.problemId }
        val heads = userId?.let { repository.findHeads(it, problemIds) }.orEmpty().groupBy { it.problemId }
        return problemIds.associateWith { problemId ->
            val all = accepted[problemId].orEmpty()
            val counted = all.filter { scope.counts(it) }
            ProblemResult(
                // Practice counts a solution in any context, a contest only its own.
                solved = userId != null && all.any { it.userId == userId && (scope.contestId == null || it.contestId == scope.contestId) },
                solvedCount = all.map { it.userId }.distinct().size,
                last = heads[problemId].orEmpty().filter { it.contestId == scope.contestId }.maxByOrNull { it.createdAt },
                best = counted.filter { it.userId == userId }.fastest(),
                record = counted.fastest(),
            )
        }
    }

    /** Best accepted submission of every user, fastest first; equal times share a rank. */
    fun leaders(problemId: String, scope: ResultScope, limit: Int): List<Leader> {
        val best = accepted(listOf(problemId)).filter { scope.counts(it) }
            .groupBy { it.userId }.values.map { it.fastest()!! }
            .sortedWith(compareBy<AcceptedSubmission> { it.timeMs }.thenBy { it.createdAt })
            .take(limit)
        return best.map { submission -> Leader(1 + best.count { it.timeMs < submission.timeMs }, submission) }
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

    private fun List<AcceptedSubmission>.fastest(): AcceptedSubmission? =
        minWithOrNull(compareBy<AcceptedSubmission> { it.timeMs }.thenBy { it.createdAt })
}
