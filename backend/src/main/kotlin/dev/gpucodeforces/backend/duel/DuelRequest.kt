package dev.gpucodeforces.backend.duel

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "duel_requests", schema = "gpucodeforces")
class DuelRequest(
    @Id val id: UUID,
    val mode: String,
    var status: String,
    @Column(name = "creator_id") val creatorId: UUID,
    @Column(name = "opponent_id") var opponentId: UUID?,
    @JdbcTypeCode(SqlTypes.ARRAY) val topics: List<String>,
    /** The processor of the duel's contest; its problem has to be solvable on it. */
    val processor: String,
    @Column(name = "created_at") val createdAt: Instant,
    @Column(name = "expires_at") var expiresAt: Instant,
    @Column(name = "contest_id") var contestId: UUID? = null,
    @Column(name = "problem_version_id") var problemVersionId: UUID? = null,
) {
    override fun equals(other: Any?) = this === other || other is DuelRequest && id == other.id

    override fun hashCode() = id.hashCode()
}

@Entity
@Table(name = "duel_active_users", schema = "gpucodeforces")
class ActiveDuel(
    @Id @Column(name = "user_id") val userId: UUID,
    @Column(name = "request_id") var requestId: UUID? = null,
)

@Entity
@Table(name = "duel_matchmaking_lock", schema = "gpucodeforces")
class MatchmakingLock(@Id val id: Int)

interface DuelRequestRepository : JpaRepository<DuelRequest, UUID> {
    fun findByModeAndStatusOrderByCreatedAt(mode: String, status: String): List<DuelRequest>

    fun findByStatusInAndExpiresAtLessThanEqual(statuses: Collection<String>, now: Instant): List<DuelRequest>

    fun findByCreatorIdAndModeAndStatus(creatorId: UUID, mode: String, status: String): DuelRequest?

    fun findByCreatorIdAndStatusInOrderByCreatedAtDesc(creatorId: UUID, statuses: Collection<String>): List<DuelRequest>

    fun findByContestId(contestId: UUID): List<DuelRequest>

    @Query("SELECT r FROM DuelRequest r WHERE r.creatorId = :userId OR r.opponentId = :userId ORDER BY r.createdAt DESC, r.id")
    fun visibleTo(userId: UUID): List<DuelRequest>
}

interface ActiveDuelRepository : JpaRepository<ActiveDuel, UUID> {
    fun findByRequestIdIsNotNull(): List<ActiveDuel>
}

interface MatchmakingLockRepository : JpaRepository<MatchmakingLock, Int> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM MatchmakingLock l WHERE l.id = 1")
    fun acquire(): MatchmakingLock?
}
