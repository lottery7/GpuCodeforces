package dev.gpucodeforces.judge.executor.processor.coralnpu

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.DATA_MOUNT
import dev.gpucodeforces.judge.executor.processor.EmulatorBackend
import dev.gpucodeforces.judge.executor.processor.EmulatorBuild
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.Refused
import dev.gpucodeforces.judge.executor.processor.SIMULATOR_MOUNT
import dev.gpucodeforces.judge.executor.processor.SimulatorExit
import dev.gpucodeforces.judge.executor.processor.SimulatorLaunch
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.processor.TestLaunch
import dev.gpucodeforces.judge.executor.processor.TestRun
import dev.gpucodeforces.judge.executor.processor.failureTitle
import dev.gpucodeforces.judge.executor.processor.scalarBits
import dev.gpucodeforces.judge.executor.processor.unitLimit
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText

/**
 * `coral-npu`: C or C++ for the Coral NPU core (RvvCoreMini: RV32IMF, Zbb, 32-bit vectors of 128 bits; 8 KB of
 * code, 32 KB of data), run bare-metal on Renode's model of the core. Renode is the host of the core here: the
 * judge's Monitor command (judge/coralnpu/judge_run.py) loads the program and the test's arrays into the core's
 * memories, starts it and reads the outputs back when it halts. The score of a test is the instructions the core
 * executed; the program has no channel to the outside, so it cannot report anything itself.
 */
