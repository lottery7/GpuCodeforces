package com.example.gpuplatform.sync.domain

/**
 * A single problem source file discovered inside the synced Git repository.
 */
data class ProblemSource(
    val slug: String,
    val absolutePath: String,
)

/**
 * Snapshot of the Git repository at a specific commit, with the list of
 * problem source files found under the configured problems directory.
 */
data class RepositorySnapshot(
    val commitSha: String,
    val problemSources: List<ProblemSource>,
)