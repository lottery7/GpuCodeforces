package com.example.gpuplatform.sync.api

import com.example.gpuplatform.integration.AbstractIntegrationTest
import com.example.gpuplatform.sync.service.AsyncSyncTrigger
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@AutoConfigureMockMvc
class GitHubWebhookControllerTest : AbstractIntegrationTest() {

    companion object {
        private const val SECRET = "test-secret"
        private const val BRANCH = "main"

        @JvmStatic
        @DynamicPropertySource
        fun webhookProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.problems.github-webhook-secret") { SECRET }
            registry.add("app.problems.git.branch") { BRANCH }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var asyncSyncTrigger: AsyncSyncTrigger

    @Test
    fun `valid push to configured branch triggers sync`() {
        val payload = """{"ref":"refs/heads/$BRANCH","repository":{"name":"gpu-problems"}}"""
        val signature = sign(SECRET, payload)

        mockMvc.perform(
            post("/api/webhooks/github")
                .header("X-GitHub-Event", "push")
                .header("X-Hub-Signature-256", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        ).andExpect(status().isAccepted)

        verify(asyncSyncTrigger).runSync()
    }

    @Test
    fun `push to a different branch is ignored`() {
        val payload = """{"ref":"refs/heads/other","repository":{"name":"x"}}"""
        val signature = sign(SECRET, payload)

        mockMvc.perform(
            post("/api/webhooks/github")
                .header("X-GitHub-Event", "push")
                .header("X-Hub-Signature-256", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        ).andExpect(status().isOk)

        verifyNoInteractions(asyncSyncTrigger)
    }

    @Test
    fun `invalid signature is rejected with 401`() {
        mockMvc.perform(
            post("/api/webhooks/github")
                .header("X-GitHub-Event", "push")
                .header("X-Hub-Signature-256", "sha256=000000000000")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"ref":"refs/heads/main"}""")
        ).andExpect(status().isUnauthorized)
    }

    private fun sign(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(body.toByteArray(Charsets.UTF_8))
        return "sha256=" + digest.joinToString("") { "%02x".format(it) }
    }
}