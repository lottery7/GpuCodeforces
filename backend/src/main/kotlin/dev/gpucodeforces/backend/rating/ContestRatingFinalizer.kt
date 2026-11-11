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
        val due = contestRepository.findByFinalizedAtIsNullAndEndAtLessThanEqual(now)
        // In the order the results become final within a processor: a rating change has to include every contest of
        // that rating whose results came before it. Ratings of different processors are independent, so one waiting
        // contest holds back only its own.
        for ((_, contests) in due.groupBy { it.processor }) {
            for (contest in contests.sortedBy { it.resultsAt }) {
                if (!ratingFinalizationService.finalize(contest.id, now)) break
            }
        }
    }
}
