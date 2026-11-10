package dev.gpucodeforces.backend.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
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
    var rating: Int? = null,
    var rank: Int? = null,
    @Column(name = "rating_delta")
    var ratingDelta: Int? = null,
    // Plain id, not a JPA association: the user module only needs to render it as a string, never navigate to the contest.
    @Column(name = "rating_contest_id")
    var ratingContestId: UUID? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    constructor(handle: String, email: String, passwordHash: String) :
        this(UUID.randomUUID(), handle, email, passwordHash)

    override fun equals(other: Any?) = this === other || (other is User && id == other.id)

    override fun hashCode() = id.hashCode()
}
