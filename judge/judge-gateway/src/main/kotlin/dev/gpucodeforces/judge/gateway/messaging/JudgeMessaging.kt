package dev.gpucodeforces.judge.gateway.messaging

import dev.gpucodeforces.judge.api.ControlMessage
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.Topology
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** Thin JSON layer over RabbitTemplate. Messages are serialized explicitly so the wire format is obvious. */
@Component
class JudgeMessaging(
    private val rabbit: RabbitTemplate,
    private val json: JsonMapper,
) {
    /** Publishes a job and waits for the broker confirm, so a 202 means the job is persisted. */
    fun publishJob(job: JudgeJob, confirmTimeout: Duration) {
        val message = message(job, job.submissionId, persistent = true)
        rabbit.invoke { ops ->
            ops.send(Topology.JOBS_EXCHANGE, job.gpuProfile, message)
            ops.waitForConfirmsOrDie(confirmTimeout.toMillis())
        }
    }

    fun publishEvent(event: JudgeEvent) {
        rabbit.send(Topology.EVENTS_EXCHANGE, Topology.eventRoutingKey(event.status), message(event, event.submissionId, persistent = true))
    }

    fun publishControl(control: ControlMessage) {
        rabbit.send(Topology.CONTROL_EXCHANGE, "", message(control, control.submissionId, persistent = false))
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
