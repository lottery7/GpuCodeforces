package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.api.ProblemsApi
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.CheckExampleRequest
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.ListProblemLeaders200ResponseInner
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.api.model.ProblemProcessor
import dev.gpucodeforces.backend.api.model.ProblemProcessorDetail
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.processor.scoreApiModel
import dev.gpucodeforces.backend.processor.toApiModel
import dev.gpucodeforces.backend.submission.AcceptedSubmission
import dev.gpucodeforces.backend.submission.ExampleChecks
import dev.gpucodeforces.backend.submission.ProblemResult
import dev.gpucodeforces.backend.submission.ResultScope
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.UserService
import dev.gpucodeforces.backend.user.toApiModel
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.Score
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import dev.gpucodeforces.backend.api.model.Problem as ProblemApiModel
import dev.gpucodeforces.backend.api.model.Score as ScoreApiModel

@RestController
class ProblemsController(
    private val problemService: ProblemService,
    private val problemCatalog: ProblemCatalog,
    private val environments: ProblemEnvironments,
    private val submissionStats: SubmissionStats,
    private val exampleChecks: ExampleChecks,
    private val userService: UserService,
    private val userRepository: UserRepository,
) : ProblemsApi {
    override fun listProblems(
        context: String,
        q: String?,
        difficulty: String?,
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
        val filtered = problemService.filter(pool, q, difficulty, tag, processor)
        val results = submissionStats.problemResults(scope(problemContext, filtered), subject?.id, Processors.ids)
        val items = filtered
            .map { it.toApiModel(results.getValue(it.version.problemId)) }
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
        val summary = problem.toApiModel(submissionStats.problemResults(scope(problemContext, listOf(problem)), viewer?.id, Processors.ids).getValue(id))
        val details = problemService.processorsOf(problem).map { processor ->
            val processorSpec = spec.processorSpec(processor)!!
            ProblemProcessorDetail(
                processor,
                version.starters.getValue(processor).filterKeys { it in processorSpec.languages },
                Score(spec.scoreLimit(processorSpec)!!, Processors.get(processor).scoreType).toApiModel(),
                environments.markdown(spec, version.judgeHash, processor),
            )
        }
        return ResponseEntity.ok(
            GetProblem200Response(
                summary.id, summary.code, summary.title, GetProblem200Response.DifficultyEnum.fromValue(summary.difficulty.value), summary.tags,
                summary.solved, summary.solvedCount, summary.lastSubmissionId, summary.lastStatus, summary.processors,
                version.statementMarkdown,
                spec.limits.memoryLimitMb.toInt(),
                details,
            ),
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

    private fun scope(context: ProblemContext, problems: List<ContextProblem>) = ResultScope(
        (context as? ProblemContext.InContest)?.contest?.id,
        problems.associate { it.version.problemId to it.version.judgeHash },
    )

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

    private fun ContextProblem.toApiModel(result: ProblemResult): ProblemApiModel = ProblemApiModel(
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
            ProblemProcessor(
                processor,
                problemCatalog.spec(version).processorSpec(processor)!!.languages,
                result.best[processor]?.id?.toString(),
                result.best[processor]?.scoreModel(),
                result.record[processor]?.scoreModel(),
            )
        },
    )

    private fun AcceptedSubmission.scoreModel(): ScoreApiModel = scoreApiModel(score, Processors.get(processor).scoreType)

    companion object {
        private const val BEST_SCORE = "bestScore"
        private val SORTS = setOf("code", "difficulty", BEST_SCORE)
        private const val LEADERS_LIMIT = 20
    }
}
