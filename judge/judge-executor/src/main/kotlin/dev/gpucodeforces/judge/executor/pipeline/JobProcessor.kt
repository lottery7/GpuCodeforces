package dev.gpucodeforces.judge.executor.pipeline

import dev.gpucodeforces.judge.api.JudgeJob
import java.util.concurrent.atomic.AtomicInteger

enum class Disposition { ACK, REQUEUE }

/** What the job listener hands deliveries to: the real pipeline, or the mock judge of the `mock` profile. */
interface JobProcessor {
    /** Jobs taken from the queue and not finished yet (reported in heartbeats). */
    val inFlight: AtomicInteger

    /** Judges one delivery of a job; [attempt] is 1 for the first delivery. */
    fun process(job: JudgeJob, attempt: Int): Disposition
}
