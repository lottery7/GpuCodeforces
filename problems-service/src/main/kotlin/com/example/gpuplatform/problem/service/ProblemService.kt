package com.example.gpuplatform.problem.service

import com.example.gpuplatform.common.api.ApiException
import com.example.gpuplatform.common.api.PageResponse
import com.example.gpuplatform.problem.api.dto.ProblemDetailDto
import com.example.gpuplatform.problem.api.dto.ProblemSummaryDto
import com.example.gpuplatform.problem.repository.ProblemRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProblemService(private val problemRepository: ProblemRepository) {

    private val allowedSortFields = setOf("title", "createdAt", "updatedAt")

    @Transactional(readOnly = true)
    fun list(
        page: Int,
        size: Int,
        tag: String?,
        sortBy: String,
        sortDir: Sort.Direction,
    ): PageResponse<ProblemSummaryDto> {
        val actualSortBy = if (sortBy in allowedSortFields) sortBy else "title"
        val pageRequest: Pageable =
            PageRequest.of(
                page.coerceAtLeast(0),
                size.coerceIn(1, 100),
                Sort.by(sortDir, actualSortBy),
            )
        val result = problemRepository.findPageable(tag?.takeIf { it.isNotBlank() }, pageRequest)
        return PageResponse(
            content = result.content.map { it.toSummary() },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getBySlug(slug: String): ProblemDetailDto {
        val problem = problemRepository.findBySlug(slug)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "Problem '$slug' not found")
        return ProblemDetailDto(
            slug = problem.slug,
            title = problem.title,
            tags = problem.tags,
            author = problem.author,
            gitHash = problem.gitCommitSha,
            statement = problem.statement,
            solutionPy = problem.solutionPy,
            testsPy = problem.testsPy,
            metadata = problem.metadata,
        )
    }

    private fun com.example.gpuplatform.problem.domain.Problem.toSummary(): ProblemSummaryDto =
        ProblemSummaryDto(
            slug = slug,
            title = title,
            tags = tags,
            author = author,
            gitHash = gitCommitSha,
        )
}