package dev.gpucodeforces.backend.rating

import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.user.UserRepository
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
) {
    /** Idempotent: a contest with ratingAppliedAt already set is left untouched. */
    @Transactional
    fun finalize(contestId: UUID, now: Instant) {
        val contest = contestRepository.findById(contestId).orElse(null) ?: return
        if (contest.ratingAppliedAt != null) return

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
