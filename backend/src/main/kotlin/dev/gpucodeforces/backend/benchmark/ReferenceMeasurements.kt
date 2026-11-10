package dev.gpucodeforces.backend.benchmark

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.submission.InvalidMeasurementException
import dev.gpucodeforces.backend.submission.JudgeGateway
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.backend.submission.SendOutcome
import dev.gpucodeforces.backend.submission.captureMeasurement
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
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

data class ReferenceMeasurementView(
    val key: String?,
    val status: String,
    val baselineTimeMs: BigDecimal?,
    val firstTimeMs: BigDecimal?,
    val secondTimeMs: BigDecimal?,
    val measuredAt: Instant?,
    val synthetic: Boolean,
    val diagnostic: String?,
    val environmentKey: String? = null,
)

@Service
class ReferenceMeasurements(
    private val catalog: ProblemCatalog,
    private val jobs: ReferenceJobRepository,
    private val versionLocks: ReferenceVersionLockRepository,
    private val monitor: ReferenceEnvironmentMonitor,
    private val gateway: JudgeGateway,
    private val properties: JudgeProperties,
    private val gpu: GpuProperties,
    private val transactions: TransactionTemplate,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun view(version: ProblemVersion): ReferenceMeasurementView? {
        val roles = roles(version)
        if (roles.isEmpty()) return null
        val environment = monitor.snapshot() ?: return pending(null, "Ожидаем доступное окружение проверки")
        val benchmarkKey = "${version.id}:${environment.key}"
        val rows = ensure(version, environment.key, roles)
        if (monitor.snapshot()?.key != environment.key) return pending(null, "Окружение проверки изменилось")
        if (rows.any { it.status == "failed" }) {
            return pending(benchmarkKey, "Не удалось измерить эталон. Автору задачи нужно проверить пакет", environment.key).copy(status = "failed")
        }
        if (rows.all { it.status == "ready" }) {
            val byRole = rows.associateBy { it.role }
            return ReferenceMeasurementView(
                benchmarkKey, "ready", byRole["baseline"]?.timeMs, byRole["first"]?.timeMs, byRole["second"]?.timeMs,
                rows.mapNotNull { it.measuredAt }.maxOrNull(), properties.synthetic, null, environment.key,
            )
        }
        return pending(benchmarkKey, null, environment.key).copy(status = if (rows.any { it.status == "running" || it.leaseUntil != null }) "running" else "pending")
    }

    fun requireReady(version: ProblemVersion): ReferenceMeasurementView {
        val current = view(version) ?: throw BadRequestException("Для этого формата нет эталонного замера")
        if (current.status != "ready") throw ServiceUnavailableException("Эталонный замер пока не готов")
        return current
    }

    fun environmentKey(): String? = monitor.snapshot()?.key

    fun environmentName(): String? = monitor.snapshot()?.model

    fun environmentKeyFor(result: JudgeResult): String? = monitor.snapshot(force = true)?.takeIf { it.accepts(result) }?.key

    @Scheduled(fixedDelayString = "\${platform.benchmarks.refresh-interval:PT5S}", initialDelayString = "\${platform.benchmarks.initial-delay:PT2S}")
    fun refresh() {
        val environment = monitor.snapshot(force = true) ?: return
        catalog.currentVersions().filter { !it.draft && roles(it).isNotEmpty() }.forEach { ensure(it, environment.key, roles(it)) }
        jobs.findByEnvironmentKeyAndStatusIn(environment.key, listOf("pending", "running")).forEach { dispatch(it.id, environment) }
    }

    fun apply(event: JudgeEvent): Boolean {
        if (!event.submissionId.startsWith(PREFIX)) return false
        val identifier = event.submissionId.removePrefix(PREFIX).substringBeforeLast("-r")
        val id = runCatching { UUID.fromString(identifier) }.getOrNull() ?: return true
        val environment = monitor.snapshot(force = event.status == SubmissionStatus.FINISHED)
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
            } else if (environment == null || environment.key != job.environmentKey || !environment.accepts(result) || result.gpu?.executorId != event.source) {
                retry(job, now, "Результат пришёл из другого или недоступного окружения проверки")
            } else {
                finish(job, result, now)
            }
        }
        return true
    }

    private fun ensure(version: ProblemVersion, environmentKey: String, roles: List<String>): List<ReferenceJob> {
        val present = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, environmentKey).associateBy { it.role }
        if (roles.all { it in present }) return roles.map { present.getValue(it) }
        return transactions.execute {
            checkNotNull(versionLocks.acquire(version.id)) { "Problem version ${version.id} disappeared" }
            val existing = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, environmentKey).associateBy { it.role }
            roles.map { role -> existing[role] ?: jobs.save(ReferenceJob(UUID.randomUUID(), version.id, environmentKey, role)) }
        }
    }

    private fun dispatch(id: UUID, environment: ReferenceEnvironmentSnapshot) {
        if (monitor.snapshot()?.key != environment.key) return
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
            val source = source(version, job.role)
            if (source.isNullOrBlank()) {
                fail(job, "Нет эталонного исходного кода для роли ${job.role}")
                return@execute null
            }
            job.sendAttempts++
            job.leaseUntil = now.plusSeconds(45)
            job.updatedAt = now
            SubmissionRequest(job.submissionId, catalog.spec(version), source, gpu.profile)
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
            if (capture.timeMs <= BigDecimal.ZERO) throw InvalidMeasurementException("Эталонное время должно быть больше нуля")
            job.timeMs = capture.timeMs
            job.measurement = json.writeValueAsString(capture.data)
            job.measuredAt = now
            job.status = "ready"
            job.diagnostic = null
            log.info("Reference measurement ready: version={} role={} environment={} timeMs={}", version.id, job.role, job.environmentKey, job.timeMs)
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
        job.timeMs = null
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
        job.timeMs = null
        job.measurement = null
        job.measuredAt = null
        job.diagnostic = diagnostic.take(8000)
        log.error("Reference measurement failed: id={} role={} environment={}: {}", job.id, job.role, job.environmentKey, diagnostic)
    }

    private fun source(version: ProblemVersion, role: String): String? = when (role) {
        "baseline" -> version.starterCode
        "first" -> catalog.format(version).firstCode
        "second" -> catalog.format(version).secondCode
        else -> null
    }

    private fun roles(version: ProblemVersion): List<String> = when (version.format) {
        "optimization" -> listOf("baseline")
        "guess" -> listOf("first", "second")
        else -> emptyList()
    }

    private fun pending(key: String?, diagnostic: String?, environmentKey: String? = null): ReferenceMeasurementView =
        ReferenceMeasurementView(key, "pending", null, null, null, null, properties.synthetic, diagnostic, environmentKey)

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
