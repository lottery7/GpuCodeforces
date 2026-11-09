package dev.gpucodeforces.judge.gateway.service

import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.gateway.messaging.JudgeMessaging
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException

/**
 * Jobs that were delivered [Topology.JOB_DELIVERY_LIMIT] times without being acked (executor crashed on them
 * or kept failing internally) end up here. The backend still needs a terminal event, so report INTERNAL_ERROR.
 */
@Component
class DeadJobListener(
    private val messaging: JudgeMessaging,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RabbitListener(queues = [Topology.JOBS_DEAD_QUEUE])
    fun onDeadJob(message: Message) {
        val job = try {
            messaging.read(message, JudgeJob::class.java)
        } catch (e: JacksonException) {
            log.error("Unreadable dead-lettered job dropped: messageId={}", message.messageProperties.messageId, e)
            return
        }
        MDC.putCloseable("submissionId", job.submissionId).use {
            val deaths = message.messageProperties.xDeathHeader
            log.error("Job dead-lettered after repeated failures, reporting INTERNAL_ERROR. problem={} x-death={}", job.problem.id, deaths)
            meters.counter("judge.gateway.dead_jobs", "gpuProfile", job.gpuProfile).increment()
            messaging.publishEvent(
                JudgeEvent(
                    submissionId = job.submissionId,
                    attempt = Topology.JOB_DELIVERY_LIMIT + 1,
                    seq = 1,
                    status = SubmissionStatus.FINISHED,
                    timestamp = clock.instant(),
                    source = "gateway",
                    result = JudgeResult(Verdict.INTERNAL_ERROR, message = "Judging failed repeatedly, the submission will be rejudged by admins"),
                ),
            )
        }
    }
}
