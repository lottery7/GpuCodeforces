package dev.gpucodeforces.judge.gateway.config

import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.Topology
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("judge.gateway")
data class GatewayProperties(
    /** GPU profiles the gateway accepts submissions for; one jobs queue is declared per profile. */
    val gpuProfiles: List<String> = listOf("rtx3060"),
    /** Profile used when a submission does not specify one. */
    val defaultGpuProfile: String = "rtx3060",
    /** Executors without a heartbeat for this long are reported as dead. */
    val heartbeatTimeout: Duration = Duration.ofSeconds(15),
    /** Window in which a repeated submissionId is treated as a duplicate (per gateway instance). */
    val dedupTtl: Duration = Duration.ofMinutes(10),
    val dedupMaxEntries: Int = 100_000,
    /** Timeout for the broker to confirm a published job before the request fails with 503. */
    val publishConfirmTimeout: Duration = Duration.ofSeconds(5),
    /** Largest request body accepted, checked before it is parsed. Must stay below the broker's message limit. */
    val maxRequestBytes: Long = 8L shl 20,
    /**
     * Stricter limits than the contract's ([SpecValidator.Limits]) for this deployment. They can only be lowered:
     * executors validate every job against the contract's own limits and reject what exceeds them.
     */
    val maxSourceBytes: Int = CONTRACT_LIMITS.maxSourceBytes,
    val maxTimeLimitMs: Long = CONTRACT_LIMITS.maxTimeLimitMs,
) {
    init {
        require(defaultGpuProfile in gpuProfiles) { "judge.gateway.default-gpu-profile must be one of gpu-profiles" }
        require(gpuProfiles.all(Topology::isProfileName)) { "gpu profile names must match ${Topology.PROFILE_NAME}" }
        require(maxSourceBytes <= CONTRACT_LIMITS.maxSourceBytes && maxTimeLimitMs <= CONTRACT_LIMITS.maxTimeLimitMs) {
            "judge.gateway limits cannot exceed $CONTRACT_LIMITS: executors would reject the jobs"
        }
    }

    val validatorLimits: SpecValidator.Limits
        get() = CONTRACT_LIMITS.copy(maxSourceBytes = maxSourceBytes, maxTimeLimitMs = maxTimeLimitMs)

    private companion object {
        val CONTRACT_LIMITS = SpecValidator.Limits()
    }
}
