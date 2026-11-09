package dev.gpucodeforces.judge.executor.messaging

import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Progress
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Topology
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import tools.jackson.databind.json.JsonMapper

class EventPublisher(
    private val rabbit: RabbitTemplate,
    private val json: JsonMapper,
) {
    /** Final results wait for the broker confirm: the job is acked only after its result is persisted. */
    fun publish(event: JudgeEvent, confirm: Boolean) {
        val msg = message(event, event.submissionId, persistent = true)
        val key = Topology.eventRoutingKey(event.status)
        if (confirm) {
            rabbit.invoke { ops ->
                ops.send(Topology.EVENTS_EXCHANGE, key, msg)
                ops.waitForConfirmsOrDie(10_000)
            }
        } else {
            rabbit.send(Topology.EVENTS_EXCHANGE, key, msg)
        }
    }

    fun publishHeartbeat(hb: ExecutorHeartbeat) {
        rabbit.send(Topology.HEARTBEATS_EXCHANGE, "", message(hb, hb.executorId, persistent = false))
    }

    fun <T : Any> read(message: Message, type: Class<T>): T = json.readValue(message.body, type)

    private fun message(payload: Any, id: String, persistent: Boolean): Message {
        val props = MessageProperties().apply {
            contentType = MessageProperties.CONTENT_TYPE_JSON
            contentEncoding = "utf-8"
            messageId = id
            deliveryMode = if (persistent) MessageDeliveryMode.PERSISTENT else MessageDeliveryMode.NON_PERSISTENT
        }
        return Message(json.writeValueAsBytes(payload), props)
    }
}

/** Publishes the events of one judging attempt in order; progress updates are throttled. */
class JobReporter(
    private val submissionId: String,
    private val attempt: Int,
    private val source: String,
    private val publisher: EventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var seq = 0
    private var lastProgressAt = 0L
    private var lastStatus: SubmissionStatus? = null

    @Synchronized
    fun status(status: SubmissionStatus) {
        send(status, progress = null)
    }

    /** Test [current] (1-based, 0 = not started) of [total] is running. */
    @Synchronized
    fun progress(current: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (current != 0 && current != total && lastStatus == SubmissionStatus.TESTING && now - lastProgressAt < MIN_PROGRESS_INTERVAL_MS) return
        lastProgressAt = now
        send(SubmissionStatus.TESTING, Progress(current, total))
    }

    /** Must succeed: throws if the broker does not confirm, so the job is redelivered instead of losing the verdict. */
    @Synchronized
    fun finish(result: JudgeResult) {
        publisher.publish(event(SubmissionStatus.FINISHED).copy(result = result), confirm = true)
        lastStatus = SubmissionStatus.FINISHED
    }

    private fun event(status: SubmissionStatus) =
        JudgeEvent(submissionId, attempt = attempt, seq = ++seq, status, clock.instant(), source = source)

    private fun send(status: SubmissionStatus, progress: Progress?) {
        val event = event(status).copy(progress = progress)
        try {
            publisher.publish(event, confirm = false)
            lastStatus = status
        } catch (e: AmqpException) {
            // progress is best effort; the final event is published with confirmation in finish()
            log.warn("Failed to publish {} event (continuing)", status, e)
        }
    }

    private companion object {
        const val MIN_PROGRESS_INTERVAL_MS = 250L
    }
}

/** Submissions cancelled via `judge.control`; remembered for an hour (the job may still be in the queue). */
class CancelRegistry(private val clock: Clock) {
    private val cancelled = ConcurrentHashMap<String, Long>()

    fun cancel(submissionId: String) {
        val now = clock.millis()
        cancelled[submissionId] = now + 3_600_000
        cancelled.entries.removeIf { it.value < now }
    }

    fun isCancelled(submissionId: String): Boolean = (cancelled[submissionId] ?: 0) > clock.millis()
}
