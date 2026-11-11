package dev.gpucodeforces.backend.rating

import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.UserRating
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

    /**
     * Applies the contest result to the ratings of its processor once its submissions are judged. Only users who
     * sent at least one submission are rated: registering without taking part changes nothing. Idempotent: a contest
     * with ratingAppliedAt already set is left untouched. Returns whether the rating is applied now.
     */
    @Transactional
    fun finalize(contestId: UUID, now: Instant): Boolean {
        val contest = contestRepository.findById(contestId).orElse(null) ?: return false
        if (contest.ratingAppliedAt != null) return true

        val pending = submissionStats.pendingInContest(contest.id)
        if (pending > 0) {
            if (now.isBefore(contest.endAt.plus(VERDICT_GRACE))) {
                log.info("Contest {} ended, rating waits for {} submission(s) still being judged", contest.id, pending)
                return false
            }
            log.warn("Contest {}: {} submission(s) still unjudged {} after the end, rating is applied without them", contest.id, pending, VERDICT_GRACE)
        }

        val processor = contest.processor
        val participants = submissionStats.contestParticipants(contest.id)
        val standings = contestService.standings(contestId.toString()).filter { UUID.fromString(it.user.id) in participants }
        if (standings.isNotEmpty()) {
            val users = userRepository.findAllById(standings.map { UUID.fromString(it.user.id) }).associateBy { it.id }
            val results = standings.map { row ->
                val userId = UUID.fromString(row.user.id)
                ParticipantResult(userId, users.getValue(userId).ratings[processor]?.rating, row.rank)
            }
            RatingCalculator.compute(results).forEach { (userId, outcome) ->
                val rank = standings.first { UUID.fromString(it.user.id) == userId }.rank
                ratingHistoryRepository.save(RatingHistory(contest.id, userId, rank, outcome.delta, outcome.ratingAfter, processor))
                val user = users.getValue(userId)
                val rating = user.ratings.getOrPut(processor) { UserRating(outcome.ratingAfter) }
                rating.rating = outcome.ratingAfter
                rating.ratingDelta = outcome.delta
                rating.ratingContestId = contest.id
            }
            userRepository.recomputeRanks()
        }
        log.info("Contest {} rating of {} applied to {} participant(s)", contest.id, processor, standings.size)
        contest.ratingAppliedAt = now
        contestRepository.save(contest)
        return true
    }

    companion object {
        /** How long after the end the rating waits for late verdicts (a submission sent in the last second). */
        val VERDICT_GRACE: Duration = Duration.ofMinutes(30)
    }
}
