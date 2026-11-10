package com.example.gpuplatform.sync.domain

import com.example.gpuplatform.problem.domain.ProblemMetadata

/**
 * A parsed and validated problem source file before persistence.
 */
data class ParsedProblem(
    val slug: String,
    val title: String,
    val tags: List<String>,
    val author: String?,
    val metadata: ProblemMetadata,
    val statement: String,
    val solutionPy: String? = null,
    val testsPy: String? = null,
)

/**
 * Thrown when a problem source file cannot be parsed or validated.
 */
class ProblemParseException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)