package dev.gpucodeforces.backend.contest

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface ContestRepository : JpaRepository<Contest, UUID>, JpaSpecificationExecutor<Contest> {
    fun findByFinalizedAtIsNullAndEndAtLessThanEqual(now: Instant): List<Contest>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Contest c WHERE c.id = :id")
    fun findForUpdate(id: UUID): Contest?
}
