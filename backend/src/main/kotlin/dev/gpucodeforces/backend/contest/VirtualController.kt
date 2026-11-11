package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.api.VirtualApi
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStats
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import dev.gpucodeforces.backend.api.model.VirtualParticipation as VirtualApiModel

@Component
class VirtualViews(private val stats: SubmissionStats, private val submissions: SubmissionRepository) {
    /** The attempt with its result, counted like a row of the contest standings from the start of the attempt. */
    fun detail(participation: VirtualParticipation): VirtualApiModel {
        val key = participation.contextKey
        val solveTimes = stats.firstAcceptedIn(key)[participation.userId].orEmpty()
            .mapValues { (_, at) -> Duration.between(participation.startedAt, at).seconds.coerceAtLeast(0).toInt() }
        val pending = submissions.search(participation.userId, null, null, key).any { !it.status.terminal }
        return VirtualApiModel(
            participation.id.toString(), participation.contestId.toString(), participation.startedAt.atOffset(ZoneOffset.UTC),
            participation.endsAt.atOffset(ZoneOffset.UTC), Instant.now().atOffset(ZoneOffset.UTC), pending, solveTimes.size,
            solveTimes.values.sum(), solveTimes,
        )
    }
}

@RestController
class VirtualController(private val virtuals: VirtualParticipationService, private val views: VirtualViews) : VirtualApi {
    override fun getVirtual(id: String): ResponseEntity<VirtualApiModel> = ResponseEntity.ok(
        views.detail(virtuals.get(id.toUuidOrBadRequest("Неверная попытка"), CurrentUser.resolve())),
    )

    // SecurityConfig requires a session on this route.
    override fun listVirtual(): ResponseEntity<List<VirtualApiModel>> = ResponseEntity.ok(virtuals.list(CurrentUser.resolve()!!).map { views.detail(it) })
}
