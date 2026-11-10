package com.example.gpuplatform.problem.api

import com.example.gpuplatform.common.api.PageResponse
import com.example.gpuplatform.problem.api.dto.ProblemDetailDto
import com.example.gpuplatform.problem.api.dto.ProblemSummaryDto
import com.example.gpuplatform.problem.service.ProblemService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/problems")
@Tag(name = "Problems", description = "Public problem catalog endpoints")
class ProblemController(private val problemService: ProblemService) {

    @GetMapping
    @Operation(
        summary = "List problems",
        description = "Paginatable and tag-filterable listing of problem summaries.",
    )
    @ApiResponse(responseCode = "200", description = "A page of problem summaries")
    fun list(
        @Parameter(description = "Zero-based page index")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "Page size (1..100)")
        @RequestParam(defaultValue = "20") size: Int,
        @Parameter(description = "Filter by a tag contained in the problem tags")
        @RequestParam(required = false) tag: String?,
        @Parameter(description = "Sort field: title, createdAt or updatedAt")
        @RequestParam(defaultValue = "title") sortBy: String,
        @Parameter(description = "Sort direction: ASC or DESC")
        @RequestParam(defaultValue = "ASC") sortDir: Sort.Direction,
    ): PageResponse<ProblemSummaryDto> =
        problemService.list(page, size, tag, sortBy, sortDir)

    @GetMapping("/{slug}")
    @Operation(
        summary = "Get problem details",
        description = "Returns the full problem including the raw Markdown statement and structured metadata.",
    )
    @ApiResponse(responseCode = "200", description = "Problem details")
    @ApiResponse(responseCode = "404", description = "Unknown slug", content = [Content(schema = Schema())])
    fun getBySlug(
        @Parameter(description = "Problem slug", required = true, `in` = ParameterIn.PATH)
        @PathVariable slug: String,
    ): ProblemDetailDto =
        problemService.getBySlug(slug)
}