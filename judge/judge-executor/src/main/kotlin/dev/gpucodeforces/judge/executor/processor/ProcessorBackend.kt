package dev.gpucodeforces.judge.executor.processor

import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.SubmissionStatus
import java.nio.file.Path

/** What a processor needs from the pipeline while it judges one job. */
interface JudgeHooks {
    fun status(status: SubmissionStatus)

    /** Test [current] (1-based, 0 = not started) of [total] is running. */
    fun progress(current: Int, total: Int)

    fun cancelled(): Boolean
}

/**
 * One processor this executor judges on: `gpu-cuda`, or an emulated one. The pipeline validates the job, owns the
 * run directory, retries, cancellation before the start, metrics and the final event; an implementation compiles,
 * prepares the tests it needs and runs them.
 *
 * Failures that are not the contestant's are exceptions: [ProcessorUnavailableException] returns the job to the
 * queue for another executor, [JudgeInfrastructureException] is retried a few times. An INTERNAL_ERROR result is
 * final; its run directory is kept for investigation.
 */
interface ProcessorBackend : AutoCloseable {
    val info: ProcessorInfo

    /** Pinned toolchain and emulator versions, for logs ([dev.gpucodeforces.judge.api.ProcessorRun.version]). */
    val version: String

    /** What to show a person, e.g. the GPU's name. */
    val model: String

    /** Jobs this processor takes from its queue at once. */
    val capacity: Int

    /** Checks the toolchain at startup; an exception stops the executor. */
    fun verify()

    fun status(): ProcessorStatus

    /** GPUs this processor uses, for heartbeats. */
    fun gpus(): List<GpuSlotStatus> = emptyList()

    /**
     * Judges [job] in [runDir], a fresh directory the pipeline deletes afterwards. Leaves [JudgeResult.processor]
     * and [JudgeResult.judgeTimeMs] to the pipeline.
     */
    fun judge(job: JudgeJob, runDir: Path, hooks: JudgeHooks): JudgeResult

    /** Called with "can this processor take jobs" whenever that changes. */
    fun onAvailabilityChange(listener: (Boolean) -> Unit) {}

    /** Periodic health checks between jobs. */
    fun maintenance() {}

    override fun close() {}
}

/** The processor cannot judge anything right now (no healthy GPU): the job goes back to the queue. */
open class ProcessorUnavailableException(msg: String) : RuntimeException(msg)

/** A failure of the judge itself (build infrastructure, a crashed tool) that another attempt may not hit. */
open class JudgeInfrastructureException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)
