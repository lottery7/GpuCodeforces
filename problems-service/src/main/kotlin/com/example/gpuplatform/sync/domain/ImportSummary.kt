package com.example.gpuplatform.sync.domain

/**
 * Counts produced by a single import run.
 */
data class ImportSummary(
    val totalDiscovered: Int,
    val imported: Int,
    val updated: Int,
    val unchanged: Int,
)