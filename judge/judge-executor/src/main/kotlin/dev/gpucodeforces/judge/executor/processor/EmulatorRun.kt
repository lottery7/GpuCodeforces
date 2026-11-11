package dev.gpucodeforces.judge.executor.processor

import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.run.OutputChecker
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Proc
import dev.gpucodeforces.judge.executor.util.nextNonce
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import kotlin.io.path.deleteIfExists
import org.slf4j.LoggerFactory

/** How a simulator run of one test ended, as the processor reads it. */
data class SimulatorExit(
    val exitCode: Int,
    /** Lines of stdout that started with the run's nonce, without it. */
    val statusLines: List<String>,
    val stderr: String,
    /** The last lines of stdout [SimulatorLaunch.keep] asked for: what a simulator prints without the nonce. */
    val kept: List<String> = emptyList(),
)

sealed interface SimulatorOutcome {
    data class Finished(val score: Double, val metrics: Map<String, Double> = emptyMap()) : SimulatorOutcome

    /**
     * The contestant's code failed in the simulator. [message] may be shown on any test; [details] (what went
     * wrong, with values) only on sample tests.
     */
    data class Failed(val verdict: Verdict, val message: String, val details: String? = null) : SimulatorOutcome
}

/** What a processor answers for one test of a job. */
sealed interface TestLaunch

/** The test fails without a simulator run, e.g. because its data cannot be placed in the processor's memory. */
class Refused(val failure: SimulatorOutcome.Failed) : TestLaunch

/** How to simulate one test: the sandboxed command, where it leaves each output array, how to read its result. */
class SimulatorLaunch(
    val spec: SandboxSpec,
    /** OUTPUT/INOUT param name -> host path of the file the run writes. */
    val outputs: Map<String, Path>,
    /** Lines of stdout without the nonce to hand to [readOutcome] too (the last [MAX_KEPT_LINES] of them). */
    val keep: (String) -> Boolean = { false },
    /** May throw [JudgeInfrastructureException] when the simulator itself broke. */
    val readOutcome: (SimulatorExit) -> SimulatorOutcome,
) : TestLaunch

/** Kept lines of one run; a simulator prints its statistics once per kernel, the last ones are what counts. */
const val MAX_KEPT_LINES = 64

data class EmulatorOutcome(
    val verdict: Verdict,
    val tests: List<TestResult>,
    val failedTest: Int? = null,
    val message: String? = null,
    val score: Score? = null,
    val metrics: Map<String, Double> = emptyMap(),
)

/**
 * The test loop every emulated processor shares: one simulator process per test, in order, stopping at the first
 * failure. The host watchdog only guards the machine; the processor's own limit is on the score, which the
 * simulator reports through status lines prefixed with a fresh nonce per test. A test passes once its outputs match
 * the reference, and a measured one only if its score is within [limit].
 *
 * Memory is limited by the sandbox (see [SandboxSpec]); nothing here polls it.
 */
