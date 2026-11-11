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
        val due = contestRepository.findAll().filter { it.ratingAppliedAt == null && it.endAt.isBefore(now) }
        // In end order within a processor: a rating change has to include every contest of that rating that ended
        // before it. Ratings of different processors are independent, so one waiting contest holds back only its own.
        for ((_, contests) in due.groupBy { it.processor }) {
            for (contest in contests.sortedBy { it.endAt }) {
                if (!ratingFinalizationService.finalize(contest.id, now)) break
            }
        }
    }
}
