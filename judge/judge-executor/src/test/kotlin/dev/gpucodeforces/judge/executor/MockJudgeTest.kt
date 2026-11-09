package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.TestSpec
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.mock.MockDirective
import dev.gpucodeforces.judge.executor.mock.MockJudge
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
        tests = listOf("one", "two", "three").map { TestSpec(it, mapOf("N" to 4)) },
        generator = GeneratorSpec(source = "def reference(test, inputs): ..."),
    )
    private val publisher = mock<EventPublisher>()
    private val clock = Clock.systemUTC()
    private val judge = MockJudge(ExecutorProperties(id = "mock-1"), publisher, CancelRegistry(clock), clock)

    /** Judges [source] and returns the events a backend would receive from the executor. */
    private fun judge(@Language("C++") source: String, gpuProfile: String = "rtx3060"): List<JudgeEvent> {
        val job = JudgeJob("s-1", problem, source = source, gpuProfile = gpuProfile, enqueuedAt = Instant.now())
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
        assertEquals(listOf("one", "two", "three"), result.tests.map { it.name })
        assertEquals((1..events.size).toList(), events.map { it.seq })
        assertEquals("mock-1", result.gpu!!.executorId)
    }

    @Test
    fun `the directive chooses the verdict, the failing test and the reported time`() {
        val result = judge("// judge-mock: verdict=WRONG_ANSWER test=2 time-ms=3.5\nvoid solve() {}").last().result!!
        assertEquals(Verdict.WRONG_ANSWER, result.verdict)
        assertEquals(2, result.failedTest)
        assertEquals(listOf(Verdict.ACCEPTED, Verdict.WRONG_ANSWER), result.tests.map { it.verdict })
        assertEquals(3.5, result.maxTimeMs)
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
    fun `a job for another GPU profile is rejected like on a real executor`() {
        val result = judge("void solve() {}", gpuProfile = "rtx4090").last().result!!
        assertEquals(Verdict.INTERNAL_ERROR, result.verdict)
        assertEquals("Invalid judge job", result.message)
    }

    @Test
    fun `a misspelt directive is an error, not an accepted submission`() {
        assertFailsWith<IllegalArgumentException> { MockDirective.parse("// judge-mock: verdict=WRONG") }
        assertFailsWith<IllegalArgumentException> { MockDirective.parse("// judge-mock: verdit=WRONG_ANSWER") }
        assertEquals(Verdict.INTERNAL_ERROR, judge("// judge-mock: verdict=WRONG\nvoid solve() {}").last().result!!.verdict)
    }
}
