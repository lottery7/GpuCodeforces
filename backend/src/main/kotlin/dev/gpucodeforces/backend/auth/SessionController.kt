package dev.gpucodeforces.backend.auth

import dev.gpucodeforces.backend.api.SessionApi
import dev.gpucodeforces.backend.api.model.LoginRequest
import dev.gpucodeforces.backend.common.UnauthorizedException
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import dev.gpucodeforces.backend.api.model.User as UserApiModel

@RestController
class SessionController(
    private val authenticationManager: AuthenticationManager,
    private val sessionIssuer: SessionIssuer,
) : SessionApi {
    // SecurityConfig requires authentication on this route, so a principal is always present here.
    override fun getCurrentUser(): ResponseEntity<UserApiModel> {
        val principal = SecurityContextHolder.getContext().authentication!!.principal as UserPrincipal
        return ResponseEntity.ok(principal.user.toApiModel())
    }

    override fun login(loginRequest: LoginRequest): ResponseEntity<UserApiModel> {
        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(loginRequest.identifier, loginRequest.password),
            )
        } catch (exception: BadCredentialsException) {
            throw UnauthorizedException("Неверный ник, почта или пароль")
        }
        val principal = authentication.principal as UserPrincipal
        sessionIssuer.issue(principal.user)
        return ResponseEntity.ok(principal.user.toApiModel())
    }

    override fun logout(): ResponseEntity<Void> {
        val token = SecurityContextHolder.getContext().authentication!!.credentials as UUID
        sessionIssuer.revoke(token)
        return ResponseEntity.noContent().build()
    }
}
