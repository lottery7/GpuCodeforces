package dev.gpucodeforces.judge.executor.mock

import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.messaging.JobReporter
import dev.gpucodeforces.judge.executor.pipeline.Disposition
import dev.gpucodeforces.judge.executor.pipeline.JobProcessor
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * What a mock submission asks for, written in its source as a comment:
 *
 *     // judge-mock: verdict=WRONG_ANSWER test=2 time-ms=3.5 tests=5
 *
 * Every key is optional; without the comment the submission is accepted. The mock does not run the problem's
 * generator, so the number of tests comes from the directive too: test 1 is a sample, the rest are hidden.
 */
data class MockDirective(
    val verdict: Verdict = Verdict.ACCEPTED,
    /** 1-based test the verdict is reported on (for verdicts that concern a test). */
    val test: Int = 1,
    /** Time reported for every accepted test. */
    val timeMs: Double = 1.0,
    /** Number of tests the problem is pretended to have. */
    val tests: Int = 3,
    val customVerdict: Verdict? = null,
) {
    fun forProblem(problem: ProblemSpec): MockDirective = when {
        problem.customOnly -> copy(verdict = customVerdict ?: Verdict.ACCEPTED, test = 1, tests = 1)
        problem.customTests.isNotEmpty() && verdict == Verdict.ACCEPTED ->
            copy(verdict = customVerdict ?: Verdict.ACCEPTED, test = tests + 1, tests = tests + problem.customTests.size)
        else -> this
    }

    companion object {
        private val LINE = Regex("""judge-mock:(.*)""")
        private const val MAX_TESTS = 200

        /** Throws [IllegalArgumentException] for a directive that cannot be understood, so a typo is not an ACCEPTED. */
        fun parse(source: String): MockDirective {
            val text = LINE.find(source)?.groupValues?.get(1) ?: return MockDirective()
            val values = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.associate {
                require('=' in it) { "judge-mock: expected key=value, got '$it'" }
                it.substringBefore('=') to it.substringAfter('=')
            }
            val unknown = values.keys - setOf("verdict", "test", "time-ms", "tests", "custom-verdict")
            require(unknown.isEmpty()) { "judge-mock: unknown keys $unknown" }
            return MockDirective(
                verdict = values["verdict"]?.let { name ->
                    Verdict.entries.find { it.name == name } ?: throw IllegalArgumentException("judge-mock: unknown verdict '$name'")
                } ?: Verdict.ACCEPTED,
                test = values["test"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("judge-mock: test must be a number") } ?: 1,
                timeMs = values["time-ms"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("judge-mock: time-ms must be a number") } ?: 1.0,
                tests = values["tests"]?.let { v ->
                    v.toIntOrNull()?.takeIf { it in 1..MAX_TESTS } ?: throw IllegalArgumentException("judge-mock: tests must be in 1..$MAX_TESTS")
                } ?: 3,
                customVerdict = values["custom-verdict"]?.let { name ->
                    Verdict.entries.find { it.name == name } ?: throw IllegalArgumentException("judge-mock: unknown custom verdict '$name'")
                },
            )
        }
    }
}

/** Builds the result a real judge would report for the outcome a [MockDirective] asks for. */
object MockResults {
    /** Verdicts reached before any test runs. */
    private val WITHOUT_TESTS = setOf(Verdict.COMPILATION_ERROR, Verdict.POLICY_VIOLATION, Verdict.INTERNAL_ERROR, Verdict.CANCELLED)

    fun concernsATest(verdict: Verdict): Boolean = verdict !in WITHOUT_TESTS

    fun of(problem: ProblemSpec, directive: MockDirective): JudgeResult = when (directive.verdict) {
        Verdict.COMPILATION_ERROR ->
            JudgeResult(Verdict.COMPILATION_ERROR, message = "Compilation failed", compileLog = "solution.cu(1): error: mock compilation error")
        Verdict.POLICY_VIOLATION -> JudgeResult(Verdict.POLICY_VIOLATION, message = "line 1: header <mock/forbidden.h> is not allowed")
        Verdict.INTERNAL_ERROR -> JudgeResult(Verdict.INTERNAL_ERROR, message = "Judging failed")
        Verdict.CANCELLED -> JudgeResult(Verdict.CANCELLED, message = "Cancelled")
        Verdict.ACCEPTED -> tested(problem, directive, failedTest = null)
        else -> tested(problem, directive, failedTest = directive.test.coerceIn(1, directive.tests))
    }

