package dev.gpucodeforces.judge.executor.pipeline

import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.ProcessorStatus
import java.util.concurrent.atomic.AtomicInteger

enum class Disposition { ACK, REQUEUE }

/** What the job listener hands deliveries to: the real pipeline, or the mock judge of the `mock` profile. */
interface JobProcessor {
    /** Jobs taken from the queue and not finished yet (reported in heartbeats). */
    val inFlight: AtomicInteger

    /** Judges one delivery of a job; [attempt] is 1 for the first delivery. */
    fun process(job: JudgeJob, attempt: Int): Disposition

    /** Every processor this executor judges, for heartbeats. */
    fun processors(): List<ProcessorStatus>

    fun gpus(): List<GpuSlotStatus>

    /** Called with "can [processor] take jobs" whenever that changes; its queue is not consumed while it cannot. */
    fun onAvailabilityChange(processor: String, listener: (Boolean) -> Unit) {}
}
