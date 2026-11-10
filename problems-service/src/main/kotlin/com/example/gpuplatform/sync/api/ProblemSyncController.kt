package com.example.gpuplatform.sync.api

import com.example.gpuplatform.sync.domain.SyncResult
import com.example.gpuplatform.sync.domain.SyncState
import com.example.gpuplatform.sync.service.ProblemSyncService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Internal/admin endpoints. These are intentionally not protected by a full
 * auth system; protect them at the deployment or network layer. An optional
 * shared key can be set via `INTERNAL_API_KEY` (guarded by an interceptor).
 */
@RestController
@RequestMapping("/api/internal/problems")
@Tag(name = "Internal Sync", description = "Admin endpoints to trigger and inspect synchronization. Protect at the network/deployment layer.")
class ProblemSyncController(private val syncService: ProblemSyncService) {

    @PostMapping("/sync")
    @Operation(
        summary = "Trigger synchronization",
        description = "Runs a synchronous sync using the configured Git repository and branch.",
        security = [SecurityRequirement(name = "internalApiKey")],
    )
    @ApiResponse(responseCode = "200", description = "Sync result")
    fun sync(): SyncResult = syncService.triggerSync()

    @GetMapping("/sync/status")
    @Operation(
        summary = "Latest sync status",
        description = "Returns the latest synchronization state.",
        security = [SecurityRequirement(name = "internalApiKey")],
    )
    @ApiResponse(responseCode = "200", description = "Latest sync state")
    fun status(): SyncState = syncService.currentState()
}