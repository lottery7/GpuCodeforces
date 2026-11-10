package dev.gpucodeforces.backend.problem

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "problems", schema = "gpucodeforces")
class Problem(
    @Id
    val id: String,
    @Column(name = "current_version_id")
    var currentVersionId: UUID? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?) = this === other || (other is Problem && id == other.id)

    override fun hashCode() = id.hashCode()
}

/** Immutable snapshot of a problem package. */
@Entity
@Table(name = "problem_versions", schema = "gpucodeforces")
class ProblemVersion(
    @Id
    val id: UUID,
    @Column(name = "problem_id", nullable = false)
    val problemId: String,
    @Column(nullable = false)
    val code: String,
    @Column(nullable = false)
    val title: String,
    @Column(nullable = false)
    val difficulty: String,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    val tags: List<String>,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    val authors: List<String>,
    @Column(nullable = false)
    val draft: Boolean,
    @Column(name = "statement_markdown", nullable = false)
    val statementMarkdown: String,
    @Column(name = "starter_code", nullable = false)
    val starterCode: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "judge_spec", nullable = false)
    val judgeSpec: String,
    @Column(name = "content_hash", nullable = false)
    val contentHash: String,
    @Column(name = "judge_hash", nullable = false)
    val judgeHash: String,
    val revision: String?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun isAuthor(handle: String?): Boolean = handle != null && authors.any { it.equals(handle, ignoreCase = true) }

    override fun equals(other: Any?) = this === other || (other is ProblemVersion && id == other.id)

    override fun hashCode() = id.hashCode()
}

interface ProblemRepository : JpaRepository<Problem, String> {
    @Query("SELECT p.id FROM Problem p WHERE p.currentVersionId IS NOT NULL")
    fun findActiveIds(): List<String>

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Problem p SET p.currentVersionId = NULL WHERE p.id IN :ids")
    fun unpublish(ids: Collection<String>): Int
}

interface ProblemVersionRepository : JpaRepository<ProblemVersion, UUID> {
    fun findByProblemIdAndContentHash(problemId: String, contentHash: String): ProblemVersion?

}
