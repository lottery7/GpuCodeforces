package dev.gpucodeforces.judge.gateway

import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import dev.gpucodeforces.judge.gateway.messaging.JudgeMessaging
import dev.gpucodeforces.judge.gateway.service.ExecutorRegistry
import dev.gpucodeforces.judge.gateway.service.SubmissionService
import dev.gpucodeforces.judge.gateway.web.ErrorHandler
import dev.gpucodeforces.judge.gateway.web.SubmissionController
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SubmissionControllerTest {
    private val messaging = mock<JudgeMessaging>()
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)
    private val properties = GatewayProperties(processors = listOf("gpu-cuda", "rtl"))
    private val service = SubmissionService(properties, messaging, mock<ExecutorRegistry>(), SimpleMeterRegistry(), clock)
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(SubmissionController(service))
        .setControllerAdvice(ErrorHandler())
        .build()

    @Language("JSON")
    private val body = """
        {
          "submissionId": "s-1",
          "source": "__global__ void k(){}\nvoid solve(const float* A, float* C, int N) {}",
          "processor": "gpu-cuda",
          "language": "cuda",
          "problem": {
            "id": "copy",
            "signature": {"params": [
              {"name": "A", "type": "FLOAT32", "role": "INPUT", "memory": "DEVICE", "size": "N"},
              {"name": "C", "type": "FLOAT32", "role": "OUTPUT", "memory": "DEVICE", "size": "N"},
              {"name": "N", "type": "INT32", "role": "SCALAR"}
            ]},
            "generator": {"source": "def tests(): ..."},
            "processors": [
              {"id": "gpu-cuda", "languages": ["cuda"]},
              {"id": "rtl", "languages": ["systemverilog"], "limit": 100000}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `accepts valid submission, publishes job and QUEUED event, dedups repeats`() {
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isAccepted() }; jsonPath("$.processor") { value("gpu-cuda") } }

        val job = argumentCaptor<JudgeJob>()
        verify(messaging).publishJob(job.capture(), any())
        assertEquals("s-1", job.firstValue.submissionId)
        assertEquals("def tests(): ...", job.firstValue.problem.generator.source)
        val event = argumentCaptor<JudgeEvent>()
        verify(messaging).publishEvent(event.capture())
        assertEquals(SubmissionStatus.QUEUED, event.firstValue.status)

        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isOk() }; jsonPath("$.duplicate") { value(true) } }
    }

    @Test
    fun `a job goes to the queue of the requested processor only`() {
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = rtl }
            .andExpect { status { isAccepted() }; jsonPath("$.processor") { value("rtl") }; jsonPath("$.language") { value("systemverilog") } }
        val job = argumentCaptor<JudgeJob>()
        verify(messaging).publishJob(job.capture(), any())
        assertEquals("rtl", job.firstValue.processor)
        assertEquals("systemverilog", job.firstValue.language)

        // the same id for another processor is not a duplicate of that job, and must not be reported as queued there
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isBadRequest() } }
        verify(messaging).publishJob(any(), any())
    }

    @Test
    fun `rejects invalid submission with details`() {
        val bad = body.replace("\"FLOAT32\", \"role\": \"OUTPUT\"", "\"FLOAT32\", \"role\": \"INPUT\"")
            .replace("\"processor\": \"gpu-cuda\"", "\"processor\": \"coral-npu\"")
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = bad }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("validation_failed") }
                // not accepted by this gateway, not declared by the problem, no output param
                jsonPath("$.details.length()") { value(3) }
            }
        verify(messaging, never()).publishJob(any(), any())
    }

    @Test
    fun `a language the problem does not allow on the processor is rejected`() {
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = rtl.replace("\"systemverilog\",", "\"cuda\",") }
            .andExpect { status { isBadRequest() }; jsonPath("$.details[0]") { value("language 'cuda' is not allowed on rtl, allowed: systemverilog") } }
    }

    @Test
    fun `a submission must name its processor`() {
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = body.replace("\"processor\": \"gpu-cuda\",", "") }
            .andExpect { status { isBadRequest() }; jsonPath("$.error") { value("malformed_request") } }
    }

    private val rtl = body.replace("\"processor\": \"gpu-cuda\",\n  \"language\": \"cuda\",", "\"processor\": \"rtl\",\n  \"language\": \"systemverilog\",")
}
