package dev.gpucodeforces.backend.auth

import dev.gpucodeforces.backend.user.User
import org.springframework.security.core.context.SecurityContextHolder

/** Many routes are optionally authenticated, so callers must handle the null (anonymous) case. */
object CurrentUser {
    fun resolve(): User? =
        (SecurityContextHolder.getContext().authentication?.principal as? UserPrincipal)?.user
}
