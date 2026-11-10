package com.example.gpuplatform.sync.api

import com.example.gpuplatform.config.ProblemsProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Guards endpoints under "/api/internal" with an optional shared secret. When
 * [ProblemsProperties.internalApiKey] is empty, no check is performed (rely on
 * network/deployment-layer protection).
 */
@Component
class InternalApiInterceptor(
    private val props: ProblemsProperties,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val configuredKey = props.internalApiKey
        if (configuredKey.isEmpty()) {
            return true
        }
        val providedKey = request.getHeader("X-Internal-Key") ?: ""
        if (java.security.MessageDigest.isEqual(
                configuredKey.toByteArray(),
                providedKey.toByteArray(),
            )
        ) {
            return true
        }

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.writer,
            mapOf(
                "status" to HttpStatus.UNAUTHORIZED.value(),
                "error" to "Unauthorized",
                "message" to "Invalid or missing internal API key",
                "path" to request.requestURI,
            )
        )
        return false
    }
}