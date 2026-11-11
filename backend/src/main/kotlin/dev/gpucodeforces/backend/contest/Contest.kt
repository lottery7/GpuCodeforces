package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.judge.api.ScoreType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

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
    /** The one processor every submission of the contest is judged on; chosen at creation, fixed once it starts. */
    @Column(nullable = false)
    var processor: String,
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
    @Column(nullable = false)
    val format: String = "standard",
    @Column(name = "challenger_id")
    val challengerId: UUID? = null,
    @Column(name = "opponent_id")
    val opponentId: UUID? = null,
    @Column(name = "surrendered_user_id")
    var surrenderedUserId: UUID? = null,
    @Column(name = "benchmark_environment_key")
    var benchmarkEnvironmentKey: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_duel_results", columnDefinition = "jsonb")
    var finalDuelResults: List<FinalDuelResult>? = null,
) {
    /** End of the hack period after [endAt]; null when the contest has none. */
    @Column(name = "hack_end_at")
    var hackEndAt: Instant? = null

    /** When the official results became final: hacks are over, everything is rejudged and the rating is applied. */
    @Column(name = "finalized_at")
    var finalizedAt: Instant? = null

    /** When the results can become final at the earliest. */
    val resultsAt: Instant get() = hackEndAt ?: endAt

    fun hacksOpen(now: Instant): Boolean = !now.isBefore(endAt) && hackEndAt?.isAfter(now) == true && finalizedAt == null

    constructor(title: String, startAt: Instant, endAt: Instant, processor: String) :
        this(UUID.randomUUID(), title, startAt, endAt, processor)

    fun phaseAt(now: Instant): ContestPhase = when {
        now.isBefore(startAt) -> ContestPhase.UPCOMING
        now.isBefore(endAt) -> ContestPhase.LIVE
        else -> ContestPhase.FINISHED
    }

    override fun equals(other: Any?) = this === other || (other is Contest && id == other.id)

    override fun hashCode() = id.hashCode()
}

data class FinalDuelResult(val userId: UUID, val score: FinalDuelScore?, val rank: Int, val solved: Int)

data class FinalDuelScore(val value: BigDecimal, val type: ScoreType)

enum class ContestPhase(val value: String) {
    UPCOMING("upcoming"),
    LIVE("live"),
    FINISHED("finished"),
}
