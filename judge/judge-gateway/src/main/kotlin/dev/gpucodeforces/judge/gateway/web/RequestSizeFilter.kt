package dev.gpucodeforces.judge.gateway.web

import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.intellij.lang.annotations.Language
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Rejects oversized request bodies before they are parsed into objects. A request that passed would not fit into a
 * broker message either, and "broker unavailable" would make the caller retry it forever.
 */
@Component
class RequestSizeFilter(private val props: GatewayProperties) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val length = request.contentLengthLong
        when {
            length > props.maxRequestBytes ->
                reject(response, HttpStatus.CONTENT_TOO_LARGE, "request_too_large", "the request body is $length bytes, limit ${props.maxRequestBytes}")
            // without a length the body could only be limited while it is being read
            length < 0 && request.method in METHODS_WITH_BODY ->
                reject(response, HttpStatus.LENGTH_REQUIRED, "length_required", "Content-Length is required")
            else -> chain.doFilter(request, response)
        }
    }

    private fun reject(response: HttpServletResponse, status: HttpStatus, error: String, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        @Language("JSON")
        val body = """{"error":"$error","details":["$detail"]}"""
        response.writer.write(body)
    }

    private companion object {
        val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")
    }
}
