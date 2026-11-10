package dev.gpucodeforces.backend.rating

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

@Embeddable
data class RatingHistoryId(
    @Column(name = "contest_id") val contestId: UUID,
    @Column(name = "user_id") val userId: UUID,
) : Serializable

@Entity
@Table(name = "rating_history", schema = "gpucodeforces")
class RatingHistory(
    @EmbeddedId
    val id: RatingHistoryId,
    @Column(nullable = false)
    val rank: Int,
    @Column(name = "rating_delta", nullable = false)
    val ratingDelta: Int,
    @Column(name = "rating_after", nullable = false)
    val ratingAfter: Int,
) {
    constructor(contestId: UUID, userId: UUID, rank: Int, ratingDelta: Int, ratingAfter: Int) :
        this(RatingHistoryId(contestId, userId), rank, ratingDelta, ratingAfter)

    override fun equals(other: Any?) = this === other || (other is RatingHistory && id == other.id)

    override fun hashCode() = id.hashCode()
}
