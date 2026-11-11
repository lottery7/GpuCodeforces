package dev.gpucodeforces.judge.gateway.service

import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.LanguageInfo
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import dev.gpucodeforces.judge.gateway.messaging.JudgeMessaging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.Exchange
import org.springframework.amqp.rabbit.annotation.Queue
import org.springframework.amqp.rabbit.annotation.QueueBinding
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException

data class ExecutorView(val alive: Boolean, val lastSeen: Instant, val heartbeat: ExecutorHeartbeat) {
    val healthyGpus: Int get() = heartbeat.gpus.count { it.healthy }

    fun processor(id: String): ProcessorStatus? = heartbeat.processors.find { it.id == id }
}

data class QueueView(
    val processor: String,
    val queue: String,
    /** Jobs waiting in the broker (not yet taken by an executor). */
    val waiting: Long?,
    val consumers: Int?,
    val aliveExecutors: Int,
    val healthyGpus: Int,
    /** Jobs of the alive executors of this processor, over all their processors: heartbeats do not split them. */
    val inFlight: Int,
    /** Jobs of this processor the alive executors take at once. */
    val capacity: Int,
)

/** A processor of the catalog and whether it can be judged now. */
data class ProcessorView(
    val id: String,
    val title: String,
    val scoreType: ScoreType,
    val languages: List<LanguageInfo>,
    val emulated: Boolean,
    /** The gateway accepts submissions for it. */
    val accepted: Boolean,
    /** An alive executor reports it healthy. */
    val available: Boolean,
    /** As an alive executor reports it; null without one. */
    val model: String?,
)

/** Keeps the latest heartbeat of every executor; used for monitoring and for the queue overview. */
@Service
class ExecutorRegistry(
    private val props: GatewayProperties,
    private val messaging: JudgeMessaging,
    private val admin: RabbitAdmin,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executors = ConcurrentHashMap<String, ExecutorView>()

    init {
        for (processor in props.processors) {
            Gauge.builder("judge.gateway.alive_executors") { alive(processor).size }.tag("processor", processor).register(meters)
        }
        if (Processors.GPU_CUDA in props.processors) {
            Gauge.builder("judge.gateway.healthy_gpus") { alive(Processors.GPU_CUDA).sumOf { it.healthyGpus } }.register(meters)
        }
    }

    /** Executors of a processor that are sending heartbeats. */
    fun alive(processor: String): List<ExecutorView> = all().filter { it.alive && it.processor(processor) != null }

    @RabbitListener(
        bindings = [QueueBinding(
            value = Queue(durable = "false", exclusive = "true", autoDelete = "true"),
            exchange = Exchange(value = Topology.HEARTBEATS_EXCHANGE, type = "fanout", durable = "false"),
        )],
    )
    fun onHeartbeat(message: Message) {
        val hb = try {
            messaging.read(message, ExecutorHeartbeat::class.java)
        } catch (e: JacksonException) {
            log.warn("Malformed heartbeat ignored", e)
            return
        }
        val prev = executors.put(hb.executorId, ExecutorView(alive = true, lastSeen = clock.instant(), hb))
        if (prev == null || !prev.alive) {
            log.info(
                "Executor {} ({}) is up: processors={} gpus={}",
                hb.executorId, hb.hostname, hb.processors.map { "${it.id} (${it.model})" }, hb.gpus.map { it.name },
            )
            val foreign = hb.processors.map { it.id } - props.processors.toSet()
            if (foreign.isNotEmpty()) {
                log.warn(
                    "Executor {} serves {}, which this gateway does not accept submissions for ({}): add them to judge.gateway.processors or fix the executor",
                    hb.executorId, foreign, props.processors,
                )
            }
            warnOnMixedModels(hb)
        } else if (prev.heartbeat.hostname != hb.hostname) {
            // Heartbeats of two machines overwrite each other here, and their events cannot be told apart.
            log.error("Two executors use the id {}: on {} and on {}. Give each its own JUDGE_EXECUTOR_ID", hb.executorId, prev.heartbeat.hostname, hb.hostname)
        }
        val unhealthy = hb.gpus.filter { !it.healthy }.map { it.index }
        val prevUnhealthy = prev?.heartbeat?.gpus.orEmpty().filter { !it.healthy }.map { it.index }
        if (unhealthy != prevUnhealthy) {
            if (unhealthy.isEmpty()) log.info("Executor {}: all GPUs healthy again", hb.executorId)
            else log.warn("Executor {}: unhealthy GPUs {}", hb.executorId, unhealthy)
        }
    }

    @Scheduled(fixedDelayString = "\${judge.gateway.liveness-check-interval:5s}")
    fun markDead() {
        val deadline = clock.instant().minus(props.heartbeatTimeout)
        executors.replaceAll { id, v ->
            if (v.alive && v.lastSeen.isBefore(deadline)) {
                log.warn("Executor {} missed heartbeats since {}, marking dead (its unacked jobs return to the queue)", id, v.lastSeen)
                v.copy(alive = false)
            } else {
                v
            }
        }
        val longDead = deadline.minus(props.heartbeatTimeout.multipliedBy(20))
        executors.entries.removeIf { !it.value.alive && it.value.lastSeen.isBefore(longDead) }
    }

    /** Results of one processor are compared with each other, so all its executors must run the same model. */
    private fun warnOnMixedModels(hb: ExecutorHeartbeat) {
        for (p in hb.processors) {
            val others = alive(p.id).filter { it.heartbeat.executorId != hb.executorId }.mapNotNull { it.processor(p.id)?.model }.toSet()
            if (others.isNotEmpty() && others != setOf(p.model)) {
                log.error("Executor {} runs {} on {}, other executors on {}: their results are not comparable", hb.executorId, p.id, p.model, others)
            }
        }
    }

    fun all(): List<ExecutorView> = executors.values.sortedBy { it.heartbeat.executorId }

    /** The whole catalog, with what alive executors report. */
    fun processors(): List<ProcessorView> = Processors.all.map { info ->
        val statuses = alive(info.id).mapNotNull { it.processor(info.id) }
        ProcessorView(
            id = info.id,
            title = info.title,
            scoreType = info.scoreType,
            languages = info.languages,
            emulated = info.emulated,
            accepted = info.id in props.processors,
            available = statuses.any { it.healthy },
            model = statuses.firstOrNull { it.healthy }?.model ?: statuses.firstOrNull()?.model,
        )
    }

    /** Queues of the accepted processors, and of any other processor an executor reports, so a misconfigured one shows up. */
    fun queues(): List<QueueView> = (props.processors + all().flatMap { v -> v.heartbeat.processors.map { it.id } }).distinct().map { processor ->
        val name = Topology.jobsQueue(processor)
        val info = try {
            admin.getQueueInfo(name)
        } catch (e: AmqpException) {
            log.warn("Cannot read queue info for {}", name, e)
            null
        }
        val alive = alive(processor)
        QueueView(
            processor = processor,
            queue = name,
            waiting = info?.messageCount,
            consumers = info?.consumerCount,
            aliveExecutors = alive.size,
            healthyGpus = if (processor == Processors.GPU_CUDA) alive.sumOf { it.healthyGpus } else 0,
            inFlight = alive.sumOf { it.heartbeat.inFlight },
            capacity = alive.sumOf { it.processor(processor)?.capacity ?: 0 },
        )
    }
}
