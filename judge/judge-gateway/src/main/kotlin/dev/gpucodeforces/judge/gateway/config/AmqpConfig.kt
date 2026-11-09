package dev.gpucodeforces.judge.gateway.config

import dev.gpucodeforces.judge.api.Topology
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declares the whole judge topology. Executors declare the parts they use as well; declarations are idempotent
 * as long as arguments match, so keep this in sync with `ExecutorAmqpConfig`.
 */
@Configuration
class AmqpConfig(private val props: GatewayProperties) {

    @Bean
    fun judgeTopology(): Declarables {
        val jobs = DirectExchange(Topology.JOBS_EXCHANGE, true, false)
        val dlx = FanoutExchange(Topology.JOBS_DLX, true, false)
        val dead = QueueBuilder.durable(Topology.JOBS_DEAD_QUEUE).quorum().build()
        val events = TopicExchange(Topology.EVENTS_EXCHANGE, true, false)
        val eventsQueue = QueueBuilder.durable(Topology.EVENTS_QUEUE).quorum().build()
        val heartbeats = FanoutExchange(Topology.HEARTBEATS_EXCHANGE, false, false)
        val control = FanoutExchange(Topology.CONTROL_EXCHANGE, false, false)

        val declarables = mutableListOf(
            jobs, dlx, dead, BindingBuilder.bind(dead).to(dlx),
            events, eventsQueue, BindingBuilder.bind(eventsQueue).to(events).with("#"),
            heartbeats, control,
        )
        for (profile in props.gpuProfiles) {
            val queue = jobsQueue(profile)
            declarables += queue
            declarables += BindingBuilder.bind(queue).to(jobs).with(profile)
        }
        return Declarables(declarables)
    }

    companion object {
        fun jobsQueue(profile: String) = QueueBuilder.durable(Topology.jobsQueue(profile))
            .quorum()
            .deliveryLimit(Topology.JOB_DELIVERY_LIMIT)
            .deadLetterExchange(Topology.JOBS_DLX)
            .build()
    }
}
