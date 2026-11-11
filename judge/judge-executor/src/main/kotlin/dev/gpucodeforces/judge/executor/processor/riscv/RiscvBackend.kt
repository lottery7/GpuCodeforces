package dev.gpucodeforces.judge.executor.processor.riscv

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
import dev.gpucodeforces.judge.executor.processor.scalarBits
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.Proc
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

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
    private val props: ExecutorProperties,
    private val toolchain: RiscvToolchain,
    private val builder: RiscvBuilder,
    private val problems: ProblemStore,
    private val sandbox: Sandbox,
) : ProcessorBackend {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.riscv
    private val pool = SlotPool.workers(props.emulator(Processors.RISCV_RVV).workers)
    private val random = SecureRandom()
    private val baselineDir = props.paths.cache.resolve("riscv").resolve("baseline")

    override val info: ProcessorInfo = Processors.get(Processors.RISCV_RVV)
    override val version: String get() = "${toolchain.description}, VLEN ${config.vlen}, harness ${RiscvHarness.VERSION}"
    override val model: String get() = "QEMU ${toolchain.qemuVersion} RV64GCV, VLEN ${config.vlen}"
    override val capacity: Int get() = props.capacityOf(info.id)

    /** The toolchain was probed when the executor created this backend. */
    override fun verify() {}

    override fun status() = ProcessorStatus(
        id = info.id, version = version, model = model, healthy = true,
        busy = pool.slots.none { it.busyWith == null }, capacity = capacity,
    )

    override fun judge(job: JudgeJob, runDir: Path, hooks: JudgeHooks): JudgeResult {
        val problem = job.problem
        // validated by the pipeline: the processor is declared, has a limit and allows the language
        val limit = problem.scoreLimit(problem.processorSpec(info.id)!!)!!
        val build = builder.build(job.source, job.language, problem.signature)
        val compileLog = build.compileLog.ifBlank { null }
        when (build) {
            is RiscvBuildOutcome.CompileError -> return JudgeResult(Verdict.COMPILATION_ERROR, message = build.message, compileLog = compileLog)
            is RiscvBuildOutcome.Ok -> log.info("Build ok (cached: {})", build.cached)
        }
        val baseline = builder.baseline(job.language, problem.signature)
        if (hooks.cancelled()) return JudgeResult(Verdict.CANCELLED, message = "Cancelled")

        val prepared = problems.acquire(problem, info.id, onPreparing = { hooks.status(SubmissionStatus.PREPARING) }, cancelled = hooks::cancelled)
        try {
            val slot = pool.acquire(job.submissionId, Duration.ofMinutes(2), hooks::cancelled)
            try {
                val outcome = EmulatorRun(sandbox).run(problem, prepared, info.scoreType, limit, runDir, hooks, config.simulationTimeLimit) { test, outDir, nonce ->
                    val base = baselineCounts(problem, prepared, test, baseline, runDir, outDir)
                    val maxInstructions = base.instructions +
                        if (test.spec.measure) instructionLimit(limit, config.maxInstructions) else config.maxInstructions
                    val (spec, outputs) = runSpec(problem, prepared, test, build.binary, runDir, outDir, nonce, maxInstructions)
                    SimulatorLaunch(spec, outputs) { exit -> outcome(exit, base, maxInstructions - base.instructions, info.scoreType) }
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
     * What the harness alone costs on [test]: measured once per problem version, test and toolchain, then cached. It
     * runs with the same paths as the solution will ([outDir], the same manifest name), because the guest reads them:
     * their lengths move the stack and change the counts a little. Under nsjail every path the guest sees is fixed.
     */
    private fun baselineCounts(
        problem: ProblemSpec, prepared: PreparedProblem, test: PreparedTest, baseline: RiscvBuildOutcome.Ok, runDir: Path, outDir: Path,
    ): RiscvCounts {
        val file = baselineDir.resolve(Hashing.sha256(baseline.key, prepared.hash, test.index.toString(), RUN_FORMAT))
        if (file.exists()) RiscvCounts.parse(file.readText())?.let { return it }
        val nonce = HexFormat.of().formatHex(ByteArray(12).also { random.nextBytes(it) })
        val (spec, outputs) = runSpec(problem, prepared, test, baseline.binary, runDir, outDir, nonce, config.maxInstructions)
        var line: String? = null
        val res = Proc.awaitLines(sandbox.start(spec), spec.command, config.simulationTimeLimit) { if (it.startsWith("$nonce ")) line = it.removePrefix("$nonce ") }
        outputs.values.forEach { Files.deleteIfExists(it) }
        val counts = line?.let(RiscvCounts::parse)
        if (!res.ok || counts == null) throw JudgeInfrastructureException("the baseline run failed: ${res.describe()}")
        Files.createDirectories(baselineDir)
        file.writeText(line!!)
        return counts
    }

    private fun runSpec(
        problem: ProblemSpec, prepared: PreparedProblem, test: PreparedTest, binary: Path, runDir: Path, outDir: Path, nonce: String,
        maxInstructions: Long,
    ): Pair<SandboxSpec, Map<String, Path>> {
        val sig = problem.signature
        val dataBytes = sig.arrays.sumOf { test.counts.getValue(it.name) * it.type.bytes }
        val maxOutput = sig.outputs.maxOfOrNull { test.counts.getValue(it.name) * it.type.bytes } ?: 0
        val qemuHome = config.qemu.parent.parent
        val spec = SandboxSpec(
            command = emptyList(),
            // only outDir is writable for the guest: the manifest stays out of its reach
            workDir = runDir,
            readOnly = mapOf(
                binary.parent to BINARY_MOUNT, prepared.inputsDir to DATA_MOUNT,
                qemuHome to qemuHome.toString(), config.plugin.parent to config.plugin.parent.toString(),
            ),
            wallTimeLimit = config.simulationTimeLimit.plusSeconds(5),
            // QEMU reserves address space for the guest up front, so only the resident memory is limited
            memoryLimitBytes = (config.simulationMemoryLimitMb shl 20) + 2 * dataBytes,
            maxFileBytes = maxOutput + (1L shl 20),
        )
        val outputs = sig.outputs.associate { it.name to outDir.resolve("${it.name}.bin") }
        val manifest = Files.createDirectories(runDir.resolve("manifests")).resolve("${outDir.fileName}.txt")
        manifest.writeText(manifest(problem, test, outputs) { sandbox.inside(spec, it) })
        val plugin = listOf(
            config.plugin.toString(), "fd=1", "nonce=$nonce", "limit=$maxInstructions",
            "allow=${sandbox.inside(spec, prepared.inputsDir)}", "allow=${sandbox.inside(spec, runDir)}",
        ).joinToString(",")
        // QEMU reports a guest killed by a signal by sending the signal to itself. nsjail starts the command as PID 1 of
        // its own namespace, and PID 1 ignores such signals: QEMU would hang. Under a shell it is an ordinary child;
        // the shell's exit code is then 128 + the signal. (`; exit` keeps the shell from exec-ing QEMU in its place.)
        val command = listOf(
            "/bin/sh", "-c", "\"$@\"; exit $?", "sh",
            config.qemu.toString(), "-R", GUEST_SPACE, "-cpu", "rv64,v=true,vlen=${config.vlen},elen=64", "-plugin", plugin,
            sandbox.inside(spec, binary), sandbox.inside(spec, manifest),
        )
        return spec.copy(command = command) to outputs
    }

    private fun manifest(problem: ProblemSpec, test: PreparedTest, outputs: Map<String, Path>, inside: (Path) -> String): String = buildString {
        for (p in problem.signature.scalars) appendLine("scalar ${p.name} ${scalarBits(p.type, test.spec.scalars.getValue(p.name))}")
        for (p in problem.signature.arrays) {
            appendLine("array ${p.name} ${test.counts.getValue(p.name)} ${test.inputs[p.name]?.let(inside) ?: "-"} ${outputs[p.name]?.let(inside) ?: "-"}")
        }
    }

    companion object {
        private const val BINARY_MOUNT = "/sim"
        private const val DATA_MOUNT = "/data"
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

        /**
         * Instructions a measured test may run beyond the harness: its weighted score is at least its instructions, so
         * past the limit the score is too; at least one, at most [cap].
         */
        fun instructionLimit(limit: Double, cap: Long): Long = limit.toLong().coerceIn(1, cap)


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
