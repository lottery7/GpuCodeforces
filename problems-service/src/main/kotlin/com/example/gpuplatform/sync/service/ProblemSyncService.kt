package com.example.gpuplatform.sync.service

import com.example.gpuplatform.sync.domain.SyncResult
import com.example.gpuplatform.sync.domain.SyncStatus
import com.example.gpuplatform.sync.domain.SyncState
import com.example.gpuplatform.sync.repository.SyncStateEntity
import com.example.gpuplatform.sync.repository.SyncStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Orchestrates a full synchronization: updates the Git repository, imports
 * problems and tracks the latest sync status.
 */
@Service
class ProblemSyncService(
    private val gitRepositoryService: GitRepositoryService,
    private val problemImportService: ProblemImportService,
    private val syncStateRepository: SyncStateRepository,
) {

    private val log = LoggerFactory.getLogger(ProblemSyncService::class.java)

    /**
     * Runs a synchronous synchronization using the configured repository.
     */
    fun triggerSync(): SyncResult {
        log.info("Sync started")
        val startedAt = Instant.now()
        markRunning()

        return try {
            val snapshot = gitRepositoryService.obtainSnapshot()
            val summary = problemImportService.importAll(snapshot)
            markSuccess(snapshot.commitSha, summary)
            log.info(
                "Sync finished successfully in {} ms: imported={}, updated={}, unchanged={}",
                java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                summary.imported, summary.updated, summary.unchanged,
            )
            SyncResult(
                status = SyncStatus.SUCCESS,
                lastImportedSha = snapshot.commitSha,
                totalDiscovered = summary.totalDiscovered,
                imported = summary.imported,
                updated = summary.updated,
                unchanged = summary.unchanged,
                errorMessage = null,
            )
        } catch (e: Exception) {
            markFailed(e)
            log.error(
                "Sync failed in {} ms: {}",
                java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                e.message, e,
            )
            SyncResult(
                status = SyncStatus.FAILED,
                lastImportedSha = getStateOrNull()?.lastImportedSha,
                totalDiscovered = 0,
                imported = 0,
                updated = 0,
                unchanged = 0,
                errorMessage = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    fun currentState(): SyncState {
        val entity = syncStateRepository.findSingle()
        return entity?.toDomain()
            ?: SyncState(
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

    @Transactional
    fun markRunning() {
        val entity = syncStateRepository.findSingle() ?: SyncStateEntity()
        entity.status = SyncStatus.RUNNING
        entity.lastAttemptedAt = Instant.now()
        entity.errorMessage = null
        entity.updatedAt = Instant.now()
        syncStateRepository.save(entity)
    }

    @Transactional
    fun markSuccess(commitSha: String, summary: com.example.gpuplatform.sync.domain.ImportSummary) {
        val entity = syncStateRepository.findSingle() ?: SyncStateEntity()
        entity.status = SyncStatus.SUCCESS
        entity.lastSuccessAt = Instant.now()
        entity.lastImportedSha = commitSha
        entity.totalDiscovered = summary.totalDiscovered
        entity.imported = summary.imported
        entity.updated = summary.updated
        entity.unchanged = summary.unchanged
        entity.errorMessage = null
        entity.updatedAt = Instant.now()
        syncStateRepository.save(entity)
    }

    @Transactional
    fun markFailed(e: Exception) {
        val entity = syncStateRepository.findSingle() ?: SyncStateEntity()
        entity.status = SyncStatus.FAILED
        entity.errorMessage = e.message?.take(1000) ?: e.javaClass.simpleName
        entity.updatedAt = Instant.now()
        syncStateRepository.save(entity)
    }

    private fun getStateOrNull() = syncStateRepository.findSingle()

    private fun SyncStateEntity.toDomain(): SyncState =
        SyncState(
            status = status,
            lastAttemptedAt = lastAttemptedAt,
            lastSuccessAt = lastSuccessAt,
            lastImportedSha = lastImportedSha,
            totalDiscovered = totalDiscovered,
            imported = imported,
            updated = updated,
            unchanged = unchanged,
            errorMessage = errorMessage,
            updatedAt = updatedAt,
        )
}