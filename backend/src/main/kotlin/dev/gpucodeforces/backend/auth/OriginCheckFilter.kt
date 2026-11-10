package dev.gpucodeforces.backend.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private val MUTATING_METHODS = setOf("POST", "PUT", "DELETE", "PATCH")

/**
 * Replaces CSRF tokens with the contract's same-origin rule: mutating requests must carry an
 * Origin header that matches this server's own origin (frontend and API share an origin).
 */
@Component
class OriginCheckFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (request.method in MUTATING_METHODS) {
            val origin = request.getHeader("Origin")
            val expected = "${request.scheme}://${request.getHeader("Host")}"
            if (origin != null && origin != expected) {
                response.status = HttpStatus.FORBIDDEN.value()
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.writer.write("""{"message":"Недопустимый источник запроса"}""")
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}
