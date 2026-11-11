package dev.gpucodeforces.judge.executor.mock

import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.ProcessorRun
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.Score
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
 *     // judge-mock: verdict=WRONG_ANSWER test=2 score=3.5 tests=5
 *
 * Every key is optional; without the comment the submission is accepted. The mock does not run the problem's
 * generator, so the number of tests comes from the directive too: test 1 is a sample, the rest are hidden.
 * `score` is in the units of the submission's processor; `time-ms` is its older name.
 */
data class MockDirective(
    val verdict: Verdict = Verdict.ACCEPTED,
    /** 1-based test the verdict is reported on (for verdicts that concern a test). */
    val test: Int = 1,
    /** Score reported for every accepted test. */
    val score: Double = 1.0,
    /** Number of tests the problem is pretended to have. */
    val tests: Int = 3,
) {
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
            val unknown = values.keys - setOf("verdict", "test", "score", "time-ms", "tests")
            require(unknown.isEmpty()) { "judge-mock: unknown keys $unknown" }
            require("score" !in values || "time-ms" !in values) { "judge-mock: score and time-ms are the same key, give one" }
            return MockDirective(
                verdict = values["verdict"]?.let { name ->
                    Verdict.entries.find { it.name == name } ?: throw IllegalArgumentException("judge-mock: unknown verdict '$name'")
                } ?: Verdict.ACCEPTED,
                test = values["test"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("judge-mock: test must be a number") } ?: 1,
                score = (values["score"] ?: values["time-ms"])
                    ?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("judge-mock: score must be a number") } ?: 1.0,
                tests = values["tests"]?.let { v ->
                    v.toIntOrNull()?.takeIf { it in 1..MAX_TESTS } ?: throw IllegalArgumentException("judge-mock: tests must be in 1..$MAX_TESTS")
                } ?: 3,
            )
        }
    }
}

/** Builds the result a real judge would report for the outcome a [MockDirective] asks for. */
object MockResults {
    /** Verdicts reached before any test runs. */
    private val WITHOUT_TESTS = setOf(Verdict.COMPILATION_ERROR, Verdict.POLICY_VIOLATION, Verdict.INTERNAL_ERROR, Verdict.CANCELLED)

    fun concernsATest(verdict: Verdict): Boolean = verdict !in WITHOUT_TESTS

    fun of(problem: ProblemSpec, processor: ProcessorInfo, directive: MockDirective): JudgeResult = when (directive.verdict) {
        Verdict.COMPILATION_ERROR ->
            JudgeResult(Verdict.COMPILATION_ERROR, message = "Compilation failed", compileLog = "solution.cu(1): error: mock compilation error")
        Verdict.POLICY_VIOLATION -> JudgeResult(Verdict.POLICY_VIOLATION, message = "line 1: header <mock/forbidden.h> is not allowed")
        Verdict.INTERNAL_ERROR -> JudgeResult(Verdict.INTERNAL_ERROR, message = "Judging failed")
        Verdict.CANCELLED -> JudgeResult(Verdict.CANCELLED, message = "Cancelled")
        Verdict.ACCEPTED -> tested(problem, processor, directive, failedTest = null)
        else -> tested(problem, processor, directive, failedTest = directive.test.coerceIn(1, directive.tests))
    }

    private fun tested(problem: ProblemSpec, processor: ProcessorInfo, directive: MockDirective, failedTest: Int?): JudgeResult {
        val last = failedTest ?: directive.tests
        val tests = (1..last).map { index ->
            val name = if (index == 1) "sample" else "test-$index"
            val sample = index == 1
            if (index == failedTest) {
                TestResult(index, name, sample, directive.verdict, message = failureMessage(directive.verdict, index))
            } else {
                TestResult(index, name, sample, Verdict.ACCEPTED, score = Score(directive.score, processor.scoreType), wallTimeMs = 1.0, runs = 1)
            }
        }
        return JudgeResult(
            verdict = directive.verdict,
            failedTest = failedTest,
            message = failedTest?.let { failureMessage(directive.verdict, it) },
            tests = tests,
            score = if (directive.verdict == Verdict.ACCEPTED) Score(directive.score, processor.scoreType) else null,
            timingMode = problem.timing.effectiveMode.takeIf { processor.id == Processors.GPU_CUDA },
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

    override fun processors(): List<ProcessorStatus> = props.processors.map {
        ProcessorStatus(it, version = TOOLCHAIN, model = modelOf(it), healthy = true, busy = false, capacity = props.capacityOf(it))
    }

    override fun gpus(): List<GpuSlotStatus> =
        if (Processors.GPU_CUDA in props.processors) props.gpus.map { GpuSlotStatus(it.index, GPU_NAME, healthy = true, busy = false) } else emptyList()

    override fun process(job: JudgeJob, attempt: Int): Disposition {
        inFlight.incrementAndGet()
        try {
            val started = System.nanoTime()
            val reporter = JobReporter(job.submissionId, job.jobId, attempt, source = props.id, publisher, clock)
            val result = judge(job, reporter)
            val served = job.processor in props.processors
            val gpu = served && job.processor == Processors.GPU_CUDA && result.tests.isNotEmpty()
            reporter.finish(
                result.copy(
                    judgeTimeMs = (System.nanoTime() - started) / 1_000_000,
                    processor = if (served) ProcessorRun(job.processor, version = TOOLCHAIN, model = modelOf(job.processor), executorId = props.id, executorVersion = javaClass.`package`?.implementationVersion ?: "dev") else null,
                    gpu = if (gpu) GpuInfo(props.gpuProfile, name = GPU_NAME, index = 0, executorId = props.id, clocksLocked = false) else null,
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
            SubmissionRequest(job.submissionId, job.problem, source = job.source, processor = job.processor, language = job.language),
        )
        if (errors.isNotEmpty() || job.processor !in props.processors) {
            log.error("Invalid job rejected: processor={} served={} errors={}", job.processor, props.processors, errors)
            return JudgeResult(Verdict.INTERNAL_ERROR, message = "Invalid judge job")
        }
        val directive = try {
            MockDirective.parse(job.source)
        } catch (e: IllegalArgumentException) {
            log.warn("Bad mock directive: {}", e.message)
            return JudgeResult(Verdict.INTERNAL_ERROR, message = e.message)
        }
        reporter.status(SubmissionStatus.COMPILING)
        val result = MockResults.of(job.problem, Processors.get(job.processor), directive)
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

    private fun modelOf(processor: String) = if (processor == Processors.GPU_CUDA) GPU_NAME else "Mock ${Processors.get(processor).title}"

    companion object {
        const val GPU_NAME = "Mock GPU"
        const val TOOLCHAIN = "mock (no compiler, no GPU, no emulator)"
    }
}
