package gpucodeforces.judge.gateway.config

import gpucodeforces.judge.api.SpecValidator
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
    val maxSourceBytes: Int = 128 * 1024,
    val maxTests: Int = 200,
    val maxTimeLimitMs: Long = 30_000,
) {
    init {
        require(defaultGpuProfile in gpuProfiles) { "judge.gateway.default-gpu-profile must be one of gpu-profiles" }
        require(gpuProfiles.all { it.matches(Regex("[a-z0-9_-]+")) }) { "gpu profile names must match [a-z0-9_-]+" }
    }

    val validatorLimits: SpecValidator.Limits
        get() = SpecValidator.Limits(maxSourceBytes = maxSourceBytes, maxTests = maxTests, maxTimeLimitMs = maxTimeLimitMs)
}
