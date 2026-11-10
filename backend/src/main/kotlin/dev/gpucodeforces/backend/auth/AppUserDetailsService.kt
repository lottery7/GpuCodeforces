package dev.gpucodeforces.backend.auth

import dev.gpucodeforces.backend.user.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class AppUserDetailsService(private val userRepository: UserRepository) : UserDetailsService {
    /** identifier is handle or email, per the login contract. */
    override fun loadUserByUsername(identifier: String): UserDetails =
        userRepository.findByHandleIgnoreCaseOrEmailIgnoreCase(identifier, identifier)
            ?.let(::UserPrincipal)
            ?: throw UsernameNotFoundException("Unknown identifier: $identifier")
}
