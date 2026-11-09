package dev.gpucodeforces.judge.gateway.web

import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionAccepted
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import dev.gpucodeforces.judge.gateway.service.ExecutorRegistry
import dev.gpucodeforces.judge.gateway.service.ExecutorView
import dev.gpucodeforces.judge.gateway.service.QueueView
import dev.gpucodeforces.judge.gateway.service.SubmissionService
import dev.gpucodeforces.judge.gateway.service.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/v1/submissions")
@Tag(name = "Submissions")
class SubmissionController(private val service: SubmissionService) {

    @PostMapping
    @Operation(
        summary = "Enqueue a submission for judging",
        description = "Validates the request and persists the job in the broker. Returns 202 immediately; " +
            "progress and the verdict arrive asynchronously as JudgeEvent messages in the `judge.events` queue. " +
            "Re-posting the same submissionId within the dedup window returns 200 with duplicate=true.",
    )
    fun submit(@RequestBody req: SubmissionRequest): ResponseEntity<SubmissionAccepted> {
        val accepted = service.submit(req)
        return ResponseEntity.status(if (accepted.duplicate) HttpStatus.OK else HttpStatus.ACCEPTED).body(accepted)
    }

    @DeleteMapping("/{submissionId}")
    @Operation(summary = "Cancel a queued or running submission (best effort); a FINISHED event with CANCELLED follows if it was caught")
    fun cancel(@PathVariable submissionId: String): ResponseEntity<Void> {
        service.cancel(submissionId)
        return ResponseEntity.accepted().build()
    }
}

data class ValidationResult(val valid: Boolean, val errors: List<String>)

@RestController
@RequestMapping("/api/v1/problems")
@Tag(name = "Problems")
class ProblemController(private val props: GatewayProperties) {

    @PostMapping("/validate")
    @Operation(summary = "Structurally validate a problem spec (no tests are generated)")
    fun validate(@RequestBody spec: ProblemSpec): ValidationResult {
        val errors = SpecValidator.validateProblem(spec, props.validatorLimits)
        return ValidationResult(errors.isEmpty(), errors)
    }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Monitoring")
class MonitoringController(private val registry: ExecutorRegistry) {

    @GetMapping("/executors")
    @Operation(summary = "Executors with their latest heartbeat")
    fun executors(): List<ExecutorView> = registry.all()

    @GetMapping("/queues")
    @Operation(summary = "Jobs waiting per GPU profile and available capacity")
    fun queues(): List<QueueView> = registry.queues()
}

data class ErrorResponse(val error: String, val details: List<String> = emptyList())

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ValidationException::class)
    fun validation(e: ValidationException) = ResponseEntity.badRequest().body(ErrorResponse("validation_failed", e.errors))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.badRequest().body(ErrorResponse("malformed_request", listOfNotNull(e.mostSpecificCause.message?.take(500))))

    @ExceptionHandler(AmqpException::class)
    fun broker(e: AmqpException): ResponseEntity<ErrorResponse> {
        log.error("Broker unavailable", e)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse("broker_unavailable"))
    }
}
