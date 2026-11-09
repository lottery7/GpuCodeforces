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
    private val service = SubmissionService(GatewayProperties(), messaging, SimpleMeterRegistry(), clock)
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(SubmissionController(service))
        .setControllerAdvice(ErrorHandler())
        .build()

    @Language("JSON")
    private val body = """
        {
          "submissionId": "s-1",
          "source": "__global__ void k(){}\nvoid solve(const float* A, float* C, int N) {}",
          "problem": {
            "id": "copy",
            "signature": {"params": [
              {"name": "A", "type": "FLOAT32", "role": "INPUT", "memory": "DEVICE", "size": "N"},
              {"name": "C", "type": "FLOAT32", "role": "OUTPUT", "memory": "DEVICE", "size": "N"},
              {"name": "N", "type": "INT32", "role": "SCALAR"}
            ]},
            "tests": [{"name": "1", "scalars": {"N": 1000}, "seed": 1}],
            "generator": {"source": "def generate(test, gen, device): ..."}
          }
        }
    """.trimIndent()

    @Test
    fun `accepts valid submission, publishes job and QUEUED event, dedups repeats`() {
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isAccepted() }; jsonPath("$.gpuProfile") { value("rtx3060") } }

        val job = argumentCaptor<JudgeJob>()
        verify(messaging).publishJob(job.capture(), any())
        assertEquals("s-1", job.firstValue.submissionId)
        assertEquals(1000, job.firstValue.problem.tests.single().scalars["N"]!!.toInt())
        val event = argumentCaptor<JudgeEvent>()
        verify(messaging).publishEvent(event.capture())
        assertEquals(SubmissionStatus.QUEUED, event.firstValue.status)

        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isOk() }; jsonPath("$.duplicate") { value(true) } }
    }

    @Test
    fun `rejects invalid submission with details`() {
        val bad = body.replace("\"FLOAT32\", \"role\": \"OUTPUT\"", "\"FLOAT32\", \"role\": \"INPUT\"")
            .replace("\"rtx3060\"", "\"x\"")
        mvc.post("/api/v1/submissions") { contentType = MediaType.APPLICATION_JSON; content = bad.replace("\"s-1\"", "\"s-2\", \"gpuProfile\": \"h100\"") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("validation_failed") }
                jsonPath("$.details.length()") { value(2) }
            }
        verify(messaging, never()).publishJob(any(), any())
    }
}
