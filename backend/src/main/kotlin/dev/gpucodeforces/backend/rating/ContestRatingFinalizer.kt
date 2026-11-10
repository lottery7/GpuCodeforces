package dev.gpucodeforces.backend.rating

import dev.gpucodeforces.backend.contest.ContestRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ContestRatingFinalizer(
    private val contestRepository: ContestRepository,
    private val ratingFinalizationService: RatingFinalizationService,
) {
    @Scheduled(fixedDelay = 60_000)
    fun finalizeDueContests() {
        val now = Instant.now()
        // In end order: a rating change has to include every contest that ended before it.
        for (contest in contestRepository.findAll().filter { it.ratingAppliedAt == null && it.endAt.isBefore(now) }.sortedBy { it.endAt }) {
            if (!ratingFinalizationService.finalize(contest.id, now)) break
        }
    }
}
