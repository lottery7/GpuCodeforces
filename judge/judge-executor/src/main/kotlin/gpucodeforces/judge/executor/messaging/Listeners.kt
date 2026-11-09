package gpucodeforces.judge.executor.messaging

import com.rabbitmq.client.Channel
import gpucodeforces.judge.api.ControlMessage
import gpucodeforces.judge.api.ControlType
import gpucodeforces.judge.api.ExecutorHeartbeat
import gpucodeforces.judge.api.JudgeJob
import gpucodeforces.judge.api.Topology
import gpucodeforces.judge.executor.config.ExecutorProperties
import gpucodeforces.judge.executor.gpu.GpuManager
import gpucodeforces.judge.executor.pipeline.Disposition
import gpucodeforces.judge.executor.pipeline.JudgePipeline
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.Exchange
import org.springframework.amqp.rabbit.annotation.Queue
import org.springframework.amqp.rabbit.annotation.QueueBinding
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Clock

@Component
class JobListener(
    private val pipeline: JudgePipeline,
    private val publisher: EventPublisher,
    private val props: ExecutorProperties,
) : ChannelAwareMessageListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessage(message: Message, channel: Channel?) {
        val tag = message.messageProperties.deliveryTag
        val job = try {
            publisher.read(message, JudgeJob::class.java)
        } catch (e: RuntimeException) {
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
        } catch (e: Throwable) {
            // e.g. the final event could not be confirmed: let the job be redelivered rather than lose the verdict
            log.error("Job processing aborted, returning it to the queue", e)
            runCatching { channel!!.basicNack(tag, false, true) }.onFailure { log.error("nack failed", it) }
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
        val control = runCatching { publisher.read(message, ControlMessage::class.java) }.getOrElse {
            log.warn("Malformed control message ignored", it)
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
    private val pipeline: JudgePipeline,
    private val publisher: EventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val startedAt = clock.instant()
    private val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault(props.id)
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
        } catch (e: RuntimeException) {
            if (!failing) log.error("Cannot publish heartbeat (broker down?)", e)
            failing = true
        }
    }

    @Scheduled(fixedDelayString = "\${judge.executor.health-check-interval:10s}")
    fun healthCheck() {
        try {
            gpus.periodicCheck()
        } catch (e: Exception) {
            log.error("GPU health check failed", e)
        }
    }
}
