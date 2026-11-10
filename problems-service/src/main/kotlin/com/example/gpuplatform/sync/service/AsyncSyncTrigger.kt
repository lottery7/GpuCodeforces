package com.example.gpuplatform.sync.service

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Thin wrapper so the webhook can kick off a background sync without
 * self-invocation of the @Async proxy.
 */
@Component
class AsyncSyncTrigger(private val problemSyncService: ProblemSyncService) {

    @Async
    fun runSync() {
        problemSyncService.triggerSync()
    }
}