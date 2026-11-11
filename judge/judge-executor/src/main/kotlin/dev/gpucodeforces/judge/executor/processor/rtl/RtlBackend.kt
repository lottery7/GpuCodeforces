package dev.gpucodeforces.judge.executor.processor.rtl

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.RtlSettings
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
import dev.gpucodeforces.judge.executor.processor.unitLimit
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * `rtl`: a SystemVerilog circuit, synthesized by Yosys and simulated by Verilator with the judge's testbench
 * ([RtlTestbench]). The score of a test is cycles until `done` times the logic depth times the transistors of the
 * circuit ([CircuitSize]); the testbench stops a test once the cycles alone exceed what the limit allows.
 */
class RtlBackend(
    props: ExecutorProperties,
    private val toolchain: RtlToolchain,
    private val builder: RtlBuilder,
    problems: ProblemStore,
    sandbox: Sandbox,
) : EmulatorBackend<RtlSimulator>(Processors.RTL, props, problems, sandbox, props.rtl.simulationTimeLimit) {
    private val config = props.rtl

    override val version: String get() = "${toolchain.description}, testbench ${RtlTestbench.VERSION}"
    override val model: String get() = "Verilator ${toolchain.verilatorVersion} + Yosys ${toolchain.yosysVersion}"

    override fun build(job: JudgeJob, processor: ProcessorSpec): EmulatorBuild<RtlSimulator> =
        builder.build(job.source, job.problem.signature, processor.rtl ?: RtlSettings()).also {
            if (it is EmulatorBuild.Ready) log.info("Circuit: {}", it.artifact.size)
        }

    /** See [RtlTestbench] for the manifest. */
    override fun launch(run: TestRun, artifact: RtlSimulator): SimulatorLaunch {
        val test = run.test
        val maxCycles = if (test.spec.measure) unitLimit(run.limit, config.maxCycles, artifact.size.perCycle) else config.maxCycles
        // the testbench keeps every element in 64 bits
        val dataBytes = run.problem.signature.arrays.sumOf { test.counts.getValue(it.name) * Long.SIZE_BYTES }
        val memory = (config.simulationMemoryLimitMb shl 20) + dataBytes
        val spec = SandboxSpec(
            command = emptyList(),
            // only outDir is writable for the circuit's simulator: the manifest stays out of its reach
            workDir = run.runDir,
            readOnly = mapOf(artifact.simulator.parent to SIMULATOR_MOUNT, run.prepared.inputsDir to DATA_MOUNT),
            wallTimeLimit = config.simulationTimeLimit.plusSeconds(5),
            memoryLimitBytes = memory,
            addressSpaceBytes = memory,
            maxFileBytes = run.maxOutputBytes + (1L shl 20),
        )
        val manifest = Files.createDirectories(run.runDir.resolve("manifests")).resolve("t${test.index}.txt")
        manifest.writeText("nonce ${run.nonce}\nmax_cycles $maxCycles\n" + run.manifest { sandbox.inside(spec, it) })
        val command = listOf(sandbox.inside(spec, artifact.simulator), sandbox.inside(spec, manifest))
        return SimulatorLaunch(spec.copy(command = command), run.outputs) { exit -> outcome(exit, artifact.size, maxCycles, info.scoreType) }
    }

    companion object {
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
