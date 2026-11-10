package com.example.gpuplatform.sync.repository

import com.example.gpuplatform.sync.domain.SyncStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Single-row table retaining the latest synchronization state.
 */
@Entity
@Table(name = "problem_sync_state")
class SyncStateEntity(
    @Id
    @Column(name = "id")
    val id: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: SyncStatus,

    @Column(name = "last_attempted_at")
    var lastAttemptedAt: Instant?,

    @Column(name = "last_success_at")
    var lastSuccessAt: Instant?,

    @Column(name = "last_imported_sha", length = 64)
    var lastImportedSha: String?,

    @Column(name = "total_discovered", nullable = false)
    var totalDiscovered: Int,

    @Column(name = "imported", nullable = false)
    var imported: Int,

    @Column(name = "updated", nullable = false)
    var updated: Int,

    @Column(name = "unchanged", nullable = false)
    var unchanged: Int,

    @Column(name = "error_message")
    var errorMessage: String?,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    constructor() : this(
        id = 1,
        status = SyncStatus.IDLE,
        lastAttemptedAt = null,
        lastSuccessAt = null,
        lastImportedSha = null,
        totalDiscovered = 0,
        imported = 0,
        updated = 0,
        unchanged = 0,
        errorMessage = null,
        updatedAt = Instant.EPOCH,
    )
}