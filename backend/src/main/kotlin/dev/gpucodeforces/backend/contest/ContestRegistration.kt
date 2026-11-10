package dev.gpucodeforces.backend.contest

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

@Embeddable
data class ContestRegistrationId(
    @Column(name = "contest_id") val contestId: UUID,
    @Column(name = "user_id") val userId: UUID,
) : Serializable

@Entity
@Table(name = "contest_registrations", schema = "gpucodeforces")
class ContestRegistration(
    @EmbeddedId
    val id: ContestRegistrationId,
) {
    constructor(contestId: UUID, userId: UUID) : this(ContestRegistrationId(contestId, userId))

    override fun equals(other: Any?) = this === other || (other is ContestRegistration && id == other.id)

    override fun hashCode() = id.hashCode()
}
