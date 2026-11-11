package dev.gpucodeforces.judge.executor.processor

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** One place a job runs alone: a GPU, or a core given to an emulator. */
interface PoolSlot {
    var healthy: Boolean
    var busyWith: String?
    var unhealthyReason: String?
}

/** A worker of an emulated processor: one single-threaded simulator at a time. Never unhealthy. */
class WorkerSlot(val index: Int) : PoolSlot {
    @Volatile override var healthy: Boolean = false
    @Volatile override var busyWith: String? = null
    @Volatile override var unhealthyReason: String? = null

    override fun toString() = "worker$index"
}

class NoHealthySlotException(msg: String) : ProcessorUnavailableException(msg)

/**
 * Exclusive slots: a solution (or problem preparation) runs alone in its slot, so measurements are not disturbed.
 * Only healthy, idle slots are in [free].
 */
class SlotPool<S : PoolSlot>(val slots: List<S>) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val free = LinkedBlockingDeque<S>()

    /** Called with "is any slot healthy" whenever that changes. */
    @Volatile var onAvailabilityChange: (Boolean) -> Unit = {}

    fun healthyCount() = slots.count { it.healthy }

    /**
     * Waits for a free slot. Throws [NoHealthySlotException] if no slot has been healthy for [noHealthyTimeout]
     * (the job is then returned to the queue for another executor), and [InterruptedException] if [cancelled].
     */
    fun acquire(owner: String, noHealthyTimeout: Duration, cancelled: () -> Boolean): S {
        var noHealthySince: Long? = null
        while (true) {
            val slot = free.pollFirst(200, TimeUnit.MILLISECONDS)
            if (slot != null) {
                if (!slot.healthy) continue
                slot.busyWith = owner
                return slot
            }
            if (cancelled()) throw InterruptedException("cancelled while waiting for a slot")
            if (healthyCount() == 0) {
                val since = noHealthySince ?: System.nanoTime().also { noHealthySince = it }
                if (System.nanoTime() - since > noHealthyTimeout.toNanos()) {
                    throw NoHealthySlotException("no healthy slot for ${noHealthyTimeout.seconds}s")
                }
            } else {
                noHealthySince = null
            }
        }
    }

    /** Non-blocking acquire used by the idle health checker. */
    fun tryAcquire(slot: S, owner: String): Boolean {
        if (!free.remove(slot)) return false
        slot.busyWith = owner
        return true
    }

    fun release(slot: S) {
        slot.busyWith = null
        if (slot.healthy) free.offerLast(slot)
    }

    fun markUnhealthy(slot: S, reason: String) {
        val wasAny = healthyCount() > 0
        if (slot.healthy) log.error("{} marked UNHEALTHY: {}", slot, reason)
        slot.healthy = false
        slot.unhealthyReason = reason
        free.remove(slot)
        if (wasAny && healthyCount() == 0) onAvailabilityChange(false)
    }

    /** Marks a non-busy slot healthy and makes it available. */
    fun markHealthy(slot: S) {
        val wasNone = healthyCount() == 0
        if (!slot.healthy) log.info("{} is healthy", slot)
        slot.healthy = true
        slot.unhealthyReason = null
        if (slot.busyWith == null && !free.contains(slot)) free.offerLast(slot)
        if (wasNone) onAvailabilityChange(true)
    }

    companion object {
        /** [count] workers, all healthy from the start. */
        fun workers(count: Int): SlotPool<WorkerSlot> =
            SlotPool(List(count) { WorkerSlot(it) }).also { pool -> pool.slots.forEach(pool::markHealthy) }
    }
}
