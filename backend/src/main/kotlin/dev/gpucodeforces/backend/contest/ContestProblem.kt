package dev.gpucodeforces.backend.contest

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.util.UUID

@Embeddable
data class ContestProblemId(
    @Column(name = "contest_id") val contestId: UUID,
    @Column(name = "problem_id") val problemId: String,
) : Serializable

@Entity
@Table(name = "contest_problems", schema = "gpucodeforces")
class ContestProblem(
    @EmbeddedId
    val id: ContestProblemId,
    @Column(nullable = false)
    var position: Int,
    // Snapshotted from the problem's global record time once the contest starts; see Contest.
    @Column(name = "baseline_time_ms")
    var baselineTimeMs: BigDecimal? = null,
) {
    constructor(contestId: UUID, problemId: String, position: Int) :
        this(ContestProblemId(contestId, problemId), position)

    override fun equals(other: Any?) = this === other || (other is ContestProblem && id == other.id)

    override fun hashCode() = id.hashCode()
}
