package dev.gpucodeforces.backend.rating

import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class RatingFinalizationService(
    private val contestRepository: ContestRepository,
    private val contestService: ContestService,
    private val submissionStats: SubmissionStats,
    private val ratingHistoryRepository: RatingHistoryRepository,
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun finalize(contestId: UUID, now: Instant): Boolean {
        val contest = contestRepository.findForUpdate(contestId) ?: return false
        if (contest.ratingAppliedAt != null) return true

        val forfeit = contest.format == "duel" && contest.surrenderedUserId != null
        val pending = if (forfeit) 0 else submissionStats.pendingInContest(contest.id)
        if (pending > 0) {
            if (now.isBefore(contest.endAt.plus(VERDICT_GRACE))) {
                log.info("Contest {} ended, rating waits for {} submission(s) still being judged", contest.id, pending)
                return false
            }
            log.warn("Contest {}: {} submission(s) still unjudged {} after the end, rating is applied without them", contest.id, pending, VERDICT_GRACE)
        }

        val participants = if (forfeit) setOfNotNull(contest.challengerId, contest.opponentId) else submissionStats.contestParticipants(contest.id)
        val standings = contestService.standings(contestId.toString()).filter { UUID.fromString(it.user.id) in participants }
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
        log.info("Contest {} rating applied to {} participant(s)", contest.id, standings.size)
        contest.ratingAppliedAt = now
        contestRepository.save(contest)
        return true
    }

    companion object {
        /** How long after the end the rating waits for late verdicts (a submission sent in the last second). */
        val VERDICT_GRACE: Duration = Duration.ofMinutes(30)
    }
}
