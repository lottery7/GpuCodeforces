package dev.gpucodeforces.backend.benchmark

import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.judge.api.ScoreType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * One reference solution of a format measured on one [processor] in one environment ([environmentKey]): the baseline
 * of an optimization, or a variant of a guess. [score] is in the processor's [scoreType].
 */
@Entity
@Table(name = "reference_jobs", schema = "gpucodeforces")
class ReferenceJob(
    @Id val id: UUID,
    @Column(name = "problem_version_id") val problemVersionId: UUID,
    @Column(name = "environment_key") val environmentKey: String,
    val role: String,
    val processor: String,
    val language: String,
    var status: String = "pending",
    var generation: Int = 0,
    var failures: Int = 0,
    @Column(name = "send_attempts") var sendAttempts: Int = 0,
    @Column(name = "job_id") var jobId: String? = null,
    @Column(name = "event_attempt") var eventAttempt: Int = 0,
    @Column(name = "event_seq") var eventSeq: Int = 0,
    @Column(name = "expected_tests") var expectedTests: Int? = null,
    @Column(name = "next_attempt_at") var nextAttemptAt: Instant = Instant.now(),
    @Column(name = "lease_until") var leaseUntil: Instant? = null,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.now(),
    var score: BigDecimal? = null,
    @Enumerated(EnumType.STRING) @Column(name = "score_type") var scoreType: ScoreType? = null,
    @JdbcTypeCode(SqlTypes.JSON) var measurement: String? = null,
    @Column(name = "measured_at") var measuredAt: Instant? = null,
    var diagnostic: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "expected_environment") var expectedEnvironment: String? = null,
) {
    val submissionId: String get() = "reference-$id-r$generation"
}

interface ReferenceJobRepository : JpaRepository<ReferenceJob, UUID> {
    fun findByProblemVersionIdAndEnvironmentKey(problemVersionId: UUID, environmentKey: String): List<ReferenceJob>

    fun findByEnvironmentKeyAndStatusIn(environmentKey: String, statuses: Collection<String>): List<ReferenceJob>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ReferenceJob j WHERE j.id = :id")
    fun findForUpdate(id: UUID): ReferenceJob?
}

interface ReferenceVersionLockRepository : Repository<ProblemVersion, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ProblemVersion v WHERE v.id = :id")
    fun acquire(id: UUID): ProblemVersion?
}
