package dev.gpucodeforces.backend.common

import dev.gpucodeforces.backend.auth.CurrentUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * One line per API request: method, path, status, duration and user (also put into the MDC as `user`, so every
 * line logged while serving the request carries it). Changes, errors and slow requests are INFO/WARN, plain
 * successful reads are DEBUG. Runs inside the security chain, after the session cookie has been resolved.
 */
class RequestLogFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val started = System.nanoTime()
        val user = CurrentUser.resolve()?.handle ?: "-"
        MDC.putCloseable(USER, user).use {
            try {
                filterChain.doFilter(request, response)
            } finally {
                val millis = (System.nanoTime() - started) / 1_000_000
                val status = response.status
                val target = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
                when {
                    status >= 500 -> log.error("{} {} -> {} in {} ms", request.method, target, status, millis)
                    millis >= SLOW_MILLIS -> log.warn("{} {} -> {} in {} ms (slow)", request.method, target, status, millis)
                    status >= 400 || request.method != "GET" -> log.info("{} {} -> {} in {} ms", request.method, target, status, millis)
                    else -> log.debug("{} {} -> {} in {} ms", request.method, target, status, millis)
                }
            }
        }
    }

    companion object {
        const val USER = "user"
        // checkExample legitimately waits for the judge; everything else should be far below this.
        private const val SLOW_MILLIS = 2_000L
    }
}
