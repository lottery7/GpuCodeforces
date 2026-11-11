package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.benchmark.ReferenceEnvironmentSnapshot
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.common.toUuidOrNotFound
import dev.gpucodeforces.backend.contest.ContestPhase
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemContext
import dev.gpucodeforces.backend.problem.ProblemEnvironments
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemService
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.Verdict
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import dev.gpucodeforces.judge.api.SubmissionStatus as JudgeStatus

/**
 * Submissions and their check queue. A submission is stored as `queued` and sent to the judge gateway right after
 * the commit; until the gateway accepts it, [resendUnsent] keeps retrying, so a crash or an unavailable judge only
 * delays it. The gateway deduplicates by submission id, so sending twice is harmless. Verdicts arrive
 * asynchronously as [JudgeEvent]s from RabbitMQ.
 */
@Service
class SubmissionService(
    private val submissionRepository: SubmissionRepository,
    private val problemService: ProblemService,
    private val problemCatalog: ProblemCatalog,
    private val contestService: ContestService,
    private val submissionStats: SubmissionStats,
    private val judgeGateway: JudgeGateway,
    private val judgeProperties: JudgeProperties,
    private val environments: ProblemEnvironments,
    private val transactionTemplate: TransactionTemplate,
    private val jsonMapper: JsonMapper,
    private val references: ReferenceMeasurements,
    private val contests: ContestRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun submit(problemId: String, contestId: String?, processor: String, language: String, code: String, author: User): Submission {
        val submittedAt = Instant.now()
        val problem = problemService.problem(problemId, submissionContext(contestId, author, submittedAt), author)
        if (problem.version.format == ProblemFormatData.GUESS) throw BadRequestException("Выберите более быстрый код вместо отправки решения")
        problemService.requireTarget(problem, processor, language)
        validateSource(code)
        if (submissionRepository.countByUserIdAndStatusIn(author.id, SubmissionStatus.PENDING) >= judgeProperties.maxPendingPerUser) {
            throw BadRequestException("Дождитесь проверки предыдущих посылок")
        }
        val version = problem.version
        val contest = (problem.context as? ProblemContext.InContest)?.contest
        val duel = contest?.takeIf { it.format == "duel" }
        val previousEnvironment = duel?.benchmarkEnvironmentKey ?: benchmarkEnvironmentKey(processor)
        val scope = ResultScope(
            contest?.id, mapOf(version.problemId to version.judgeHash),
            previousEnvironment, duel?.startAt, duel?.endAt?.takeIf { it.isBefore(submittedAt) } ?: submittedAt,
        )
        val submission = transactionTemplate.execute {
            if (duel != null) {
                val current = contests.findForUpdate(duel.id) ?: throw NotFoundApiException("Дуэль не найдена")
                if (current.ratingAppliedAt != null || current.finalDuelResults != null) throw BadRequestException("Результат дуэли уже зафиксирован; отправьте решение в практику")
                if (current.phaseAt(submittedAt) != ContestPhase.LIVE) throw BadRequestException("Решение пришло вне времени дуэли; отправьте его в практику")
            }
            submissionRepository.save(
                Submission(
                    id = UUID.randomUUID(),
                    userId = author.id,
                    problemId = version.problemId,
                    problemVersionId = version.id,
                    contestId = contest?.id,
                    code = code,
                    environmentMarkdown = environments.markdown(
                        problemCatalog.spec(version), version.judgeHash, processor, aggregation = problemCatalog.format(version).aggregation(),
                    ),
                    judgeHash = version.judgeHash,
                    processor = processor,
                    language = language,
                    previousBestScore = submissionStats.bestScore(author.id, version.problemId, scope, processor),
                    createdAt = submittedAt,
                ).apply { benchmarkEnvironmentKey = previousEnvironment?.takeUnless { it == "unavailable" } },
            )
        }!!
        withSubmission(submission.id) {
            log.info(
                "Submission created: problem={} version={} contest={} processor={} language={} code={} bytes",
                submission.problemId, submission.problemVersionId, submission.contestId ?: "practice", submission.processor, submission.language,
                code.toByteArray().size,
            )
        }
        return send(submission)
    }

    fun get(id: String): Submission =
        submissionRepository.findById(id.toUuidOrNotFound("Посылка не найдена")).orElseThrow { NotFoundApiException("Посылка не найдена") }

    /** Only after a judge failure: the same code, context, version and time are checked again. */
    fun recheck(id: String, author: User): Submission {
        val submission = transactionTemplate.execute {
            val submission = submissionRepository.findForUpdate(get(id).id)!!
            if (submission.userId != author.id) throw ForbiddenException("Повторить проверку может только автор посылки")
            if (submission.status != SubmissionStatus.SYSTEM_ERROR && submission.status != SubmissionStatus.MEASUREMENT_ERROR) {
                throw BadRequestException("Повторная проверка доступна только после сбоя проверки")
            }
            submission.contestId?.let { id ->
                val contest = contests.findForUpdate(id) ?: throw NotFoundApiException("Контест не найден")
                if (contest.format == "duel" && (contest.ratingAppliedAt != null || contest.finalDuelResults != null)) {
                    throw BadRequestException("Результат дуэли уже зафиксирован; отправьте решение в практику")
                }
            }
            submission.recheckCount++
            submission.status = SubmissionStatus.QUEUED
            submission.score = null
            submission.scoreType = null
            submission.metrics = emptyMap()
            submission.measurement = null
            submission.benchmarkEnvironmentKey = null
            submission.expectedEnvironment = null
            submission.previousBestScore = null
            submission.passedTests = 0
            submission.totalTests = null
            submission.diagnostic = ""
            submission.jobId = null
            submission.eventAttempt = 0
            submission.eventSeq = 0
            submission.finishedAt = null
            submission.updatedAt = Instant.now()
            submission
        }!!
        withSubmission(submission.id) { log.info("Recheck #{} requested, judge id {}", submission.recheckCount, submission.judgeSubmissionId) }
        return send(submission)
    }

    @Scheduled(fixedDelayString = "\${platform.judge.resend-interval:PT15S}")
    fun resendUnsent() {
        val unsent = submissionRepository.findUnsent(SubmissionStatus.QUEUED, Instant.now().minusSeconds(RESEND_AFTER_SECONDS))
        if (unsent.isNotEmpty()) log.info("Resending {} submission(s) the judge gateway has not accepted yet", unsent.size)
        unsent.forEach { send(it) }
    }

    /**
     * Hang detector: a submission the gateway accepted but the judge has not reported on for
     * `platform.judge.stuck-after` is logged with what is known about it, on every check until it moves.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "\${platform.judge.stuck-check-interval:PT1M}")
    fun reportStuck() {
        submissionRepository.findStuck(SubmissionStatus.PENDING, Instant.now().minus(judgeProperties.stuckAfter)).forEach { submission ->
            withSubmission(submission.id) {
                log.warn(
                    "No news from the judge for {} min: status={} job={} lastEvent=a{}/#{} judgeId={}. Check the gateway " +
                        "(GET /api/v1/queues: live executors of processor {}) and the executor log for this judge id",
                    Duration.between(submission.updatedAt, Instant.now()).toMinutes(), submission.status.value, submission.jobId,
                    submission.eventAttempt, submission.eventSeq, submission.judgeSubmissionId, submission.processor,
                )
            }
        }
    }

    /** Applies one judge event; events are unordered and may repeat, see [JudgeEvent]. */
    fun apply(event: JudgeEvent) {
        val id = event.submissionId.substringBefore(Submission.RECHECK_SEPARATOR)
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: return log.warn("Judge event for an id the backend never issued: {} (job {}, {})", event.submissionId, event.jobId, event.status)
        withSubmission(uuid) {
            log.debug(
                "Judge event {} a{}/#{} job={} from {} progress={}", event.status, event.attempt, event.seq, event.jobId, event.source,
                event.progress?.let { "${it.currentTest}/${it.totalTests}" },
            )
            transactionTemplate.executeWithoutResult { applyLocked(uuid, event) }
        }
    }

    private fun applyLocked(uuid: UUID, event: JudgeEvent) {
        val submission = submissionRepository.findForUpdate(uuid)
            ?: return log.warn("Judge event {} for a submission that does not exist", event.status)
        when {
            event.submissionId != submission.judgeSubmissionId ->
                return log.info("Ignoring {} of an earlier check {} (current {})", event.status, event.submissionId, submission.judgeSubmissionId)
            submission.status.terminal ->
                return log.debug("Ignoring {} a{}/#{}: the verdict is already stored", event.status, event.attempt, event.seq)
            submission.jobId != null && submission.jobId != event.jobId ->
                return log.warn("Ignoring {} of job {}: the gateway accepted this submission as job {}", event.status, event.jobId, submission.jobId)
        }
        val duel = if (event.status == JudgeStatus.FINISHED) submission.contestId?.let { contests.findForUpdate(it) }?.takeIf { it.format == "duel" } else null
        submission.jobId = event.jobId
        submission.updatedAt = Instant.now()
        if (event.status == JudgeStatus.FINISHED) {
            finish(submission, event, duel != null)
        } else if (compareValues(event.attempt, submission.eventAttempt).let { it > 0 || it == 0 && event.seq > submission.eventSeq }) {
            if (event.attempt > submission.eventAttempt && submission.eventAttempt > 0) {
                log.warn("The judge restarted the job: attempt {} after {} (executor crash or retry)", event.attempt, submission.eventAttempt)
            }
            val previous = submission.status
            submission.eventAttempt = event.attempt
            submission.eventSeq = event.seq
            submission.status = when (event.status) {
                JudgeStatus.QUEUED -> SubmissionStatus.QUEUED
                JudgeStatus.COMPILING, JudgeStatus.PREPARING -> SubmissionStatus.COMPILING
                JudgeStatus.TESTING, JudgeStatus.FINISHED -> SubmissionStatus.CHECKING
            }
            event.progress?.let {
                submission.totalTests = it.totalTests
                // the judge reports test 0 of N before the first test starts
                submission.passedTests = (it.currentTest - 1).coerceAtLeast(0)
            }
            if (previous != submission.status) {
                log.info(
                    "Status {} -> {} (judge {} a{}/#{} on {})",
                    previous.value, submission.status.value, event.status, event.attempt, event.seq, event.source,
                )
            }
        }
    }

    private fun finish(submission: Submission, event: JudgeEvent, duel: Boolean) {
        val result = event.result ?: error("FINISHED event ${event.jobId}/${event.attempt}/${event.seq} has no result")
        submission.status = result.verdict.toStatus()
        submission.passedTests = result.tests.count { it.verdict == Verdict.ACCEPTED }
        if (result.verdict == Verdict.ACCEPTED) {
            val score = result.score ?: error("Accepted result ${event.jobId} has no score")
            if (score.type != Processors.get(submission.processor).scoreType) {
                error("Accepted result ${event.jobId} has a ${score.type} score, ${submission.processor} reports ${Processors.get(submission.processor).scoreType}")
            }
            val expectedTestCount = submission.totalTests
            submission.totalTests = expectedTestCount ?: result.tests.size
            submission.score = BigDecimal.valueOf(score.value)
            submission.scoreType = score.type
            submission.metrics = result.metrics
            measure(submission, result, expectedTestCount, event.source)
            // An accepted submission ran every test of its processor: the problem page can list them now.
            if (submission.status == SubmissionStatus.ACCEPTED) environments.recordTests(submission.judgeHash, submission.processor, result.tests)
        }
        if (submission.status != SubmissionStatus.MEASUREMENT_ERROR) submission.diagnostic = result.diagnostic()
        submission.finishedAt = if (duel) Instant.now() else event.timestamp
        val format = "Verdict {} -> {}: passed {}/{} failedTest={} score={}, judged in {} ms on {} ({}): {}"
        val args = arrayOf<Any?>(
            result.verdict, submission.status.value, submission.passedTests, submission.totalTests ?: "?", result.failedTest,
            result.score?.let { "${it.value} ${it.type}" }, result.judgeTimeMs, result.processor?.model ?: event.source,
            result.processor?.executorId ?: event.source, result.message,
        )
        // A judge failure is not the contestant's fault: it has to stand out in the log.
        if (submission.status == SubmissionStatus.SYSTEM_ERROR) log.error(format, *args) else log.info(format, *args)
    }

    /**
     * The score under the problem's aggregation: the judge's maximum over the measured tests, or their geometric mean
     * for the formats (docs/backend/formats.md). On the real GPU records count within the current reference environment.
     */
    private fun measure(submission: Submission, result: JudgeResult, expectedTestCount: Int?, source: String) {
        val version = problemCatalog.versions(listOf(submission.problemVersionId)).getValue(submission.problemVersionId)
        try {
            val run = result.processor ?: throw InvalidMeasurementException("Результат не содержит процессор")
            if (run.id != submission.processor) throw InvalidMeasurementException("Результат пришёл от другого процессора")
            val expected = submission.expectedEnvironment?.let { jsonMapper.readValue(it, ReferenceEnvironmentSnapshot::class.java) }
            val environment = references.environmentFor(result, expected, source)
            val environmentKey = environment?.key ?: if (expected == null && run.executorId == source) references.environmentKeyFor(result) else null
            if (submission.processor == Processors.GPU_CUDA && environmentKey == null) {
                throw InvalidMeasurementException("Не удалось подтвердить окружение замера. Повторите проверку")
            }
            val measurement = captureMeasurement(
                result, problemCatalog.spec(version), problemCatalog.format(version).aggregation(), environment?.synthetic ?: judgeProperties.synthetic, expectedTestCount,
            )
            if (submission.processor == Processors.GPU_CUDA && submission.benchmarkEnvironmentKey != environmentKey || submission.previousBestScore == null) {
                val contest = submission.contestId?.let { contestService.get(it.toString()) }
                val duel = contest?.takeIf { it.format == "duel" }
                val end = duel?.endAt?.takeIf { it.isBefore(submission.createdAt) } ?: submission.createdAt
                val scope = ResultScope(submission.contestId, mapOf(version.problemId to version.judgeHash), environmentKey, duel?.startAt, end)
                submission.previousBestScore = submissionStats.bestScore(submission.userId, version.problemId, scope, submission.processor)
            }
            submission.score = measurement.score
            submission.measurement = jsonMapper.writeValueAsString(measurement.data)
            submission.benchmarkEnvironmentKey = environmentKey
        } catch (exception: InvalidMeasurementException) {
            submission.status = SubmissionStatus.MEASUREMENT_ERROR
            submission.score = null
            submission.scoreType = null
            submission.metrics = emptyMap()
            submission.measurement = null
            submission.diagnostic = exception.message!!
        }
    }

    /** Results on the real GPU are compared within the current reference environment, the others within the processor. */
    private fun benchmarkEnvironmentKey(processor: String): String? =
        if (processor == Processors.GPU_CUDA) references.environmentKey(processor) ?: "unavailable" else null

    private fun send(submission: Submission): Submission = withSubmission(submission.id) {
        val environment = references.environmentSnapshot(submission.processor)
        val prepared = transactionTemplate.execute {
            val current = submissionRepository.findForUpdate(submission.id)!!
            if (current.judgeSubmissionId != submission.judgeSubmissionId || current.status.terminal) return@execute null
            if (current.expectedEnvironment == null && current.jobId == null && environment != null) {
                current.expectedEnvironment = jsonMapper.writeValueAsString(environment)
            }
            current
        } ?: return@withSubmission get(submission.id.toString())
        val request = SubmissionRequest(
            submissionId = prepared.judgeSubmissionId,
            problem = problemCatalog.spec(problemCatalog.versions(listOf(prepared.problemVersionId)).getValue(prepared.problemVersionId)),
            source = prepared.code,
            processor = prepared.processor,
            language = prepared.language,
        )
        val outcome = judgeGateway.send(request)
        transactionTemplate.execute {
            val current = submissionRepository.findForUpdate(submission.id)!!
            if (current.judgeSubmissionId != request.submissionId || current.status.terminal) return@execute current
            when (outcome) {
                is SendOutcome.Accepted -> if (current.jobId == null) {
                    current.jobId = outcome.jobId
                    current.updatedAt = Instant.now()
                    log.info("Judge gateway accepted {} as job {}", request.submissionId, outcome.jobId)
                }
                is SendOutcome.Rejected -> {
                    log.error("Judge gateway rejected {}: {}", request.submissionId, outcome.reason)
                    current.status = SubmissionStatus.SYSTEM_ERROR
                    current.diagnostic = "Проверяющая система отклонила посылку."
                    current.finishedAt = Instant.now()
                }
                is SendOutcome.Retry -> if (current.createdAt.isBefore(Instant.now().minus(judgeProperties.sendTimeout))) {
                    log.error("Judge gateway did not accept {} within {}, giving up: {}", request.submissionId, judgeProperties.sendTimeout, outcome.reason)
                    current.status = SubmissionStatus.SYSTEM_ERROR
                    current.diagnostic = "Проверяющая система недоступна. Повторите проверку позже."
                    current.finishedAt = Instant.now()
                } else {
                    log.warn("Judge gateway did not accept {}, will retry: {}", request.submissionId, outcome.reason)
                }
            }
            current
        }!!
    }

    fun submissionContext(contestId: String?, author: User, submittedAt: Instant = Instant.now()): ProblemContext {
        if (contestId == null) return ProblemContext.Practice
        val context = problemService.context(contestId, author) as ProblemContext.InContest
        val contest = context.contest
        if (contest.phaseAt(submittedAt) != ContestPhase.LIVE) throw BadRequestException("Контест не идёт; решение можно отправить в практику")
        if (!contestService.isRegistered(contest.id, author.id)) throw ForbiddenException("Для отправки нужна регистрация на контест")
        if (contest.format == "duel" && contest.benchmarkEnvironmentKey != null && references.environmentKey(contest.processor) != contest.benchmarkEnvironmentKey) {
            throw ServiceUnavailableException("Окружение проверки этой дуэли сейчас недоступно")
        }
        return context
    }

    /** Every line logged inside carries the submission id (`sub` in the log pattern). */
    private fun <T> withSubmission(id: UUID, block: () -> T): T = MDC.putCloseable(SUBMISSION_MDC, id.toString()).use { block() }

    companion object {
        const val SUBMISSION_MDC = "submission"

        /** Submissions younger than this are still being sent by the request that created them. */
        private const val RESEND_AFTER_SECONDS = 10L

        fun validateSource(code: String) {
            val bytes = code.toByteArray().size
            val limit = SpecValidator.Limits().maxSourceBytes
            if (code.isBlank()) throw BadRequestException("Пустое решение")
            if (bytes > limit) throw BadRequestException("Решение больше $limit байт")
            if (code.contains('\u0000')) throw BadRequestException("Решение содержит нулевые байты")
        }
    }
}
