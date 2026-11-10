package com.example.gpuplatform.sync.domain

import java.time.Instant

enum class SyncStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
}

/**
 * Result of a single sync run, used to persist the latest sync state.
 */
data class SyncState(
    val status: SyncStatus,
    val lastAttemptedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastImportedSha: String?,
    val totalDiscovered: Int,
    val imported: Int,
    val updated: Int,
    val unchanged: Int,
    val errorMessage: String?,
    val updatedAt: Instant,
)

/**
 * Summary returned to the caller after a sync triggers/completes.
 */
data class SyncResult(
    val status: SyncStatus,
    val lastImportedSha: String?,
    val totalDiscovered: Int,
    val imported: Int,
    val updated: Int,
    val unchanged: Int,
    val errorMessage: String?,
)