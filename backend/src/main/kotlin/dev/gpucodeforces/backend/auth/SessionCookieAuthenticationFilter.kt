package dev.gpucodeforces.backend.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

/** Resolves the `session` cookie into an Authentication; absence or expiry just leaves the request anonymous. */
@Component
class SessionCookieAuthenticationFilter(private val sessionRepository: SessionRepository) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val session = SessionCookies.readToken(request)?.let { sessionRepository.findByTokenAndExpiresAtAfter(it, Instant.now()) }
        if (session != null) {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(UserPrincipal(session.user), session.token, emptyList())
        }
        filterChain.doFilter(request, response)
    }
}
