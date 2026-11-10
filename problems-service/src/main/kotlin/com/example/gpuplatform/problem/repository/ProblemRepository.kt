package com.example.gpuplatform.problem.repository

import com.example.gpuplatform.problem.domain.Problem
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProblemRepository : JpaRepository<Problem, UUID> {

    fun findBySlug(slug: String): Problem?

    fun existsBySlug(slug: String): Boolean

    /**
     * Paginated listing, optionally filtered by a tag present in the problem's
     * jsonb `tags` array. The sort is appended by Spring Data from [Pageable].
     * Uses the jsonb `?` operator for element-membership checks.
     */
    @Query(
        value = """
            SELECT * FROM problems
            WHERE (:tag IS NULL OR tags @> jsonb_build_array(:tag))
        """,
        countQuery = """
            SELECT count(*) FROM problems
            WHERE (:tag IS NULL OR tags @> jsonb_build_array(:tag))
        """,
        nativeQuery = true,
    )
    fun findPageable(@Param("tag") tag: String?, pageable: Pageable): Page<Problem>
}