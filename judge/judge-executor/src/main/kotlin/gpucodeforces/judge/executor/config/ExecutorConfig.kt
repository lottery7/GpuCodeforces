package gpucodeforces.judge.executor.config

import gpucodeforces.judge.api.Topology
import gpucodeforces.judge.executor.build.Builder
import gpucodeforces.judge.executor.build.Toolchain
import gpucodeforces.judge.executor.gpu.GpuManager
import gpucodeforces.judge.executor.messaging.CancelRegistry
import gpucodeforces.judge.executor.messaging.EventPublisher
import gpucodeforces.judge.executor.messaging.JobListener
import gpucodeforces.judge.executor.pipeline.JudgePipeline
import gpucodeforces.judge.executor.problem.ProblemStore
import gpucodeforces.judge.executor.run.HarnessRunner
import gpucodeforces.judge.executor.sandbox.DirectSandbox
import gpucodeforces.judge.executor.sandbox.NsjailSandbox
import gpucodeforces.judge.executor.sandbox.Sandbox
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
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Clock

@Configuration
class ExecutorConfig(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** Fails startup unless the pinned toolchain is present. */
    @Bean
    fun toolchain(): Toolchain = Toolchain(props).also { it.verify() }

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

    @Bean(destroyMethod = "stop")
    fun gpuManager(toolchain: Toolchain): GpuManager = GpuManager(props).also { it.start() }

    @Bean
    fun builder(toolchain: Toolchain, sandbox: Sandbox) = Builder(props, toolchain, sandbox)

    @Bean
    fun problemStore(gpus: GpuManager) = ProblemStore(props, gpus)

    @Bean
    fun harnessRunner(sandbox: Sandbox) = HarnessRunner(props, sandbox)

    @Bean
    fun eventPublisher(rabbit: RabbitTemplate, json: JsonMapper) = EventPublisher(rabbit, json)

    @Bean
    fun cancelRegistry(clock: Clock) = CancelRegistry(clock)

    @Bean
    fun judgePipeline(
        toolchain: Toolchain, builder: Builder, problems: ProblemStore, runner: HarnessRunner, gpus: GpuManager,
        publisher: EventPublisher, cancels: CancelRegistry, meters: MeterRegistry, clock: Clock,
    ) = JudgePipeline(props, toolchain, builder, problems, runner, gpus, publisher, cancels, meters, clock)

    /** Same declarations as the gateway (idempotent), so an executor can start first. */
    @Bean
    fun executorTopology(): Declarables {
        val jobs = DirectExchange(Topology.JOBS_EXCHANGE, true, false)
        val dlx = FanoutExchange(Topology.JOBS_DLX, true, false)
        val dead = QueueBuilder.durable(Topology.JOBS_DEAD_QUEUE).quorum().build()
        val queue = QueueBuilder.durable(Topology.jobsQueue(props.gpuProfile)).quorum()
            .deliveryLimit(Topology.JOB_DELIVERY_LIMIT).deadLetterExchange(Topology.JOBS_DLX).build()
        val events = TopicExchange(Topology.EVENTS_EXCHANGE, true, false)
        val eventsQueue = QueueBuilder.durable(Topology.EVENTS_QUEUE).quorum().build()
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
            setShutdownTimeout(60_000)
            setMessageListener(listener)
            log.info("Consuming {} with {} consumers ({} GPUs + {} prefetch)", Topology.jobsQueue(props.gpuProfile), props.capacity, props.gpus.size, props.prefetchExtra)
        }
}
