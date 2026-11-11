package dev.gpucodeforces.backend.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notifications", schema = "gpucodeforces")
class Notification(
    @Id val id: UUID,
    @Column(name = "user_id") val userId: UUID,
    val type: String,
    val message: String,
    @Column(name = "duel_request_id") val duelRequestId: UUID,
    @Column(name = "contest_id") val contestId: UUID?,
    @Column(name = "created_at") val createdAt: Instant,
    @Column(name = "read_at") var readAt: Instant? = null,
)

interface NotificationRepository : JpaRepository<Notification, UUID> {
    @Query(value = "SELECT * FROM gpucodeforces.notifications WHERE user_id = :userId ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset", nativeQuery = true)
    fun list(userId: UUID, offset: Int, limit: Int): List<Notification>

    fun countByUserId(userId: UUID): Long

    fun countByUserIdAndReadAtIsNull(userId: UUID): Long
}
