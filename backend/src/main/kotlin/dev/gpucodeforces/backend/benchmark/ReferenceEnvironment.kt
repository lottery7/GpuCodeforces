package dev.gpucodeforces.backend.benchmark

import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.submission.JudgeExecutorView
import dev.gpucodeforces.backend.submission.JudgeGateway
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Processors
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * Where the reference solutions of the formats are measured on one [processor]: results of a format are compared
 * only under the same [key]. On `gpu-cuda` the key covers the GPU model, its clocks and the toolchain, and a result
 * counts only from the GPUs it was taken on ([executors]: executor -> GPU indexes). An emulated processor gives the
 * same numbers on every executor and its versions are pinned, so its key is the processor itself (plus the mode).
 */
data class ReferenceEnvironmentSnapshot(
    val key: String,
    val processor: String,
    val model: String,
    val synthetic: Boolean,
    val toolchain: String,
    val executors: Map<String, Set<Int>>,
) {
    fun accepts(result: JudgeResult): Boolean {
        val run = result.processor ?: return false
        if (run.id != processor || run.version.startsWith("mock ") != synthetic || run.executorId !in executors) return false
        if (processor != Processors.GPU_CUDA) return true
        val info = result.gpu ?: return false
        return info.throttled != true && run.version == toolchain && info.name.trim() == model && executors[info.executorId]?.contains(info.index) == true
    }
}

/** Reference environment of every processor some live executor serves consistently; [gpuName] is the configured GPU. */
fun referenceEnvironments(
    views: List<JudgeExecutorView>, properties: JudgeProperties, gpuName: String, now: Instant,
): Map<String, ReferenceEnvironmentSnapshot> {
    val live = views.filter { it.alive && it.lastSeen.isAfter(now.minusSeconds(20)) }
    val processors = live.flatMap { view -> view.heartbeat.processors.map { it.id } }.toSet()
    return processors.mapNotNull { id ->
        (if (id == Processors.GPU_CUDA) gpuEnvironment(live, properties, gpuName) else emulatorEnvironment(id, live, properties))?.let { id to it }
    }.toMap()
}

private fun gpuEnvironment(live: List<JudgeExecutorView>, properties: JudgeProperties, gpuName: String): ReferenceEnvironmentSnapshot? {
    val eligible = live.mapNotNull { view -> view.heartbeat.processors.find { it.id == Processors.GPU_CUDA }?.let { view to it } }
        .filter { (view, _) -> view.heartbeat.gpus.any { slot -> slot.healthy } }
    if (eligible.isEmpty()) return null
    val models = eligible.flatMap { (view, _) -> view.heartbeat.gpus.filter { slot -> slot.healthy }.map { slot -> slot.name.trim() } }.toSet()
    val versions = eligible.map { (view, _) -> view.heartbeat.version.trim() }.toSet()
    val clocks = eligible.flatMap { (view, _) -> view.heartbeat.gpus.filter { slot -> slot.healthy }.map { slot -> slot.clocksLocked } }.toSet()
    val toolchains = eligible.map { (_, processor) -> processor.version }.toSet()
    if (models.size != 1 || versions.size != 1 || clocks.size != 1 || toolchains.size != 1 || models.single().isBlank() || versions.single().isBlank()) return null
    val toolchain = toolchains.single().takeIf { it.isNotBlank() } ?: return null
    val model = models.single()
    if ((model == MOCK_GPU) != properties.synthetic) return null
    if (toolchain.startsWith("mock ") != properties.synthetic) return null
    val key = key("reference-v2", properties.executionMode, Processors.GPU_CUDA, gpuName, model, versions.single(), toolchain, clocks.single().toString())
    return ReferenceEnvironmentSnapshot(
        key, Processors.GPU_CUDA, model, properties.synthetic, toolchain,
        eligible.associate { (view, _) -> view.heartbeat.executorId to view.heartbeat.gpus.filter { it.healthy }.map { it.index }.toSet() },
    )
}

private fun emulatorEnvironment(id: String, live: List<JudgeExecutorView>, properties: JudgeProperties): ReferenceEnvironmentSnapshot? {
    val eligible = live.mapNotNull { view -> view.heartbeat.processors.find { it.id == id && it.healthy }?.let { view to it } }
    if (eligible.isEmpty()) return null
    val toolchains = eligible.map { (_, processor) -> processor.version }.toSet()
    // a mock executor next to real ones of the same processor: no consistent numbers to measure with
    if (toolchains.any { it.startsWith("mock ") != properties.synthetic }) return null
    val (_, any) = eligible.first()
    return ReferenceEnvironmentSnapshot(
        key("reference-v2", properties.executionMode, id, properties.synthetic.toString()), id, any.model, properties.synthetic,
        toolchains.sorted().joinToString(" | "), eligible.associate { (view, _) -> view.heartbeat.executorId to emptySet() },
    )
}

private fun key(vararg parts: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray()))

private const val MOCK_GPU = "Mock GPU"

@Component
class ReferenceEnvironmentMonitor(private val gateway: JudgeGateway, private val properties: JudgeProperties, private val processors: ProcessorCatalog) {
    private var checkedAt: Instant = Instant.MIN
    private var current: Map<String, ReferenceEnvironmentSnapshot> = emptyMap()

    /** The environment of [processor] now, or null when no live executor serves it consistently. */
    fun snapshot(processor: String, force: Boolean = false): ReferenceEnvironmentSnapshot? = snapshots(force)[processor]

    @Synchronized
    fun snapshots(force: Boolean = false): Map<String, ReferenceEnvironmentSnapshot> {
        val now = Instant.now()
        if (force || Duration.between(checkedAt, now) >= Duration.ofSeconds(5)) {
            current = gateway.executors()?.let { referenceEnvironments(it, properties, processors.model(Processors.GPU_CUDA), now) }.orEmpty()
            checkedAt = now
        }
        return current
    }
}
