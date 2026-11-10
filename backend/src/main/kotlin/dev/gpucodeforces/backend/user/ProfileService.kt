package dev.gpucodeforces.backend.user

import dev.gpucodeforces.backend.api.model.GetUserProfile200Response
import dev.gpucodeforces.backend.api.model.GetUserProfile200ResponseContestsInner
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.rating.RatingHistoryRepository
import dev.gpucodeforces.backend.submission.SubmissionStats
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ProfileService(
    private val userService: UserService,
    private val ratingHistoryRepository: RatingHistoryRepository,
    private val contestRepository: ContestRepository,
    private val submissionStats: SubmissionStats,
) {
    fun buildProfile(id: String): GetUserProfile200Response {
        val user = userService.getById(id)
        val now = Instant.now()
        val contests = ratingHistoryRepository.findByIdUserId(user.id)
            .mapNotNull { history -> contestRepository.findById(history.id.contestId).orElse(null)?.let { it to history } }
            .sortedByDescending { (contest, _) -> contest.startAt }
            .map { (contest, history) ->
                GetUserProfile200ResponseContestsInner(
                    contest.id.toString(),
                    contest.title,
                    GetUserProfile200ResponseContestsInner.PhaseEnum.fromValue(contest.phaseAt(now).value),
                    history.rank,
                    history.ratingDelta,
                    history.ratingAfter,
                )
            }
        return GetUserProfile200Response(user.toApiModel(), submissionStats.solvedProblemCount(user.id), contests)
    }
}
