package dev.gpucodeforces.backend.submission

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Immutable result of a completed check, retained before starting another one. */
@Entity
@Table(name = "judging_runs", schema = "gpucodeforces")
class JudgingRun(
    @Id val id: UUID,
    @Column(name = "submission_id") val submissionId: UUID,
    @Column(name = "run_number") val runNumber: Int,
    val status: String,
    @Column(name = "time_ms") val timeMs: BigDecimal?,
    val diagnostic: String,
    @Column(name = "test_suite") val testSuite: String,
    @Column(name = "finished_at") val finishedAt: Instant?,
) {
    constructor(s: Submission) : this(UUID.randomUUID(), s.id, s.recheckCount, s.status.value, s.timeMs, s.diagnostic, s.testSuite, s.finishedAt)
}

interface JudgingRunRepository : JpaRepository<JudgingRun, UUID> {
    fun findBySubmissionIdIn(ids: Collection<UUID>): List<JudgingRun>
}
