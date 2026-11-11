package dev.gpucodeforces.backend.auth

import dev.gpucodeforces.backend.api.model.ErrorResponse
import dev.gpucodeforces.backend.common.RequestLogFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.json.JsonMapper

/**
 * The frontend contract's `security: [{}, {session: []}]` default means most operations are
 * optionally authenticated; only the routes listed below require a session. Everything else
 * stays permitAll here and reads the resolved [UserPrincipal] (or its absence) in the service layer.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val sessionCookieAuthenticationFilter: SessionCookieAuthenticationFilter,
    private val originCheckFilter: OriginCheckFilter,
    private val jsonMapper: JsonMapper,
) {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/**")
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> writeError(response, HttpStatus.UNAUTHORIZED, "Нужен вход") }
                it.accessDeniedHandler { _, response, _ -> writeError(response, HttpStatus.FORBIDDEN, "Нет доступа") }
            }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/session").authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/session").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/submissions/*/recheck").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/submissions").authenticated()
                    .requestMatchers("/api/virtual", "/api/virtual/*").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/contests/*/virtual", "/api/hacks", "/api/hacks/*/retry").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/problems/*/example").authenticated()
                    .requestMatchers("/api/admin/**").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/contests").authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/contests/*").authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/contests/*/registration").authenticated()
                    .anyRequest().permitAll()
            }
            .addFilterBefore(originCheckFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(sessionCookieAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(RequestLogFilter(), SessionCookieAuthenticationFilter::class.java)
        return http.build()
    }

    private fun writeError(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(jsonMapper.writeValueAsString(ErrorResponse(message)))
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(userDetailsService: UserDetailsService, passwordEncoder: PasswordEncoder): AuthenticationManager {
        val provider = DaoAuthenticationProvider(userDetailsService)
        provider.setPasswordEncoder(passwordEncoder)
        return ProviderManager(provider)
    }
}
