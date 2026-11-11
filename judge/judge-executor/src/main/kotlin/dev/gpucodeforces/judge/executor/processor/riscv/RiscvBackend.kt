package dev.gpucodeforces.judge.executor.processor.riscv

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
import dev.gpucodeforces.judge.executor.processor.unitLimit
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.Proc
import dev.gpucodeforces.judge.executor.util.nextNonce
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** What the judge's QEMU plugin counted in one run. */
data class RiscvCounts(val instructions: Long, val l1Misses: Long, val llMisses: Long, val denied: Long = 0) {
    /** Instructions plus estimated stall cycles, in the spirit of Cachegrind: 10 per L1 miss, 100 per L2 miss. */
    val weighted: Long get() = instructions + 10 * l1Misses + 100 * llMisses

    operator fun minus(base: RiscvCounts) = RiscvCounts(
        (instructions - base.instructions).coerceAtLeast(0),
        (l1Misses - base.l1Misses).coerceAtLeast(0),
        (llMisses - base.llMisses).coerceAtLeast(0),
        denied,
    )

    companion object {
        /** `insns <n> l1i_misses <n> l1d_misses <n> l2_misses <n> denied <n>`, the plugin's line without the nonce. */
        fun parse(line: String): RiscvCounts? {
            val fields = line.trim().split(' ')
            if (fields.size != 10) return null
            val values = fields.chunked(2).associate { (k, v) -> k to (v.toLongOrNull() ?: return null) }
            return RiscvCounts(
                values["insns"] ?: return null,
                (values["l1i_misses"] ?: return null) + (values["l1d_misses"] ?: return null),
                values["l2_misses"] ?: return null,
                values["denied"] ?: return null,
            )
        }
    }
}

/**
 * `riscv-rvv`: C, C++ or assembly for RV64GCV, run under QEMU user mode with the judge's plugin
 * (judge/riscv/judge_plugin.c). The score of a test is the weighted instructions ([RiscvCounts.weighted]) of the
 * whole program minus those of the same harness with an empty `solve` on the same test.
 */
