package dev.gpucodeforces.backend.rating

import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.UserRating
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.submission.HackService
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
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
    private val submissions: SubmissionRepository,
    private val hacks: HackService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Makes the results of a contest final and applies them to the ratings of its processor: after the hack period,
     * once the hacks are judged and every accepted submission has passed the tests of the successful ones. Only users
     * who sent at least one official submission are rated: registering without taking part changes nothing.
     * Idempotent: a contest with ratingAppliedAt already set is left untouched. Returns whether the rating is applied
     * now.
     */
    @Transactional
    fun finalize(contestId: UUID, now: Instant): Boolean {
        // the lock keeps new official submissions, rechecks and hacks out until the results are final
        val contest = contestRepository.findForUpdate(contestId) ?: return false
        if (contest.ratingAppliedAt != null) return true
        if (now.isBefore(contest.resultsAt)) return false
        if (!settled(contest, now)) return false
        contest.finalizedAt = now

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

    /**
     * Whether nothing can change the standings any more. The work the platform does itself is always waited for: a
     * hack in the queue and the rejudging on the tests of successful hacks. A check that failed in the judge (of a
     * submission or of a hack) is waited for until somebody repeats it, and a verdict that does not come until the
     * judge is heard from - but only for [VERDICT_GRACE]: the next contests of the processor wait behind this one.
     */
    private fun settled(contest: Contest, now: Instant): Boolean {
        val hackList = hacks.list(contest.id)
        val official = submissions.findByContestId(contest.id).filter { it.official }
        val queuedHacks = hackList.count { it.status == "queued" }
        // an answer to a guess ran no tests, so no hack concerns it
        val stale = official.count {
            it.status == SubmissionStatus.ACCEPTED && it.guessChoice == null && it.testSuite != hacks.suite(contest.id, it.problemId)
        }
        if (queuedHacks > 0 || stale > 0) {
            log.info("Contest {} waits for {} hack(s) in the queue and {} submission(s) to be rejudged on the tests of hacks", contest.id, queuedHacks, stale)
            return false
        }
        val failed = official.count { it.status == SubmissionStatus.SYSTEM_ERROR || it.status == SubmissionStatus.MEASUREMENT_ERROR } +
            hackList.count { it.status == "system_error" }
        val pending = official.filter { !it.status.terminal }
        if (failed == 0 && pending.isEmpty()) return true
        val graceOver = !now.isBefore(contest.resultsAt.plus(VERDICT_GRACE)) && pending.none { it.updatedAt.plus(VERDICT_GRACE).isAfter(now) }
        if (!graceOver) {
            log.info("Contest {} ended, rating waits for {} submission(s) being judged and {} failed check(s)", contest.id, pending.size, failed)
            return false
        }
        log.warn(
            "Contest {}: {} submission(s) unjudged and {} check(s) failed {} after the results were due, rating is applied without them",
            contest.id, pending.size, failed, VERDICT_GRACE,
        )
        return true
    }

    companion object {
        /** How long the results wait for late verdicts (a submission sent in the last second) and for failed checks to be repeated. */
        val VERDICT_GRACE: Duration = Duration.ofMinutes(30)
    }
}
