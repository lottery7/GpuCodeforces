package dev.gpucodeforces.judge.executor.config

import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.executor.compile.Builder
import dev.gpucodeforces.judge.executor.compile.Toolchain
import dev.gpucodeforces.judge.executor.gpu.GpuManager
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.messaging.JobListener
import dev.gpucodeforces.judge.executor.pipeline.JobProcessor
import dev.gpucodeforces.judge.executor.pipeline.JudgePipeline
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.run.HarnessRunner
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import dev.gpucodeforces.judge.executor.sandbox.NsjailSandbox
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.util.daemonThread
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Clock

@Configuration
class ExecutorConfig(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** Fails startup unless the pinned toolchain is present. */
    @Profile(REAL)
    @Bean
    fun toolchain(): Toolchain = Toolchain(props).also { it.verify() }

    @Profile(REAL)
    @Bean
    fun sandbox(): Sandbox {
        Files.createDirectories(props.paths.work)
        return when (props.sandbox.type) {
            SandboxType.NSJAIL -> NsjailSandbox(props.sandbox).also { it.selfTest(props.paths.work.resolve(".selftest")) }
            SandboxType.NONE -> DirectSandbox().also {
                log.warn("SANDBOX DISABLED (judge.executor.sandbox.type=NONE): solutions run without isolation. Use only for development.")
            }
        }
    }

    @Profile(REAL)
    @Bean(destroyMethod = "stop")
    fun gpuManager(toolchain: Toolchain): GpuManager = GpuManager(props).also { it.start() }

    @Profile(REAL)
    @Bean
    fun builder(toolchain: Toolchain, sandbox: Sandbox) = Builder(props, toolchain, sandbox)

    @Profile(REAL)
    @Bean
    fun problemStore(gpus: GpuManager, toolchain: Toolchain, sandbox: Sandbox) = ProblemStore(props, gpus, toolchain, sandbox)

    @Profile(REAL)
    @Bean
    fun harnessRunner(sandbox: Sandbox) = HarnessRunner(props, sandbox)

    @Bean
    fun eventPublisher(rabbit: RabbitTemplate, json: JsonMapper) = EventPublisher(rabbit, json)

    @Bean
    fun cancelRegistry(clock: Clock) = CancelRegistry(clock)

    @Profile(REAL)
    @Bean
    fun judgePipeline(
        toolchain: Toolchain, builder: Builder, problems: ProblemStore, runner: HarnessRunner, gpus: GpuManager,
        publisher: EventPublisher, cancels: CancelRegistry, meters: MeterRegistry, clock: Clock,
    ): JobProcessor = JudgePipeline(props, toolchain, builder, problems, runner, gpus, publisher, cancels, meters, clock)

    /** Same declarations as the gateway (idempotent), so an executor can start first. */
    @Bean
    fun executorTopology(): Declarables {
        val jobs = DirectExchange(Topology.JOBS_EXCHANGE, true, false)
        val dlx = FanoutExchange(Topology.JOBS_DLX, true, false)
        val dead = QueueBuilder.durable(Topology.JOBS_DEAD_QUEUE).quorum().deliveryLimit(Topology.NEVER_DROP).build()
        val queue = QueueBuilder.durable(Topology.jobsQueue(props.gpuProfile)).quorum()
            .deliveryLimit(Topology.JOB_DELIVERY_LIMIT).deadLetterExchange(Topology.JOBS_DLX).build()
        val events = TopicExchange(Topology.EVENTS_EXCHANGE, true, false)
        val eventsQueue = QueueBuilder.durable(Topology.EVENTS_QUEUE).quorum().deliveryLimit(Topology.NEVER_DROP).build()
        return Declarables(
            jobs, dlx, dead, BindingBuilder.bind(dead).to(dlx),
            queue, BindingBuilder.bind(queue).to(jobs).with(props.gpuProfile),
            events, eventsQueue, BindingBuilder.bind(eventsQueue).to(events).with("#"),
            FanoutExchange(Topology.HEARTBEATS_EXCHANGE, false, false),
            FanoutExchange(Topology.CONTROL_EXCHANGE, false, false),
        )
    }

    /**
     * Competing consumer on `judge.jobs.<profile>`: one consumer per GPU plus [ExecutorProperties.prefetchExtra],
     * each with prefetch 1, so the broker balances jobs by actual free capacity. Manual ack after the verdict is
     * persisted; unacked jobs of a crashed executor go back to the queue.
     */
    @Bean
    fun jobsContainer(connectionFactory: ConnectionFactory, listener: JobListener, gpus: GpuManager): SimpleMessageListenerContainer =
        SimpleMessageListenerContainer(connectionFactory).apply {
            setQueueNames(Topology.jobsQueue(props.gpuProfile))
            acknowledgeMode = AcknowledgeMode.MANUAL
            setPrefetchCount(1)
            setConcurrentConsumers(props.capacity)
            setMaxConcurrentConsumers(props.capacity)
            setDefaultRequeueRejected(true)
            setShutdownTimeout(props.shutdownTimeout.toMillis())
            setMessageListener(listener)
            log.info("Consuming {} with {} consumers ({} GPUs + {} prefetch)", Topology.jobsQueue(props.gpuProfile), props.capacity, props.gpus.size, props.prefetchExtra)
        }.also { container ->
            // With no usable GPU a job taken from the queue can only wait and go back, and each round trip counts
            // towards its delivery limit: left consuming, a broken executor would dead-letter its whole queue.
            // Not consuming leaves the jobs to other executors of the profile, or waiting until the GPU recovers.
            gpus.pool.onAvailabilityChange = { available ->
                // not on the caller's thread: it may be a consumer, and stop() waits for consumers to finish
                daemonThread("jobs-container-switch") {
                    if (available) {
                        log.info("A GPU is healthy again: resuming job consumption")
                        container.start()
                    } else {
                        log.error("No healthy GPU left: job consumption paused until one recovers")
                        container.stop()
                    }
                }
            }
        }
}
