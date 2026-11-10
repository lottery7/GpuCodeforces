package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.api.ProblemsApi
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.CheckExampleRequest
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.ListProblemLeaders200ResponseInner
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import dev.gpucodeforces.backend.api.model.Problem as ProblemApiModel

@RestController
class ProblemsController(
    private val problemService: ProblemService,
    private val problemCatalog: ProblemCatalog,
    private val gpuProperties: GpuProperties,
) : ProblemsApi {
    override fun listProblems(
        context: String,
        q: String?,
        difficulty: String?,
        tag: String?,
        solved: Boolean?,
        sort: String,
        userId: String?,
        offset: Int,
        limit: Int,
    ): ResponseEntity<ListProblems200Response> {
        if (sort !in SORTS) throw BadRequestException("Неизвестная сортировка")
        val viewer = CurrentUser.resolve()
        val problemContext = problemService.context(context, viewer)
        val pool = problemService.problems(problemContext, viewer)
        val tags = pool.flatMap { it.version.tags }.distinct().sorted()
        val items = problemService.filter(pool, q, difficulty, tag)
            .map { it.version.toApiModel() }
            .filter { solved == null || it.solved == solved }
            .let { if (problemContext == ProblemContext.Practice) sorted(it, sort) else it }
        return ResponseEntity.ok(ListProblems200Response(items.drop(offset).take(limit), items.size, tags))
    }

    override fun getProblem(id: String, context: String): ResponseEntity<GetProblem200Response> {
        val viewer = CurrentUser.resolve()
        val problem = problemService.problem(id, problemService.context(context, viewer), viewer)
        val version = problem.version
        val spec = problemCatalog.spec(version)
        val summary = version.toApiModel()
        val gpuName = (problem.context as? ProblemContext.InContest)?.contest?.gpu ?: gpuProperties.name
        return ResponseEntity.ok(
            GetProblem200Response(
                summary.id, summary.code, summary.title, GetProblem200Response.DifficultyEnum.fromValue(summary.difficulty.value), summary.tags,
                summary.solved, summary.solvedCount, summary.lastSubmissionId, summary.lastStatus,
                summary.bestSubmissionId, summary.bestTimeMs, summary.recordTimeMs,
                version.statementMarkdown,
                version.starterCode,
                BigDecimal.valueOf(spec.limits.timeLimitMs).movePointLeft(3),
                spec.limits.memoryLimitMb.toInt(),
                environmentMarkdown(spec, gpuName),
            ),
        )
    }

    override fun checkExample(id: String, checkExampleRequest: CheckExampleRequest, context: String): ResponseEntity<CheckExample200Response> =
        throw ServiceUnavailableException("Проверка примера пока недоступна")

    override fun listProblemLeaders(id: String, context: String): ResponseEntity<List<ListProblemLeaders200ResponseInner>> {
        val viewer = CurrentUser.resolve()
        problemService.problem(id, problemService.context(context, viewer), viewer)
        return ResponseEntity.ok(emptyList())
    }

    private fun sorted(items: List<ProblemApiModel>, sort: String): List<ProblemApiModel> {
        val byCode = compareBy<ProblemApiModel> { it.code }
        return when (sort) {
            "difficulty" -> items.sortedWith(compareBy<ProblemApiModel> { ProblemPackageReader.DIFFICULTIES.indexOf(it.difficulty.value) }.then(byCode))
            "bestTime" -> items.sortedWith(compareBy<ProblemApiModel, BigDecimal?>(nullsLast()) { it.bestTimeMs }.then(byCode))
            else -> items.sortedWith(byCode)
        }
    }

    companion object {
        private val SORTS = setOf("code", "difficulty", "bestTime")
    }
}

fun ProblemVersion.toApiModel(): ProblemApiModel = ProblemApiModel(
    problemId,
    code,
    title,
    ProblemApiModel.DifficultyEnum.fromValue(difficulty),
    tags,
    false,
    0,
    null,
    null,
    null,
    null,
    null,
)
