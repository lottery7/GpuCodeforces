package dev.gpucodeforces.backend.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import java.time.Duration
import java.util.UUID

/** Cookie is HttpOnly/SameSite=Lax per the contract; Secure mirrors the request scheme so http-only local dev still works. */
object SessionCookies {
    const val COOKIE_NAME = "session"
    val TTL: Duration = Duration.ofDays(30)

    fun readToken(request: HttpServletRequest): UUID? =
        request.cookies
            ?.firstOrNull { it.name == COOKIE_NAME }
            ?.value
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }

    fun set(request: HttpServletRequest, response: HttpServletResponse, token: UUID) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, token.toString(), TTL).toString())
    }

    fun clear(request: HttpServletRequest, response: HttpServletResponse) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, "", Duration.ZERO).toString())
    }

    private fun build(request: HttpServletRequest, value: String, maxAge: Duration) =
        ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true)
            .secure(request.isSecure)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAge)
            .build()
}
