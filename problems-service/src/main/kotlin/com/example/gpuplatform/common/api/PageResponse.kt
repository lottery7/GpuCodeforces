package com.example.gpuplatform.common.api

/**
 * Consistent pagination envelope used by list endpoints.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)