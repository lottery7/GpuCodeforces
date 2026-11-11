package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.api.VirtualApi
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.submission.ResultScope
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStats
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import dev.gpucodeforces.backend.api.model.VirtualParticipation as VirtualApiModel

@Component
class VirtualViews(
    private val contests: ContestService,
    private val catalog: ProblemCatalog,
    private val stats: SubmissionStats,
    private val submissions: SubmissionRepository,
    private val gpu: GpuProperties,
) {
    fun detail(participation: VirtualParticipation): VirtualApiModel {
        val contest = contests.get(participation.contestId.toString())
        val versions = catalog.versions(contests.problemsOf(contest.id).mapNotNull { it.problemVersionId }).values
        val key = "virtual:${participation.id}"
        val times = stats.contestBestTimes(ResultScope(contest.id, versions.associate { it.problemId to it.judgeHash }, contest.gpuProfile ?: gpu.profile, key))[participation.userId].orEmpty()
        val baselines = contests.baselines(contest)
        val speedup = times.entries.sumOf { (problem, time) -> (baselines[problem] ?: time).divide(time, 3, RoundingMode.HALF_UP) }
        val pending = submissions.search(participation.userId, null, null, key).any { !it.status.terminal }
        return VirtualApiModel(participation.id.toString(), contest.id.toString(), participation.startedAt.atOffset(ZoneOffset.UTC),
            participation.endsAt.atOffset(ZoneOffset.UTC), Instant.now().atOffset(ZoneOffset.UTC), pending, times.size, speedup, times)
    }
}

@RestController
class VirtualController(private val virtuals: VirtualParticipationService, private val views: VirtualViews) : VirtualApi {
    override fun getVirtual(id: String): ResponseEntity<VirtualApiModel> = ResponseEntity.ok(
        views.detail(virtuals.get(id.toUuidOrBadRequest("Неверная попытка"), CurrentUser.resolve())),
    )

    override fun listVirtual(): ResponseEntity<List<VirtualApiModel>> = ResponseEntity.ok(virtuals.list(CurrentUser.resolve()!!).map { views.detail(it) })
}
