package dev.gpucodeforces.judge.api

/** RabbitMQ names shared by the gateway, executors and the backend. */
object Topology {
    /** Direct exchange; routing key = GPU profile. */
    const val JOBS_EXCHANGE = "judge.jobs"

    /** Quorum queue per GPU profile, consumed by executors of that profile (competing consumers). */
    fun jobsQueue(gpuProfile: String) = "judge.jobs.$gpuProfile"

    /** Jobs rejected too many times (poison messages) are dead-lettered here; the gateway reports them as INTERNAL_ERROR. */
    const val JOBS_DLX = "judge.jobs.dlx"
    const val JOBS_DEAD_QUEUE = "judge.jobs.dead"

    /** Max deliveries of one job (executor crashes / internal errors) before it is dead-lettered. */
    const val JOB_DELIVERY_LIMIT = 5

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
