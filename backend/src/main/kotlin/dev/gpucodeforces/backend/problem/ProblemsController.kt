package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.api.ProblemsApi
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.CheckExampleRequest
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.ListProblemLeaders200ResponseInner
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.api.model.SubmitGuessRequest
import dev.gpucodeforces.backend.api.model.CreateComparisonRequest
import dev.gpucodeforces.backend.api.model.Submission
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.api.model.ReferenceMeasurement
import dev.gpucodeforces.backend.api.model.ReferenceMeasurementStatus
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurementView
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.submission.ExampleChecks
import dev.gpucodeforces.backend.submission.ProblemResult
import dev.gpucodeforces.backend.submission.ResultScope
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.submission.GuessService
import dev.gpucodeforces.backend.submission.SubmissionViews
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.UserService
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.ZoneOffset
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
    private val guessService: GuessService,
    private val submissionViews: SubmissionViews,
    private val comparisonService: ComparisonService,
    private val submissionRepository: SubmissionRepository,
    private val judgeProperties: JudgeProperties,
    private val references: ReferenceMeasurements,
) : ProblemsApi {
    override fun listProblems(
        context: String,
        q: String?,
        difficulty: String?,
        format: String?,
        tag: String?,
        solved: Boolean?,
        sort: String,
        userId: String?,
        offset: Int,
        limit: Int,
    ): ResponseEntity<ListProblems200Response> {
        if (sort !in SORTS) throw BadRequestException("Неизвестная сортировка")
        val viewer = CurrentUser.resolve()
        val subject = userId?.let { userService.getById(it) } ?: viewer
        val problemContext = problemService.context(context, viewer)
        val pool = problemService.problems(problemContext, viewer)
        val tags = pool.flatMap { it.version.tags }.distinct().sorted()
        if (format != null && format !in ProblemFormatData.FORMATS) throw BadRequestException("Неизвестный формат")
        val filtered = problemService.filter(pool, q, difficulty, tag).filter { format == null || it.version.format == format }
        val measured = filtered.associate { it.version.id to references.view(it.version) }
        val environmentKey = references.environmentKey() ?: "unavailable"
        val results = filtered.groupBy { problem ->
            if (problem.version.format == ProblemFormatData.STANDARD) environmentKey else measured[problem.version.id]?.environmentKey ?: "unavailable"
        }.flatMap { (environment, group) -> submissionStats.problemResults(scope(problemContext, group, environment), subject?.id).entries }
            .associate { it.toPair() }
        val items = filtered
            .map { it.version.toApiModel(results.getValue(it.version.problemId), measured[it.version.id]) }
            .filter { solved == null || it.solved == solved }
            .let { if (problemContext == ProblemContext.Practice) sorted(it, sort) else it }
        return ResponseEntity.ok(ListProblems200Response(items.drop(offset).take(limit), items.size, tags))
    }

    override fun getProblem(id: String, context: String): ResponseEntity<GetProblem200Response> {
        val viewer = CurrentUser.resolve()
        val problemContext = problemService.context(context, viewer)
        val problem = problemService.problem(id, problemContext, viewer)
        val version = problem.version
        val spec = problemCatalog.spec(version)
        val format = problemCatalog.format(version)
        val reference = references.view(version)
        val environmentKey = if (version.format == ProblemFormatData.STANDARD) references.environmentKey() else reference?.environmentKey
        val summary = version.toApiModel(submissionStats.problemResults(scope(problemContext, listOf(problem), environmentKey ?: "unavailable"), viewer?.id).getValue(id), reference)
        val gpuName = references.environmentName() ?: gpuProperties.name
        val guessSubmissionId = if (viewer != null && version.format == ProblemFormatData.GUESS && reference?.status == "ready" && reference.key != null) {
            submissionRepository.findGuess(viewer.id, version.id, (problemContext as? ProblemContext.InContest)?.contest?.id, reference.key)?.id?.toString()
        } else null
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
                environmentMarkdown(spec, gpuName.takeUnless { judgeProperties.synthetic }),
            ).format(GetProblem200Response.FormatEnum.fromValue(version.format))
                .versionId(version.id.toString())
                .baselineTimeMs(summary.baselineTimeMs)
                .referenceMeasurement(summary.referenceMeasurement)
                .firstCode(format.firstCode)
                .secondCode(format.secondCode)
                .guessSubmissionId(guessSubmissionId)
                .benchmark(benchmark(spec, format, gpuName, judgeProperties.synthetic)),
        )
    }

    override fun submitGuess(id: String, submitGuessRequest: SubmitGuessRequest, context: String): ResponseEntity<Submission> {
        val author = CurrentUser.resolve()!!
        val submission = guessService.submit(id, context, submitGuessRequest.choice.value, author, submitGuessRequest.benchmarkKey)
        return ResponseEntity.ok(submissionViews.detail(submission, author))
    }

    override fun createComparison(id: String, createComparisonRequest: CreateComparisonRequest): ResponseEntity<ProblemApiModel> {
        val author = CurrentUser.resolve()!!
        val version = with(createComparisonRequest) {
            comparisonService.create(id, firstSubmissionId, secondSubmissionId, explanation, author)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            version.toApiModel(ProblemResult(false, 0, null, null, null), references.view(version)),
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

    private fun scope(context: ProblemContext, problems: List<ContextProblem>, environmentKey: String = references.environmentKey() ?: "unavailable") = ResultScope(
        (context as? ProblemContext.InContest)?.contest?.id,
        problems.associate { it.version.problemId to it.version.judgeHash },
        gpuProperties.profile,
        environmentKey,
    )

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
        private const val LEADERS_LIMIT = 20
    }
}

fun ProblemVersion.toApiModel(result: ProblemResult, reference: ReferenceMeasurementView? = null): ProblemApiModel = ProblemApiModel(
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
).format(ProblemApiModel.FormatEnum.fromValue(format))
    .baselineTimeMs(reference?.baselineTimeMs?.takeIf { reference.status == "ready" })
    .referenceMeasurement(reference?.let {
        ReferenceMeasurement().key(it.key).status(ReferenceMeasurementStatus.fromValue(it.status))
            .measuredAt(it.measuredAt?.atOffset(ZoneOffset.UTC)).diagnostic(it.diagnostic)
    })
