package com.example.gpuplatform.problem.api.dto

import com.example.gpuplatform.problem.domain.ProblemMetadata

data class ProblemSummaryDto(
    val slug: String,
    val title: String,
    val tags: List<String>,
    val author: String?,
    val gitHash: String?,
)

data class ProblemDetailDto(
    val slug: String,
    val title: String,
    val tags: List<String>,
    val author: String?,
    val gitHash: String?,
    val statement: String,
    val solutionPy: String?,
    val testsPy: String?,
    val metadata: ProblemMetadata,
)