package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.api.ProblemsApi
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.CheckExampleRequest
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.ListProblemLeaders200ResponseInner
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.api.model.ProblemProcessor
import dev.gpucodeforces.backend.api.model.ProblemProcessorDetail
import dev.gpucodeforces.backend.api.model.SubmitGuessRequest
import dev.gpucodeforces.backend.api.model.CreateComparisonRequest
import dev.gpucodeforces.backend.api.model.Submission
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.api.model.ReferenceMeasurement
import dev.gpucodeforces.backend.api.model.ReferenceMeasurementStatus
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurementView
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.processor.scoreApiModel
import dev.gpucodeforces.backend.processor.toApiModel
import dev.gpucodeforces.backend.submission.AcceptedSubmission
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
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.Score
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.ZoneOffset
import java.util.UUID
import dev.gpucodeforces.backend.api.model.Problem as ProblemApiModel
import dev.gpucodeforces.backend.api.model.Score as ScoreApiModel

@RestController
class ProblemsController(
    private val problemService: ProblemService,
    private val problemCatalog: ProblemCatalog,
    private val environments: ProblemEnvironments,
    private val processorCatalog: ProcessorCatalog,
    private val submissionStats: SubmissionStats,
    private val exampleChecks: ExampleChecks,
    private val userService: UserService,
    private val userRepository: UserRepository,
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
        processor: String?,
        sort: String,
        userId: String?,
        offset: Int,
        limit: Int,
    ): ResponseEntity<ListProblems200Response> {
        if (sort !in SORTS) throw BadRequestException("Неизвестная сортировка")
        if (sort == BEST_SCORE && processor == null) throw BadRequestException("Сортировка по score требует процессор")
        val viewer = CurrentUser.resolve()
        val subject = userId?.let { userService.getById(it) } ?: viewer
        val problemContext = problemService.context(context, viewer)
        val pool = problemService.problems(problemContext, viewer)
        val tags = pool.flatMap { it.version.tags }.distinct().sorted()
        if (format != null && format !in ProblemFormatData.FORMATS) throw BadRequestException("Неизвестный формат")
        val filtered = problemService.filter(pool, q, difficulty, tag, processor).filter { format == null || it.version.format == format }
        val measured = filtered.associate { it.version.id to references.views(it.version) }
        val results = results(problemContext, filtered, measured, subject?.id)
        val items = filtered
            .map { it.toApiModel(results.getValue(it.version.problemId), measured.getValue(it.version.id)) }
            .filter { solved == null || it.solved == solved }
            .let { if (problemContext == ProblemContext.Practice) sorted(it, sort, processor) else it }
        return ResponseEntity.ok(ListProblems200Response(items.drop(offset).take(limit), items.size, tags))
    }

    override fun getProblem(id: String, context: String): ResponseEntity<GetProblem200Response> {
        val viewer = CurrentUser.resolve()
        val problemContext = problemService.context(context, viewer)
        val problem = problemService.problem(id, problemContext, viewer)
        val version = problem.version
        val spec = problemCatalog.spec(version)
        val format = problemCatalog.format(version)
        val measured = references.views(version)
        val summary = problem.toApiModel(results(problemContext, listOf(problem), mapOf(version.id to measured), viewer?.id).getValue(id), measured)
        val details = problemService.processorsOf(problem).map { processor ->
            val processorSpec = spec.processorSpec(processor)!!
            ProblemProcessorDetail(
                processor,
                version.starters[processor].orEmpty().filterKeys { it in processorSpec.languages },
                Score(spec.scoreLimit(processorSpec)!!, Processors.get(processor).scoreType).toApiModel(),
                environments.markdown(spec, version.judgeHash, processor),
            ).benchmark(benchmark(spec, format, processor, model(processor), judgeProperties.synthetic))
        }
        // a guess has one processor: the answer belongs to the measurements of that processor
        val guessReference = if (version.format == ProblemFormatData.GUESS) measured.values.singleOrNull() else null
        val guessSubmissionId = if (viewer != null && guessReference?.status == "ready" && guessReference.key != null) {
            submissionRepository.findGuess(viewer.id, version.id, (problemContext as? ProblemContext.InContest)?.contest?.id, guessReference.key)
                ?.id?.toString()
        } else {
            null
        }
        return ResponseEntity.ok(
            GetProblem200Response(
                summary.id, summary.code, summary.title, GetProblem200Response.DifficultyEnum.fromValue(summary.difficulty.value), summary.tags,
                summary.solved, summary.solvedCount, summary.lastSubmissionId, summary.lastStatus, summary.processors,
                version.statementMarkdown,
                spec.limits.memoryLimitMb.toInt(),
                details,
            ).format(GetProblem200Response.FormatEnum.fromValue(version.format))
                .versionId(version.id.toString())
                .firstCode(format.firstCode)
                .secondCode(format.secondCode)
                .guessSubmissionId(guessSubmissionId),
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
        val problem = problemService.problem(version.problemId, ProblemContext.Practice, author)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            problem.toApiModel(ProblemResult(false, 0, null, emptyMap(), emptyMap()), references.views(version)),
        )
    }

    // SecurityConfig requires a session: the example runs on the judge.
    override fun checkExample(id: String, checkExampleRequest: CheckExampleRequest, context: String): ResponseEntity<CheckExample200Response> {
        val author = CurrentUser.resolve()!!
        val problem = problemService.problem(id, problemService.context(context, author), author)
        val outcome = with(checkExampleRequest) {
            problemService.requireTarget(problem, processor, language)
            exampleChecks.check(problem, processor, language, code, author)
        }
        return ResponseEntity.ok(CheckExample200Response(outcome.passed, outcome.diagnostic))
    }

    override fun listProblemLeaders(id: String, processor: String, context: String): ResponseEntity<List<ListProblemLeaders200ResponseInner>> {
        val viewer = CurrentUser.resolve()
        val problemContext = problemService.context(context, viewer)
        val problem = problemService.problem(id, problemContext, viewer)
        if (processor !in problemService.processorsOf(problem)) throw BadRequestException("Задачу нельзя сдавать на процессоре $processor")
        val leaders = submissionStats.leaders(id, scope(problemContext, listOf(problem)), processor, LEADERS_LIMIT)
        val users = userRepository.findAllById(leaders.map { it.submission.userId }).associateBy { it.id }
        return ResponseEntity.ok(
            leaders.map {
                ListProblemLeaders200ResponseInner(it.rank, users.getValue(it.submission.userId).toApiModel(), it.submission.scoreModel(), it.submission.id.toString())
            },
        )
    }

    private fun scope(context: ProblemContext, problems: List<ContextProblem>, gpuEnvironmentKey: String = currentGpuEnvironment()) = ResultScope(
        (context as? ProblemContext.InContest)?.contest?.id,
        problems.associate { it.version.problemId to it.version.judgeHash },
        gpuEnvironmentKey,
    )

    private fun currentGpuEnvironment(): String = references.environmentKey(Processors.GPU_CUDA) ?: "unavailable"

    /**
     * Results per problem. Records on the real GPU count within one environment: the current one, or for a format the
     * one its reference was measured in, so a speedup compares results of one cohort. Emulators give the same numbers
     * everywhere and are not filtered.
     */
    private fun results(
        context: ProblemContext, problems: List<ContextProblem>, measured: Map<UUID, Map<String, ReferenceMeasurementView>>, userId: UUID?,
    ): Map<String, ProblemResult> {
        val current = currentGpuEnvironment()
        return problems.groupBy { problem ->
            if (problem.version.format == ProblemFormatData.STANDARD) {
                current
            } else {
                measured[problem.version.id]?.get(Processors.GPU_CUDA)?.environmentKey ?: "unavailable"
            }
        }.flatMap { (environment, group) -> submissionStats.problemResults(scope(context, group, environment), userId, Processors.ids).entries }
            .associate { it.toPair() }
    }

    private fun sorted(items: List<ProblemApiModel>, sort: String, processor: String?): List<ProblemApiModel> {
        val byCode = compareBy<ProblemApiModel> { it.code }
        return when (sort) {
            "difficulty" -> items.sortedWith(compareBy<ProblemApiModel> { ProblemPackageReader.DIFFICULTIES.indexOf(it.difficulty.value) }.then(byCode))
            BEST_SCORE -> items.sortedWith(
                compareBy<ProblemApiModel, BigDecimal?>(nullsLast()) { problem -> problem.processors.find { it.id == processor }?.bestScore?.value }.then(byCode),
            )
            else -> items.sortedWith(byCode)
        }
    }

    /** What a person is shown as the model of [processor]: the GPU the executors report now, else the configured one. */
    private fun model(processor: String): String =
        (if (processor == Processors.GPU_CUDA) references.environmentName() else null) ?: processorCatalog.model(processor)

    private fun ContextProblem.toApiModel(result: ProblemResult, measured: Map<String, ReferenceMeasurementView>): ProblemApiModel = ProblemApiModel(
        version.problemId,
        version.code,
        version.title,
        ProblemApiModel.DifficultyEnum.fromValue(version.difficulty),
        version.tags,
        result.solved,
        result.solvedCount,
        result.last?.id?.toString(),
        result.last?.status?.value,
        problemService.processorsOf(this).map { processor ->
            val reference = measured[processor]
            ProblemProcessor(
                processor,
                problemCatalog.spec(version).processorSpec(processor)!!.languages,
                result.best[processor]?.id?.toString(),
                result.best[processor]?.scoreModel(),
                result.record[processor]?.scoreModel(),
            ).baselineScore(reference?.takeIf { it.status == "ready" }?.baselineScore?.let { scoreApiModel(it, reference.scoreType) })
                .referenceMeasurement(reference?.let {
                    ReferenceMeasurement().key(it.key).status(ReferenceMeasurementStatus.fromValue(it.status))
                        .measuredAt(it.measuredAt?.atOffset(ZoneOffset.UTC)).diagnostic(it.diagnostic)
                })
        },
    ).format(ProblemApiModel.FormatEnum.fromValue(version.format))

    // records and leaders hold scored submissions only (SubmissionStats): an answer to a guess has none
    private fun AcceptedSubmission.scoreModel(): ScoreApiModel = scoreApiModel(score!!, Processors.get(processor).scoreType)

    companion object {
        private const val BEST_SCORE = "bestScore"
        private val SORTS = setOf("code", "difficulty", BEST_SCORE)
        private const val LEADERS_LIMIT = 20
    }
}
