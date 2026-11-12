package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.ContentLanguage
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.intellij.lang.annotations.Language
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
    @Column(nullable = false)
    val source: String = "package",
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
    /** The statement in Russian; [statementMarkdownEn] is the English one. */
    @Column(name = "statement_markdown", nullable = false)
    val statementMarkdown: String,
    /** Processors the problem can be judged on, in the order of problem.json; duplicated from [judgeSpec] for filters. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    val processors: List<String>,
    /** Processor -> language -> starter code. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    val starters: Map<String, Map<String, String>>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "judge_spec", nullable = false)
    val judgeSpec: String,
    @Column(name = "content_hash", nullable = false)
    val contentHash: String,
    @Column(name = "judge_hash", nullable = false)
    val judgeHash: String,
    val revision: String?,
    @Column(nullable = false)
    val format: String = ProblemFormatData.STANDARD,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "format_data", nullable = false)
    val formatData: String = "{}",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    /** Null in a version imported before packages had two languages: [title] is then its only title. */
    @Column(name = "title_en")
    val titleEn: String? = null,
    @Column(name = "statement_markdown_en")
    val statementMarkdownEn: String? = null,
) {
    fun title(language: ContentLanguage): String = titleEn?.takeIf { language == ContentLanguage.EN } ?: title

    fun statement(language: ContentLanguage): String = statementMarkdownEn?.takeIf { language == ContentLanguage.EN } ?: statementMarkdown

    fun isAuthor(handle: String?): Boolean = handle != null && authors.any { it.equals(handle, ignoreCase = true) }

    override fun equals(other: Any?) = this === other || (other is ProblemVersion && id == other.id)

    override fun hashCode() = id.hashCode()
}

interface ProblemRepository : JpaRepository<Problem, String> {
    /** Published problems that come from the problem repository; comparisons are created on the platform. */
    @Query("SELECT p.id FROM Problem p WHERE p.currentVersionId IS NOT NULL AND p.source = 'package'")
    fun findActivePackageIds(): List<String>

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Problem p SET p.currentVersionId = NULL WHERE p.id IN :ids")
    fun unpublish(ids: Collection<String>): Int
}

interface ProblemVersionRepository : JpaRepository<ProblemVersion, UUID> {
    fun findByProblemIdAndContentHash(problemId: String, contentHash: String): ProblemVersion?

    /** The lateral lookup reads each current version by its key: a join would go through the whole version history. */
    @Query(value = CURRENT_VERSIONS, nativeQuery = true)
    fun findCurrent(): List<ProblemVersion>
}

@Language("SQL")
private const val CURRENT_VERSIONS = """
    SELECT v.* FROM gpucodeforces.problems p
    JOIN LATERAL (SELECT * FROM gpucodeforces.problem_versions WHERE id = p.current_version_id LIMIT 1) v ON true
    WHERE p.current_version_id IS NOT NULL
"""
