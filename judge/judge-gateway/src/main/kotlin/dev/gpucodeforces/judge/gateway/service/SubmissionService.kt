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
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.AmqpException
import org.springframework.stereotype.Service

class ValidationException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

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
            val profile = req.gpuProfile ?: props.defaultGpuProfile
            val errors = buildList {
                if (profile !in props.gpuProfiles) add("unknown gpuProfile '$profile', available: ${props.gpuProfiles}")
                addAll(SpecValidator.validateSubmission(req, props.validatorLimits))
            }
            if (errors.isNotEmpty()) {
                log.info("Rejected submission for problem {}: {}", req.problem.id, errors)
                meters.counter("judge.gateway.submissions", "result", "rejected").increment()
                throw ValidationException(errors)
            }

            val now = clock.instant()
            recent.firstAccepted(req.submissionId, Accepted(now, profile))?.let { first ->
                if (first.gpuProfile != profile) {
                    // answering "duplicate" would claim the job is queued for a GPU it will never run on
                    throw ValidationException(listOf("submissionId was already accepted for gpuProfile '${first.gpuProfile}', not '$profile'"))
                }
                log.info("Duplicate submission (first accepted at {}), not enqueued again", first.at)
                meters.counter("judge.gateway.submissions", "result", "duplicate").increment()
                return SubmissionAccepted(req.submissionId, profile, SubmissionStatus.QUEUED, queuedAt = first.at, duplicate = true)
            }

            val job = JudgeJob(req.submissionId, req.problem, source = req.source, gpuProfile = profile, enqueuedAt = now)
            try {
                messaging.publishJob(job, props.publishConfirmTimeout)
            } catch (e: RuntimeException) {
                recent.forget(req.submissionId)
                log.error("Failed to publish job for problem {} to profile {}", req.problem.id, profile, e)
                meters.counter("judge.gateway.submissions", "result", "broker_error").increment()
                throw e
            }
            publishQueuedEvent(job)
            meters.counter("judge.gateway.submissions", "result", "accepted", "gpuProfile", profile).increment()
            log.info(
                "Accepted submission: problem={} version={} profile={} tests={} sourceBytes={}",
                req.problem.id, req.problem.version, profile, req.problem.tests.size, req.source.length,
            )
            if (executors.alive(profile).isEmpty()) {
                // The job is durable and will be judged once an executor of this GPU type connects, but nothing
                // else tells the caller that it may wait: executors also look absent right after a gateway restart.
                log.warn("No live executor serves gpuProfile {}: the submission waits in {}", profile, Topology.jobsQueue(profile))
                meters.counter("judge.gateway.submissions_without_executor", "gpuProfile", profile).increment()
            }
            return SubmissionAccepted(req.submissionId, profile, SubmissionStatus.QUEUED, queuedAt = now)
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
                JudgeEvent(job.submissionId, attempt = 0, seq = 0, status = SubmissionStatus.QUEUED, timestamp = job.enqueuedAt, source = "gateway"),
            )
        } catch (e: AmqpException) {
            log.warn("Failed to publish QUEUED event", e)
        }
    }
}

internal data class Accepted(val at: Instant, val gpuProfile: String)

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
    fun forget(id: String) {
        map.remove(id)
    }
}
