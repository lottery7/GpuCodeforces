package dev.gpucodeforces.backend.contest

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType
import java.util.UUID

interface ContestRepository : JpaRepository<Contest, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Contest c WHERE c.id = :id")
    fun findForUpdate(id: UUID): Contest?
}
