package dev.gpucodeforces.backend.common

import dev.gpucodeforces.backend.api.model.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.ErrorResponse as SpringErrorResponse

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ErrorResponse> {
        if (exception.status.is5xxServerError) log.warn("{}: {}", exception.status.value(), exception.message) else log.info("{}: {}", exception.status.value(), exception.message)
        return ResponseEntity.status(exception.status).body(ErrorResponse(exception.message!!))
    }

    @ExceptionHandler(
        MethodArgumentNotValidException::class, HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class,
    )
    fun handleMalformedRequest(exception: Exception): ResponseEntity<ErrorResponse> {
        log.info("400: malformed request: {}", exception.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("Неверный запрос"))
    }

    /**
     * Spring's other client errors (unknown route, wrong method) keep their status. Anything else is
     * a bug: full stack trace in the log, no internals in the response.
     */
    @ExceptionHandler(Exception::class)
    fun handleOther(exception: Exception): ResponseEntity<ErrorResponse> {
        if (exception is SpringErrorResponse && !exception.statusCode.is5xxServerError) {
            val status = exception.statusCode
            log.info("{}: {}", status.value(), exception.message)
            return ResponseEntity.status(status).body(ErrorResponse(HttpStatus.resolve(status.value())?.reasonPhrase ?: "Ошибка запроса"))
        }
        log.error("Unhandled exception", exception)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Внутренняя ошибка сервера"))
    }
}
