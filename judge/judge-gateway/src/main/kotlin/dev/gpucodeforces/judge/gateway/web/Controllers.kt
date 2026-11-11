package dev.gpucodeforces.judge.gateway.web

import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionAccepted
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.gateway.api.MonitoringApi
import dev.gpucodeforces.judge.gateway.api.ProblemsApi
import dev.gpucodeforces.judge.gateway.api.SubmissionsApi
import dev.gpucodeforces.judge.gateway.api.model.ErrorResponse
import dev.gpucodeforces.judge.gateway.api.model.ValidationResult
import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import dev.gpucodeforces.judge.gateway.service.ExecutorRegistry
import dev.gpucodeforces.judge.gateway.service.ExecutorView
import dev.gpucodeforces.judge.gateway.service.ProcessorView
import dev.gpucodeforces.judge.gateway.service.QueueView
import dev.gpucodeforces.judge.gateway.service.SubmissionService
import dev.gpucodeforces.judge.gateway.service.SubmissionInProgressException
import dev.gpucodeforces.judge.gateway.service.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import tools.jackson.databind.DatabindException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
class SubmissionController(private val service: SubmissionService) : SubmissionsApi {

    override fun submit(submissionRequest: SubmissionRequest): ResponseEntity<SubmissionAccepted> {
        val accepted = service.submit(submissionRequest)
        return ResponseEntity.status(if (accepted.duplicate) HttpStatus.OK else HttpStatus.ACCEPTED).body(accepted)
    }

    override fun cancel(submissionId: String): ResponseEntity<Void> {
        service.cancel(submissionId)
        return ResponseEntity.accepted().build()
    }
}

@RestController
class ProblemController(private val props: GatewayProperties) : ProblemsApi {

    override fun validateProblem(problemSpec: ProblemSpec): ResponseEntity<ValidationResult> {
        val errors = SpecValidator.validateProblem(problemSpec, props.validatorLimits)
        return ResponseEntity.ok(ValidationResult(errors.isEmpty(), errors))
    }
}

@RestController
class MonitoringController(private val registry: ExecutorRegistry) : MonitoringApi {

    override fun listExecutors(): ResponseEntity<List<ExecutorView>> = ResponseEntity.ok(registry.all())

    override fun listQueues(): ResponseEntity<List<QueueView>> = ResponseEntity.ok(registry.queues())

    override fun listProcessors(): ResponseEntity<List<ProcessorView>> = ResponseEntity.ok(registry.processors())
}

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ValidationException::class)
    fun validation(e: ValidationException) = ResponseEntity.badRequest().body(ErrorResponse("validation_failed", e.errors))

    @ExceptionHandler(SubmissionInProgressException::class)
    fun inProgress(e: SubmissionInProgressException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse("submission_in_progress", listOfNotNull(e.message)))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        // The parser's own message names internal classes; the caller only needs to know which field is wrong.
        val path = (e.cause as? DatabindException)?.path.orEmpty().joinToString(".") { it.propertyName ?: "[${it.index}]" }
        val detail = if (path.isEmpty()) "the request body is not valid JSON for this endpoint" else "field '$path' is missing or invalid"
        log.info("Malformed request: {}", e.mostSpecificCause.message)
        return ResponseEntity.badRequest().body(ErrorResponse("malformed_request", listOf(detail)))
    }

    @ExceptionHandler(AmqpException::class)
    fun broker(e: AmqpException): ResponseEntity<ErrorResponse> {
        log.error("Broker unavailable", e)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse("broker_unavailable", emptyList()))
    }
}
