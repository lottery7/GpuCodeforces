package dev.gpucodeforces.backend.benchmark

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.submission.InvalidMeasurementException
import dev.gpucodeforces.backend.submission.MeasurementData
import dev.gpucodeforces.backend.submission.JudgeGateway
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.backend.submission.SendOutcome
import dev.gpucodeforces.backend.submission.captureMeasurement
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The reference measurements of a format on one [processor]; scores are in [scoreType] and only when [status] is ready. */
data class ReferenceMeasurementView(
    val processor: String,
    val key: String?,
    val status: String,
    val scoreType: ScoreType,
    val baselineScore: BigDecimal?,
    val firstScore: BigDecimal?,
    val secondScore: BigDecimal?,
    val measuredAt: Instant?,
    val synthetic: Boolean,
    val diagnostic: String?,
    val environmentKey: String? = null,
    val model: String? = null,
)

/** A reference solution to measure: its role and the language it is written in. */
private data class Target(val role: String, val language: String)

/**
 * Reference solutions of the formats, measured by the judge on every processor of the problem the platform offers:
 * the baseline of an optimization on each of them, both variants of a guess on its one processor. See
 * docs/backend/reference-measurements.md.
 */
@Service
class ReferenceMeasurements(
    private val catalog: ProblemCatalog,
    private val processorCatalog: ProcessorCatalog,
    private val jobs: ReferenceJobRepository,
    private val versionLocks: ReferenceVersionLockRepository,
    private val monitor: ReferenceEnvironmentMonitor,
    private val gateway: JudgeGateway,
    private val properties: JudgeProperties,
    private val transactions: TransactionTemplate,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Per processor of the problem; empty for a standard problem. */
    fun views(version: ProblemVersion): Map<String, ReferenceMeasurementView> =
        targets(version).mapValues { (processor, targets) -> view(version, processor, targets) }

    fun view(version: ProblemVersion, processor: String): ReferenceMeasurementView? =
        targets(version)[processor]?.let { view(version, processor, it) }

    private fun view(version: ProblemVersion, processor: String, targets: List<Target>): ReferenceMeasurementView {
        val environment = monitor.snapshot(processor) ?: return pending(processor, null, "Ожидаем доступное окружение проверки")
        val benchmarkKey = "${version.id}:${environment.key}"
        val rows = ensure(version, processor, environment.key, targets)
        if (monitor.snapshot(processor)?.key != environment.key) return pending(processor, null, "Окружение проверки изменилось")
        if (rows.any { it.status == "failed" }) {
            return pending(processor, benchmarkKey, "Не удалось измерить эталон. Автору задачи нужно проверить пакет", environment.key).copy(status = "failed")
        }
        if (rows.all { it.status == "ready" }) {
            val byRole = rows.associateBy { it.role }
            val measured = json.readValue(checkNotNull(rows.first().measurement), MeasurementData::class.java)
            return ReferenceMeasurementView(
                processor, benchmarkKey, "ready", Processors.get(processor).scoreType, byRole["baseline"]?.score, byRole["first"]?.score,
                byRole["second"]?.score, rows.mapNotNull { it.measuredAt }.maxOrNull(), measured.synthetic, null, environment.key, measured.model,
            )
        }
        val running = rows.any { it.status == "running" || it.leaseUntil != null }
        return pending(processor, benchmarkKey, null, environment.key).copy(status = if (running) "running" else "pending")
    }

    fun viewInEnvironment(version: ProblemVersion, processor: String, environmentKey: String): ReferenceMeasurementView? {
        val targets = targets(version)[processor] ?: return null
        val benchmarkKey = "${version.id}:$environmentKey"
        val rows = ensure(version, processor, environmentKey, targets)
        if (rows.any { it.status == "failed" }) return pending(processor, benchmarkKey, "Не удалось измерить эталон", environmentKey).copy(status = "failed")
        if (rows.any { it.status != "ready" }) return pending(processor, benchmarkKey, null, environmentKey)
        val byRole = rows.associateBy { it.role }
        val measured = json.readValue(checkNotNull(rows.first().measurement), MeasurementData::class.java)
        return ReferenceMeasurementView(
            processor, benchmarkKey, "ready", Processors.get(processor).scoreType, byRole["baseline"]?.score, byRole["first"]?.score,
            byRole["second"]?.score, rows.mapNotNull { it.measuredAt }.maxOrNull(), measured.synthetic, null, environmentKey, measured.model,
        )
    }

    fun requireReady(version: ProblemVersion, processor: String): ReferenceMeasurementView {
        val current = view(version, processor) ?: throw BadRequestException("Для этого формата нет эталонного замера на процессоре $processor")
        if (current.status != "ready") throw ServiceUnavailableException("Эталонный замер пока не готов")
        return current
    }

    /** Key of the current reference environment of [processor], null while no executor serves it. */
    fun environmentKey(processor: String): String? = monitor.snapshot(processor)?.key

    /** Model of the real GPU the executors report now. */
    fun environmentName(): String? = monitor.snapshot(Processors.GPU_CUDA)?.model

    /** Key of the environment [result] was measured in, if it is the current one of its processor. */
    fun environmentKeyFor(result: JudgeResult): String? =
        result.processor?.let { monitor.snapshot(it.id, force = true) }?.takeIf { it.accepts(result) }?.key

    @Scheduled(fixedDelayString = "\${platform.benchmarks.refresh-interval:PT5S}", initialDelayString = "\${platform.benchmarks.initial-delay:PT2S}")
    fun refresh() {
        val environments = monitor.snapshots(force = true)
        if (environments.isEmpty()) return
        catalog.currentVersions().filter { !it.draft }.forEach { version ->
            targets(version).forEach { (processor, targets) ->
                environments[processor]?.let { ensure(version, processor, it.key, targets) }
            }
        }
        environments.values.forEach { environment ->
            jobs.findByEnvironmentKeyAndStatusIn(environment.key, listOf("pending", "running")).forEach { dispatch(it.id, environment) }
        }
    }

    fun apply(event: JudgeEvent): Boolean {
        if (!event.submissionId.startsWith(PREFIX)) return false
        val identifier = event.submissionId.removePrefix(PREFIX).substringBeforeLast("-r")
        val id = runCatching { UUID.fromString(identifier) }.getOrNull() ?: return true
        val processor = jobs.findById(id).orElse(null)?.processor ?: return true
        val environment = monitor.snapshot(processor, force = event.status == SubmissionStatus.FINISHED)
        transactions.executeWithoutResult {
            val job = jobs.findForUpdate(id) ?: return@executeWithoutResult
            if (event.submissionId != job.submissionId || job.status in TERMINAL) return@executeWithoutResult
            if (job.jobId != null && job.jobId != event.jobId) return@executeWithoutResult
            if (event.attempt < job.eventAttempt || event.attempt == job.eventAttempt && event.seq <= job.eventSeq && job.jobId != null) return@executeWithoutResult
            val now = Instant.now()
            job.jobId = event.jobId
            if (event.attempt > job.eventAttempt) job.expectedTests = null
            job.eventAttempt = event.attempt
            job.eventSeq = event.seq
            job.updatedAt = now
            job.leaseUntil = null
            if (event.status != SubmissionStatus.FINISHED) {
                job.status = "running"
                event.progress?.let { job.expectedTests = it.totalTests }
                return@executeWithoutResult
            }
            val result = event.result
            if (result == null || result.verdict in INFRA_FAILURES) {
                retry(job, now, result?.message ?: "Нет результата эталонной проверки")
            } else if (result.verdict != Verdict.ACCEPTED) {
                fail(job, "${result.verdict}: ${result.message.orEmpty()}\n${result.compileLog.orEmpty()}")
            } else if (environment == null || environment.key != job.environmentKey || !environment.accepts(result) || result.processor?.executorId != event.source) {
                retry(job, now, "Результат пришёл из другого или недоступного окружения проверки")
            } else {
                finish(job, result, now)
            }
        }
        return true
    }

    private fun ensure(version: ProblemVersion, processor: String, environmentKey: String, targets: List<Target>): List<ReferenceJob> {
        val present = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, environmentKey).associateBy { it.role }
        if (targets.all { it.role in present }) return targets.map { present.getValue(it.role) }
        return transactions.execute {
            checkNotNull(versionLocks.acquire(version.id)) { "Problem version ${version.id} disappeared" }
            val existing = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, environmentKey).associateBy { it.role }
            targets.map { target ->
                existing[target.role] ?: jobs.save(ReferenceJob(UUID.randomUUID(), version.id, environmentKey, target.role, processor, target.language))
            }
        }!!
    }

    private fun dispatch(id: UUID, environment: ReferenceEnvironmentSnapshot) {
        if (monitor.snapshot(environment.processor)?.key != environment.key) return
        val request = transactions.execute {
            val job = jobs.findForUpdate(id) ?: return@execute null
            val now = Instant.now()
            if (job.environmentKey != environment.key || job.status in TERMINAL || job.nextAttemptAt.isAfter(now)) return@execute null
            if (job.status == "running") {
                if (job.updatedAt.isBefore(now.minus(JOB_TIMEOUT))) retry(job, now, "Истёк срок ожидания эталонной проверки")
                return@execute null
            }
            if (job.leaseUntil?.isAfter(now) == true) return@execute null
            if (job.sendAttempts >= MAX_SEND_ATTEMPTS) {
                fail(job, "Проверяющая система не приняла эталонную проверку после $MAX_SEND_ATTEMPTS попыток")
                return@execute null
            }
            val version = catalog.versions(listOf(job.problemVersionId)).getValue(job.problemVersionId)
            val source = source(version, job)
            if (source.isNullOrBlank()) {
                fail(job, "Нет эталонного исходного кода для роли ${job.role}")
                return@execute null
            }
            job.sendAttempts++
            job.leaseUntil = now.plusSeconds(45)
            job.updatedAt = now
            SubmissionRequest(job.submissionId, catalog.spec(version), source, job.processor, job.language)
        } ?: return
        val outcome = gateway.send(request)
        transactions.executeWithoutResult {
            val job = jobs.findForUpdate(id) ?: return@executeWithoutResult
            if (job.submissionId != request.submissionId || job.status in TERMINAL) return@executeWithoutResult
            val now = Instant.now()
            job.leaseUntil = null
            when (outcome) {
                is SendOutcome.Accepted -> if (job.jobId == null) {
                    job.jobId = outcome.jobId
                    job.status = "running"
                    job.updatedAt = now
                }
                is SendOutcome.Rejected -> fail(job, outcome.reason)
                is SendOutcome.Retry -> if (job.jobId == null) {
                    job.status = "pending"
                    job.nextAttemptAt = now.plus(backoff(job.sendAttempts))
                    job.diagnostic = outcome.reason.take(8000)
                }
            }
        }
    }

    private fun finish(job: ReferenceJob, result: JudgeResult, now: Instant) {
        val version = catalog.versions(listOf(job.problemVersionId)).getValue(job.problemVersionId)
        try {
            val capture = captureMeasurement(result, catalog.spec(version), catalog.format(version).aggregation(), properties.synthetic, job.expectedTests)
            if (capture.score <= BigDecimal.ZERO) throw InvalidMeasurementException("Эталонный результат должен быть больше нуля")
            job.score = capture.score
            job.scoreType = capture.data.scoreType
            job.measurement = json.writeValueAsString(capture.data)
            job.measuredAt = now
            job.status = "ready"
            job.diagnostic = null
            log.info(
                "Reference measurement ready: version={} role={} processor={} environment={} score={} {}",
                version.id, job.role, job.processor, job.environmentKey, job.score, job.scoreType,
            )
        } catch (exception: InvalidMeasurementException) {
            retry(job, now, exception.message!!)
        }
    }

    private fun retry(job: ReferenceJob, now: Instant, diagnostic: String) {
        job.failures++
        if (job.failures >= MAX_FAILURES) {
            fail(job, diagnostic)
            return
        }
        job.generation++
        job.status = "pending"
        job.jobId = null
        job.eventAttempt = 0
        job.eventSeq = 0
        job.expectedTests = null
        job.sendAttempts = 0
        job.leaseUntil = null
        job.score = null
        job.scoreType = null
        job.measurement = null
        job.measuredAt = null
        job.nextAttemptAt = now.plus(backoff(job.failures))
        job.updatedAt = now
        job.diagnostic = diagnostic.take(8000)
        log.warn("Retrying reference measurement {} generation {}: {}", job.id, job.generation, diagnostic)
    }

    private fun fail(job: ReferenceJob, diagnostic: String) {
        job.status = "failed"
        job.leaseUntil = null
        job.score = null
        job.scoreType = null
        job.measurement = null
        job.measuredAt = null
        job.diagnostic = diagnostic.take(8000)
        log.error("Reference measurement failed: id={} role={} environment={}: {}", job.id, job.role, job.environmentKey, diagnostic)
    }

    private fun source(version: ProblemVersion, job: ReferenceJob): String? = when (job.role) {
        "baseline" -> version.starters[job.processor]?.get(job.language)
        "first" -> catalog.format(version).firstCode
        "second" -> catalog.format(version).secondCode
        else -> null
    }

    /**
     * What each processor of [version] measures: an optimization the baseline (the starter of the processor's first
     * language, which the package must provide), a guess both variants on its only processor and language. Only the
     * processors this platform offers.
     */
    private fun targets(version: ProblemVersion): Map<String, List<Target>> {
        if (version.format == ProblemFormatData.STANDARD) return emptyMap()
        return catalog.spec(version).declaredProcessors().filter { processorCatalog.isEnabled(it.id) }.associate { processor ->
            val language = processor.languages.first()
            processor.id to when (version.format) {
                ProblemFormatData.OPTIMIZATION -> listOf(Target("baseline", language))
                ProblemFormatData.GUESS -> listOf(Target("first", language), Target("second", language))
                else -> emptyList()
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun pending(processor: String, key: String?, diagnostic: String?, environmentKey: String? = null): ReferenceMeasurementView =
        ReferenceMeasurementView(
            processor, key, "pending", Processors.get(processor).scoreType, null, null, null, null, properties.synthetic, diagnostic, environmentKey,
        )

    private fun backoff(attempt: Int): Duration = Duration.ofSeconds((5L shl attempt.coerceAtMost(4)).coerceAtMost(60))

    companion object {
        private const val PREFIX = "reference-"
        private val TERMINAL = setOf("ready", "failed")
        private val INFRA_FAILURES = setOf(Verdict.INTERNAL_ERROR, Verdict.CANCELLED)
        private val JOB_TIMEOUT = Duration.ofMinutes(30)
        private const val MAX_FAILURES = 3
        private const val MAX_SEND_ATTEMPTS = 12
    }
}
