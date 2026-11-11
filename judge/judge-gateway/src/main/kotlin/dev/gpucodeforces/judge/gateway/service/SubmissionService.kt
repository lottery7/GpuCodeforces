package dev.gpucodeforces.judge.gateway.service

import dev.gpucodeforces.judge.api.ControlMessage
import dev.gpucodeforces.judge.api.ControlType
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionAccepted
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import dev.gpucodeforces.judge.gateway.messaging.JudgeMessaging
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.AmqpException
import org.springframework.stereotype.Service

class ValidationException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/** The same submission id is being accepted by another request right now: its outcome is not known yet. */
class SubmissionInProgressException(submissionId: String) : RuntimeException("submission $submissionId is being accepted by another request")

@Service
class SubmissionService(
    private val props: GatewayProperties,
    private val messaging: JudgeMessaging,
    private val executors: ExecutorRegistry,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val recent = RecentIds(props.dedupMaxEntries, props.dedupTtl)

    fun submit(req: SubmissionRequest): SubmissionAccepted {
        MDC.putCloseable("submissionId", req.submissionId).use {
            val processor = req.processor
            val errors = buildList {
                if (processor !in props.processors) {
                    add("processor '${processor.take(SpecValidator.MAX_NAME_LENGTH)}' is not accepted here, accepted: ${props.processors.joinToString()}")
                }
                addAll(SpecValidator.validateSubmission(req, props.validatorLimits))
            }
            if (errors.isNotEmpty()) {
                log.info("Rejected submission for problem {}: {}", req.problem.id, errors)
                meters.counter("judge.gateway.submissions", "result", "rejected").increment()
                throw ValidationException(errors)
            }

            val now = clock.instant()
            val jobId = UUID.randomUUID().toString()
            recent.firstAccepted(req.submissionId, Accepted(now, processor, req.language, jobId))?.let { first ->
                // Until the broker confirms the first request nobody knows whether its job exists: answering
                // "queued" now would leave the submission waiting forever if that publish then fails.
                if (first.pending) throw SubmissionInProgressException(req.submissionId)
                if (first.processor != processor || first.language != req.language) {
                    // answering "duplicate" would claim the job is queued for a processor it will never run on
                    throw ValidationException(
                        listOf("submissionId was already accepted for ${first.processor}/${first.language}, not $processor/${req.language}"),
                    )
                }
                log.info("Duplicate submission (first accepted at {}), not enqueued again", first.at)
                meters.counter("judge.gateway.submissions", "result", "duplicate").increment()
                return SubmissionAccepted(req.submissionId, processor, req.language, SubmissionStatus.QUEUED, queuedAt = first.at, duplicate = true, jobId = first.jobId)
            }

            val job = JudgeJob(req.submissionId, req.problem, source = req.source, processor = processor, language = req.language, enqueuedAt = now, jobId = jobId)
            try {
                messaging.publishJob(job, props.publishConfirmTimeout)
            } catch (e: RuntimeException) {
                recent.forget(req.submissionId)
                log.error("Failed to publish job for problem {} to processor {}", req.problem.id, processor, e)
                meters.counter("judge.gateway.submissions", "result", "broker_error").increment()
                throw e
            }
            recent.confirm(req.submissionId)
            publishQueuedEvent(job)
            meters.counter("judge.gateway.submissions", "result", "accepted", "processor", processor).increment()
            log.info(
                "Accepted submission: problem={} version={} processor={} language={} sourceBytes={}",
                req.problem.id, req.problem.version, processor, req.language, req.source.length,
            )
            if (executors.alive(processor).isEmpty()) {
                // The job is durable and will be judged once an executor of this processor connects, but nothing
                // else tells the caller that it may wait: executors also look absent right after a gateway restart.
                log.warn("No live executor serves processor {}: the submission waits in {}", processor, Topology.jobsQueue(processor))
                meters.counter("judge.gateway.submissions_without_executor", "processor", processor).increment()
            }
            return SubmissionAccepted(req.submissionId, processor, req.language, SubmissionStatus.QUEUED, queuedAt = now, jobId = jobId)
        }
    }

    fun cancel(submissionId: String) {
        if (!SpecValidator.isValidId(submissionId)) throw ValidationException(listOf("invalid submissionId"))
        MDC.putCloseable("submissionId", submissionId).use {
            messaging.publishControl(ControlMessage(ControlType.CANCEL, submissionId, clock.instant()))
            log.info("Cancel requested")
        }
    }

    private fun publishQueuedEvent(job: JudgeJob) {
        // Best effort: the job is already persisted, a lost QUEUED event only delays the first status update.
        try {
            messaging.publishEvent(
                JudgeEvent(job.submissionId, attempt = 0, seq = 0, status = SubmissionStatus.QUEUED, timestamp = job.enqueuedAt, source = "gateway", jobId = job.jobId),
            )
        } catch (e: AmqpException) {
            log.warn("Failed to publish QUEUED event", e)
        }
    }
}

/** [pending] until the broker has confirmed the job. */
internal data class Accepted(val at: Instant, val processor: String, val language: String, val jobId: String, val pending: Boolean = true)

/** Bounded, time-limited memory of recently accepted submission ids (per gateway instance). */
internal class RecentIds(private val maxEntries: Int, private val ttl: Duration) {
    private val map = object : LinkedHashMap<String, Accepted>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Accepted>) = size > maxEntries
    }

    /** Returns the earlier acceptance if the id is a recent duplicate, otherwise records [now] and returns null. */
    @Synchronized
    fun firstAccepted(id: String, now: Accepted): Accepted? {
        val prev = map[id]
        if (prev != null && prev.at.plus(ttl).isAfter(now.at)) return prev
        map.remove(id)
        map[id] = now
        return null
    }

    @Synchronized
    fun confirm(id: String) {
        map.computeIfPresent(id) { _, accepted -> accepted.copy(pending = false) }
    }

    @Synchronized
    fun forget(id: String) {
        map.remove(id)
    }
}
