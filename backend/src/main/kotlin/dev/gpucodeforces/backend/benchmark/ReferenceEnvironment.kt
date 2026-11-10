package dev.gpucodeforces.backend.benchmark

import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.submission.JudgeExecutorView
import dev.gpucodeforces.backend.submission.JudgeGateway
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.judge.api.JudgeResult
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

data class ReferenceEnvironmentSnapshot(
    val key: String,
    val profile: String,
    val model: String,
    val synthetic: Boolean,
    val toolchain: String,
    val executors: Map<String, Set<Int>>,
) {
    fun accepts(result: JudgeResult): Boolean {
        val info = result.gpu ?: return false
        val mock = result.toolchain?.startsWith("mock ") == true
        return info.throttled != true && result.toolchain == toolchain && mock == synthetic && info.profile == profile && info.name.trim() == model &&
            executors[info.executorId]?.contains(info.index) == true
    }
}

fun referenceEnvironment(
    views: List<JudgeExecutorView>, properties: JudgeProperties, gpu: GpuProperties, now: Instant,
): ReferenceEnvironmentSnapshot? {
    val eligible = views.filter { it.alive && it.lastSeen.isAfter(now.minusSeconds(20)) && it.heartbeat.gpuProfile == gpu.profile }
        .filter { it.heartbeat.gpus.any { slot -> slot.healthy } }
    if (eligible.isEmpty()) return null
    val models = eligible.flatMap { it.heartbeat.gpus.filter { slot -> slot.healthy }.map { slot -> slot.name.trim() } }.toSet()
    val versions = eligible.map { it.heartbeat.version.trim() }.toSet()
    val clocks = eligible.flatMap { it.heartbeat.gpus.filter { slot -> slot.healthy }.map { slot -> slot.clocksLocked } }.toSet()
    val toolchains = eligible.map { it.heartbeat.toolchain }.toSet()
    if (models.size != 1 || versions.size != 1 || clocks.size != 1 || toolchains.size != 1 || models.single().isBlank() || versions.single().isBlank()) return null
    val toolchain = toolchains.single()?.takeIf { it.isNotBlank() } ?: return null
    val model = models.single()
    if ((model == "Mock GPU") != properties.synthetic) return null
    if (toolchain.startsWith("mock ") != properties.synthetic) return null
    val identity = listOf("reference-v1", properties.executionMode, gpu.profile, gpu.name, model, versions.single(), toolchain, clocks.single().toString()).joinToString("\u0000")
    val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
    return ReferenceEnvironmentSnapshot(
        key, gpu.profile, model, properties.synthetic, toolchain,
        eligible.associate { view -> view.heartbeat.executorId to view.heartbeat.gpus.filter { it.healthy }.map { it.index }.toSet() },
    )
}

@Component
class ReferenceEnvironmentMonitor(private val gateway: JudgeGateway, private val properties: JudgeProperties, private val gpu: GpuProperties) {
    private var checkedAt: Instant = Instant.MIN
    private var current: ReferenceEnvironmentSnapshot? = null

    @Synchronized
    fun snapshot(force: Boolean = false): ReferenceEnvironmentSnapshot? {
        val now = Instant.now()
        if (force || Duration.between(checkedAt, now) >= Duration.ofSeconds(5)) {
            current = gateway.executors()?.let { referenceEnvironment(it, properties, gpu, now) }
            checkedAt = now
        }
        return current
    }
}
