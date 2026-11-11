package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorRun
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.config.PathsConfig
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.pipeline.Disposition
import dev.gpucodeforces.judge.executor.pipeline.JudgePipeline
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.ProcessorBackend
import dev.gpucodeforces.judge.executor.processor.ProcessorUnavailableException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class JudgePipelineTest {
    @TempDir
    lateinit var tmp: Path

    private val problem = ProblemSpec(
        id = "copy",
        signature = Signature(
            params = listOf(
                ParamSpec("A", DType.INT32, ParamRole.INPUT, MemorySpace.HOST, "N"),
                ParamSpec("C", DType.INT32, ParamRole.OUTPUT, MemorySpace.HOST, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        ),
        generator = GeneratorSpec(source = "def tests(): ..."),
        processors = listOf(ProcessorSpec(Processors.RTL, listOf("systemverilog"), limit = 1e6)),
    )
    private val publisher = mock<EventPublisher>()

    /** A processor whose judge() does what the test says and remembers the run directory it got. */
    private class FakeBackend(val outcome: (Path) -> JudgeResult) : ProcessorBackend {
        var runDir: Path? = null
        override val info = Processors.get(Processors.RTL)
        override val version = "fake 1.0"
        override val model = "Fake circuit"
        override val capacity = 1
        override fun verify() {}
        override fun status() = ProcessorStatus(info.id, version, model, healthy = true, busy = false, capacity = 1)
        override fun judge(job: JudgeJob, runDir: Path, hooks: JudgeHooks): JudgeResult {
            this.runDir = runDir
            hooks.progress(1, 1)
            return outcome(runDir)
        }
    }

    private fun process(backend: FakeBackend, processor: String = Processors.RTL, language: String = "systemverilog"): Pair<Disposition, JudgeResult?> {
        val props = ExecutorProperties(id = "exec-1", processors = listOf(Processors.RTL), paths = PathsConfig(work = tmp.resolve("work"), failed = tmp.resolve("failed")))
        val pipeline = JudgePipeline(props, listOf(backend), publisher, CancelRegistry(Clock.systemUTC()), SimpleMeterRegistry(), Clock.systemUTC())
        val job = JudgeJob("s-1", problem, "module solve; endmodule", processor, language, Instant.now(), "job-1")
        val disposition = pipeline.process(job, 1)
        val events = argumentCaptor<JudgeEvent>()
        verify(publisher, atLeastOnce()).publish(events.capture(), any())
        return disposition to events.allValues.lastOrNull { it.status == SubmissionStatus.FINISHED }?.result
    }

    @Test
    fun `a job is judged by the backend of its processor, which the result names`() {
        val backend = FakeBackend { JudgeResult(Verdict.ACCEPTED, score = Score(42.0, ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS)) }
        val (disposition, result) = process(backend)
        assertEquals(Disposition.ACK, disposition)
        assertEquals(Score(42.0, ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS), result!!.score)
        assertEquals(ProcessorRun(Processors.RTL, "fake 1.0", "Fake circuit", "exec-1", "dev"), result.processor)
        assertTrue(!backend.runDir!!.exists(), "the run directory is removed after the verdict")
    }

    @Test
    fun `a processor this executor does not serve is an invalid job`() {
        val backend = FakeBackend { error("must not be called") }
        val (_, result) = process(backend, Processors.GPU_CUDA, "cuda")
        assertEquals("Invalid judge job", result!!.message)
        assertNull(result.processor)
        assertNull(backend.runDir)
    }

    @Test
    fun `infrastructure failures are retried and keep the run directory, an unavailable processor gives the job back`() {
        val broken = FakeBackend { dir -> Files.writeString(dir.resolve("log.txt"), "simulator crashed"); throw JudgeInfrastructureException("simulator crashed") }
        assertEquals(Disposition.REQUEUE, process(broken).first)
        assertTrue(Files.exists(tmp.resolve("failed").resolve(broken.runDir!!.fileName).resolve("log.txt")))

        assertEquals(Disposition.REQUEUE, process(FakeBackend { throw ProcessorUnavailableException("no worker") }).first)
        verify(publisher, never()).publish(any(), eq(true))
    }
}
