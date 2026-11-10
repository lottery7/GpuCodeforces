package com.example.gpuplatform.common.api

import com.example.gpuplatform.sync.domain.ProblemParseException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(e.status, e.message, request)

    @ExceptionHandler(ProblemParseException::class)
    fun handleParse(e: ProblemParseException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.UNPROCESSABLE_ENTITY, e.message ?: "Problem parsing error", request)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, "Invalid value for parameter '${e.name}'", request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "Validation failed", request)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, "Malformed request body", request)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.NOT_FOUND, "Resource not found", request)

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception for {} {}", request.method, request.requestURI, e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request)
    }

    private fun respond(status: HttpStatus, message: String, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(
            ErrorResponse(
                timestamp = Instant.now().toString(),
                status = status.value(),
                error = status.reasonPhrase,
                message = message,
                path = request.requestURI,
            )
        )
}