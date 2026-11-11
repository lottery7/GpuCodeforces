package dev.gpucodeforces.judge.api

/** RabbitMQ names shared by the gateway, executors and the backend. */
object Topology {
    /** Direct exchange; routing key = processor id. */
    const val JOBS_EXCHANGE = "judge.jobs"

    /** Quorum queue per processor, consumed by executors of that processor (competing consumers). */
    fun jobsQueue(processor: String) = "judge.jobs.$processor"

    /**
     * What a processor id may look like. It ends up in a queue name; every id in [Processors] follows it.
     */
    const val PROFILE_NAME = "[a-z0-9_-]+"

    fun isProfileName(name: String): Boolean = Regex(PROFILE_NAME).matches(name)

    /** Jobs rejected too many times (poison messages) are dead-lettered here; the gateway reports them as INTERNAL_ERROR. */
    const val JOBS_DLX = "judge.jobs.dlx"
    const val JOBS_DEAD_QUEUE = "judge.jobs.dead"

    /** Max deliveries of one job (executor crashes / internal errors) before it is dead-lettered. */
    const val JOB_DELIVERY_LIMIT = 5

    /**
     * `x-delivery-limit` of the events queue and of the dead-jobs queue. Quorum queues otherwise drop a message
     * after 20 redeliveries, and here that message is somebody's verdict: a consumer that keeps failing (the
     * backend's database is down) has to find it still there when it recovers.
     */
    const val NEVER_DROP = -1

    /**
     * [JudgeEvent.attempt] of the FINISHED event the gateway publishes for a dead-lettered job. Far above any
     * executor attempt, so its `(jobId, attempt, seq)` cannot collide with an executor's event.
     */
    const val DEAD_JOB_ATTEMPT = 1_000_000

    /** Topic exchange; routing key `submission.<status>` (lower case). */
    const val EVENTS_EXCHANGE = "judge.events"

    /** Durable queue the backend consumes. Bound to [EVENTS_EXCHANGE] with `#`. */
    const val EVENTS_QUEUE = "judge.events"

    fun eventRoutingKey(status: SubmissionStatus) = "submission.${status.name.lowercase()}"

    /** Fanout exchange for [ExecutorHeartbeat]. */
    const val HEARTBEATS_EXCHANGE = "judge.heartbeats"

    /** Fanout exchange for [ControlMessage]. */
    const val CONTROL_EXCHANGE = "judge.control"

    /** Header with the number of previous deliveries, set by RabbitMQ on quorum queues. */
    const val DELIVERY_COUNT_HEADER = "x-delivery-count"
}
