package com.example.gpuplatform.common.api

import org.springframework.http.HttpStatus

/**
 * Application-level exception carrying an HTTP status and a message for clients.
 */
class ApiException(
    val status: HttpStatus,
    override val message: String,
) : RuntimeException(message)

/**
 * Consistent error response body.
 */
data class ErrorResponse(
    val timestamp: String,
    val status: Int,
    val error: String,
    val message: String,
    val path: String?,
)