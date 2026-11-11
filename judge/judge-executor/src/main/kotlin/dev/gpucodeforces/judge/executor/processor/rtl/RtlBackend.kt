package dev.gpucodeforces.judge.executor.processor.rtl

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.EmulatorRun
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.ProcessorBackend
import dev.gpucodeforces.judge.executor.processor.SimulatorExit
import dev.gpucodeforces.judge.executor.processor.SimulatorLaunch
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.processor.SlotPool
import dev.gpucodeforces.judge.executor.processor.WorkerSlot
import dev.gpucodeforces.judge.executor.processor.failureTitle
import dev.gpucodeforces.judge.executor.processor.scalarBits
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

/**
 * `rtl`: a SystemVerilog circuit, synthesized by Yosys and simulated by Verilator with the judge's testbench
 * ([RtlTestbench]). The score of a test is cycles until `done` times the logic depth times the transistors of the
 * circuit ([CircuitSize]); the testbench stops a test once the cycles alone exceed what the limit allows.
 */
class RtlBackend(
    private val props: ExecutorProperties,
    private val toolchain: RtlToolchain,
    private val builder: RtlBuilder,
    private val problems: ProblemStore,
    private val sandbox: Sandbox,
) : ProcessorBackend {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.rtl
    private val pool = SlotPool.workers(props.emulator(Processors.RTL).workers)

    override val info: ProcessorInfo = Processors.get(Processors.RTL)
    override val version: String get() = "${toolchain.description}, testbench ${RtlTestbench.VERSION}"
    override val model: String get() = "Verilator ${toolchain.verilatorVersion} + Yosys ${toolchain.yosysVersion}"
    override val capacity: Int get() = props.capacityOf(info.id)

    /** The toolchain was probed when the executor created this backend. */
    override fun verify() {}

    override fun status() = ProcessorStatus(
        id = info.id, version = version, model = model,
        healthy = true,
        busy = pool.slots.none { it.busyWith == null },
        capacity = capacity,
    )

    override fun judge(job: JudgeJob, runDir: Path, hooks: JudgeHooks): JudgeResult {
        val problem = job.problem
        // validated by the pipeline: the processor is declared and has a limit
        val processor = problem.processorSpec(info.id)!!
        val limit = problem.scoreLimit(processor)!!
        val settings = processor.rtl ?: RtlSettings()

        val build = builder.build(job.source, problem.signature, settings)
        val compileLog = build.compileLog.ifBlank { null }
        when (build) {
            is RtlBuildOutcome.CompileError -> {
                log.info("Compilation error: {}", build.message)
                return JudgeResult(Verdict.COMPILATION_ERROR, message = build.message, compileLog = compileLog)
            }
            is RtlBuildOutcome.Violation -> {
                log.info("Policy violation: {}", build.violations)
                return JudgeResult(Verdict.POLICY_VIOLATION, message = build.violations.joinToString("\n"))
            }
            is RtlBuildOutcome.Ok -> log.info("Build ok: {} (cached: {})", build.size, build.cached)
        }
        if (hooks.cancelled()) return JudgeResult(Verdict.CANCELLED, message = "Cancelled")

        val prepared = problems.acquire(problem, info.id, onPreparing = { hooks.status(SubmissionStatus.PREPARING) }, cancelled = hooks::cancelled)
        try {
            val slot = pool.acquire(job.submissionId, Duration.ofMinutes(2), hooks::cancelled)
            try {
                val outcome = EmulatorRun(sandbox).run(
                    problem, prepared, info.scoreType, limit, runDir, hooks, config.simulationTimeLimit,
                ) { test, outDir, nonce -> launch(problem, prepared, test, build, limit, runDir, outDir, nonce) }
                return JudgeResult(
                    verdict = outcome.verdict,
                    failedTest = outcome.failedTest,
                    message = outcome.message,
                    compileLog = compileLog,
                    tests = outcome.tests,
                    score = outcome.score,
                    metrics = outcome.metrics,
                )
            } finally {
                pool.release(slot)
            }
        } finally {
            problems.release(prepared.hash)
        }
    }

    private fun launch(
        problem: ProblemSpec, prepared: PreparedProblem, test: PreparedTest, build: RtlBuildOutcome.Ok,
        limit: Double, runDir: Path, outDir: Path, nonce: String,
    ): SimulatorLaunch {
        val sig = problem.signature
        val maxCycles = if (test.spec.measure) cycleLimit(limit, build.size.perCycle, config.maxCycles) else config.maxCycles
        val dataBytes = sig.arrays.sumOf { test.counts.getValue(it.name) * Long.SIZE_BYTES }
        val maxOutput = sig.outputs.maxOfOrNull { test.counts.getValue(it.name) * it.type.bytes } ?: 0
        val memory = (config.simulationMemoryLimitMb shl 20) + dataBytes
        val spec = SandboxSpec(
            command = emptyList(),
            // only outDir is writable for the circuit's simulator: the manifest stays out of its reach
            workDir = runDir,
            readOnly = mapOf(build.simulator.parent to SIMULATOR_MOUNT, prepared.inputsDir to DATA_MOUNT),
            wallTimeLimit = config.simulationTimeLimit.plusSeconds(5),
            memoryLimitBytes = memory,
            addressSpaceBytes = memory,
            maxFileBytes = maxOutput + (1L shl 20),
        )
        val outputs = sig.outputs.associate { it.name to outDir.resolve("${it.name}.bin") }
        val manifest = Files.createDirectories(runDir.resolve("manifests")).resolve("t${test.index}.txt")
        manifest.writeText(manifest(problem, test, outputs, nonce, maxCycles) { sandbox.inside(spec, it) })
        val command = listOf(sandbox.inside(spec, build.simulator), sandbox.inside(spec, manifest))
        return SimulatorLaunch(spec.copy(command = command), outputs) { exit -> outcome(exit, build.size, maxCycles, info.scoreType) }
    }

    /** See [RtlTestbench] for the format; [inside] maps a host path into the sandbox. */
    private fun manifest(
        problem: ProblemSpec, test: PreparedTest, outputs: Map<String, Path>, nonce: String, maxCycles: Long, inside: (Path) -> String,
    ): String = buildString {
        appendLine("nonce $nonce")
        appendLine("max_cycles $maxCycles")
        for (p in problem.signature.scalars) {
            appendLine("scalar ${p.name} ${scalarBits(p.type, test.spec.scalars.getValue(p.name))}")
        }
        for (p in problem.signature.arrays) {
            val input = test.inputs[p.name]?.let(inside) ?: "-"
            val output = outputs[p.name]?.let(inside) ?: "-"
            appendLine("array ${p.name} ${test.counts.getValue(p.name)} $input $output")
        }
    }

    companion object {
        private const val SIMULATOR_MOUNT = "/sim"
        private const val DATA_MOUNT = "/data"

        /**
         * Cycles a measured test may run: the most whose score (cycles x [perCycle]) stays within [limit], at least one
         * (a circuit too large for any cycle then fails on the first) and at most [cap].
         */
        fun cycleLimit(limit: Double, perCycle: Long, cap: Long): Long =
            if (perCycle <= 0) cap else (limit / perCycle).toLong().coerceIn(1, cap)


        /** How a simulation ended, from the testbench's exit code and status lines. */
        fun outcome(exit: SimulatorExit, size: CircuitSize, maxCycles: Long, scoreType: ScoreType): SimulatorOutcome {
            val status = exit.statusLines.firstOrNull()
            return when (exit.exitCode) {
                0 -> {
                    val cycles = status?.removePrefix("cycles ")?.takeIf { status.startsWith("cycles ") }?.toLongOrNull()
                        ?: throw JudgeInfrastructureException("the testbench finished without a cycle count: ${exit.statusLines}")
                    SimulatorOutcome.Finished(cycles.toDouble() * size.perCycle, size.metrics(cycles))
                }
                RtlTestbench.EXIT_ERROR -> SimulatorOutcome.Failed(
                    Verdict.RUNTIME_ERROR, failureTitle(Verdict.RUNTIME_ERROR, scoreType),
                    details = status?.removePrefix("error ") ?: "the circuit failed",
                )
                RtlTestbench.EXIT_LIMIT -> SimulatorOutcome.Failed(
                    Verdict.TIME_LIMIT_EXCEEDED, "${failureTitle(Verdict.TIME_LIMIT_EXCEEDED, scoreType)} (done did not rise within $maxCycles cycles)",
                )
                RtlTestbench.EXIT_BROKEN -> throw JudgeInfrastructureException("the testbench rejected its input: ${exit.stderr.takeLast(2000)}")
                // killed by the sandbox's memory limit, or a crash of the simulated circuit's code
                else -> SimulatorOutcome.Failed(
                    Verdict.RUNTIME_ERROR, failureTitle(Verdict.RUNTIME_ERROR, scoreType), details = "the simulator exited with code ${exit.exitCode}",
                )
            }
        }
    }
}
