package dev.gpucodeforces.judge.executor.processor.cudasim

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
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
import dev.gpucodeforces.judge.executor.processor.failureTitle
import dev.gpucodeforces.judge.executor.processor.gpucuda.CUDA_PATH
import dev.gpucodeforces.judge.executor.processor.scalarBits
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.Proc
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `cuda-sim`: CUDA on GPGPU-Sim (accel-sim fork) with the QV100 model, PTX executed cycle by cycle. The score of a
 * test is `gpu_tot_sim_cycle`, the simulated cycles of all kernels of the call. Tests that are not measured run in
 * the functional mode, which counts no cycles and is several times faster.
 *
 * Accepted limitation (judge/README.md): the simulator runs in the solution's process and prints its statistics to
 * the same stdout, so a solution could print forged lines; the last statistics before the harness's `done` count.
 */
class CudaSimBackend(
    private val props: ExecutorProperties,
    private val versionLine: String,
    private val builder: CudaSimBuilder,
    private val problems: ProblemStore,
    private val sandbox: Sandbox,
) : ProcessorBackend {
    private val config = props.cudaSim
    private val pool = SlotPool.workers(props.emulator(Processors.CUDA_SIM).workers)

    override val info: ProcessorInfo = Processors.get(Processors.CUDA_SIM)
    override val version: String get() = "$versionLine, harness ${CudaSimHarness.VERSION}"
    override val model: String get() = "GPGPU-Sim ${config.gpu}"
    override val capacity: Int get() = props.capacityOf(info.id)

    /** The toolchain was probed when the executor created this backend. */
    override fun verify() {}

    override fun status() = ProcessorStatus(
        id = info.id, version = version, model = model, healthy = true,
        busy = pool.slots.none { it.busyWith == null }, capacity = capacity,
    )

    override fun judge(job: JudgeJob, runDir: Path, hooks: JudgeHooks): JudgeResult {
        val problem = job.problem
        // validated by the pipeline: the processor is declared and has a limit
        val limit = problem.scoreLimit(problem.processorSpec(info.id)!!)!!
        val build = builder.build(job.source, problem.signature)
        val compileLog = build.compileLog.ifBlank { null }
        when (build) {
            is CudaSimBuildOutcome.CompileError -> return JudgeResult(Verdict.COMPILATION_ERROR, message = build.message, compileLog = compileLog)
            is CudaSimBuildOutcome.Violation -> return JudgeResult(Verdict.POLICY_VIOLATION, message = build.message, compileLog = compileLog)
            is CudaSimBuildOutcome.Ok -> {}
        }
        if (hooks.cancelled()) return JudgeResult(Verdict.CANCELLED, message = "Cancelled")
        val prepared = problems.acquire(problem, info.id, onPreparing = { hooks.status(SubmissionStatus.PREPARING) }, cancelled = hooks::cancelled)
        try {
            val slot = pool.acquire(job.submissionId, Duration.ofMinutes(2), hooks::cancelled)
            try {
                val outcome = EmulatorRun(sandbox).run(problem, prepared, info.scoreType, limit, runDir, hooks, config.simulationTimeLimit) { test, outDir, nonce ->
                    launch(problem, prepared, test, build.binary, outDir, nonce, if (test.spec.measure) cycleLimit(limit, config.maxCycles) else null)
                }
                return JudgeResult(
                    verdict = outcome.verdict, failedTest = outcome.failedTest, message = outcome.message, compileLog = compileLog,
                    tests = outcome.tests, score = outcome.score, metrics = outcome.metrics,
                )
            } finally {
                pool.release(slot)
            }
        } finally {
            problems.release(prepared.hash)
        }
    }

    /**
     * GPGPU-Sim reads `gpgpusim.config` from its working directory and writes the PTX it extracts there, so every
     * test runs in its own output directory with a copy of the model's files; [maxCycles] null = functional mode.
     */
    private fun launch(
        problem: ProblemSpec, prepared: PreparedProblem, test: PreparedTest, binary: Path, outDir: Path, nonce: String, maxCycles: Long?,
    ): SimulatorLaunch {
        for (file in config.gpuConfigs.listDirectoryEntries().filter { it.isRegularFile() }) {
            Files.copy(file, outDir.resolve(file.name))
        }
        val options = if (maxCycles != null) "-gpgpu_max_cycle $maxCycles" else "-gpgpu_ptx_sim_mode 1"
        outDir.resolve("gpgpusim.config").let { it.writeText(it.readText() + "\n# the judge's\n$options\n") }
        val sig = problem.signature
        val dataBytes = sig.arrays.sumOf { test.counts.getValue(it.name) * it.type.bytes }
        val maxOutput = sig.outputs.maxOfOrNull { test.counts.getValue(it.name) * it.type.bytes } ?: 0
        val spec = SandboxSpec(
            command = emptyList(), path = CUDA_PATH, workDir = outDir,
            readOnly = mapOf(binary.parent to BINARY_MOUNT, prepared.inputsDir to DATA_MOUNT, config.home to config.home.toString()),
            env = mapOf("LD_LIBRARY_PATH" to config.runtime.toString(), "CUDA_INSTALL_PATH" to "/usr/local/cuda") + SIMULATOR_ENV,
            wallTimeLimit = config.simulationTimeLimit.plusSeconds(5),
            memoryLimitBytes = (config.simulationMemoryLimitMb shl 20) + 2 * dataBytes,
            maxFileBytes = maxOutput + (256L shl 20),
        )
        val outputs = sig.outputs.associate { it.name to outDir.resolve("${it.name}.bin") }
        val manifest = outDir.resolve("manifest.txt")
        manifest.writeText(buildString {
            for (p in sig.scalars) appendLine("scalar ${p.name} ${scalarBits(p.type, test.spec.scalars.getValue(p.name))}")
            for (p in sig.arrays) {
                appendLine("array ${p.name} ${test.counts.getValue(p.name)} ${test.inputs[p.name]?.let { sandbox.inside(spec, it) } ?: "-"} ${outputs[p.name]?.let { sandbox.inside(spec, it) } ?: "-"}")
            }
        })
        val command = listOf(sandbox.inside(spec, binary), sandbox.inside(spec, manifest), nonce)
        return SimulatorLaunch(spec.copy(command = command), outputs, readOutcome = { exit -> outcome(exit, maxCycles, info.scoreType) }, keep = ::isStatistic)
    }

    companion object {
        private const val BINARY_MOUNT = "/sim"
        private const val DATA_MOUNT = "/data"
        private const val CYCLES = "gpu_tot_sim_cycle"
        private const val INSTRUCTIONS = "gpu_tot_sim_insn"
        private const val MAX_CYCLES_REACHED = "reaching the maximum cycles"

        /**
         * What GPGPU-Sim's `setup_environment` exports for CUDA 6 and later. Without them the simulator takes the old way
         * of reading the binary's code, finds no PTX in it and aborts at the first kernel registration.
         */
        private val SIMULATOR_ENV = mapOf(
            "CUOBJDUMP_SIM_FILE" to "jj", "PTX_SIM_USE_PTX_FILE" to "1.ptx", "PTX_SIM_KERNELFILE" to "_1.ptx",
        )

        fun isStatistic(line: String) = line.startsWith(CYCLES) || line.startsWith(INSTRUCTIONS) || MAX_CYCLES_REACHED in line

        /** Cycles a measured test may run: as many as the limit, at least one, at most [cap]. */
        fun cycleLimit(limit: Double, cap: Long): Long = limit.toLong().coerceIn(1, cap)

        private fun last(lines: List<String>, name: String): Long? =
            lines.lastOrNull { it.startsWith(name) }?.substringAfter('=')?.trim()?.toLongOrNull()

        /** How a run ended, from the harness's status and the simulator's last statistics. */
        fun outcome(exit: SimulatorExit, maxCycles: Long?, scoreType: ScoreType): SimulatorOutcome {
            val status = exit.statusLines.lastOrNull()
            return when {
                exit.kept.any { MAX_CYCLES_REACHED in it } -> SimulatorOutcome.Failed(
                    Verdict.TIME_LIMIT_EXCEEDED, "${failureTitle(Verdict.TIME_LIMIT_EXCEEDED, scoreType)} (more than $maxCycles cycles)",
                )
                status != null && status.startsWith("error ") -> SimulatorOutcome.Failed(
                    Verdict.RUNTIME_ERROR, failureTitle(Verdict.RUNTIME_ERROR, scoreType), details = status.removePrefix("error "),
                )
                exit.exitCode == 0 && status == "done" -> {
                    // the functional mode counts no cycles: an unmeasured test has no score
                    val cycles = last(exit.kept, CYCLES) ?: 0
                    val instructions = last(exit.kept, INSTRUCTIONS) ?: 0
                    SimulatorOutcome.Finished(cycles.toDouble(), mapOf("cycles" to cycles.toDouble(), "instructions" to instructions.toDouble()))
                }
                exit.exitCode == CudaSimHarness.EXIT_BROKEN && status == null ->
                    throw JudgeInfrastructureException("the cuda-sim harness rejected its input: ${exit.stderr.takeLast(2000)}")
                else -> SimulatorOutcome.Failed(
                    Verdict.RUNTIME_ERROR, failureTitle(Verdict.RUNTIME_ERROR, scoreType), details = "the program exited with code ${exit.exitCode}",
                )
            }
        }

        /** `nvcc`'s release and GPGPU-Sim's commit; throws [IllegalStateException] if one is missing or unexpected. */
        fun probe(props: ExecutorProperties): String {
            val config = props.cudaSim
            check(Files.isRegularFile(config.runtime.resolve("libcudart.so.12")) || Files.isRegularFile(config.runtime.resolve("libcudart.so"))) {
                "GPGPU-Sim's libcudart is missing in ${config.runtime}"
            }
            check(Files.isRegularFile(config.gpuConfigs.resolve("gpgpusim.config"))) { "no gpgpusim.config for ${config.gpu}" }
            val commit = config.home.resolve("VERSION").takeIf { Files.isRegularFile(it) }?.readText()?.trim()
                ?: error("${config.home}/VERSION is missing: the image writes the GPGPU-Sim commit there")
            config.expectedVersion?.let { check(commit.startsWith(it)) { "GPGPU-Sim $commit is not the expected $it" } }
            val nvcc = Proc.run(listOf(config.nvcc, "--version"), Duration.ofSeconds(30))
            check(nvcc.ok) { "nvcc is not usable: ${nvcc.describe()}" }
            val release = Regex("""release (\S+),""").find(nvcc.stdout)?.groupValues?.get(1) ?: "?"
            return "GPGPU-Sim ${commit.take(12)} (${config.gpu}), nvcc $release"
        }
    }
}
