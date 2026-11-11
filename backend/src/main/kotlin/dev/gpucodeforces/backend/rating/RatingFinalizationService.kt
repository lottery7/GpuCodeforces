package dev.gpucodeforces.backend.rating

import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.contest.FinalDuelResult
import dev.gpucodeforces.backend.contest.FinalDuelScore
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.UserRating
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.backend.submission.HackService
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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

    @Transactional
    fun finalize(contestId: UUID, now: Instant): Boolean {
        val contest = contestRepository.findForUpdate(contestId) ?: return false
        if (contest.ratingAppliedAt != null) return true
        if (now.isBefore(contest.resultsAt)) return false
        val finalizedAt = maxOf(now, Instant.now())
        val forfeit = contest.format == "duel" && contest.surrenderedUserId != null
        if (contest.format == "duel") {
            val pending = if (forfeit) 0 else submissionStats.pendingInContest(contest.id, contest.startAt, contest.endAt)
            if (pending > 0) {
                if (now.isBefore(contest.endAt.plus(VERDICT_GRACE))) {
                    log.info("Contest {} ended, rating waits for {} submission(s) still being judged", contest.id, pending)
                    return false
                }
                log.warn("Contest {}: {} submission(s) still unjudged {} after the end, rating is applied without them", contest.id, pending, VERDICT_GRACE)
            }
        } else if (!settled(contest, now)) return false
        contest.finalizedAt = finalizedAt

        val processor = contest.processor
        val submitted = if (forfeit) emptySet() else submissionStats.contestParticipants(contest.id)
        val players = setOfNotNull(contest.challengerId, contest.opponentId)
        val participants = if (contest.format == "duel") {
            if (forfeit || submitted.any { it in players }) players else emptySet()
        } else submitted

        val allStandings = contestService.standings(contestId.toString())
        if (contest.format == "duel" && contest.finalDuelResults == null) {
            contest.finalDuelResults = allStandings.map { row ->
                FinalDuelResult(UUID.fromString(row.user.id), row.bestScore?.let { FinalDuelScore(it.value, ScoreType.valueOf(it.type.value)) }, row.rank, row.solved)
            }
        }
        val standings = allStandings.filter { UUID.fromString(it.user.id) in participants }
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
        contest.ratingAppliedAt = finalizedAt
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
