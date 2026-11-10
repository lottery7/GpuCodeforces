package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.api.SubmissionsApi
import dev.gpucodeforces.backend.api.model.ListSubmissions200Response
import dev.gpucodeforces.backend.api.model.SubmitSolutionRequest
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.UnauthorizedException
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.UserService
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID
import dev.gpucodeforces.backend.api.model.Submission as SubmissionApiModel

@RestController
class SubmissionsController(
    private val submissionService: SubmissionService,
    private val submissionRepository: SubmissionRepository,
    private val userService: UserService,
    private val views: SubmissionViews,
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
        val contestId = context.takeUnless { it == ALL || it == PRACTICE }?.toUuidOrBadRequest("Неизвестный контекст")
        val rows = submissionRepository.search(target.id, problemId, statusFilter, context == PRACTICE, contestId)
        return ResponseEntity.ok(ListSubmissions200Response(views.summaries(rows.drop(offset).take(limit)), rows.size))
    }

    // SecurityConfig requires a session for submitting and rechecking.
    override fun submitSolution(submitSolutionRequest: SubmitSolutionRequest): ResponseEntity<SubmissionApiModel> {
        val author = CurrentUser.resolve()!!
        val submission = with(submitSolutionRequest) { submissionService.submit(problemId, contestId, code, author) }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(views.detail(submission, author))
    }

    override fun getSubmission(id: String): ResponseEntity<SubmissionApiModel> =
        ResponseEntity.ok(views.detail(submissionService.get(id), CurrentUser.resolve()))

    override fun recheckSubmission(id: String): ResponseEntity<SubmissionApiModel> {
        val author = CurrentUser.resolve()!!
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(views.detail(submissionService.recheck(id, author), author))
    }

    companion object {
        private const val ALL = "all"
        private const val PRACTICE = "practice"
    }
}

/** Maps submissions to the contract, loading problems, users and contests of a page at once. */
@Component
class SubmissionViews(
    private val problemCatalog: ProblemCatalog,
    private val contestService: ContestService,
    private val contestRepository: ContestRepository,
    private val userRepository: UserRepository,
    private val gpuProperties: GpuProperties,
) {
    fun summaries(rows: List<SubmissionRow>): List<SubmissionApiModel> {
        val versions = problemCatalog.versions(rows.map { it.problemVersionId }.toSet())
        val users = userRepository.findAllById(rows.map { it.userId }.toSet()).associateBy { it.id }
        val contests = contestRepository.findAllById(rows.mapNotNull { it.contestId }.toSet()).associateBy { it.id }
        val comparableHashes = mutableMapOf<Pair<String, UUID?>, String?>()
        return rows.map { row ->
            val contest = row.contestId?.let { contests.getValue(it) }
            // Accepted results are comparable while the context still judges this problem the same way.
            val contextHash = comparableHashes.getOrPut(row.problemId to row.contestId) { contextJudgeHash(row.problemId, row.contestId) }
            SubmissionApiModel(
                row.id.toString(),
                row.problemId,
                versions.getValue(row.problemVersionId).title,
                users.getValue(row.userId).toApiModel(),
                row.contestId?.toString(),
                contest?.title,
                row.createdAt.atOffset(ZoneOffset.UTC),
                SubmissionApiModel.StatusEnum.fromValue(row.status.value),
                row.timeMs,
                row.status == SubmissionStatus.ACCEPTED && row.judgeHash == contextHash && row.gpuProfile == gpuProperties.profile,
            )
        }
    }

    /** Detail of one submission; code and diagnostic only for its author. */
    fun detail(submission: Submission, viewer: User?): SubmissionApiModel {
        val summary = summaries(listOf(submission.toRow())).single()
            .passedTests(submission.passedTests)
            .totalTests(submission.totalTests)
            .previousBestTimeMs(submission.previousBestTimeMs)
            .environmentMarkdown(submission.environmentMarkdown)
        return if (viewer?.id == submission.userId) summary.code(submission.code).diagnostic(submission.diagnostic) else summary
    }

    private fun contextJudgeHash(problemId: String, contestId: UUID?): String? = if (contestId == null) {
        problemCatalog.currentVersion(problemId)?.judgeHash
    } else {
        contestService.problemsOf(contestId).firstOrNull { it.id.problemId == problemId }?.problemVersionId
            ?.let { problemCatalog.versions(listOf(it))[it]?.judgeHash }
    }
}