class CoralNpuBackend(
    props: ExecutorProperties,
    private val toolchain: CoralNpuToolchain,
    private val builder: CoralNpuBuilder,
    problems: ProblemStore,
    sandbox: Sandbox,
) : EmulatorBackend<CoralNpuProgram>(Processors.CORAL_NPU, props, problems, sandbox, props.coralNpu.simulationTimeLimit) {
    private val config = props.coralNpu

    override val version: String get() = "${toolchain.description}, harness ${CoralNpuHarness.VERSION}"
    override val model: String get() = "Coral NPU (RV32IMF, Zve32x, VLEN 128) in Renode ${toolchain.renodeVersion}"

    override fun build(job: JudgeJob, processor: ProcessorSpec): EmulatorBuild<CoralNpuProgram> =
        builder.build(job.source, job.language, job.problem.signature)

    override fun launch(run: TestRun, artifact: CoralNpuProgram): TestLaunch {
        val placement = CoralNpuPlacement.of(run.problem.signature, run.test.counts, artifact.dataEnd)
        if (!placement.fits) return Refused(doesNotFit(run, artifact))
        val allowed = if (run.test.spec.measure) unitLimit(run.limit, config.maxInstructions) else config.maxInstructions
        val renodeHome = config.renode.parent
        val scripts = config.platform.parent
        val spec = SandboxSpec(
            command = emptyList(),
            workDir = run.runDir,
            readOnly = mapOf(
                artifact.path.parent to SIMULATOR_MOUNT, run.prepared.inputsDir to DATA_MOUNT,
                renodeHome to renodeHome.toString(), scripts to scripts.toString(),
            ),
            wallTimeLimit = config.simulationTimeLimit.plusSeconds(5),
            memoryLimitBytes = config.simulationMemoryLimitMb shl 20,
            // Renode keeps its translation cache in a file
            maxFileBytes = run.maxOutputBytes + TRANSLATION_CACHE_BYTES + (1L shl 20),
        )
        // Renode rewrites its settings file, so it lies where the sandbox may write
        val settings = run.outDir.resolve("renode.config")
        settings.writeText(RENODE_SETTINGS)
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-rw-rw-"))
        val manifest = Files.createDirectories(run.runDir.resolve("manifests")).resolve("${run.outDir.fileName}.txt")
        manifest.writeText(manifest(run, placement, sandbox.inside(spec, artifact.path), allowed) { sandbox.inside(spec, it) })
        val command = listOf(
            config.renode.toString(), "--console", "--disable-gui", "--plain", "--hide-log",
            "--config", sandbox.inside(spec, settings),
            config.platform.toString(), "-e", "judge_run \"${sandbox.inside(spec, manifest)}\"",
        )
        return SimulatorLaunch(spec.copy(command = command), run.outputs, keep = { it.isNotBlank() }) { exit -> outcome(exit, allowed, info.scoreType) }
    }

    private fun doesNotFit(run: TestRun, program: CoralNpuProgram) = SimulatorOutcome.Failed(
        Verdict.MEMORY_LIMIT_EXCEEDED, failureTitle(Verdict.MEMORY_LIMIT_EXCEEDED, info.scoreType),
        details = "the data memory is ${CoralNpuMemory.DATA_BYTES} bytes: the program's own data takes " +
            "${program.dataEnd - CoralNpuMemory.DATA_START}, the arrays of the test ${run.dataBytes}, and ${CoralNpuMemory.STACK_RESERVE} are left to the stack",
    )

    companion object {
        /** What judge_run.py reads, see its header. Paths are those inside the sandbox ([inside] maps a host path). */
        fun manifest(run: TestRun, placement: CoralNpuPlacement, program: String, limit: Long, inside: (Path) -> String): String = buildString {
            val sig = run.problem.signature
            appendLine("nonce ${run.nonce}")
            appendLine("limit $limit")
            appendLine("elf $program")
            for (words in CoralNpuHarness.argWords(sig)) {
                val p = words.param
                val bits = if (p.role == ParamRole.SCALAR) scalarBits(p.type, run.test.spec.scalars.getValue(p.name)).toULong() else placement.addresses.getValue(p.name).toULong()
                for (i in 0 until words.count) {
                    appendLine("word ${CoralNpuMemory.DATA_START + WORD_BYTES * (words.first + i)} ${(bits shr (Int.SIZE_BITS * i)) and WORD_MASK}")
                }
            }
            for (p in sig.arrays) {
                val address = placement.addresses.getValue(p.name)
                val bytes = run.test.counts.getValue(p.name) * p.type.bytes
                val input = run.test.inputs[p.name]
                if (input != null) appendLine("load $address $bytes ${inside(input)}") else appendLine("fill $address $bytes $UNWRITTEN")
                run.outputs[p.name]?.let { appendLine("dump $address $bytes ${inside(it)}") }
            }
        }

        /**
         * By default Renode starts with 32 MB of translated code and lets it grow to 128 MB. The core has 8 KB of
         * instructions; a fixed cache also fixes what the sandbox has to allow.
         */
        private const val TRANSLATION_CACHE_BYTES = 16L shl 20

        /** The settings file of a run; Renode adds the defaults of everything else to it. */
        private const val RENODE_SETTINGS = "[translation]\nmin-tb-size = $TRANSLATION_CACHE_BYTES\nmax-tb-size = $TRANSLATION_CACHE_BYTES\n"

        private const val WORD_BYTES = 4
        private const val WORD_MASK = 0xFFFF_FFFFuL

        /** An output array starts as these bytes, as on `riscv-rvv`: an element `solve` never wrote is not a zero. */
        private const val UNWRITTEN = 0xFF

        /** How a run ended, from the line of judge_run.py: `halted|limit|fault <instructions>` or `trap <instructions> <cause>`. */
        fun outcome(exit: SimulatorExit, maxInstructions: Long, scoreType: ScoreType): SimulatorOutcome {
            val fields = exit.statusLines.lastOrNull()?.split(' ').orEmpty()
            val instructions = fields.getOrNull(1)?.toLongOrNull()
                ?: throw JudgeInfrastructureException(
                    "Renode finished without a result (exit code ${exit.exitCode}): " +
                        (exit.kept.takeLast(12) + exit.stderr.trim().takeLast(1500)).filter { it.isNotBlank() }.joinToString(" | ").take(4000),
                )
            val tooLong = SimulatorOutcome.Failed(
                Verdict.TIME_LIMIT_EXCEEDED, "${failureTitle(Verdict.TIME_LIMIT_EXCEEDED, scoreType)} (more than $maxInstructions instructions)",
            )
            fun crashed(what: String) = SimulatorOutcome.Failed(
                Verdict.RUNTIME_ERROR, failureTitle(Verdict.RUNTIME_ERROR, scoreType), details = "$what after $instructions instructions",
            )
            return when (fields.first()) {
                // the run is stopped a little after the limit, so a program may still halt beyond it
                "halted" -> if (instructions > maxInstructions) tooLong else SimulatorOutcome.Finished(instructions.toDouble())
                "limit" -> tooLong
                "fault" -> crashed("the core raised FAULT: an access outside its instruction and data memories")
                "trap" -> crashed("the core trapped: ${trapName(fields.getOrNull(2)?.toIntOrNull())}")
                else -> throw JudgeInfrastructureException("an unknown result from Renode: ${exit.statusLines}")
            }
        }

        /** RISC-V exception codes, as Renode's hook reports them. */
        private fun trapName(cause: Int?): String = when (cause) {
            0 -> "instruction address misaligned"
            1 -> "instruction access fault"
            2 -> "illegal instruction"
            3 -> "breakpoint"
            4 -> "load address misaligned"
            5 -> "load access fault"
            6 -> "store address misaligned"
            7 -> "store access fault"
            8, 9, 11 -> "environment call"
            null -> "unknown cause"
            else -> "cause $cause"
        }
    }
}
