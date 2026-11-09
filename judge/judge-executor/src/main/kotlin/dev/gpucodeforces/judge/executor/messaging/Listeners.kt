package dev.gpucodeforces.judge.executor.messaging

import com.rabbitmq.client.Channel
import dev.gpucodeforces.judge.api.ControlMessage
import dev.gpucodeforces.judge.api.ControlType
import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.config.REAL
import dev.gpucodeforces.judge.executor.gpu.GpuManager
import dev.gpucodeforces.judge.executor.pipeline.Disposition
import dev.gpucodeforces.judge.executor.pipeline.JobProcessor
import java.net.InetAddress
import java.time.Clock
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.Exchange
import org.springframework.amqp.rabbit.annotation.Queue
import org.springframework.amqp.rabbit.annotation.QueueBinding
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException

@Component
class JobListener(
    private val pipeline: JobProcessor,
    private val publisher: EventPublisher,
    private val props: ExecutorProperties,
) : ChannelAwareMessageListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessage(message: Message, channel: Channel?) {
        val tag = message.messageProperties.deliveryTag
        val job = try {
            publisher.read(message, JudgeJob::class.java)
        } catch (e: JacksonException) {
            log.error("Unreadable job dropped to the dead-letter queue: messageId={}", message.messageProperties.messageId, e)
            channel!!.basicReject(tag, false)
            return
        }
        val previousDeliveries = (message.messageProperties.headers[Topology.DELIVERY_COUNT_HEADER] as? Number)?.toInt()
            ?: if (message.messageProperties.isRedelivered == true) 1 else 0
        val attempt = previousDeliveries + 1
        MDC.put("submissionId", job.submissionId)
        MDC.put("attempt", attempt.toString())
        MDC.put("problem", job.problem.id)
        MDC.put("executor", props.id)
        try {
            when (pipeline.process(job, attempt)) {
                Disposition.ACK -> channel!!.basicAck(tag, false)
                Disposition.REQUEUE -> channel!!.basicNack(tag, false, true)
            }
        } catch (e: Exception) {
            // e.g. the final event could not be confirmed: let the job be redelivered rather than lose the verdict
            log.error("Job processing aborted, returning it to the queue", e)
            channel!!.basicNack(tag, false, true)
        } finally {
            MDC.clear()
        }
    }
}

@Component
class ControlListener(private val cancels: CancelRegistry, private val publisher: EventPublisher) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RabbitListener(
        bindings = [QueueBinding(
            value = Queue(durable = "false", exclusive = "true", autoDelete = "true"),
            exchange = Exchange(value = Topology.CONTROL_EXCHANGE, type = "fanout", durable = "false"),
        )],
    )
    fun onControl(message: Message) {
        val control = try {
            publisher.read(message, ControlMessage::class.java)
        } catch (e: JacksonException) {
            log.warn("Malformed control message ignored", e)
            return
        }
        if (control.type == ControlType.CANCEL) {
            cancels.cancel(control.submissionId)
            log.info("Cancel received for submission {}", control.submissionId)
        }
    }
}

@Component
class HeartbeatPublisher(
    private val props: ExecutorProperties,
    private val gpus: GpuManager,
    private val pipeline: JobProcessor,
    private val publisher: EventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val startedAt = clock.instant()
    private val hostname = InetAddress.getLocalHost().hostName
    private val version = javaClass.`package`?.implementationVersion ?: "dev"
    @Volatile private var failing = false

    @Scheduled(fixedDelayString = "\${judge.executor.heartbeat-interval:5s}")
    fun beat() {
        try {
            publisher.publishHeartbeat(
                ExecutorHeartbeat(
                    executorId = props.id,
                    hostname = hostname,
                    gpuProfile = props.gpuProfile,
                    version = version,
                    startedAt = startedAt,
                    timestamp = clock.instant(),
                    gpus = gpus.pool.slots.map { it.status() },
                    inFlight = pipeline.inFlight.get(),
                    capacity = props.capacity,
                ),
            )
            if (failing) log.info("Heartbeats are being delivered again")
            failing = false
        } catch (e: AmqpException) {
            if (!failing) log.error("Cannot publish heartbeat (broker down?)", e)
            failing = true
        }
    }
}

/** Probes the GPUs between jobs. Absent in the mock profile, where there is nothing to probe. */
@Component
@Profile(REAL)
class GpuHealthChecker(private val gpus: GpuManager) {
    @Scheduled(fixedDelayString = "\${judge.executor.health-check-interval:10s}")
    fun healthCheck() {
        gpus.periodicCheck()
    }
}