class RiscvBackend(
    props: ExecutorProperties,
    private val toolchain: RiscvToolchain,
    private val builder: RiscvBuilder,
    problems: ProblemStore,
    sandbox: Sandbox,
) : EmulatorBackend<RiscvBackend.Binaries>(Processors.RISCV_RVV, props, problems, sandbox, props.riscv.simulationTimeLimit) {
    private val config = props.riscv
    private val random = SecureRandom()
    private val baselineDir = props.paths.cache.resolve("riscv").resolve("baseline")

    /** The submission with the harness, and the harness alone ([RiscvBuilder.baseline]). */
    class Binaries(val solution: Path, val baseline: RiscvBinary)

    override val version: String get() = "${toolchain.description}, VLEN ${config.vlen}, harness ${RiscvHarness.VERSION}"
    override val model: String get() = "QEMU ${toolchain.qemuVersion} RV64GCV, VLEN ${config.vlen}"

    override fun build(job: JudgeJob, processor: ProcessorSpec): EmulatorBuild<Binaries> {
        val signature = job.problem.signature
        return when (val build = builder.build(job.source, job.language, signature)) {
            is EmulatorBuild.Rejected -> build
            is EmulatorBuild.Ready ->
                EmulatorBuild.Ready(Binaries(build.artifact.path, builder.baseline(job.language, signature)), build.compileLog)
        }
    }

    override fun launch(run: TestRun, artifact: Binaries): SimulatorLaunch {
        val base = baselineCounts(run, artifact.baseline)
        val allowed = if (run.test.spec.measure) unitLimit(run.limit, config.maxInstructions) else config.maxInstructions
        val spec = runSpec(run, artifact.solution, run.nonce, base.instructions + allowed)
        return SimulatorLaunch(spec, run.outputs) { exit -> outcome(exit, base, allowed, info.scoreType) }
    }

    /**
     * What the harness alone costs on a test: measured once per problem version, test and toolchain, then cached. It
     * runs with the same paths as the solution will (the output directory, the same manifest name), because the guest
     * reads them: their lengths move the stack and change the counts a little. Under nsjail every path the guest sees
     * is fixed.
     */
    private fun baselineCounts(run: TestRun, baseline: RiscvBinary): RiscvCounts {
        val file = baselineDir.resolve(Hashing.sha256(baseline.key, run.prepared.hash, run.test.index.toString(), RUN_FORMAT))
        if (file.exists()) RiscvCounts.parse(file.readText())?.let { return it }
        val nonce = random.nextNonce()
        val spec = runSpec(run, baseline.path, nonce, config.maxInstructions)
        var line: String? = null
        val res = Proc.awaitLines(sandbox.start(spec), spec.command, config.simulationTimeLimit) { if (it.startsWith("$nonce ")) line = it.removePrefix("$nonce ") }
        run.outputs.values.forEach { Files.deleteIfExists(it) }
        val counted = line?.let { text -> RiscvCounts.parse(text)?.let { text to it } }
        if (!res.ok || counted == null) throw JudgeInfrastructureException("the baseline run failed: ${res.describe()}")
        Files.createDirectories(baselineDir)
        file.writeText(counted.first)
        return counted.second
    }

    private fun runSpec(run: TestRun, binary: Path, nonce: String, maxInstructions: Long): SandboxSpec {
        val qemuHome = config.qemu.parent.parent
        val spec = SandboxSpec(
            command = emptyList(),
            // only the output directory is writable for the guest: the manifest stays out of its reach
            workDir = run.runDir,
            readOnly = mapOf(
                binary.parent to SIMULATOR_MOUNT, run.prepared.inputsDir to DATA_MOUNT,
                qemuHome to qemuHome.toString(), config.plugin.parent to config.plugin.parent.toString(),
            ),
            wallTimeLimit = config.simulationTimeLimit.plusSeconds(5),
            // QEMU reserves address space for the guest up front, so only the resident memory is limited
            memoryLimitBytes = (config.simulationMemoryLimitMb shl 20) + 2 * run.dataBytes,
            maxFileBytes = run.maxOutputBytes + (1L shl 20),
        )
        val manifest = Files.createDirectories(run.runDir.resolve("manifests")).resolve("${run.outDir.fileName}.txt")
        manifest.writeText(run.manifest { sandbox.inside(spec, it) })
        val plugin = listOf(
            config.plugin.toString(), "fd=1", "nonce=$nonce", "limit=$maxInstructions",
            "allow=${sandbox.inside(spec, run.prepared.inputsDir)}", "allow=${sandbox.inside(spec, run.runDir)}",
        ).joinToString(",")
        // QEMU reports a guest killed by a signal by sending the signal to itself. nsjail starts the command as PID 1 of
        // its own namespace, and PID 1 ignores such signals: QEMU would hang. Under a shell it is an ordinary child;
        // the shell's exit code is then 128 + the signal. (`; exit` keeps the shell from exec-ing QEMU in its place.)
        val command = listOf(
            "/bin/sh", "-c", "\"$@\"; exit $?", "sh",
            config.qemu.toString(), "-R", GUEST_SPACE, "-cpu", "rv64,v=true,vlen=${config.vlen},elen=64", "-plugin", plugin,
            sandbox.inside(spec, binary), sandbox.inside(spec, manifest),
        )
        return spec.copy(command = command)
    }

    companion object {
        /**
         * Bump when the way a run is started changes (command, environment, paths): the guest sees all of them, so a
         * cached baseline measured the old way no longer matches.
         */
        private const val RUN_FORMAT = "3"

        /**
         * The guest's address space, reserved up front: QEMU then places the guest's stack and mappings inside it the
         * same way every time. Without it, QEMU probes the host's randomized layout, and the simulated L2 sees the
         * arrays at other offsets now and then.
         */
        private const val GUEST_SPACE = "0x200000000"

        /** How a run ended, from QEMU's exit code (the guest's) and the plugin's line. */
        fun outcome(exit: SimulatorExit, base: RiscvCounts, maxInstructions: Long, scoreType: ScoreType): SimulatorOutcome {
            val line = exit.statusLines.lastOrNull()
            return when {
                line == "limit" -> SimulatorOutcome.Failed(
                    Verdict.TIME_LIMIT_EXCEEDED, "${failureTitle(Verdict.TIME_LIMIT_EXCEEDED, scoreType)} (more than $maxInstructions instructions)",
                )
                exit.exitCode != 0 -> SimulatorOutcome.Failed(
                    Verdict.RUNTIME_ERROR, failureTitle(Verdict.RUNTIME_ERROR, scoreType), details = "the program exited with code ${exit.exitCode}",
                )
                else -> {
                    val counts = line?.let(RiscvCounts::parse)
                        ?: throw JudgeInfrastructureException("QEMU finished without the plugin's counts: ${exit.statusLines}")
                    val own = counts - base
                    SimulatorOutcome.Finished(
                        own.weighted.toDouble(),
                        buildMap {
                            put("instructions", own.instructions.toDouble())
                            put("l1_misses", own.l1Misses.toDouble())
                            put("ll_misses", own.llMisses.toDouble())
                            if (counts.denied > 0) put("denied_syscalls", counts.denied.toDouble())
                        },
                    )
                }
            }
        }
    }
}
