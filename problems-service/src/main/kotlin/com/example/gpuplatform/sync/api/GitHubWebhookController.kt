package com.example.gpuplatform.sync.api

import com.example.gpuplatform.config.ProblemsProperties
import com.example.gpuplatform.sync.service.AsyncSyncTrigger
import com.example.gpuplatform.sync.service.GitHubWebhookVerifier
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GitHub push webhook endpoint. Does not trust any URL or branch from the
 * payload: it only triggers a sync with the configured repository settings
 * when the push is to the configured branch.
 */
@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks", description = "GitHub push webhook")
class GitHubWebhookController(
    private val props: ProblemsProperties,
    private val verifier: GitHubWebhookVerifier,
    private val asyncSyncTrigger: AsyncSyncTrigger,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(GitHubWebhookController::class.java)

    @PostMapping("/github")
    @Operation(
        summary = "GitHub push webhook",
        description = "Verifies the GitHub signature and triggers a background sync when the push targets the configured branch.",
    )
    fun githubWebhook(
        @RequestHeader(value = "X-GitHub-Event", defaultValue = "") event: String,
        @RequestHeader(value = GitHubWebhookVerifier.HEADER, required = false) signature: String?,
        @RequestBody rawBody: ByteArray,
    ): ResponseEntity<Any> {
        val secret = props.githubWebhookSecret
        if (secret.isEmpty()) {
            log.warn("Received webhook but no GITHUB_WEBHOOK_SECRET configured")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "Webhook not configured"))
        }
        if (!verifier.verify(secret, signature, rawBody)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "Invalid signature"))
        }
        if (event != "push") {
            return ResponseEntity.ok(mapOf("status" to "ignored"))
        }

        val ref = objectMapper.readTree(rawBody).get("ref")?.asText() ?: ""
        val expectedRef = "refs/heads/${props.git.branch}"
        if (ref != expectedRef) {
            log.info("Ignoring push for ref '{}' (expecting '{}')", ref, expectedRef)
            return ResponseEntity.ok(mapOf("status" to "ignored", "ref" to ref))
        }

        asyncSyncTrigger.runSync()
        return ResponseEntity.accepted().body(mapOf("status" to "sync_triggered"))
    }
}