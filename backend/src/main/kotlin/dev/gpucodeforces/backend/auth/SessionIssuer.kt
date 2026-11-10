package dev.gpucodeforces.backend.auth

import dev.gpucodeforces.backend.user.User
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant
import java.util.UUID

/** Mints/revokes sessions and sets/clears the cookie on the current request; shared by login and registration. */
@Service
class SessionIssuer(private val sessionRepository: SessionRepository) {
    fun issue(user: User): Session {
        val session = sessionRepository.save(Session(user, Instant.now().plus(SessionCookies.TTL)))
        val attributes = currentRequestAttributes()
        SessionCookies.set(attributes.request, attributes.response!!, session.token)
        return session
    }

    fun revoke(token: UUID) {
        sessionRepository.deleteById(token)
        val attributes = currentRequestAttributes()
        SessionCookies.clear(attributes.request, attributes.response!!)
    }

    private fun currentRequestAttributes() = RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
}
