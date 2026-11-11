package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.toApiModel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import dev.gpucodeforces.backend.api.model.Contest as ContestApiModel

fun Contest.toApiModel(
    problems: List<ContestProblem>,
    viewer: User?,
    registered: Boolean,
    now: Instant,
    rulesMarkdown: String? = null,
    resultsPending: Boolean = false,
): ContestApiModel {
    val isAuthor = viewer != null && authors.any { it.id == viewer.id }
    val visibleProblemIds = if (phaseAt(now) == ContestPhase.UPCOMING && !isAuthor) {
        emptyList()
    } else {
        problems.sortedBy { it.position }.map { it.id.problemId }
    }
    return ContestApiModel(
        id.toString(),
        title,
        startAt.toOffsetDateTime(),
        endAt.toOffsetDateTime(),
        authors.map { it.toApiModel() },
        visibleProblemIds,
        problems.size,
        registered,
        processor,
    ).rulesMarkdown(rulesMarkdown)
        .format(ContestApiModel.FormatEnum.fromValue(format))
        .challengerId(challengerId?.toString())
        .opponentId(opponentId?.toString())
        .surrenderedUserId(surrenderedUserId?.toString())
        .resultsPending(resultsPending)
        .hackEndAt(hackEndAt?.toOffsetDateTime())
        .finalized(finalizedAt != null)
        .serverNow(now.toOffsetDateTime())
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
