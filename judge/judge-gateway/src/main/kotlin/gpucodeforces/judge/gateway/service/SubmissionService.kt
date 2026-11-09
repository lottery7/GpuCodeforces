package gpucodeforces.judge.gateway.service

import gpucodeforces.judge.api.ControlMessage
import gpucodeforces.judge.api.ControlType
import gpucodeforces.judge.api.JudgeEvent
import gpucodeforces.judge.api.JudgeJob
import gpucodeforces.judge.api.SpecValidator
import gpucodeforces.judge.api.SubmissionAccepted
import gpucodeforces.judge.api.SubmissionRequest
import gpucodeforces.judge.api.SubmissionStatus
import gpucodeforces.judge.gateway.config.GatewayProperties
import gpucodeforces.judge.gateway.messaging.JudgeMessaging
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

class ValidationException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

@Service
class SubmissionService(
    private val props: GatewayProperties,
    private val messaging: JudgeMessaging,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val recent = RecentIds(props.dedupMaxEntries, props.dedupTtl, clock)

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
            recent.firstSeen(req.submissionId, now)?.let { firstAt ->
                log.info("Duplicate submission (first accepted at {}), not enqueued again", firstAt)
                meters.counter("judge.gateway.submissions", "result", "duplicate").increment()
                return SubmissionAccepted(req.submissionId, profile, SubmissionStatus.QUEUED, firstAt, duplicate = true)
            }

            val job = JudgeJob(req.submissionId, req.problem, req.source, profile, now)
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
            return SubmissionAccepted(req.submissionId, profile, SubmissionStatus.QUEUED, now)
        }
    }

    fun cancel(submissionId: String) {
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
        } catch (e: RuntimeException) {
            log.warn("Failed to publish QUEUED event", e)
        }
    }
}

/** Bounded, time-limited memory of recently accepted submission ids (per gateway instance). */
internal class RecentIds(private val maxEntries: Int, private val ttl: java.time.Duration, private val clock: Clock) {
    private val map = object : LinkedHashMap<String, Instant>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>) = size > maxEntries
    }

    /** Returns the time the id was first seen if it is a recent duplicate, otherwise records it and returns null. */
    @Synchronized
    fun firstSeen(id: String, now: Instant): Instant? {
        val prev = map[id]
        if (prev != null && prev.plus(ttl).isAfter(now)) return prev
        map.remove(id)
        map[id] = now
        return null
    }

    @Synchronized
    fun forget(id: String) {
        map.remove(id)
    }
}
