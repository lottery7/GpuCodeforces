package dev.gpucodeforces.judge.executor.processor.cudasim

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.DATA_MOUNT
import dev.gpucodeforces.judge.executor.processor.EmulatorBackend
import dev.gpucodeforces.judge.executor.processor.EmulatorBuild
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.SIMULATOR_MOUNT
import dev.gpucodeforces.judge.executor.processor.SimulatorExit
import dev.gpucodeforces.judge.executor.processor.SimulatorLaunch
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.processor.TestRun
import dev.gpucodeforces.judge.executor.processor.failureTitle
import dev.gpucodeforces.judge.executor.processor.gpucuda.CUDA_PATH
import dev.gpucodeforces.judge.executor.processor.unitLimit
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
    props: ExecutorProperties,
    private val versionLine: String,
    private val builder: CudaSimBuilder,
    problems: ProblemStore,
    sandbox: Sandbox,
) : EmulatorBackend<Path>(Processors.CUDA_SIM, props, problems, sandbox, props.cudaSim.simulationTimeLimit) {
    private val config = props.cudaSim

    override val version: String get() = "$versionLine, harness ${CudaSimHarness.VERSION}"
    override val model: String get() = "GPGPU-Sim ${config.gpu}"

    override fun build(job: JudgeJob, processor: ProcessorSpec): EmulatorBuild<Path> = builder.build(job.source, job.problem.signature)

    /**
     * GPGPU-Sim reads `gpgpusim.config` from its working directory and writes the PTX it extracts there, so every
     * test runs in its own output directory with a copy of the model's files.
     */
    override fun launch(run: TestRun, artifact: Path): SimulatorLaunch {
        val outDir = run.outDir
        for (file in config.gpuConfigs.listDirectoryEntries().filter { it.isRegularFile() }) {
            Files.copy(file, outDir.resolve(file.name))
        }
        // null = the functional mode
        val maxCycles = if (run.test.spec.measure) unitLimit(run.limit, config.maxCycles) else null
        val options = if (maxCycles != null) "-gpgpu_max_cycle $maxCycles" else "-gpgpu_ptx_sim_mode 1"
        outDir.resolve("gpgpusim.config").let { it.writeText(it.readText() + "\n# the judge's\n$options\n") }
        val spec = SandboxSpec(
            command = emptyList(), path = CUDA_PATH, workDir = outDir,
            readOnly = mapOf(artifact.parent to SIMULATOR_MOUNT, run.prepared.inputsDir to DATA_MOUNT, config.home to config.home.toString()),
            env = mapOf("LD_LIBRARY_PATH" to config.runtime.toString(), "CUDA_INSTALL_PATH" to "/usr/local/cuda") + SIMULATOR_ENV,
            wallTimeLimit = config.simulationTimeLimit.plusSeconds(5),
            memoryLimitBytes = (config.simulationMemoryLimitMb shl 20) + 2 * run.dataBytes,
            maxFileBytes = run.maxOutputBytes + (256L shl 20),
        )
        val manifest = outDir.resolve("manifest.txt")
        manifest.writeText(run.manifest { sandbox.inside(spec, it) })
        val command = listOf(sandbox.inside(spec, artifact), sandbox.inside(spec, manifest), run.nonce)
        return SimulatorLaunch(spec.copy(command = command), run.outputs, readOutcome = { exit -> outcome(exit, maxCycles, info.scoreType) }, keep = ::isStatistic)
    }

    companion object {
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
