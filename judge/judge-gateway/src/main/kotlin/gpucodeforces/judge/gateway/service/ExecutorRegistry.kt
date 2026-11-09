package gpucodeforces.judge.gateway.service

import gpucodeforces.judge.api.ExecutorHeartbeat
import gpucodeforces.judge.api.Topology
import gpucodeforces.judge.gateway.config.GatewayProperties
import gpucodeforces.judge.gateway.messaging.JudgeMessaging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.Exchange
import org.springframework.amqp.rabbit.annotation.Queue
import org.springframework.amqp.rabbit.annotation.QueueBinding
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ExecutorView(val alive: Boolean, val lastSeen: Instant, val heartbeat: ExecutorHeartbeat)

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
            Gauge.builder("judge.gateway.healthy_gpus") { all().filter { it.alive && it.heartbeat.gpuProfile == profile }.sumOf { v -> v.heartbeat.gpus.count { g -> g.healthy } } }
                .tag("gpuProfile", profile).register(meters)
        }
    }

    @RabbitListener(
        bindings = [QueueBinding(
            value = Queue(durable = "false", exclusive = "true", autoDelete = "true"),
            exchange = Exchange(value = Topology.HEARTBEATS_EXCHANGE, type = "fanout", durable = "false"),
        )],
    )
    fun onHeartbeat(message: Message) {
        val hb = try {
            messaging.read(message, ExecutorHeartbeat::class.java)
        } catch (e: RuntimeException) {
            log.warn("Malformed heartbeat ignored", e)
            return
        }
        val prev = executors.put(hb.executorId, ExecutorView(true, clock.instant(), hb))
        if (prev == null || !prev.alive) {
            log.info("Executor {} ({}) is up: profile={} gpus={}", hb.executorId, hb.hostname, hb.gpuProfile, hb.gpus.map { it.name })
        }
        val unhealthy = hb.gpus.filter { !it.healthy }.map { it.index }
        val prevUnhealthy = prev?.heartbeat?.gpus?.filter { !it.healthy }?.map { it.index } ?: emptyList()
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
        // forget executors that have been dead for a long time
        executors.entries.removeIf { !it.value.alive && it.value.lastSeen.isBefore(deadline.minus(props.heartbeatTimeout.multipliedBy(20))) }
    }

    fun all(): List<ExecutorView> = executors.values.sortedBy { it.heartbeat.executorId }

    fun queues(): List<QueueView> = props.gpuProfiles.map { profile ->
        val name = Topology.jobsQueue(profile)
        val info = try {
            admin.getQueueInfo(name)
        } catch (e: RuntimeException) {
            log.warn("Cannot read queue info for {}", name, e)
            null
        }
        val alive = all().filter { it.alive && it.heartbeat.gpuProfile == profile }
        QueueView(
            gpuProfile = profile,
            queue = name,
            waiting = info?.messageCount,
            consumers = info?.consumerCount,
            aliveExecutors = alive.size,
            healthyGpus = alive.sumOf { v -> v.heartbeat.gpus.count { it.healthy } },
            inFlight = alive.sumOf { it.heartbeat.inFlight },
            capacity = alive.sumOf { it.heartbeat.capacity },
        )
    }
}
