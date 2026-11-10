package dev.gpucodeforces.backend.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface SessionRepository : JpaRepository<Session, UUID> {
    fun findByTokenAndExpiresAtAfter(token: UUID, now: Instant): Session?
}
