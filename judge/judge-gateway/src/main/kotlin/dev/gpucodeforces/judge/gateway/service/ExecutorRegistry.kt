package dev.gpucodeforces.judge.gateway.service

import dev.gpucodeforces.judge.api.ExecutorHeartbeat
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
}

data class QueueView(
    val gpuProfile: String,
    val queue: String,
    /** Jobs waiting in the broker (not yet taken by an executor). */
    val waiting: Long?,
    val consumers: Int?,
    val aliveExecutors: Int,
    val healthyGpus: Int,
    val inFlight: Int,
    val capacity: Int,
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
        for (profile in props.gpuProfiles) {
            Gauge.builder("judge.gateway.healthy_gpus") { alive(profile).sumOf { it.healthyGpus } }.tag("gpuProfile", profile).register(meters)
            Gauge.builder("judge.gateway.alive_executors") { alive(profile).size }.tag("gpuProfile", profile).register(meters)
        }
    }

    /** Executors of a GPU profile that are sending heartbeats. */
    fun alive(profile: String): List<ExecutorView> = all().filter { it.alive && it.heartbeat.gpuProfile == profile }

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
            log.info("Executor {} ({}) is up: profile={} gpus={}", hb.executorId, hb.hostname, hb.gpuProfile, hb.gpus.map { it.name })
            if (hb.gpuProfile !in props.gpuProfiles) {
                log.warn(
                    "Executor {} serves gpuProfile {}, which this gateway does not accept submissions for ({}): add it to judge.gateway.gpu-profiles or fix the executor",
                    hb.executorId, hb.gpuProfile, props.gpuProfiles,
                )
            }
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

    fun all(): List<ExecutorView> = executors.values.sortedBy { it.heartbeat.executorId }

    /** Queues of the configured profiles, and of any other profile an executor reports, so a misnamed one shows up. */
    fun queues(): List<QueueView> = (props.gpuProfiles + all().map { it.heartbeat.gpuProfile }).distinct().map { profile ->
        val name = Topology.jobsQueue(profile)
        val info = try {
            admin.getQueueInfo(name)
        } catch (e: AmqpException) {
            log.warn("Cannot read queue info for {}", name, e)
            null
        }
        val alive = alive(profile)
        QueueView(
            gpuProfile = profile,
            queue = name,
            waiting = info?.messageCount,
            consumers = info?.consumerCount,
            aliveExecutors = alive.size,
            healthyGpus = alive.sumOf { it.healthyGpus },
            inFlight = alive.sumOf { it.heartbeat.inFlight },
            capacity = alive.sumOf { it.heartbeat.capacity },
        )
    }
}
