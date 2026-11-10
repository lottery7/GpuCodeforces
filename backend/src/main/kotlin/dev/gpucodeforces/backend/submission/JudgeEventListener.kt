package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Topology
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/** The events part of the judge topology, declared exactly as the gateway does, so the backend can start first. */
@Configuration
class JudgeEventsTopology {
    @Bean
    fun judgeEventsDeclarables(): Declarables {
        val exchange = TopicExchange(Topology.EVENTS_EXCHANGE, true, false)
        val queue = QueueBuilder.durable(Topology.EVENTS_QUEUE).quorum().deliveryLimit(Topology.NEVER_DROP).build()
        return Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with("#"))
    }
}

/**
 * Consumes verdicts and progress from the judge. A failure to store an event (database down) requeues it: the
 * queue never drops a message, and processing is idempotent.
 */
@Component
class JudgeEventListener(
    private val submissionService: SubmissionService,
    private val exampleChecks: ExampleChecks,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RabbitListener(queues = [Topology.EVENTS_QUEUE])
    fun onEvent(message: Message) {
        val event = try {
            jsonMapper.readValue(message.body, JudgeEvent::class.java)
        } catch (exception: JacksonException) {
            log.error("Dropping malformed judge event: {}", exception.originalMessage)
            throw AmqpRejectAndDontRequeueException("Malformed judge event", exception)
        }
        try {
            if (exampleChecks.isExample(event.submissionId)) {
                if (event.status == SubmissionStatus.FINISHED) exampleChecks.complete(event.submissionId, event.result!!)
            } else {
                submissionService.apply(event)
            }
        } catch (exception: Exception) {
            // The broker redelivers the event; the log says which one keeps failing.
            log.error("Failed to apply judge event {} a{}/#{} of {} (job {}), it will be redelivered",
                event.status, event.attempt, event.seq, event.submissionId, event.jobId, exception)
            throw exception
        }
    }
}
