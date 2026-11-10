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
        gpu,
    ).rulesMarkdown(rulesMarkdown)
        .format(ContestApiModel.FormatEnum.fromValue(format))
        .challengerId(challengerId?.toString())
        .opponentId(opponentId?.toString())
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
