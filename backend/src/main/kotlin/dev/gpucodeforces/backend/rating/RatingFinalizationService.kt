package dev.gpucodeforces.backend.rating

import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.submission.HackService
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class RatingFinalizationService(
    private val contestRepository: ContestRepository,
    private val contestService: ContestService,
    private val ratingHistoryRepository: RatingHistoryRepository,
    private val userRepository: UserRepository,
    private val submissions: SubmissionRepository,
    private val hacks: HackService,
) {
    /** Idempotent: a contest with ratingAppliedAt already set is left untouched. */
    @Transactional
    fun finalize(contestId: UUID, now: Instant) {
        val contest = contestRepository.findForUpdate(contestId) ?: return
        if (contest.ratingAppliedAt != null) return
        if (now.isBefore(contest.hackEndAt ?: contest.endAt)) return
        if (hacks.list(contestId).any { it.status == "queued" || it.status == "system_error" }) return
        val official = submissions.findByContestId(contestId).filter { it.contextKey == contestId.toString() }
        if (official.any { !it.status.terminal || it.status == SubmissionStatus.SYSTEM_ERROR || it.status == SubmissionStatus.MEASUREMENT_ERROR }) return
        if (official.any { it.status == SubmissionStatus.ACCEPTED && it.testSuite != hacks.suite(contestId, it.problemId) }) return

        val times = contestService.baselines(contest).toMutableMap()
        official.filter { it.status == SubmissionStatus.ACCEPTED }.groupBy { it.problemId }.forEach { (problem, solutions) ->
            times.putIfAbsent(problem, solutions.minOf { it.timeMs!! })
        }
        contest.baselineTimes = times
        contest.finalizedAt = now

        val standings = contestService.standings(contestId.toString())
        if (standings.isNotEmpty()) {
            val results = standings.map { row -> ParticipantResult(UUID.fromString(row.user.id), row.user.rating, row.rank) }
            RatingCalculator.compute(results).forEach { (userId, outcome) ->
                val rank = standings.first { UUID.fromString(it.user.id) == userId }.rank
                ratingHistoryRepository.save(RatingHistory(contest.id, userId, rank, outcome.delta, outcome.ratingAfter))
                val user = userRepository.findById(userId).orElseThrow()
                user.rating = outcome.ratingAfter
                user.ratingDelta = outcome.delta
                user.ratingContestId = contest.id
            }
            userRepository.recomputeRanks()
        }
        contest.ratingAppliedAt = now
        contestRepository.save(contest)
    }
}
