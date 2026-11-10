package dev.gpucodeforces.backend.auth

import dev.gpucodeforces.backend.user.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/** No role concept in the MVP; resource-level checks (contest authorship, profile ownership) live in services. */
class UserPrincipal(val user: User) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun getPassword(): String = user.passwordHash

    override fun getUsername(): String = user.id.toString()
}
