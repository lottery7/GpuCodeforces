package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.CustomTest
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.mock.MockDirective
import dev.gpucodeforces.judge.executor.mock.MockJudge
import dev.gpucodeforces.judge.executor.mock.MockResults
import dev.gpucodeforces.judge.executor.pipeline.Disposition
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.intellij.lang.annotations.Language
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class MockJudgeTest {
    private val problem = ProblemSpec(
        id = "copy",
        signature = Signature(
            params = listOf(
                ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("C", DType.FLOAT32, ParamRole.OUTPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        ),
        generator = GeneratorSpec(source = "def reference(test, inputs): ..."),
        processors = listOf(
            ProcessorSpec(Processors.GPU_CUDA, listOf("cuda")),
            ProcessorSpec(Processors.RTL, listOf("systemverilog"), limit = 1e6),
        ),
    )
    private val publisher = mock<EventPublisher>()

    @Test
    fun `mock can accept a solution initially and fail it on a hack`() {
        val directive = MockDirective.parse("// judge-mock: custom-verdict=WRONG_ANSWER")
        val gpu = Processors.get(Processors.GPU_CUDA)
        assertEquals(Verdict.ACCEPTED, MockResults.of(problem, gpu, directive.forProblem(problem)).verdict)
        val expanded = problem.copy(allowCustomTests = true, customTests = listOf(CustomTest(mapOf("N" to 1), mapOf("A" to listOf(1)))))
        val result = MockResults.of(expanded, gpu, directive.forProblem(expanded))
        assertEquals(Verdict.WRONG_ANSWER, result.verdict)
        assertEquals(4, result.failedTest)
        val candidate = expanded.copy(customOnly = true)
        assertEquals(1, MockResults.of(candidate, gpu, directive.forProblem(candidate)).failedTest)
    }
    private val clock = Clock.systemUTC()
    private val props = ExecutorProperties(id = "mock-1", processors = listOf(Processors.GPU_CUDA, Processors.RTL))
    private val judge = MockJudge(props, publisher, CancelRegistry(clock), clock)

    /** Judges [source] and returns the events a backend would receive from the executor. */
    private fun judge(
        @Language("C++") source: String,
        processor: String = Processors.GPU_CUDA,
        language: String = "cuda",
        judge: MockJudge = this.judge,
    ): List<JudgeEvent> {
        val job = JudgeJob("s-1", problem, source = source, processor = processor, language = language, enqueuedAt = Instant.now(), jobId = "job-1")
        assertEquals(Disposition.ACK, judge.process(job, 1))
        val events = argumentCaptor<JudgeEvent>()
        verify(publisher, atLeastOnce()).publish(events.capture(), any())
        return events.allValues
    }

    @Test
    fun `a submission without a directive is accepted after the usual sequence of events`() {
        val events = judge("void solve(const float* A, float* C, int N) {}")
        // progress events between the first and the last test are throttled, so only the order of statuses is fixed
        val statuses = events.map { it.status }
        assertEquals(listOf(SubmissionStatus.COMPILING, SubmissionStatus.TESTING, SubmissionStatus.FINISHED), statuses.distinct())
        assertEquals(statuses.sorted(), statuses)
        assertEquals(3, events.last { it.status == SubmissionStatus.TESTING }.progress!!.currentTest)
        val result = events.last().result!!
        assertEquals(Verdict.ACCEPTED, result.verdict)
        assertEquals(listOf("sample", "test-2", "test-3"), result.tests.map { it.name })
        assertEquals(listOf(true, false, false), result.tests.map { it.sample })
        assertEquals((1..events.size).toList(), events.map { it.seq })
        assertEquals("mock-1", result.gpu!!.executorId)
        assertEquals("mock-1", result.processor!!.executorId)
        assertEquals(Score(1.0, ScoreType.TIME_MS), result.score)
        assertEquals(setOf("job-1"), events.map { it.jobId }.toSet())
    }

    @Test
    fun `the directive chooses the verdict, the failing test and the reported score`() {
        val events = judge("// judge-mock: verdict=WRONG_ANSWER test=2 time-ms=3.5 tests=5\nvoid solve() {}")
        assertEquals(5, events.first { it.status == SubmissionStatus.TESTING }.progress!!.totalTests)
        val result = events.last().result!!
        assertEquals(Verdict.WRONG_ANSWER, result.verdict)
        assertEquals(2, result.failedTest)
        assertEquals(listOf(Verdict.ACCEPTED, Verdict.WRONG_ANSWER), result.tests.map { it.verdict })
        assertEquals(Score(3.5, ScoreType.TIME_MS), result.tests.first().score)
        assertNull(result.score, "only an accepted submission has a score")
    }

    @Test
    fun `an emulated processor reports a score of its own type and no GPU`() {
        val result = judge("// judge-mock: score=4096\nmodule solve; endmodule", Processors.RTL, "systemverilog").last().result!!
        assertEquals(Verdict.ACCEPTED, result.verdict)
        assertEquals(Score(4096.0, ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS), result.score)
        assertEquals(Processors.RTL, result.processor!!.id)
        assertNull(result.gpu)
        assertNull(result.timingMode)
    }

    @Test
    fun `a compilation error finishes before any test`() {
        val events = judge("// judge-mock: verdict=COMPILATION_ERROR\nvoid solve() {}")
        assertEquals(listOf(SubmissionStatus.COMPILING, SubmissionStatus.FINISHED), events.map { it.status })
        val result = events.last().result!!
        assertEquals(Verdict.COMPILATION_ERROR, result.verdict)
        assertEquals(emptyList(), result.tests)
        assertNull(result.gpu)
    }

    @Test
    fun `a job for a processor this executor does not serve, or in the wrong language, is rejected`() {
        val gpuOnly = MockJudge(ExecutorProperties(id = "mock-2"), publisher, CancelRegistry(clock), clock)
        val result = judge("module solve; endmodule", Processors.RTL, "systemverilog", gpuOnly).last().result!!
        assertEquals(Verdict.INTERNAL_ERROR, result.verdict)
        assertEquals("Invalid judge job", result.message)
        assertNull(result.processor)
        assertEquals("Invalid judge job", judge("void solve() {}", language = "systemverilog").last().result!!.message)
    }

    @Test
    fun `a misspelt directive is an error, not an accepted submission`() {
        assertFailsWith<IllegalArgumentException> { MockDirective.parse("// judge-mock: verdict=WRONG") }
        assertFailsWith<IllegalArgumentException> { MockDirective.parse("// judge-mock: verdit=WRONG_ANSWER") }
        assertFailsWith<IllegalArgumentException> { MockDirective.parse("// judge-mock: tests=0") }
        assertFailsWith<IllegalArgumentException> { MockDirective.parse("// judge-mock: score=1 time-ms=2") }
        assertEquals(Verdict.INTERNAL_ERROR, judge("// judge-mock: verdict=WRONG\nvoid solve() {}").last().result!!.verdict)
    }
}
