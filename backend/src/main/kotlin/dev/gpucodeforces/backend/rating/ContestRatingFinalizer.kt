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
        contestRepository.findByFinalizedAtIsNullAndEndAtLessThanEqual(now)
            .forEach { ratingFinalizationService.finalize(it.id, now) }
    }
}
