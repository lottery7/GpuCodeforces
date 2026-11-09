package gpucodeforces.judge.executor.gpu

import gpucodeforces.judge.api.GpuSlotStatus
import gpucodeforces.judge.executor.config.GpuSlotConfig
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class GpuSlot(val config: GpuSlotConfig) {
    val index: Int get() = config.index

    @Volatile var name: String = "GPU ${config.index}"
    @Volatile var healthy: Boolean = false
    @Volatile var busyWith: String? = null
    @Volatile var clocksLocked: Boolean = false
    @Volatile var lastProbe: Instant = Instant.EPOCH
    @Volatile var unhealthyReason: String? = null
    var monitor: GpuMonitor? = null

    fun status() = GpuSlotStatus(index, name, healthy, busyWith != null, busyWith, clocksLocked)

    override fun toString() = "gpu$index($name)"
}

class NoHealthyGpuException(msg: String) : RuntimeException(msg)

/**
 * Exclusive GPU slots: a solution (or problem preparation) runs alone on its GPU so timings are not disturbed.
 * Only healthy, idle slots are in [free].
 */
class GpuPool(val slots: List<GpuSlot>) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val free = LinkedBlockingDeque<GpuSlot>()

    /** Called with the number of healthy GPUs whenever it changes between zero and non-zero. */
    @Volatile var onAvailabilityChange: (Boolean) -> Unit = {}

    fun healthyCount() = slots.count { it.healthy }

    /**
     * Waits for a free GPU. Throws [NoHealthyGpuException] if no GPU has been healthy for [noHealthyTimeout]
     * (the job is then returned to the queue for another executor), and [InterruptedException] if [cancelled].
     */
    fun acquire(owner: String, noHealthyTimeout: Duration, cancelled: () -> Boolean): GpuSlot {
        var noHealthySince: Long? = null
        while (true) {
            val slot = free.pollFirst(200, TimeUnit.MILLISECONDS)
            if (slot != null) {
                if (!slot.healthy) continue
                slot.busyWith = owner
                return slot
            }
            if (cancelled()) throw InterruptedException("cancelled while waiting for a GPU")
            if (healthyCount() == 0) {
                val since = noHealthySince ?: System.nanoTime().also { noHealthySince = it }
                if (System.nanoTime() - since > noHealthyTimeout.toNanos()) {
                    throw NoHealthyGpuException("no healthy GPU for ${noHealthyTimeout.seconds}s")
                }
            } else {
                noHealthySince = null
            }
        }
    }

    /** Non-blocking acquire used by the idle health checker. */
    fun tryAcquire(slot: GpuSlot, owner: String): Boolean {
        if (!free.remove(slot)) return false
        slot.busyWith = owner
        return true
    }

    fun release(slot: GpuSlot) {
        slot.busyWith = null
        if (slot.healthy) free.offerLast(slot)
    }

    fun markUnhealthy(slot: GpuSlot, reason: String) {
        val wasAny = healthyCount() > 0
        if (slot.healthy) log.error("{} marked UNHEALTHY: {}", slot, reason)
        slot.healthy = false
        slot.unhealthyReason = reason
        free.remove(slot)
        if (wasAny && healthyCount() == 0) {
            log.error("No healthy GPUs left on this executor, pausing job consumption")
            onAvailabilityChange(false)
        }
    }

    /** Marks a non-busy slot healthy and makes it available. */
    fun markHealthy(slot: GpuSlot) {
        val wasNone = healthyCount() == 0
        if (!slot.healthy) log.info("{} is healthy", slot)
        slot.healthy = true
        slot.unhealthyReason = null
        if (slot.busyWith == null && !free.contains(slot)) free.offerLast(slot)
        if (wasNone) onAvailabilityChange(true)
    }
}
