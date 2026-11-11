package dev.gpucodeforces.judge.executor.processor

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory

/** Where the sandbox of a simulator shows what was built from the submission, and the inputs of the tests. */
const val SIMULATOR_MOUNT = "/sim"
const val DATA_MOUNT = "/data"

/** What an emulated processor made of a submission. */
sealed interface EmulatorBuild<out B> {
    val compileLog: String

    /** [artifact] is what [EmulatorBackend.launch] simulates. */
    data class Ready<B>(val artifact: B, override val compileLog: String) : EmulatorBuild<B>

    /** The submission's own fault: [verdict] is COMPILATION_ERROR or POLICY_VIOLATION. */
    data class Rejected(val verdict: Verdict, val message: String, override val compileLog: String = "") : EmulatorBuild<Nothing>
}

/** One test of a job as its processor launches it. Only [outDir] is writable for the simulator. */
class TestRun(
    val problem: ProblemSpec,
    val prepared: PreparedProblem,
    val test: PreparedTest,
    /** The problem's score limit on this processor. */
    val limit: Double,
    val runDir: Path,
    val outDir: Path,
    val nonce: String,
) {
    private val signature get() = problem.signature

    /** OUTPUT/INOUT param name -> host path of the file the run writes. */
    val outputs: Map<String, Path> = signature.outputs.associate { it.name to outDir.resolve("${it.name}.bin") }

    /** Bytes of all arrays of the test, as stored in the files. */
    val dataBytes: Long get() = signature.arrays.sumOf { bytes(it.name, it.type.bytes) }

    val maxOutputBytes: Long get() = signature.outputs.maxOfOrNull { bytes(it.name, it.type.bytes) } ?: 0

    private fun bytes(param: String, elementBytes: Int): Long = test.counts.getValue(param) * elementBytes

    /**
     * The `scalar <name> <bits>` and `array <name> <count> <input> <output>` lines every harness and testbench reads;
     * [inside] maps a host path into the sandbox, `-` stands for no file.
     */
    fun manifest(inside: (Path) -> String): String = buildString {
        for (p in signature.scalars) appendLine("scalar ${p.name} ${scalarBits(p.type, test.spec.scalars.getValue(p.name))}")
        for (p in signature.arrays) {
            val input = test.inputs[p.name]?.let(inside) ?: "-"
            val output = outputs[p.name]?.let(inside) ?: "-"
            appendLine("array ${p.name} ${test.counts.getValue(p.name)} $input $output")
        }
    }
}

/**
 * Units of a simulator (cycles, instructions) a measured test may run: the most whose score (units x [perUnit]) stays
 * within [limit], at least one (so a submission too expensive for any unit fails on the first) and at most [cap].
 */
fun unitLimit(limit: Double, cap: Long, perUnit: Long = 1): Long =
    if (perUnit <= 0) cap else (limit / perUnit).toLong().coerceIn(1, cap)

/**
 * What the emulated processors share around [EmulatorRun]: a pool of workers, and the order of a job - build, the
 * problem's tests, a worker, the tests one by one. A processor says how to [build] a submission and how to [launch]
 * one test of it; [B] is what it builds.
 */
abstract class EmulatorBackend<B>(
    processor: String,
    protected val props: ExecutorProperties,
    private val problems: ProblemStore,
    protected val sandbox: Sandbox,
    /** Host time one simulator run may take. */
    private val hostTimeLimit: Duration,
) : ProcessorBackend {
    protected val log = LoggerFactory.getLogger(javaClass)
    private val pool = SlotPool.workers(props.emulator(processor).workers)

    final override val info: ProcessorInfo = Processors.get(processor)
    final override val capacity: Int get() = props.capacityOf(info.id)

    /** The toolchain was probed when the executor created this backend. */
    final override fun verify() {}

    final override fun status() = ProcessorStatus(
        id = info.id, version = version, model = model, healthy = true,
        busy = pool.slots.none { it.busyWith == null }, capacity = capacity,
    )

    /** Builds the submission of [job]; [processor] is what the problem declares for this processor. */
    protected abstract fun build(job: JudgeJob, processor: ProcessorSpec): EmulatorBuild<B>

    /** How to simulate one test of the submission built into [artifact]. */
    protected abstract fun launch(run: TestRun, artifact: B): SimulatorLaunch

    final override fun judge(job: JudgeJob, runDir: Path, hooks: JudgeHooks): JudgeResult {
        val problem = job.problem
        // validated by the pipeline: the processor is declared, has a limit and allows the language
        val processor = problem.processorSpec(info.id)!!
        val limit = problem.scoreLimit(processor)!!
        val build = when (val built = build(job, processor)) {
            is EmulatorBuild.Rejected -> {
                log.info("{}: {}", built.verdict, built.message)
                return JudgeResult(built.verdict, message = built.message, compileLog = built.compileLog.ifBlank { null })
            }
            is EmulatorBuild.Ready -> built
        }
        if (hooks.cancelled()) return JudgeResult(Verdict.CANCELLED, message = "Cancelled")

        val prepared = problems.acquire(problem, info.id, onPreparing = { hooks.status(SubmissionStatus.PREPARING) }, cancelled = hooks::cancelled)
        try {
            val slot = pool.acquire(job.submissionId, NO_WORKER_TIMEOUT, hooks::cancelled)
            try {
                val outcome = EmulatorRun(sandbox).run(problem, prepared, info.scoreType, limit, runDir, hooks, hostTimeLimit) { test, outDir, nonce ->
                    launch(TestRun(problem, prepared, test, limit, runDir, outDir, nonce), build.artifact)
                }
                return JudgeResult(
                    verdict = outcome.verdict, failedTest = outcome.failedTest, message = outcome.message,
                    compileLog = build.compileLog.ifBlank { null }, tests = outcome.tests, score = outcome.score, metrics = outcome.metrics,
                )
            } finally {
                pool.release(slot)
            }
        } finally {
            problems.release(prepared.hash)
        }
    }

    private companion object {
        val NO_WORKER_TIMEOUT: Duration = Duration.ofMinutes(2)
    }
}
