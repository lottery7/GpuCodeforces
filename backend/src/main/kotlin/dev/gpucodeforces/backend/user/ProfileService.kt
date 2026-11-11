package dev.gpucodeforces.backend.user

import dev.gpucodeforces.backend.api.model.GetUserProfile200Response
import dev.gpucodeforces.backend.api.model.GetUserProfile200ResponseContestsInner
import dev.gpucodeforces.backend.api.model.RatingHistoryPoint
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.rating.RatingHistory
import dev.gpucodeforces.backend.rating.RatingHistoryRepository
import dev.gpucodeforces.backend.submission.SubmissionStats
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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
                    history.processor,
                    history.rank,
                    history.ratingDelta,
                    history.ratingAfter,
                )
            }
        return GetUserProfile200Response(user.toApiModel(), submissionStats.solvedProblemCount(user.id), contests)
    }

    /**
     * Rating change of every finished contest within [after, before], ordered by contest start ascending. Every
     * processor has a rating of its own, so a chart is drawn from the points of one [processor].
     */
    fun ratingHistory(userId: UUID, processor: String?, after: Instant?, before: Instant?): List<RatingHistoryPoint> =
        ratingHistoryRepository.findByIdUserId(userId)
            .filter { processor == null || it.processor == processor }
            .mapNotNull { history -> contestRepository.findById(history.id.contestId).orElse(null)?.let { history to it } }
            .filter { (_, contest) ->
                (after == null || !contest.startAt.isBefore(after)) && (before == null || !contest.startAt.isAfter(before))
            }
            .sortedBy { (_, contest) -> contest.startAt }
            .map { (history, contest) -> history.toApiModel(contest.title, contest.startAt) }
}

private fun RatingHistory.toApiModel(title: String, startAt: Instant): RatingHistoryPoint = RatingHistoryPoint(
    id.contestId.toString(),
    title,
    processor,
    startAt.atOffset(ZoneOffset.UTC),
    rank,
    ratingDelta,
    ratingAfter,
)
