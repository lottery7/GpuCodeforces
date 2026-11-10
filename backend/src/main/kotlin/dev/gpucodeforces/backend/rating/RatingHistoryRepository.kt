package dev.gpucodeforces.backend.rating

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RatingHistoryRepository : JpaRepository<RatingHistory, RatingHistoryId> {
    fun findByIdUserId(userId: UUID): List<RatingHistory>
}
