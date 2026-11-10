package dev.gpucodeforces.judge.gateway.service

import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import dev.gpucodeforces.judge.gateway.messaging.JudgeMessaging
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.AmqpException
import org.springframework.amqp.ImmediateRequeueAmqpException
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
    private val props: GatewayProperties,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The verdict of a dead job exists nowhere else, so it is published with a broker confirm, and a failure to
     * publish puts the job back into the dead queue instead of dropping it.
     */
    @RabbitListener(queues = [Topology.JOBS_DEAD_QUEUE])
    fun onDeadJob(message: Message) {
        val job = try {
            messaging.read(message, JudgeJob::class.java)
        } catch (e: JacksonException) {
            // Nothing can judge it, but the message id is the submission id: its owner still gets an answer.
            val submissionId = message.messageProperties.messageId
            log.error("Unreadable dead-lettered job: messageId={}", submissionId, e)
            if (submissionId != null) report(submissionId, jobId = UNREADABLE_JOB)
            return
        }
        MDC.putCloseable("submissionId", job.submissionId).use {
            log.error("Job dead-lettered after repeated failures, reporting INTERNAL_ERROR. problem={} x-death={}", job.problem.id, message.messageProperties.xDeathHeader)
            meters.counter("judge.gateway.dead_jobs", "gpuProfile", job.gpuProfile).increment()
            report(job.submissionId, job.jobId)
        }
    }

    private fun report(submissionId: String, jobId: String) {
        val event = JudgeEvent(
            submissionId = submissionId,
            attempt = Topology.DEAD_JOB_ATTEMPT,
            seq = 1,
            status = SubmissionStatus.FINISHED,
            timestamp = clock.instant(),
            source = "gateway",
            result = JudgeResult(Verdict.INTERNAL_ERROR, message = "Judging failed repeatedly, the submission will be rejudged by admins"),
            jobId = jobId,
        )
        try {
            messaging.publishEventConfirmed(event, props.publishConfirmTimeout)
        } catch (e: AmqpException) {
            throw ImmediateRequeueAmqpException("verdict of dead job $jobId could not be published", e)
        }
    }

    private companion object {
        const val UNREADABLE_JOB = "unreadable"
    }
}
