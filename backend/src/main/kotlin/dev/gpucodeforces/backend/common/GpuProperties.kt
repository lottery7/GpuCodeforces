package dev.gpucodeforces.backend.common

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The single GPU contests and practice are judged on: [profile] is the judge's routing key (JUDGE_GPU_PROFILE of
 * the executors), [name] is what the contest page and the environment description show.
 */
@ConfigurationProperties("platform.gpu")
data class GpuProperties(
    val profile: String = "rtx3060",
    val name: String = "NVIDIA GeForce RTX 3060",
)
