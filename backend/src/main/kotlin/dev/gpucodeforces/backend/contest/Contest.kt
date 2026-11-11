package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal

@Entity
@Table(name = "contests", schema = "gpucodeforces")
class Contest(
    @Id
    val id: UUID,
    var title: String,
    @Column(name = "start_at")
    var startAt: Instant,
    @Column(name = "end_at")
    var endAt: Instant,
    @Column(nullable = false)
    var gpu: String,
    @Column(name = "rating_applied_at")
    var ratingAppliedAt: Instant? = null,
    // Eager: every Contest response includes authors, and open-in-view is disabled.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "contest_authors",
        schema = "gpucodeforces",
        joinColumns = [JoinColumn(name = "contest_id")],
        inverseJoinColumns = [JoinColumn(name = "user_id")],
    )
    var authors: MutableSet<User> = mutableSetOf(),
) {
    @Column(name = "hack_end_at")
    var hackEndAt: Instant? = null

    @Column(name = "finalized_at")
    var finalizedAt: Instant? = null

    @Column(name = "gpu_profile")
    var gpuProfile: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "baseline_times", nullable = false)
    var baselineTimes: Map<String, BigDecimal> = emptyMap()

    fun hacksOpen(now: Instant): Boolean = !now.isBefore(endAt) && hackEndAt?.isAfter(now) == true && finalizedAt == null

    constructor(title: String, startAt: Instant, endAt: Instant, gpu: String) :
        this(UUID.randomUUID(), title, startAt, endAt, gpu)

    fun phaseAt(now: Instant): ContestPhase = when {
        now.isBefore(startAt) -> ContestPhase.UPCOMING
        now.isBefore(endAt) -> ContestPhase.LIVE
        else -> ContestPhase.FINISHED
    }

    override fun equals(other: Any?) = this === other || (other is Contest && id == other.id)

    override fun hashCode() = id.hashCode()
}

enum class ContestPhase(val value: String) {
    UPCOMING("upcoming"),
    LIVE("live"),
    FINISHED("finished"),
}
