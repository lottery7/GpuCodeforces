package dev.gpucodeforces.judge.executor.pipeline

import dev.gpucodeforces.judge.api.CompileSpec
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.compile.BuildInfrastructureException
import dev.gpucodeforces.judge.executor.compile.BuildOutcome
import dev.gpucodeforces.judge.executor.compile.Builder
import dev.gpucodeforces.judge.executor.compile.EffectivePolicy
import dev.gpucodeforces.judge.executor.compile.Toolchain
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.gpu.GpuManager
import dev.gpucodeforces.judge.executor.gpu.GpuSlot
import dev.gpucodeforces.judge.executor.gpu.NoHealthyGpuException
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.messaging.JobReporter
import dev.gpucodeforces.judge.executor.problem.ProblemPreparationException
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.run.HarnessRunner
import dev.gpucodeforces.judge.executor.run.RunOutcome
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.util.FileTrees
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.name
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/** Judges one delivery of a job: compile -> prepare tests -> run on a GPU -> report. */
class JudgePipeline(
    private val props: ExecutorProperties,
    private val toolchain: Toolchain,
    private val builder: Builder,
    private val problems: ProblemStore,
    private val runner: HarnessRunner,
    private val gpus: GpuManager,
    private val publisher: EventPublisher,
    private val cancels: CancelRegistry,
    private val meters: MeterRegistry,
    private val clock: Clock,
) : JobProcessor {
    private val log = LoggerFactory.getLogger(javaClass)
    override val inFlight = AtomicInteger()

    override fun process(job: JudgeJob, attempt: Int): Disposition {
        inFlight.incrementAndGet()
        val started = System.nanoTime()
        val reporter = JobReporter(job.submissionId, job.jobId, attempt, source = props.id, publisher, clock)
        var runDir: Path? = null
        var keepRunDir = false
        val cancelled = { cancels.isCancelled(job.submissionId) }
        log.info(
            "Judging submission: problem={} v{} attempt={} waited={}ms",
            job.problem.id, job.problem.version, attempt,
            Duration.between(job.enqueuedAt, clock.instant()).toMillis(),
        )
        try {
            if (cancelled()) return finish(reporter, started, JudgeResult(Verdict.CANCELLED, message = "Cancelled"))
            val errors = SpecValidator.validateSubmission(
                SubmissionRequest(job.submissionId, job.problem, source = job.source, gpuProfile = job.gpuProfile),
            )
            if (errors.isNotEmpty() || job.gpuProfile != props.gpuProfile) {
                log.error("Invalid job rejected: profile={} errors={}", job.gpuProfile, errors)
                return finish(reporter, started, JudgeResult(Verdict.INTERNAL_ERROR, message = "Invalid judge job"))
            }
            val arch = job.problem.compile.arch
            if (arch != null && !CompileSpec.runsOn(arch, props.profile.arch)) {
                // a mismatch between the problem and the GPU chosen for the contest: retrying cannot help
                log.error("Job rejected: problem is compiled for {}, GPU profile {} is {}", arch, props.gpuProfile, props.profile.arch)
                return finish(
                    reporter, started,
                    JudgeResult(Verdict.INTERNAL_ERROR, message = "Problem targets $arch, which GPU profile ${props.gpuProfile} (${props.profile.arch}) cannot run"),
                )
            }

            reporter.status(SubmissionStatus.COMPILING)
            // Created only now: the submission id is part of the name and has just been validated. A fresh name per
            // delivery keeps a redelivered or re-posted submission away from the directory of a run still in flight.
            val dir = Files.createTempDirectory(Files.createDirectories(props.paths.work), "${job.submissionId}-a$attempt-")
            runDir = dir
            FileTrees.makeReadableByAll(dir)
            val timingMode = job.problem.timing.effectiveMode
            val policy = EffectivePolicy.of(props.profile.policy, job.problem.policy, timingMode)
            val build = timed("compile") { builder.build(job.source, job.problem.signature, job.problem.compile, policy, dir) }
            when (build) {
                is BuildOutcome.CompileError -> {
                    log.info("Compilation error: {}", build.message)
                    return finish(reporter, started, JudgeResult(Verdict.COMPILATION_ERROR, message = build.message, compileLog = build.compileLog))
                }
                is BuildOutcome.Violation -> {
                    log.info("Policy violation: {}", build.violations)
                    return finish(
                        reporter, started,
                        JudgeResult(Verdict.POLICY_VIOLATION, message = build.violations.joinToString("\n"), compileLog = build.compileLog.ifBlank { null }),
                    )
                }
                is BuildOutcome.Ok -> log.info("Build ok (object cached: {})", build.cached)
            }
            if (cancelled()) return finish(reporter, started, JudgeResult(Verdict.CANCELLED, message = "Cancelled"))

            val prepared = timed("prepare") {
                problems.acquire(job.problem, onPreparing = { reporter.status(SubmissionStatus.PREPARING) }, cancelled = cancelled)
            }
            try {
                val total = prepared.tests.size
                reporter.progress(0, total)
                val slot = gpus.pool.acquire(job.submissionId, Duration.ofMinutes(2), cancelled)
                var outcome: RunOutcome? = null
                var probed = false
                MDC.put("gpu", slot.index.toString())
                try {
                    val o = timed("run") {
                        runner.run(job.problem, prepared, binary = build.binary, runDir = dir, slot, onTestStart = { reporter.progress(it, total) }, cancelled)
                    }
                    outcome = o
                    if (o.solutionSuspected) {
                        // Decided by the probe, which therefore runs before the verdict here: a healthy GPU means
                        // the solution broke its own start-up, and that is not worth five retries on the GPU.
                        probed = true
                        if (gpus.checkAfterFailure(slot, "start-up failure of ${job.submissionId}")) {
                            log.info("Solution failed before the first test: {}", o.internalReason)
                            val first = prepared.tests.first()
                            val message = "Runtime error on test 1 (the solution failed before the first test)"
                            val result = JudgeResult(
                                Verdict.RUNTIME_ERROR, failedTest = 1, message = message, compileLog = build.compileLog.ifBlank { null },
                                tests = listOf(TestResult(1, first.spec.name, first.spec.sample, Verdict.RUNTIME_ERROR, message = message)),
                                timingMode = timingMode, gpu = gpuInfo(slot, o),
                            )
                            return finish(reporter, started, result)
                        }
                    }
                    if (o.verdict == Verdict.INTERNAL_ERROR) {
                        keepRunDir = true
                        log.error("Internal error while running: {}", o.internalReason)
                        if (o.retryable && attempt < Topology.JOB_DELIVERY_LIMIT) return requeue("run failed: ${o.internalReason}")
                    }
                    val result = JudgeResult(
                        verdict = o.verdict,
                        failedTest = o.failedTest,
                        message = o.message,
                        compileLog = build.compileLog.ifBlank { null },
                        tests = o.tests,
                        maxTimeMs = o.tests.filter { it.verdict == Verdict.ACCEPTED }.mapNotNull { it.timeMs }.maxOrNull(),
                        timingMode = timingMode,
                        gpu = gpuInfo(slot, o),
                    )
                    return finish(reporter, started, result)
                } finally {
                    // The verdict is already published: the probe creates a CUDA context, and the contestant
                    // should not wait for it. The GPU stays taken until it is known to be usable.
                    if (!probed && (outcome == null || outcome.needsHealthCheck)) {
                        gpus.checkAfterFailure(slot, "${outcome?.verdict ?: "exception"} of ${job.submissionId}")
                    }
                    gpus.pool.release(slot)
                    MDC.remove("gpu")
                }
            } finally {
                problems.release(prepared.hash)
            }
        } catch (e: InterruptedException) {
            if (cancelled()) return finish(reporter, started, JudgeResult(Verdict.CANCELLED, message = "Cancelled"))
            Thread.currentThread().interrupt()
            log.warn("Interrupted (shutdown?), returning job to the queue")
            return Disposition.REQUEUE
        } catch (e: NoHealthyGpuException) {
            log.error("No healthy GPU on this executor, returning job to the queue: {}", e.message)
            return Disposition.REQUEUE
        } catch (e: ProblemPreparationException) {
            if (e.problemFault) {
                log.error("Problem {} is broken, tests cannot be generated: {}", job.problem.id, e.message)
                return finish(reporter, started, JudgeResult(Verdict.INTERNAL_ERROR, message = "Tests of this problem could not be generated"))
            }
            keepRunDir = true
            log.error("Problem preparation failed (infrastructure)", e)
            return retryOrFail(reporter, started, attempt, "problem preparation: ${e.message}")
        } catch (e: BuildInfrastructureException) {
            keepRunDir = true
            log.error("Build infrastructure failure", e)
            return retryOrFail(reporter, started, attempt, "build: ${e.message}")
        } catch (e: Exception) {
            keepRunDir = true
            log.error("Unexpected failure while judging", e)
            return retryOrFail(reporter, started, attempt, e.toString())
        } finally {
            inFlight.decrementAndGet()
            cleanup(runDir, keepRunDir)
        }
    }

    private fun retryOrFail(reporter: JobReporter, started: Long, attempt: Int, reason: String): Disposition =
        if (attempt < Topology.JOB_DELIVERY_LIMIT) requeue(reason)
        else finish(reporter, started, JudgeResult(Verdict.INTERNAL_ERROR, message = "Judging failed"))

    private fun requeue(reason: String): Disposition {
        log.warn("Returning job to the queue for another attempt: {}", reason)
        meters.counter("judge.executor.requeues").increment()
        return Disposition.REQUEUE
    }

    private fun finish(reporter: JobReporter, started: Long, result: JudgeResult): Disposition {
        val judgeMs = (System.nanoTime() - started) / 1_000_000
        val full = result.copy(judgeTimeMs = judgeMs, toolchain = toolchain.description)
        reporter.finish(full)
        meters.counter("judge.executor.verdicts", "verdict", result.verdict.name).increment()
        log.info(
            "Verdict {}{} in {}ms (max time {} ms)",
            result.verdict, result.failedTest?.let { " on test $it" } ?: "", judgeMs, result.maxTimeMs?.let { "%.3f".format(it) } ?: "-",
        )
        return Disposition.ACK
    }

    private fun gpuInfo(slot: GpuSlot, o: RunOutcome) = GpuInfo(
        profile = props.gpuProfile,
        name = slot.name,
        index = slot.index,
        executorId = props.id,
        maxTemperatureC = o.stats.maxTemp,
        minSmClockMhz = o.stats.minSm,
        maxSmClockMhz = o.stats.maxSm,
        throttled = o.stats.throttled,
    )

    private fun <T> timed(stage: String, block: () -> T): T {
        val sample = Timer.start(meters)
        try {
            return block()
        } finally {
            sample.stop(meters.timer("judge.executor.stage", "stage", stage))
        }
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
