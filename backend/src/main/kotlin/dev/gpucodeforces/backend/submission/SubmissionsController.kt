package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.common.ContentLanguage
import dev.gpucodeforces.backend.api.SubmissionsApi
import dev.gpucodeforces.backend.api.model.BenchmarkAggregation
import dev.gpucodeforces.backend.api.model.GuessChoice
import dev.gpucodeforces.backend.api.model.GuessResult
import dev.gpucodeforces.backend.api.model.ListLatestSubmissions200Response
import dev.gpucodeforces.backend.api.model.ListSubmissions200Response
import dev.gpucodeforces.backend.api.model.MeasurementSummary
import dev.gpucodeforces.backend.api.model.MeasurementTiming
import dev.gpucodeforces.backend.api.model.SubmitSolutionRequest
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.UnauthorizedException
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.contest.VirtualParticipation
import dev.gpucodeforces.backend.contest.VirtualParticipationRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemService
import dev.gpucodeforces.backend.processor.scoreApiModel
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.UserService
import dev.gpucodeforces.backend.user.toApiModel
import dev.gpucodeforces.judge.api.Processors
import java.math.BigDecimal
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import dev.gpucodeforces.backend.api.model.Submission as SubmissionApiModel

@RestController
class SubmissionsController(
    private val submissionService: SubmissionService,
    private val submissionRepository: SubmissionRepository,
    private val userService: UserService,
    private val views: SubmissionViews,
    private val problems: ProblemService,
) : SubmissionsApi {
    override fun listSubmissions(
        userId: String?,
        problemId: String?,
        context: String,
        status: String?,
        offset: Int,
        limit: Int,
    ): ResponseEntity<ListSubmissions200Response> {
        val target = userId?.let { userService.getById(it) } ?: CurrentUser.resolve() ?: throw UnauthorizedException("Нужен вход или userId")
        val statusFilter = status?.let { SubmissionStatus.ofOrNull(it) ?: throw BadRequestException("Неизвестный статус") }
        val viewer = CurrentUser.resolve()
        val contextKey = context.takeUnless { it == ALL }?.let { problems.context(it, viewer).key }
        // submissions of a virtual attempt are seen by its owner alone
        val rows = submissionRepository.search(target.id, problemId, statusFilter, contextKey)
            .filter { !it.contextKey.startsWith(VirtualParticipation.CONTEXT_PREFIX) || it.userId == viewer?.id }
        return ResponseEntity.ok(ListSubmissions200Response(views.summaries(rows.drop(offset).take(limit)), rows.size))
    }

    // SecurityConfig requires a session for submitting and rechecking.
    override fun submitSolution(submitSolutionRequest: SubmitSolutionRequest): ResponseEntity<SubmissionApiModel> {
        val author = CurrentUser.resolve()!!
        val submission = with(submitSolutionRequest) { submissionService.submit(problemId, contestId, processor, language, code, author, context) }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(views.detail(submission, author))
    }

    override fun listLatestSubmissions(offset: Int, limit: Int): ResponseEntity<ListLatestSubmissions200Response> =
        ResponseEntity.ok(ListLatestSubmissions200Response(views.summaries(submissionRepository.findLatest(offset, limit))))

    override fun getSubmission(id: String): ResponseEntity<SubmissionApiModel> =
        ResponseEntity.ok(views.detail(submissionService.get(id), CurrentUser.resolve()))

    override fun recheckSubmission(id: String): ResponseEntity<SubmissionApiModel> {
        val author = CurrentUser.resolve()!!
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(views.detail(submissionService.recheck(id, author), author))
    }

    companion object {
        private const val ALL = "all"
    }
}

