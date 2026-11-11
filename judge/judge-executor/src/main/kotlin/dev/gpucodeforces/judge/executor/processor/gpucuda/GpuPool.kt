package dev.gpucodeforces.judge.executor.processor.gpucuda

import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.executor.config.GpuSlotConfig
import dev.gpucodeforces.judge.executor.processor.PoolSlot
import dev.gpucodeforces.judge.executor.processor.SlotPool
import dev.gpucodeforces.judge.executor.sandbox.GpuAccess
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

/** Exclusive GPUs: a solution or a problem preparation runs alone on its GPU so timings are not disturbed. */
typealias GpuPool = SlotPool<GpuSlot>

class GpuSlot(val config: GpuSlotConfig) : PoolSlot {
    val index: Int get() = config.index

    @Volatile var name: String = "GPU ${config.index}"

    /** Architecture the GPU itself reports (`sm_86`), known after the first successful probe. */
    @Volatile var arch: String? = null
    @Volatile override var healthy: Boolean = false
    @Volatile override var busyWith: String? = null
    @Volatile var clocksLocked: Boolean = false
    @Volatile var lastProbe: Instant = Instant.EPOCH
    @Volatile override var unhealthyReason: String? = null
    var monitor: GpuMonitor? = null

    /** Serialises health probes of this GPU without making other GPUs wait. */
    val probeLock = ReentrantLock()

    fun status() = GpuSlotStatus(index, name, healthy = healthy, busy = busyWith != null, submissionId = busyWith, clocksLocked = clocksLocked)

    fun access() = GpuAccess(index, config.deviceNode)

    override fun toString() = "gpu$index($name)"
}

/** `PATH` of every sandbox of `gpu-cuda`: nvcc and the CUDA tools come first, as they always did. */
val CUDA_PATH = listOf("/usr/local/cuda/bin") + SandboxSpec.DEFAULT_PATH
