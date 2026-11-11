package dev.gpucodeforces.judge.executor.config

import dev.gpucodeforces.judge.executor.messaging.CancelRegistry
import dev.gpucodeforces.judge.executor.messaging.EventPublisher
import dev.gpucodeforces.judge.executor.mock.MockJudge
import dev.gpucodeforces.judge.executor.pipeline.JobProcessor
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** Spring profile of an executor without a compiler, sandbox, GPU or emulator. */
const val MOCK = "mock"

/** Beans that need the real toolchain and hardware. */
const val REAL = "!$MOCK"

/**
 * `SPRING_PROFILES_ACTIVE=mock`: the executor keeps its place in the system (queues of its processors, events,
 * heartbeats, cancellation) but verdicts come from [MockJudge]. For integration tests on machines without a GPU.
 */
@Configuration
@Profile(MOCK)
class MockExecutorConfig(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun mockJudge(publisher: EventPublisher, cancels: CancelRegistry, clock: Clock): JobProcessor {
        log.warn("MOCK EXECUTOR: submissions for {} are not compiled or run; verdicts come from judge-mock comments", props.processors)
        return MockJudge(props, publisher, cancels, clock)
    }
}