/** Maps submissions to the contract, loading problems, users and contests of a page at once. */
@Component
class SubmissionViews(
    private val problemCatalog: ProblemCatalog,
    private val contestService: ContestService,
    private val contestRepository: ContestRepository,
    private val userRepository: UserRepository,
    private val jsonMapper: JsonMapper,
    private val references: ReferenceMeasurements,
    private val virtuals: VirtualParticipationRepository,
) {
    /** For the request being answered: the titles of the problems come in its language. */
    fun summaries(rows: List<SubmissionRow>): List<SubmissionApiModel> {
        val language = ContentLanguage.current()
        val versions = problemCatalog.versions(rows.map { it.problemVersionId }.toSet())
        val users = userRepository.findAllById(rows.map { it.userId }.toSet()).associateBy { it.id }
        val contests = contestRepository.findAllById(rows.mapNotNull { it.contestId }.toSet()).associateBy { it.id }
        val comparableHashes = mutableMapOf<Pair<String, UUID?>, String?>()
        val gpuEnvironmentKey = references.environmentKey(Processors.GPU_CUDA)
        return rows.map { row ->
            val contest = row.contestId?.let { contests.getValue(it) }
            val duel = contest?.takeIf { it.format == "duel" && row.contextKey == it.id.toString() }
            val comparableEnvironment = duel?.benchmarkEnvironmentKey ?: gpuEnvironmentKey
            val finishedBefore = duel?.ratingAppliedAt
            // Accepted results are comparable while the context still judges this problem the same way.
            val contextHash = comparableHashes.getOrPut(row.problemId to row.contestId) { contextJudgeHash(row.problemId, row.contestId) }
            SubmissionApiModel(
                row.id.toString(),
                row.problemId,
                versions.getValue(row.problemVersionId).title(language),
                users.getValue(row.userId).toApiModel(),
                row.contestId?.toString(),
                contest?.title,
                row.createdAt.atOffset(ZoneOffset.UTC),
                SubmissionApiModel.StatusEnum.fromValue(row.status.value),
                row.processor,
                row.language,
                // The judge sets a score and its type together, and SubmissionService stores them so.
                row.score?.let { scoreApiModel(it, row.scoreType!!) },
                // Scores are compared within a processor; on the real GPU also within the current environment.
                row.status == SubmissionStatus.ACCEPTED && row.score != null && row.judgeHash == contextHash &&
                    (row.processor != Processors.GPU_CUDA || comparableEnvironment != null && row.benchmarkEnvironmentKey == comparableEnvironment) &&
                    (duel == null || row.processor == duel.processor && !row.createdAt.isBefore(duel.startAt) && row.createdAt.isBefore(duel.endAt)) &&
                    (finishedBefore == null || row.finishedAt == null || !row.finishedAt.isAfter(finishedBefore)),
            ).format(SubmissionApiModel.FormatEnum.fromValue(versions.getValue(row.problemVersionId).format))
                .context(row.contextKey)
        }
    }

    /** Detail of one submission; code and diagnostic only for its author. */
    fun detail(submission: Submission, viewer: User?): SubmissionApiModel {
        if (submission.contextKey.startsWith(VirtualParticipation.CONTEXT_PREFIX) && submission.userId != viewer?.id) {
            throw ForbiddenException("Это посылка чужого виртуального участия")
        }
        val summary = summaries(listOf(submission.toRow())).single()
            .passedTests(submission.passedTests)
            .totalTests(submission.totalTests)
            .previousBestScore(submission.previousBestScore?.let { scoreApiModel(it, Processors.get(submission.processor).scoreType) })
            .metrics(submission.metrics.mapValues { BigDecimal.valueOf(it.value) })
            .environmentMarkdown(submission.environmentMarkdown)
        submission.measurement?.let { source ->
            val measured = jsonMapper.readValue(source, MeasurementData::class.java)
            summary.measurement(
                MeasurementSummary().aggregation(BenchmarkAggregation.fromValue(measured.aggregation))
                    .timingMode(measured.timingMode?.let(MeasurementTiming::fromValue))
                    .model(measured.model.takeUnless { measured.synthetic })
                    .synthetic(measured.synthetic)
                    .testCount(measured.tests.size),
            )
        }
        val isAuthor = viewer?.id == submission.userId
        val now = Instant.now()
        val contest = submission.contestId?.let { contestService.get(it.toString()) }
        // Official code is published once the contest stops taking solutions, yet not to somebody who is solving a
        // contest virtually right now.
        val open = contest != null && submission.official && !now.isBefore(contest.endAt) &&
            (contest.format != "duel" || viewer != null && (viewer.id == contest.challengerId || viewer.id == contest.opponentId)) &&
            !(viewer != null && virtuals.existsByUserIdAndStartedAtLessThanEqualAndEndsAtGreaterThan(viewer.id, now, now))
        summary.sourcePublic(open)
        summary.recheckAllowed(
            isAuthor && (submission.status == SubmissionStatus.SYSTEM_ERROR || submission.status == SubmissionStatus.MEASUREMENT_ERROR) &&
                !(submission.official && (contest?.finalizedAt != null || contest?.format == "duel" &&
                    (contest.ratingAppliedAt != null || contest.finalDuelResults != null))),
        )
        summary.hackable(
            open && contest.hacksOpen(now) && submission.status == SubmissionStatus.ACCEPTED && submission.guessChoice == null &&
                viewer != null && !isAuthor &&
                problemCatalog.spec(problemCatalog.versions(listOf(submission.problemVersionId)).getValue(submission.problemVersionId)).allowCustomTests,
        )
        if (!isAuthor) return if (open) summary.code(submission.code) else summary
        summary.code(submission.code).diagnostic(submission.diagnostic)
        submission.guessChoice?.let { choice ->
            val data = submission.guessResult?.let { jsonMapper.readValue(it, GuessSnapshot::class.java) } ?: return summary
            val explanation = data.explanation(ContentLanguage.current())
            // the diagnostic of an answer is the explanation saved with it (GuessService): it follows the language too
            summary.diagnostic(explanation)
            summary.guessResult(GuessResult(
                GuessChoice.fromValue(choice), GuessChoice.fromValue(data.correctChoice()),
                scoreApiModel(data.firstScore, data.scoreType), scoreApiModel(data.secondScore, data.scoreType), explanation,
            ).benchmarkKey(data.benchmarkKey).synthetic(data.synthetic))
        }
        return summary
    }

    private fun contextJudgeHash(problemId: String, contestId: UUID?): String? = if (contestId == null) {
        problemCatalog.currentVersion(problemId)?.judgeHash
    } else {
        contestService.problemsOf(contestId).firstOrNull { it.id.problemId == problemId }?.problemVersionId
            ?.let { problemCatalog.versions(listOf(it))[it]?.judgeHash }
    }
}
