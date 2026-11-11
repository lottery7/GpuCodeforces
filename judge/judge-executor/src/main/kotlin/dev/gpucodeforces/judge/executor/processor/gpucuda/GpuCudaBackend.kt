package dev.gpucodeforces.judge.executor.processor.gpucuda

import dev.gpucodeforces.judge.api.CompileSpec
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.ProcessorBackend
import dev.gpucodeforces.judge.executor.processor.maxMeasuredScore
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/** A run failed in a way another attempt may not: the pipeline returns the job to the queue. */
class RetryableRunException(msg: String) : JudgeInfrastructureException(msg)

/**
 * `gpu-cuda`: the solution is compiled with nvcc, linked with the harness and timed on a real GPU of this machine.
 * The score of a test is its measured time, ms.
 */
class GpuCudaBackend(
    private val props: ExecutorProperties,
    private val toolchain: Toolchain,
    private val builder: Builder,
    private val problems: ProblemStore,
    private val runner: HarnessRunner,
    private val gpus: GpuManager,
    private val meters: MeterRegistry,
) : ProcessorBackend {
    private val log = LoggerFactory.getLogger(javaClass)

    override val info: ProcessorInfo = Processors.get(Processors.GPU_CUDA)
    override val version: String get() = toolchain.description

    /** The card the profile names; every GPU of this executor was checked against it at startup. */
    override val model: String
        get() = props.profile.gpuName?.takeIf { it.isNotBlank() } ?: gpus.pool.slots.first().name

    override val capacity: Int get() = props.capacityOf(info.id)

    /** The toolchain is verified and the GPUs probed when the executor creates this backend, before it. */
    override fun verify() {
        check(gpus.pool.healthyCount() > 0) { "no healthy GPU" }
    }

    override fun status() = ProcessorStatus(
        id = info.id, version = version, model = model,
        healthy = gpus.pool.healthyCount() > 0,
        busy = gpus.pool.slots.none { it.healthy && it.busyWith == null },
        capacity = capacity,
    )

    override fun gpus(): List<GpuSlotStatus> = gpus.pool.slots.map { it.status() }

    override fun onAvailabilityChange(listener: (Boolean) -> Unit) {
        gpus.pool.onAvailabilityChange = listener
    }

    override fun maintenance() = gpus.periodicCheck()

    override fun close() = gpus.stop()

    override fun judge(job: JudgeJob, runDir: Path, hooks: JudgeHooks): JudgeResult {
        val problem = job.problem
        val arch = problem.compile.arch
        if (arch != null && !CompileSpec.runsOn(arch, props.profile.arch)) {
            // a mismatch between the problem and the GPU of the platform: retrying cannot help
            log.error("Job rejected: problem is compiled for {}, GPU profile {} is {}", arch, props.gpuProfile, props.profile.arch)
            return JudgeResult(Verdict.INTERNAL_ERROR, message = "Problem targets $arch, which GPU profile ${props.gpuProfile} (${props.profile.arch}) cannot run")
        }
        // validated by the pipeline: the processor is declared and has a limit
        val timeLimitMs = problem.scoreLimit(problem.processorSpec(info.id)!!)!!

        val timingMode = problem.timing.effectiveMode
        val policy = EffectivePolicy.of(props.profile.policy, problem.policy, timingMode)
        val build = timed("compile") { builder.build(job.source, problem.signature, problem.compile, policy, runDir) }
        when (build) {
            is BuildOutcome.CompileError -> {
                log.info("Compilation error: {}", build.message)
                return JudgeResult(Verdict.COMPILATION_ERROR, message = build.message, compileLog = build.compileLog)
            }
            is BuildOutcome.Violation -> {
                log.info("Policy violation: {}", build.violations)
                return JudgeResult(Verdict.POLICY_VIOLATION, message = build.violations.joinToString("\n"), compileLog = build.compileLog.ifBlank { null })
            }
            is BuildOutcome.Ok -> log.info("Build ok (object cached: {})", build.cached)
        }
        if (hooks.cancelled()) return JudgeResult(Verdict.CANCELLED, message = "Cancelled")

        val prepared = timed("prepare") {
            problems.acquire(problem, info.id, onPreparing = { hooks.status(SubmissionStatus.PREPARING) }, cancelled = hooks::cancelled)
        }
        try {
            val total = prepared.tests.size
            hooks.progress(0, total)
            val slot = gpus.pool.acquire(job.submissionId, Duration.ofMinutes(2), hooks::cancelled)
            var outcome: RunOutcome? = null
            var probed = false
            MDC.put("gpu", slot.index.toString())
            try {
                val o = timed("run") {
                    runner.run(problem, prepared, binary = build.binary, runDir = runDir, slot, timeLimitMs, onTestStart = { hooks.progress(it, total) }, hooks::cancelled)
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
                        return JudgeResult(
                            Verdict.RUNTIME_ERROR, failedTest = 1, message = message, compileLog = build.compileLog.ifBlank { null },
                            tests = listOf(TestResult(1, first.spec.name, first.spec.sample, Verdict.RUNTIME_ERROR, measured = first.spec.measure, message = message)),
                            timingMode = timingMode, gpu = gpuInfo(slot, o),
                        )
                    }
                }
                if (o.verdict == Verdict.INTERNAL_ERROR) {
                    log.error("Internal error while running: {}", o.internalReason)
                    if (o.retryable) throw RetryableRunException("run failed: ${o.internalReason}")
                }
                return JudgeResult(
                    verdict = o.verdict,
                    failedTest = o.failedTest,
                    message = o.message,
                    compileLog = build.compileLog.ifBlank { null },
                    tests = o.tests,
                    score = if (o.verdict == Verdict.ACCEPTED) o.tests.maxMeasuredScore() else null,
                    timingMode = timingMode,
                    gpu = gpuInfo(slot, o),
                )
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
        clocksLocked = slot.clocksLocked,
    )

    private fun <T> timed(stage: String, block: () -> T): T {
        val sample = Timer.start(meters)
        try {
            return block()
        } finally {
            sample.stop(meters.timer("judge.executor.stage", "stage", stage, "processor", info.id))
        }
    }
}
