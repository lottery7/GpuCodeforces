package dev.gpucodeforces.judge.executor.pipeline

import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProcessorRun
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.messaging.JobReporter
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.ProcessorBackend
import dev.gpucodeforces.judge.executor.processor.ProcessorUnavailableException
import dev.gpucodeforces.judge.executor.problem.ProblemPreparationException
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.util.FileTrees
import io.micrometer.core.instrument.MeterRegistry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.name
import org.slf4j.LoggerFactory

/** Judges one delivery of a job on the processor it names: validate -> processor's judge() -> report. */
class JudgePipeline(
    private val props: ExecutorProperties,
    backends: List<ProcessorBackend>,
    private val publisher: EventPublisher,
    private val cancels: CancelRegistry,
    private val meters: MeterRegistry,
    private val clock: Clock,
) : JobProcessor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val backends: Map<String, ProcessorBackend> = backends.associateBy { it.info.id }
    override val inFlight = AtomicInteger()

    override fun processors(): List<ProcessorStatus> = backends.values.map { it.status() }

    override fun gpus(): List<GpuSlotStatus> = backends.values.flatMap { it.gpus() }

    override fun onAvailabilityChange(processor: String, listener: (Boolean) -> Unit) {
        backends[processor]?.onAvailabilityChange(listener)
    }

    override fun process(job: JudgeJob, attempt: Int): Disposition {
        inFlight.incrementAndGet()
        val started = System.nanoTime()
        val reporter = JobReporter(job.submissionId, job.jobId, attempt, source = props.id, publisher, clock)
        var runDir: Path? = null
        var keepRunDir = false
        val isCancelled = { cancels.isCancelled(job.submissionId) }
        log.info(
            "Judging submission: problem={} v{} processor={} language={} attempt={} waited={}ms",
            job.problem.id, job.problem.version, job.processor, job.language, attempt,
            Duration.between(job.enqueuedAt, clock.instant()).toMillis(),
        )
        try {
            if (isCancelled()) return finish(reporter, started, null, JudgeResult(Verdict.CANCELLED, message = "Cancelled"))
            val errors = SpecValidator.validateSubmission(
                SubmissionRequest(job.submissionId, job.problem, source = job.source, processor = job.processor, language = job.language),
            )
            val backend = backends[job.processor]
            if (errors.isNotEmpty() || backend == null) {
                log.error("Invalid job rejected: processor={} served={} errors={}", job.processor, backends.keys, errors)
                return finish(reporter, started, null, JudgeResult(Verdict.INTERNAL_ERROR, message = "Invalid judge job"))
            }

            reporter.status(SubmissionStatus.COMPILING)
            // Created only now: the submission id is part of the name and has just been validated. A fresh name per
            // delivery keeps a redelivered or re-posted submission away from the directory of a run still in flight.
            val dir = Files.createTempDirectory(Files.createDirectories(props.paths.work), "${job.submissionId}-a$attempt-")
            runDir = dir
            FileTrees.makeReadableByAll(dir)
            val hooks = object : JudgeHooks {
                override fun status(status: SubmissionStatus) = reporter.status(status)
                override fun progress(current: Int, total: Int) = reporter.progress(current, total)
                override fun cancelled() = isCancelled()
            }
            val result = backend.judge(job, dir, hooks)
            if (result.verdict == Verdict.INTERNAL_ERROR) keepRunDir = true
            return finish(reporter, started, backend, result)
        } catch (e: InterruptedException) {
            if (isCancelled()) return finish(reporter, started, backends[job.processor], JudgeResult(Verdict.CANCELLED, message = "Cancelled"))
            Thread.currentThread().interrupt()
            log.warn("Interrupted (shutdown?), returning job to the queue")
            return Disposition.REQUEUE
        } catch (e: ProcessorUnavailableException) {
            log.error("Processor {} cannot judge on this executor now, returning job to the queue: {}", job.processor, e.message)
            return Disposition.REQUEUE
        } catch (e: ProblemPreparationException) {
            if (e.invalidInput) {
                return finish(reporter, started, backends[job.processor], JudgeResult(Verdict.INTERNAL_ERROR, message = "Invalid custom test"))
            }
            if (e.problemFault) {
                log.error("Problem {} is broken, tests cannot be generated: {}", job.problem.id, e.message)
                return finish(reporter, started, backends[job.processor], JudgeResult(Verdict.INTERNAL_ERROR, message = "Tests of this problem could not be generated"))
            }
            keepRunDir = true
            log.error("Problem preparation failed (infrastructure)", e)
            return retryOrFail(reporter, started, job, attempt, "problem preparation: ${e.message}")
        } catch (e: JudgeInfrastructureException) {
            keepRunDir = true
            log.error("Judge infrastructure failure", e)
            return retryOrFail(reporter, started, job, attempt, e.message ?: e.toString())
        } catch (e: Exception) {
            keepRunDir = true
            log.error("Unexpected failure while judging", e)
            return retryOrFail(reporter, started, job, attempt, e.toString())
        } finally {
            inFlight.decrementAndGet()
            cleanup(runDir, keepRunDir)
        }
    }

    private fun retryOrFail(reporter: JobReporter, started: Long, job: JudgeJob, attempt: Int, reason: String): Disposition =
        if (attempt < Topology.JOB_DELIVERY_LIMIT) requeue(reason)
        else finish(reporter, started, backends[job.processor], JudgeResult(Verdict.INTERNAL_ERROR, message = "Judging failed"))

    private fun requeue(reason: String): Disposition {
        log.warn("Returning job to the queue for another attempt: {}", reason)
        meters.counter("judge.executor.requeues").increment()
        return Disposition.REQUEUE
    }

    /** [backend] is null when the job never reached a processor. */
    private fun finish(reporter: JobReporter, started: Long, backend: ProcessorBackend?, result: JudgeResult): Disposition {
        val judgeMs = (System.nanoTime() - started) / 1_000_000
        val run = backend?.let { ProcessorRun(it.info.id, version = it.version, model = it.model, executorId = props.id, executorVersion = javaClass.`package`?.implementationVersion ?: "dev") }
        reporter.finish(result.copy(judgeTimeMs = judgeMs, processor = run))
        meters.counter("judge.executor.verdicts", "verdict", result.verdict.name, "processor", backend?.info?.id ?: "none").increment()
        log.info(
            "Verdict {}{} in {}ms (score {})",
            result.verdict, result.failedTest?.let { " on test $it" } ?: "", judgeMs,
            result.score?.let { "%.3f %s".format(it.value, it.type) } ?: "-",
        )
        return Disposition.ACK
    }

    /** The run directory held files written by the solution: see [FileTrees] for why nothing here follows links. */
    private fun cleanup(runDir: Path?, keep: Boolean) {
        if (runDir == null) return
        try {
            if (keep) {
                val failed = Files.createDirectories(props.paths.failed)
                val target = failed.resolve(runDir.name)
                FileTrees.delete(runDir.resolve("out")) // outputs can be huge
                // not Files.move: work is usually tmpfs and failed is a disk, and a directory cannot be moved across file stores
                FileTrees.copyRegularFiles(runDir, target, FAILED_RUN_BYTES)
                Sandbox.logFile(runDir).takeIf { it.exists() }?.let { Files.move(it, Sandbox.logFile(target)) }
                log.warn("Run directory kept for investigation: {}", target)
                pruneFailed(failed)
            }
            FileTrees.delete(runDir)
            Sandbox.deleteLog(runDir)
        } catch (e: IOException) {
            log.warn("Cleanup of {} failed", runDir, e)
        }
    }

    private fun pruneFailed(dir: Path) {
        val entries = Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
            .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
        entries.drop(props.run.keepFailedRuns).forEach {
            FileTrees.delete(it)
            Sandbox.deleteLog(it)
        }
    }

    private companion object {
        /** A kept run directory holds the binary, the manifest and logs: megabytes. */
        const val FAILED_RUN_BYTES = 64L shl 20
    }
}
