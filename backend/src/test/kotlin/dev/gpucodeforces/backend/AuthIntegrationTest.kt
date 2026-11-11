package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.ListUsers200Response
import dev.gpucodeforces.backend.api.model.LoginRequest
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    @Test
    @AllowFullScan("users", "SELECT count\\(\\*\\).*lower\\(handle\\) LIKE", "The one-user fixture is cheaper to scan than to probe GIN; selective search is tested separately with 10000 users", maxTableRows = 1)
    fun `register, duplicate rejection, login, current user, logout`() {
        val handle = "cuda_user"
        val registerRequest = RegisterUserRequest(handle, "cuda_user@example.com", "password123")

        val registerResult = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(registerRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody(User::class.java)
            .returnResult()
        assertThat(registerResult.responseBody?.handle).isEqualTo(handle)
        assertThat(registerResult.responseBody?.ratings).isEmpty()
        val registerSessionCookie = registerResult.responseCookies.getFirst("session")?.value
        assertThat(registerSessionCookie).isNotNull()

        client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(registerRequest)
            .exchange()
            .expectStatus().isBadRequest

        val loginResult = client.post().uri("/session").contentType(MediaType.APPLICATION_JSON)
            .body(LoginRequest(handle, "password123"))
            .exchange()
            .expectStatus().isOk
            .expectBody(User::class.java)
            .returnResult()
        val sessionCookie = loginResult.responseCookies.getFirst("session")!!.value

        client.post().uri("/session").contentType(MediaType.APPLICATION_JSON)
            .body(LoginRequest(handle, "wrong-password"))
            .exchange()
            .expectStatus().isUnauthorized

        val meResult = client.get().uri("/session")
            .cookie("session", sessionCookie)
            .exchange()
            .expectStatus().isOk
            .expectBody(User::class.java)
            .returnResult()
        assertThat(meResult.responseBody?.handle).isEqualTo(handle)

        client.get().uri("/session").exchange().expectStatus().isUnauthorized

        val listResult = client.get().uri("/users?q=$handle")
            .exchange()
            .expectStatus().isOk
            .expectBody(ListUsers200Response::class.java)
            .returnResult()
        assertThat(listResult.responseBody?.items?.map { it.handle }).containsExactly(handle)

        client.delete().uri("/session")
            .cookie("session", sessionCookie)
            .exchange()
            .expectStatus().isNoContent

        client.get().uri("/session")
            .cookie("session", sessionCookie)
            .exchange()
            .expectStatus().isUnauthorized
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