    private fun tested(problem: ProblemSpec, directive: MockDirective, failedTest: Int?): JudgeResult {
        val last = failedTest ?: directive.tests
        val tests = (1..last).map { index ->
            val name = if (index == 1) "sample" else "test-$index"
            val sample = index == 1
            if (index == failedTest) {
                TestResult(index, name, sample, directive.verdict, message = failureMessage(directive.verdict, index))
            } else {
                TestResult(index, name, sample, Verdict.ACCEPTED, timeMs = directive.timeMs, wallTimeMs = directive.timeMs, runs = 1)
            }
        }
        return JudgeResult(
            verdict = directive.verdict,
            failedTest = failedTest,
            message = failedTest?.let { failureMessage(directive.verdict, it) },
            tests = tests,
            maxTimeMs = tests.mapNotNull { it.timeMs }.maxOrNull(),
            timingMode = problem.timing.effectiveMode,
        )
    }

    private fun failureMessage(verdict: Verdict, test: Int): String = when (verdict) {
        Verdict.WRONG_ANSWER -> "Wrong answer on test $test"
        Verdict.TIME_LIMIT_EXCEEDED -> "Time limit exceeded on test $test"
        Verdict.MEMORY_LIMIT_EXCEEDED -> "Memory limit exceeded on test $test"
        else -> "Runtime error on test $test"
    }
}

/**
 * Stands in for the whole judging pipeline in the `mock` profile: no compiler, sandbox or GPU. It consumes jobs
 * and publishes the same sequence of events as a real executor (COMPILING, TESTING k/n, FINISHED), with the
 * outcome taken from the [MockDirective] in the submission's source, so a backend can be tested end to end.
 */
class MockJudge(
    private val props: ExecutorProperties,
    private val publisher: EventPublisher,
    private val cancels: CancelRegistry,
    private val clock: Clock,
) : JobProcessor {
    private val log = LoggerFactory.getLogger(javaClass)
    override val inFlight = AtomicInteger()

    override fun process(job: JudgeJob, attempt: Int): Disposition {
        inFlight.incrementAndGet()
        try {
            val started = System.nanoTime()
            val reporter = JobReporter(job.submissionId, job.jobId, attempt, source = props.id, publisher, clock)
            val result = judge(job, reporter)
            reporter.finish(
                result.copy(
                    judgeTimeMs = (System.nanoTime() - started) / 1_000_000,
                    toolchain = TOOLCHAIN,
                    gpu = if (result.tests.isEmpty()) null else GpuInfo(props.gpuProfile, name = GPU_NAME, index = 0, executorId = props.id),
                ),
            )
            log.info("Mock verdict {} for problem {}", result.verdict, job.problem.id)
            return Disposition.ACK
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun judge(job: JudgeJob, reporter: JobReporter): JudgeResult {
        val cancelled = JudgeResult(Verdict.CANCELLED, message = "Cancelled")
        if (cancels.isCancelled(job.submissionId)) return cancelled
        // the same checks as the real pipeline: a backend must not pass here with a job a real executor rejects
        val errors = SpecValidator.validateSubmission(
            SubmissionRequest(job.submissionId, job.problem, source = job.source, gpuProfile = job.gpuProfile),
        )
        if (errors.isNotEmpty() || job.gpuProfile != props.gpuProfile) {
            log.error("Invalid job rejected: profile={} errors={}", job.gpuProfile, errors)
            return JudgeResult(Verdict.INTERNAL_ERROR, message = "Invalid judge job")
        }
        val directive = try {
                MockDirective.parse(job.source).forProblem(job.problem)
        } catch (e: IllegalArgumentException) {
            log.warn("Bad mock directive: {}", e.message)
            return JudgeResult(Verdict.INTERNAL_ERROR, message = e.message)
        }
        reporter.status(SubmissionStatus.COMPILING)
        val result = MockResults.of(job.problem, directive)
        val total = directive.tests
        if (MockResults.concernsATest(directive.verdict)) {
            reporter.progress(0, total)
            for (test in result.tests) {
                if (cancels.isCancelled(job.submissionId)) return cancelled
                reporter.progress(test.index, total)
                Thread.sleep(props.mock.testDelay.toMillis())
            }
        }
        return result
    }

    companion object {
        const val GPU_NAME = "Mock GPU"
        const val TOOLCHAIN = "mock (no compiler, no GPU)"
    }
}
