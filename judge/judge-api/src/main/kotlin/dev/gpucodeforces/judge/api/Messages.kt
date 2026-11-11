package dev.gpucodeforces.judge.api

import java.time.Instant

/** Body of `POST /api/v1/submissions`. */
data class SubmissionRequest(
    /** Backend-generated id; idempotency key. */
    val submissionId: String,
    val problem: ProblemSpec,
    /** Contents of the contestant's file, in [language]. */
    val source: String,
    /** Processor to judge on, from [Processors]; the problem has to declare it. */
    val processor: String,
    /** One of the languages the problem allows on [processor]. */
    val language: String,
)

/** Response of `POST /api/v1/submissions`. */
data class SubmissionAccepted(
    val submissionId: String,
    val processor: String,
    val language: String,
    val status: SubmissionStatus,
    val queuedAt: Instant,
    /** True if this submission id was already accepted recently and was not enqueued again. */
    val duplicate: Boolean = false,
    /** Id of the job that judges this submission; its events carry the same [JudgeEvent.jobId]. */
    val jobId: String,
)

/** Message published by the gateway to `judge.jobs.<processor>`. */
data class JudgeJob(
    val submissionId: String,
    val problem: ProblemSpec,
    val source: String,
    val processor: String,
    val language: String,
    val enqueuedAt: Instant,
    /**
     * Unique per accepted request (a UUID from the gateway). A submission id can end up in two jobs - a retry after
     * a publish timeout, a rejudge - and their events would otherwise be indistinguishable.
     */
    val jobId: String,
)

enum class SubmissionStatus {
    /** Accepted by the gateway, waiting for a free executor. */
    QUEUED,

    /** Executor compiles and statically checks the solution. */
    COMPILING,

    /** Executor generates tests and reference answers for a new problem version (cached afterwards). */
    PREPARING,

    /** Solution is running on its processor; see [JudgeEvent.progress]. */
    TESTING,

    /** Terminal; [JudgeEvent.result] is set. */
    FINISHED,
}

/**
 * Everything the backend receives from the judge, via queue `judge.events`.
 * [seq] grows within an attempt; a new [attempt] (executor crash, retry) restarts from COMPILING with seq 1.
 *
 * Delivery order is NOT guaranteed: events travel over different broker channels, and the gateway's QUEUED
 * (attempt 0) may arrive after an executor's first events. A consumer has to order events by `(attempt, seq)`,
 * never move a submission back once it has seen FINISHED, treat QUEUED as optional, and be idempotent: the same
 * event can be delivered twice. The identity of an event is `(jobId, attempt, seq)`.
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
    /** The job this event belongs to, see [JudgeJob.jobId]. */
    val jobId: String,
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

    /** Forbidden header, inline asm, forbidden SASS instruction or symbol, forbidden construct of the language. */
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
    /** Max score over measured tests; set on ACCEPTED only. */
    val score: Score? = null,
    /** Further numbers of the submission, by name (e.g. `cycles` and `cells` on `rtl`): those of the test that gave [score]. */
    val metrics: Map<String, Double> = emptyMap(),
    /** Set once a processor took the job; absent on results decided before that (invalid job, dead job). */
    val processor: ProcessorRun? = null,
    /** `gpu-cuda` only. */
    val timingMode: TimingMode? = null,
    /** `gpu-cuda` only. */
    val gpu: GpuInfo? = null,
    /** Total time spent by the judge on this attempt, ms. */
    val judgeTimeMs: Long = 0,
)

/** Which processor judged a submission. */
data class ProcessorRun(
    val id: String,
    /**
     * Pinned toolchain and emulator, e.g. "nvcc 12.8.93 (sm_86), g++ 13.3.0, torch 2.9.1". For logs and
     * diagnostics: versions are fixed per processor, so results do not depend on it.
     */
    val version: String,
    /** What to show a person: "NVIDIA GeForce RTX 3060", "Verilator 5.x + Yosys 0.x". */
    val model: String,
    val executorId: String,
    val executorVersion: String? = null,
)

data class TestResult(
    /** 1-based. */
    val index: Int,
    val name: String,
    val sample: Boolean,
    val verdict: Verdict,
    /**
     * Score of the test in the processor's type; on `gpu-cuda` the measured time per the timing mode (median of
     * runs if re-measured). Set for every test that ran to the end, measured or not.
     */
    val score: Score? = null,
    /** Whether [score] counts towards the submission's score and is checked against the limit. */
    val measured: Boolean = true,
    /** Further numbers of the test, by name. */
    val metrics: Map<String, Double> = emptyMap(),
    /** Host wall time of the run of this test, ms (on `gpu-cuda`: of the `solve` call). */
    val wallTimeMs: Double? = null,
    val runs: Int? = null,
    /** Safe to show to the contestant. For sample tests may include the first mismatch. */
    val message: String? = null,
    /**
     * Details beyond [message] (first mismatch, stderr), present on sample tests only. What a hidden test would
     * reveal - reference values, input data printed to stderr - never leaves the executor: it is in its log.
     */
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
    val clocksLocked: Boolean? = null,
)

/** Published by executors to `judge.heartbeats` every few seconds. */
data class ExecutorHeartbeat(
    val executorId: String,
    val hostname: String,
    /** Processors this executor judges, each consumed from its own queue. */
    val processors: List<ProcessorStatus>,
    val version: String,
    val startedAt: Instant,
    val timestamp: Instant,
    /** Empty on an executor without a GPU. */
    val gpus: List<GpuSlotStatus>,
    /** Jobs taken from the queue and not yet finished. */
    val inFlight: Int,
    /** Max jobs taken from the queue at once (prefetch). */
    val capacity: Int,
)

data class ProcessorStatus(
    val id: String,
    /** See [ProcessorRun.version]. */
    val version: String,
    /** See [ProcessorRun.model]. */
    val model: String,
    /** Can take a job now (on `gpu-cuda`: at least one GPU passed its health probe). */
    val healthy: Boolean,
    /** Every worker of the processor is judging. */
    val busy: Boolean,
    /** Jobs this executor takes from the processor's queue at once. */
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