class EmulatorRun(private val sandbox: Sandbox) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    fun run(
        problem: ProblemSpec,
        prepared: PreparedProblem,
        scoreType: ScoreType,
        limit: Double,
        runDir: Path,
        hooks: JudgeHooks,
        /** Host time one simulator run may take. */
        hostTimeLimit: Duration,
        launch: (test: PreparedTest, outDir: Path, nonce: String) -> TestLaunch,
    ): EmulatorOutcome {
        val results = mutableListOf<TestResult>()
        val total = prepared.tests.size
        hooks.progress(0, total)
        for (test in prepared.tests) {
            if (hooks.cancelled()) return EmulatorOutcome(Verdict.CANCELLED, results, message = "Cancelled")
            hooks.progress(test.index, total)
            val outDir = Files.createDirectories(runDir.resolve("out").resolve("t${test.index}"))
            FileTrees.makeWritableByAll(outDir)
            val nonce = random.nextNonce()
            val result = when (val sim = launch(test, outDir, nonce)) {
                is Refused -> failed(test, sim.failure, stderrTail = "")
                is SimulatorLaunch -> try {
                    runTest(problem, test, sim, nonce, scoreType, limit, hostTimeLimit)
                } finally {
                    sim.outputs.values.forEach { it.deleteIfExists() }
                }
            }
            results += result
            if (result.verdict != Verdict.ACCEPTED) {
                return EmulatorOutcome(result.verdict, results, failedTest = test.index, message = "${failureTitle(result.verdict, scoreType)} on test ${test.index}")
            }
        }
        val best = results.filter { it.measured }.maxByOrNull { it.score!!.value }
        return EmulatorOutcome(Verdict.ACCEPTED, results, score = best?.score, metrics = best?.metrics.orEmpty())
    }

    private fun result(test: PreparedTest, verdict: Verdict, message: String? = null, debug: String? = null) =
        TestResult(test.index, test.spec.name, test.spec.sample, verdict, measured = test.spec.measure, message = message, debug = debug)

    /** A failure of the contestant's code: what went wrong is shown on sample tests only, and logged for the hidden ones. */
    private fun failed(test: PreparedTest, failure: SimulatorOutcome.Failed, stderrTail: String): TestResult {
        val details = listOfNotNull(failure.details, stderrTail.ifEmpty { null }?.let { "stderr:\n$it" }).joinToString("\n")
        if (!test.spec.sample && details.isNotEmpty()) log.info("Hidden test {} '{}': {}", test.index, test.spec.name, details.take(4000))
        return result(test, failure.verdict, failure.message, debug = details.takeIf { test.spec.sample && it.isNotEmpty() })
    }

    private fun runTest(
        problem: ProblemSpec, test: PreparedTest, sim: SimulatorLaunch, nonce: String, scoreType: ScoreType, limit: Double, hostTimeLimit: Duration,
    ): TestResult {

        val prefix = "$nonce "
        val status = mutableListOf<String>()
        val kept = ArrayDeque<String>()
        val process = sandbox.start(sim.spec)
        // one reader thread fills them, and awaitLines joins that thread before it returns
        val res = Proc.awaitLines(process, sim.spec.command, hostTimeLimit) { line ->
            // a simulator reports a few lines; anything beyond that is the contestant's doing
            if (line.startsWith(prefix)) {
                if (status.size < MAX_STATUS_LINES) status += line.removePrefix(prefix)
            } else if (sim.keep(line)) {
                kept.addLast(line)
                if (kept.size > MAX_KEPT_LINES) kept.removeFirst()
            }
        }
        if (res.timedOut) {
            log.info("Test {}: simulator exceeded {} s of host time", test.index, hostTimeLimit.seconds)
            return result(test, Verdict.TIME_LIMIT_EXCEEDED, "${failureTitle(Verdict.TIME_LIMIT_EXCEEDED, scoreType)} (the simulation ran out of host time)")
        }
        val outcome = sim.readOutcome(SimulatorExit(res.exitCode, status, res.stderr, kept.toList()))
        val stderrTail = res.stderr.trim().takeLast(2000)
        return when (outcome) {
            is SimulatorOutcome.Failed -> failed(test, outcome, stderrTail)
            is SimulatorOutcome.Finished -> {
                val score = Score(outcome.score, scoreType)
                val wall = res.durationMs.toDouble()
                val passed = TestResult(
                    test.index, test.spec.name, test.spec.sample, Verdict.ACCEPTED, score = score, measured = test.spec.measure,
                    metrics = outcome.metrics, wallTimeMs = wall, runs = 1,
                )
                val mismatch = problem.signature.outputs.firstNotNullOfOrNull { p ->
                    OutputChecker.check(p.name, p.type, actual = sim.outputs.getValue(p.name), expected = test.expected.getValue(p.name), problem.checker)
                        .takeIf { !it.ok }
                }
                when {
                    mismatch != null -> {
                        val details = "${mismatch.summary}; ${mismatch.detail}"
                        if (!test.spec.sample) log.info("Hidden test {} '{}': {}", test.index, test.spec.name, details.take(4000))
                        passed.copy(verdict = Verdict.WRONG_ANSWER, message = if (test.spec.sample) details else "Wrong answer")
                    }
                    test.spec.measure && outcome.score > limit ->
                        passed.copy(verdict = Verdict.TIME_LIMIT_EXCEEDED, message = "${failureTitle(Verdict.TIME_LIMIT_EXCEEDED, scoreType)} (${format(outcome.score)} > ${format(limit)})")
                    else -> passed
                }
            }
        }
    }

    private fun format(v: Double): String = if (v == Math.rint(v) && v < 1e15) v.toLong().toString() else "%.3f".format(v)

    private companion object {
        const val MAX_STATUS_LINES = 64
    }
}

/** What a failed test is called: an exceeded limit names the processor's unit. */
fun failureTitle(verdict: Verdict, scoreType: ScoreType): String = when (verdict) {
    Verdict.TIME_LIMIT_EXCEEDED -> when (scoreType) {
        ScoreType.TIME_MS -> "Time limit exceeded"
        ScoreType.CYCLES -> "Cycle limit exceeded"
        ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS -> "Cycles x depth x transistors limit exceeded"
        ScoreType.WEIGHTED_INSTRUCTIONS, ScoreType.INSTRUCTIONS -> "Instruction limit exceeded"
    }
    Verdict.WRONG_ANSWER -> "Wrong answer"
    Verdict.MEMORY_LIMIT_EXCEEDED -> "Memory limit exceeded"
    else -> "Runtime error"
}

/** The score of a submission: the maximum over its measured tests. */
fun List<TestResult>.maxMeasuredScore(): Score? =
    filter { it.measured && it.verdict == Verdict.ACCEPTED }.mapNotNull { it.score }.maxByOrNull { it.value }
