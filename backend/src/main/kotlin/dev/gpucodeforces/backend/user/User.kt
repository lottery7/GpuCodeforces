package dev.gpucodeforces.backend.user

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users", schema = "gpucodeforces")
class User(
    @Id
    val id: UUID,
    @Column(nullable = false, length = 24)
    var handle: String,
    @Column(nullable = false)
    var email: String,
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    /**
     * Rating per processor; a processor the user has no rated contest on is absent. Eager: every User response
     * carries them, and open-in-view is disabled.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_ratings", schema = "gpucodeforces", joinColumns = [JoinColumn(name = "user_id")])
    @MapKeyColumn(name = "processor")
    var ratings: MutableMap<String, UserRating> = mutableMapOf()

    constructor(handle: String, email: String, passwordHash: String) :
        this(UUID.randomUUID(), handle, email, passwordHash)

    override fun equals(other: Any?) = this === other || (other is User && id == other.id)

    override fun hashCode() = id.hashCode()
}

/** A user's rating on one processor. */
@Embeddable
class UserRating(
    @Column(nullable = false)
    var rating: Int,
    /** Precomputed among the rated users of the processor, see UserRepository.recomputeRanks. */
    var rank: Int? = null,
    @Column(name = "rating_delta")
    var ratingDelta: Int? = null,
    // Plain id, not a JPA association: the user module only needs to render it as a string, never navigate to the contest.
    @Column(name = "rating_contest_id")
    var ratingContestId: UUID? = null,
)
