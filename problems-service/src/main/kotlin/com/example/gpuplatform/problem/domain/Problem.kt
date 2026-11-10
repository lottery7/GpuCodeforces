package com.example.gpuplatform.problem.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "problems")
class Problem(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "slug", nullable = false, unique = true, length = 150)
    val slug: String,

    @Column(name = "title", nullable = false, length = 255)
    var title: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    var tags: List<String>,

    @Column(name = "author")
    var author: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    var metadata: ProblemMetadata,

    @Column(name = "statement", nullable = false)
    var statement: String,

    @Column(name = "solution_py")
    var solutionPy: String?,

    @Column(name = "tests_py")
    var testsPy: String?,

    @Column(name = "git_commit_sha", length = 64)
    var gitCommitSha: String?,

    @Column(name = "content_hash", nullable = false, length = 64)
    var contentHash: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)