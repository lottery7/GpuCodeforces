package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.judge.api.ScoreType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Converter
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Statuses of the frontend contract. */
enum class SubmissionStatus(val value: String, val terminal: Boolean) {
    QUEUED("queued", false),
    COMPILING("compiling", false),
    CHECKING("checking", false),
    // The judge measures while testing, so the backend never reports these two; they stay valid filters.
    MEASURING("measuring", false),
    ACCEPTED("accepted", true),
    COMPILE_ERROR("compile_error", true),
    WRONG_ANSWER("wrong_answer", true),
    RUNTIME_ERROR("runtime_error", true),
    TIME_LIMIT("time_limit", true),
    MEMORY_LIMIT("memory_limit", true),
    SYSTEM_ERROR("system_error", true),
    MEASUREMENT_ERROR("measurement_error", true),
    ;

    companion object {
        val PENDING = entries.filterNot { it.terminal }

        fun ofOrNull(value: String): SubmissionStatus? = entries.firstOrNull { it.value == value }
    }
}

// Nullable because Hibernate also converts query parameters, and the optional status filter can be null.
@Converter(autoApply = true)
class SubmissionStatusConverter : AttributeConverter<SubmissionStatus?, String?> {
    override fun convertToDatabaseColumn(attribute: SubmissionStatus?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): SubmissionStatus? =
        dbData?.let { SubmissionStatus.ofOrNull(it) ?: error("Unknown submission status $it") }
}

@Entity
@Table(name = "submissions", schema = "gpucodeforces")
class Submission(
    @Id
    val id: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "problem_id", nullable = false)
    val problemId: String,
    @Column(name = "problem_version_id", nullable = false)
    val problemVersionId: UUID,
    @Column(name = "contest_id")
    val contestId: UUID?,
    @Column(nullable = false)
    val code: String,
    @Column(name = "environment_markdown", nullable = false)
    val environmentMarkdown: String,
    @Column(name = "judge_hash", nullable = false)
    val judgeHash: String,
    /** Accepted results are comparable within one (judgeHash, processor). */
    @Column(nullable = false)
    val processor: String,
    @Column(nullable = false)
    val language: String,
    /** Best accepted score of the author in the same context and configuration before this submission. */
    @Column(name = "previous_best_score")
    var previousBestScore: BigDecimal?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "guess_choice")
    val guessChoice: String? = null,
) {
    @Column(nullable = false)
    var status: SubmissionStatus = SubmissionStatus.QUEUED

    /** Set for accepted submissions only, in units of [scoreType]. */
    var score: BigDecimal? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "score_type")
    var scoreType: ScoreType? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var metrics: Map<String, Double> = emptyMap()

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "measurement")
    var measurement: String? = null

    @Column(name = "benchmark_environment_key")
    var benchmarkEnvironmentKey: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_environment")
    var expectedEnvironment: String? = null

    @Column(name = "guess_benchmark_key")
    var guessBenchmarkKey: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guess_result")
    var guessResult: String? = null

    @Column(name = "passed_tests", nullable = false)
    var passedTests: Int = 0

    @Column(name = "total_tests")
    var totalTests: Int? = null

    @Column(nullable = false)
    var diagnostic: String = ""

    @Column(name = "recheck_count", nullable = false)
    var recheckCount: Int = 0

    @Column(name = "job_id")
    var jobId: String? = null

    @Column(name = "event_attempt", nullable = false)
    var eventAttempt: Int = 0

    @Column(name = "event_seq", nullable = false)
    var eventSeq: Int = 0

    @Column(name = "finished_at")
    var finishedAt: Instant? = null

    /** Last sign of life: creation, acceptance by the gateway or a judge event; see SubmissionService.reportStuck. */
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt

    /** Id of the current judge job; a recheck must not be deduplicated by the gateway as the original. */
    val judgeSubmissionId: String get() = if (recheckCount == 0) id.toString() else "$id$RECHECK_SEPARATOR$recheckCount"

    override fun equals(other: Any?) = this === other || (other is Submission && id == other.id)

    override fun hashCode() = id.hashCode()

    fun toRow() = SubmissionRow(
        id, userId, problemId, problemVersionId, contestId, status, score, scoreType, judgeHash, processor, language, createdAt,
        benchmarkEnvironmentKey, finishedAt,
    )

    companion object {
        const val RECHECK_SEPARATOR = ".r"
    }
}

class SubmissionRow(
    val id: UUID,
    val userId: UUID,
    val problemId: String,
    val problemVersionId: UUID,
    val contestId: UUID?,
    val status: SubmissionStatus,
    val score: BigDecimal?,
    val scoreType: ScoreType?,
    val judgeHash: String,
    val processor: String,
    val language: String,
    val createdAt: Instant,
    val benchmarkEnvironmentKey: String? = null,
    val finishedAt: Instant? = null,
)

interface SubmissionRepository : JpaRepository<Submission, UUID> {
    @Query("SELECT s FROM Submission s WHERE s.userId = :userId AND s.problemVersionId = :versionId " +
        "AND s.guessChoice IS NOT NULL AND ((:contestId IS NULL AND s.contestId IS NULL) OR s.contestId = :contestId) " +
        "AND ((:benchmarkKey IS NULL AND s.guessBenchmarkKey IS NULL) OR s.guessBenchmarkKey = :benchmarkKey)")
    fun findGuess(userId: UUID, versionId: UUID, contestId: UUID?, benchmarkKey: String? = null): Submission?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Submission s WHERE s.id = :id")
    fun findForUpdate(id: UUID): Submission?

    @Query("SELECT s FROM Submission s WHERE s.status = :status AND s.jobId IS NULL AND s.createdAt < :before ORDER BY s.createdAt")
    fun findUnsent(status: SubmissionStatus, before: Instant): List<Submission>

    @Query("SELECT s FROM Submission s WHERE s.status IN :statuses AND s.jobId IS NOT NULL AND s.updatedAt < :before")
    fun findStuck(statuses: Collection<SubmissionStatus>, before: Instant): List<Submission>

    fun countByUserIdAndStatusIn(userId: UUID, statuses: Collection<SubmissionStatus>): Long

    /** History rows without code and diagnostic, newest first. */
    @Query(
        """
        SELECT new dev.gpucodeforces.backend.submission.SubmissionRow(
            s.id, s.userId, s.problemId, s.problemVersionId, s.contestId, s.status, s.score, s.scoreType, s.judgeHash, s.processor,
            s.language, s.createdAt, s.benchmarkEnvironmentKey, s.finishedAt)
        FROM Submission s
        WHERE s.userId = :userId
          AND (:problemId IS NULL OR s.problemId = :problemId)
          AND (:status IS NULL OR s.status = :status)
          AND (:practiceOnly = FALSE OR s.contestId IS NULL)
          AND (:contestId IS NULL OR s.contestId = :contestId)
        ORDER BY s.createdAt DESC
        """,
    )
    fun search(userId: UUID, problemId: String?, status: SubmissionStatus?, practiceOnly: Boolean, contestId: UUID?): List<SubmissionRow>
}
