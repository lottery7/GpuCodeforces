package gpucodeforces.judge.api

import java.time.Instant

/** Body of `POST /api/v1/submissions`. */
data class SubmissionRequest(
    /** Backend-generated id; idempotency key. */
    val submissionId: String,
    val problem: ProblemSpec,
    /** Contents of the contestant's `.cu` file. */
    val source: String,
    /** GPU profile to judge on (e.g. `rtx3060`). Null = gateway default. */
    val gpuProfile: String? = null,
)

/** Response of `POST /api/v1/submissions`. */
data class SubmissionAccepted(
    val submissionId: String,
    val gpuProfile: String,
    val status: SubmissionStatus,
    val queuedAt: Instant,
    /** True if this submission id was already accepted recently and was not enqueued again. */
    val duplicate: Boolean = false,
)

/** Message published by the gateway to `judge.jobs.<gpuProfile>`. */
data class JudgeJob(
    val submissionId: String,
    val problem: ProblemSpec,
    val source: String,
    val gpuProfile: String,
    val enqueuedAt: Instant,
)

enum class SubmissionStatus {
    /** Accepted by the gateway, waiting for a free executor. */
    QUEUED,

    /** Executor compiles and statically checks the solution. */
    COMPILING,

    /** Executor generates tests and reference answers for a new problem version (cached afterwards). */
    PREPARING,

    /** Solution is running on the GPU; see [JudgeEvent.progress]. */
    TESTING,

    /** Terminal; [JudgeEvent.result] is set. */
    FINISHED,
}

/**
 * Everything the backend receives from the judge, via queue `judge.events`.
 * Events of one attempt are published in order; [seq] grows within an attempt.
 * A new [attempt] (executor crash, retry) restarts from COMPILING with seq 1.
 */
data class JudgeEvent(
    val submissionId: String,
    val attempt: Int,
    val seq: Int,
    val status: SubmissionStatus,
    val timestamp: Instant,
    /** `gateway` or executor id. */
    val source: String,
    val progress: Progress? = null,
    val result: JudgeResult? = null,
)

data class Progress(
    /** 1-based index of the test currently running (or last finished). */
    val currentTest: Int,
    val totalTests: Int,
)

enum class Verdict {
    ACCEPTED,
    WRONG_ANSWER,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    RUNTIME_ERROR,
    COMPILATION_ERROR,

    /** Forbidden header, inline asm, forbidden SASS instruction or symbol. */
    POLICY_VIOLATION,

    /** Judge failure, not the contestant's fault. Safe to rejudge. */
    INTERNAL_ERROR,
    CANCELLED,
}

data class JudgeResult(
    val verdict: Verdict,
    /** 1-based index of the first failed test, null if none failed or failure happened before testing. */
    val failedTest: Int? = null,
    /** Short human-readable explanation, safe to show to the contestant. */
    val message: String? = null,
    /** Compiler output (sanitized), present on COMPILATION_ERROR and warnings. */
    val compileLog: String? = null,
    /** Results of executed tests in order, up to and including the failed one. */
    val tests: List<TestResult> = emptyList(),
    /** Max measured time over passed tests, ms. */
    val maxTimeMs: Double? = null,
    val timingMode: TimingMode? = null,
    val gpu: GpuInfo? = null,
    /** Total time spent by the judge on this attempt, ms. */
    val judgeTimeMs: Long = 0,
    /** Pinned toolchain used, e.g. "nvcc 12.8.93 (sm_86), g++ 13.3.0, torch 2.8.0+cu128". */
    val toolchain: String? = null,
)

data class TestResult(
    /** 1-based. */
    val index: Int,
    val name: String,
    val sample: Boolean,
    val verdict: Verdict,
    /** Measured time per the timing mode (median of runs if re-measured), ms. */
    val timeMs: Double? = null,
    /** Wall time of the `solve` call, ms. */
    val wallTimeMs: Double? = null,
    val runs: Int? = null,
    /** Safe to show to the contestant. For sample tests may include the first mismatch. */
    val message: String? = null,
    /** Judges-only details (mismatch values of hidden tests, stderr of hidden tests). Never show to contestants. */
    val debug: String? = null,
)

data class GpuInfo(
    val profile: String,
    val name: String,
    val index: Int,
    val executorId: String,
    /** Telemetry sampled during the run; lets admins discard noisy measurements. */
    val maxTemperatureC: Int? = null,
    val minSmClockMhz: Int? = null,
    val maxSmClockMhz: Int? = null,
    val throttled: Boolean? = null,
)

/** Published by executors to `judge.heartbeats` every few seconds. */
data class ExecutorHeartbeat(
    val executorId: String,
    val hostname: String,
    val gpuProfile: String,
    val version: String,
    val startedAt: Instant,
    val timestamp: Instant,
    val gpus: List<GpuSlotStatus>,
    /** Jobs taken from the queue and not yet finished. */
    val inFlight: Int,
    /** Max jobs taken from the queue at once (prefetch). */
    val capacity: Int,
)

data class GpuSlotStatus(
    val index: Int,
    val name: String,
    val healthy: Boolean,
    val busy: Boolean,
    val submissionId: String? = null,
    val clocksLocked: Boolean = false,
)

enum class ControlType { CANCEL }

/** Published by the gateway to `judge.control` (fanout to all executors). */
data class ControlMessage(
    val type: ControlType,
    val submissionId: String,
    val timestamp: Instant,
)
