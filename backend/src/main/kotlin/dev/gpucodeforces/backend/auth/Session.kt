package dev.gpucodeforces.backend.auth

import dev.gpucodeforces.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sessions", schema = "gpucodeforces")
class Session(
    @Id
    val token: UUID,
    // Eager: the whole point of looking up a session is to read its user, and open-in-view is disabled.
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
) {
    constructor(user: User, expiresAt: Instant) : this(UUID.randomUUID(), user, expiresAt)

    override fun equals(other: Any?) = this === other || (other is Session && token == other.token)

    override fun hashCode() = token.hashCode()
}
