package dev.gpucodeforces.judge.executor.config

import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.messaging.JobListener
import dev.gpucodeforces.judge.executor.pipeline.JobProcessor
import dev.gpucodeforces.judge.executor.pipeline.JudgePipeline
import dev.gpucodeforces.judge.executor.processor.ProcessorBackend
import dev.gpucodeforces.judge.executor.processor.ProcessorBackends
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuBackend
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuBuilder
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuToolchain
import dev.gpucodeforces.judge.executor.processor.cudasim.CudaSimBackend
import dev.gpucodeforces.judge.executor.processor.cudasim.CudaSimBuilder
import dev.gpucodeforces.judge.executor.processor.gpucuda.Builder
import dev.gpucodeforces.judge.executor.processor.gpucuda.GpuCudaBackend
import dev.gpucodeforces.judge.executor.processor.gpucuda.GpuManager
import dev.gpucodeforces.judge.executor.processor.gpucuda.HarnessRunner
import dev.gpucodeforces.judge.executor.processor.gpucuda.Toolchain
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvBackend
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvBuilder
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvToolchain
import dev.gpucodeforces.judge.executor.processor.rtl.RtlBackend
import dev.gpucodeforces.judge.executor.processor.rtl.RtlBuilder
import dev.gpucodeforces.judge.executor.processor.rtl.RtlToolchain
import dev.gpucodeforces.judge.executor.problem.PrepareRuntime
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import dev.gpucodeforces.judge.executor.sandbox.NsjailSandbox
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.util.daemonThread
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarable
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Configuration
class ExecutorConfig(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** Fails startup unless the pinned torch is present (when one is configured). */
    @Profile(REAL)
    @Bean
    fun prepareRuntime(): PrepareRuntime = PrepareRuntime(props).also { it.verify() }

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

    /**
     * One backend per processor of [ExecutorProperties.processors]; each probes its toolchain here, so a missing or
     * wrong one stops the executor at startup. The GPU parts exist only with `gpu-cuda`: its
     * toolchain is verified before the GPUs are probed (the probe is built with nvcc), and an executor without it
     * has no GPU at all, so tests are generated on the CPU.
     */
    @Profile(REAL)
    @Bean(destroyMethod = "close")
    fun processorBackends(runtime: PrepareRuntime, sandbox: Sandbox, meters: MeterRegistry): ProcessorBackends {
        val cuda = if (Processors.GPU_CUDA in props.processors) {
            val toolchain = Toolchain(props, runtime).also { it.verify() }
            CudaHost(toolchain, GpuManager(props).also { it.start() })
        } else {
            null
        }
        val problems = ProblemStore(props, cuda?.gpus?.pool, runtime, sandbox)
        val backends = props.processors.map { id ->
            when {
                id == Processors.GPU_CUDA && cuda != null -> GpuCudaBackend(
                    props, cuda.toolchain, Builder(props, cuda.toolchain, sandbox), problems, HarnessRunner(props, sandbox), cuda.gpus, meters,
                )
                id == Processors.RTL -> RtlToolchain.probe(props.rtl).let { toolchain ->
                    RtlBackend(props, toolchain, RtlBuilder(props, toolchain, sandbox), problems, sandbox)
                }
                id == Processors.RISCV_RVV -> RiscvToolchain.probe(props.riscv).let { toolchain ->
                    RiscvBackend(props, toolchain, RiscvBuilder(props, toolchain, sandbox), problems, sandbox)
                }
                id == Processors.CUDA_SIM -> CudaSimBackend.probe(props).let { version ->
                    CudaSimBackend(props, version, CudaSimBuilder(props, version, sandbox), problems, sandbox)
                }
                id == Processors.CORAL_NPU -> CoralNpuToolchain.probe(props.coralNpu).let { toolchain ->
                    CoralNpuBackend(props, toolchain, CoralNpuBuilder(props, toolchain, sandbox), problems, sandbox)
                }
                else -> error("processor $id is not implemented by this executor yet: remove it from judge.executor.processors")
            }
        }
        backends.forEach(ProcessorBackend::verify)
        log.info("Judging on {}", backends.map { "${it.info.id} (${it.model}; ${it.version})" })
        return ProcessorBackends(backends)
    }

    private class CudaHost(val toolchain: Toolchain, val gpus: GpuManager)

    @Bean
    fun eventPublisher(rabbit: RabbitTemplate, json: JsonMapper) = EventPublisher(rabbit, json)

    @Bean
    fun cancelRegistry(clock: Clock) = CancelRegistry(clock)

    @Profile(REAL)
    @Bean
    fun judgePipeline(
        backends: ProcessorBackends, publisher: EventPublisher, cancels: CancelRegistry, meters: MeterRegistry, clock: Clock,
    ): JobProcessor = JudgePipeline(props, backends.all, publisher, cancels, meters, clock)

    /** Same declarations as the gateway (idempotent), so an executor can start first. */
    @Bean
    fun executorTopology(): Declarables {
        val jobs = DirectExchange(Topology.JOBS_EXCHANGE, true, false)
        val dlx = FanoutExchange(Topology.JOBS_DLX, true, false)
        val dead = QueueBuilder.durable(Topology.JOBS_DEAD_QUEUE).quorum().deliveryLimit(Topology.NEVER_DROP).build()
        val events = TopicExchange(Topology.EVENTS_EXCHANGE, true, false)
        val eventsQueue = QueueBuilder.durable(Topology.EVENTS_QUEUE).quorum().deliveryLimit(Topology.NEVER_DROP).build()
        val declarables = mutableListOf<Declarable>(
            jobs, dlx, dead, BindingBuilder.bind(dead).to(dlx),
            events, eventsQueue, BindingBuilder.bind(eventsQueue).to(events).with("#"),
            FanoutExchange(Topology.HEARTBEATS_EXCHANGE, false, false),
            FanoutExchange(Topology.CONTROL_EXCHANGE, false, false),
        )
        for (processor in props.processors) {
            val queue = QueueBuilder.durable(Topology.jobsQueue(processor)).quorum()
                .deliveryLimit(Topology.JOB_DELIVERY_LIMIT).deadLetterExchange(Topology.JOBS_DLX).build()
            declarables += queue
            declarables += BindingBuilder.bind(queue).to(jobs).with(processor)
        }
        return Declarables(declarables)
    }

    /**
     * A competing consumer on `judge.jobs.<processor>` for every processor: one consumer per slot of the processor
     * (GPU, emulator worker) plus [ExecutorProperties.prefetchExtra], each with prefetch 1, so the broker balances
     * jobs by actual free capacity. Manual ack after the verdict is persisted; unacked jobs of a crashed executor
     * go back to the queue.
     */
    @Bean
    fun jobConsumers(connectionFactory: ConnectionFactory, listener: JobListener, processor: JobProcessor): JobConsumers {
        val containers = props.processors.associateWith { id ->
            SimpleMessageListenerContainer(connectionFactory).apply {
                setQueueNames(Topology.jobsQueue(id))
                acknowledgeMode = AcknowledgeMode.MANUAL
                setPrefetchCount(1)
                setConcurrentConsumers(props.capacityOf(id))
                setMaxConcurrentConsumers(props.capacityOf(id))
                setDefaultRequeueRejected(true)
                setShutdownTimeout(props.shutdownTimeout.toMillis())
                setMessageListener(listener)
                afterPropertiesSet()
                log.info("Consuming {} with {} consumers ({} slots + {} prefetch)", Topology.jobsQueue(id), props.capacityOf(id), props.slotsOf(id), props.prefetchExtra)
            }
        }
        for ((id, container) in containers) {
            // A job a processor cannot run can only wait and go back, and each round trip counts towards its delivery
            // limit: left consuming, a broken executor would dead-letter its whole queue. Not consuming leaves the
            // jobs to other executors of the processor, or waiting until it recovers.
            processor.onAvailabilityChange(id) { available ->
                // not on the caller's thread: it may be a consumer, and stop() waits for consumers to finish
                daemonThread("jobs-container-switch") {
                    if (available) {
                        log.info("Processor {} can judge again: resuming consumption of its queue", id)
                        container.start()
                    } else {
                        log.error("Processor {} cannot judge: consumption of its queue paused until it recovers", id)
                        container.stop()
                    }
                }
            }
        }
        return JobConsumers(containers, props.shutdownTimeout.toMillis())
    }
}

/** Starts and stops the listener container of every processor's queue with the application. */
class JobConsumers(
    private val containers: Map<String, SimpleMessageListenerContainer>,
    private val shutdownTimeoutMs: Long,
) : SmartLifecycle, DisposableBean {
    @Volatile private var running = false

    override fun start() {
        containers.values.forEach { it.start() }
        running = true
    }

    /** In parallel: each container waits up to the shutdown timeout for its jobs to finish. */
    override fun stop() {
        val stopped = CountDownLatch(containers.size)
        containers.values.forEach { it.stop { stopped.countDown() } }
        stopped.await(shutdownTimeoutMs + STOP_SLACK_MS, TimeUnit.MILLISECONDS)
        running = false
    }

    override fun isRunning() = running

    /** Like the listener containers Spring manages itself: stop before anything they use. */
    override fun getPhase() = Int.MAX_VALUE

    override fun destroy() {
        containers.values.forEach { it.destroy() }
    }

    private companion object {
        const val STOP_SLACK_MS = 5_000L
    }
}
