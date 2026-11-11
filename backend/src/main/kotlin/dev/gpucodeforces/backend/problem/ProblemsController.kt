package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.api.ProblemsApi
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.CheckExampleRequest
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.ListProblemLeaders200ResponseInner
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.submission.ExampleChecks
import dev.gpucodeforces.backend.submission.ProblemResult
import dev.gpucodeforces.backend.submission.ResultScope
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.UserService
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import dev.gpucodeforces.backend.api.model.Problem as ProblemApiModel

@RestController
class ProblemsController(
    private val problemService: ProblemService,
    private val problemCatalog: ProblemCatalog,
    private val submissionStats: SubmissionStats,
    private val exampleChecks: ExampleChecks,
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val gpuProperties: GpuProperties,
    private val problemListing: ProblemListing,
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
        val viewer = CurrentUser.resolve()
        val subject = userId?.let { userService.getById(it) } ?: viewer
        val problemContext = problemService.context(context, viewer)
        val page = problemListing.page(problemContext, viewer, subject?.id, q, difficulty, tag, solved, sort, problemContext.gpuProfile(gpuProperties.profile), offset, limit)
        val results = submissionStats.problemResults(ResultScope(
            (problemContext as? ProblemContext.InContest)?.contest?.id,
            page.items.associate { it.problemId to it.judgeHash }, problemContext.gpuProfile(gpuProperties.profile), problemContext.key,
        ), subject?.id)
        val items = page.items.map { metadata ->
            val result = results.getValue(metadata.problemId)
            ProblemApiModel(metadata.problemId, metadata.code, metadata.title, ProblemApiModel.DifficultyEnum.fromValue(metadata.difficulty),
                metadata.tags, result.solved, result.solvedCount, result.last?.id?.toString(), result.last?.status?.value,
                result.best?.id?.toString(), result.best?.timeMs, result.record?.timeMs)
        }
        return ResponseEntity.ok(ListProblems200Response(items, page.total, page.tags))
    }

    override fun getProblem(id: String, context: String): ResponseEntity<GetProblem200Response> {
        val viewer = CurrentUser.resolve()
        val problemContext = problemService.context(context, viewer)
        val problem = problemService.problem(id, problemContext, viewer)
        val version = problem.version
        val spec = problemCatalog.spec(version)
        val summary = version.toApiModel(submissionStats.problemResults(scope(problemContext, listOf(problem)), viewer?.id).getValue(id))
        val gpuName = (problemContext as? ProblemContext.InContest)?.contest?.gpu ?: gpuProperties.name
        return ResponseEntity.ok(
            GetProblem200Response(
                summary.id, summary.code, summary.title, GetProblem200Response.DifficultyEnum.fromValue(summary.difficulty.value), summary.tags,
                summary.solved, summary.solvedCount, summary.lastSubmissionId, summary.lastStatus,
                summary.bestSubmissionId, summary.bestTimeMs, summary.recordTimeMs,
                version.statementMarkdown,
                version.starterCode,
                BigDecimal.valueOf(spec.limits.timeLimitMs).movePointLeft(3),
                // The page shows it as GPU memory; without a GPU limit the solution is bounded by its RAM limit.
                (spec.limits.gpuMemoryLimitMb ?: spec.limits.memoryLimitMb).toInt(),
                environmentMarkdown(spec, gpuName),
            ),
        )
    }

    // SecurityConfig requires a session: the example runs on the judge's GPU.
    override fun checkExample(id: String, checkExampleRequest: CheckExampleRequest, context: String): ResponseEntity<CheckExample200Response> {
        val author = CurrentUser.resolve()!!
        val problem = problemService.problem(id, problemService.context(context, author), author)
        val outcome = exampleChecks.check(problem, checkExampleRequest.code, author)
        return ResponseEntity.ok(CheckExample200Response(outcome.passed, outcome.diagnostic))
    }

    override fun listProblemLeaders(id: String, context: String): ResponseEntity<List<ListProblemLeaders200ResponseInner>> {
        val viewer = CurrentUser.resolve()
        val problemContext = problemService.context(context, viewer)
        val problem = problemService.problem(id, problemContext, viewer)
        val leaders = submissionStats.leaders(id, scope(problemContext, listOf(problem)), LEADERS_LIMIT)
        val users = userRepository.findAllById(leaders.map { it.submission.userId }).associateBy { it.id }
        return ResponseEntity.ok(
            leaders.map {
                ListProblemLeaders200ResponseInner(it.rank, users.getValue(it.submission.userId).toApiModel(), it.submission.timeMs, it.submission.id.toString())
            },
        )
    }

    private fun scope(context: ProblemContext, problems: List<ContextProblem>) = ResultScope(
        (context as? ProblemContext.InContest)?.contest?.id,
        problems.associate { it.version.problemId to it.version.judgeHash },
        context.gpuProfile(gpuProperties.profile),
        context.key,
    )

    companion object {
        private const val LEADERS_LIMIT = 20
    }
}

fun ProblemVersion.toApiModel(result: ProblemResult): ProblemApiModel = ProblemApiModel(
    problemId,
    code,
    title,
    ProblemApiModel.DifficultyEnum.fromValue(difficulty),
    tags,
    result.solved,
    result.solvedCount,
    result.last?.id?.toString(),
    result.last?.status?.value,
    result.best?.id?.toString(),
    result.best?.timeMs,
    result.record?.timeMs,
)
