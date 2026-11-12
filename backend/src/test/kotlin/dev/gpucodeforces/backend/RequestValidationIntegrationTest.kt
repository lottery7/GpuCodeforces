package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.ErrorResponse
import dev.gpucodeforces.backend.api.model.ListLatestSubmissions200Response
import dev.gpucodeforces.backend.api.model.LoginRequest
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

/** Requests the contract does not allow are refused as the client's errors, with a message a person can read. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestValidationIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun refused(uri: String): String = client.get().uri(uri).exchange()
        .expectStatus().isBadRequest.expectBody(ErrorResponse::class.java).returnResult().responseBody!!.message

    @Test
    fun `a page outside the bounds of the contract is refused instead of being read`() {
        // the feed is public: an unbounded limit would read and map the whole table of submissions
        val statuses = listOf("limit=0", "limit=101", "limit=100000000", "offset=-1", "offset=abc")
            .associateWith { client.get().uri("/submissions/latest?$it").exchange().returnResult(String::class.java).status.value() }
        assertThat(statuses.filterValues { it != 400 }).isEmpty()
        assertThat(refused("/users?limit=1000")).isEqualTo("Неверный запрос")
        assertThat(refused("/problems?offset=-20")).isEqualTo("Неверный запрос")

        val page = client.get().uri("/submissions/latest?offset=0&limit=100").exchange()
            .expectStatus().isOk.expectBody(ListLatestSubmissions200Response::class.java).returnResult().responseBody!!
        assertThat(page.items).isEmpty()
    }

    @Test
    fun `a parameter that cannot be read is the client's error`() {
        assertThat(refused("/users/${UUID.randomUUID()}/rating-history?after=yesterday")).isEqualTo("Неверный запрос")
        // a required parameter is missing
        assertThat(refused("/problems/vector_add/leaders")).isEqualTo("Неверный запрос")
        assertThat(refused("/contests?phase=someday")).isEqualTo("Неизвестная фаза контеста")
    }

    @Test
    fun `a password BCrypt cannot hash is refused with the reason`() {
        // 37 Cyrillic letters take 74 bytes in UTF-8, over the 72 BCrypt hashes
        val refused = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest("long_password", "long_password@example.com", "п".repeat(37)))
            .exchange()
            .expectStatus().isBadRequest
            .expectBody(ErrorResponse::class.java).returnResult().responseBody!!
        assertThat(refused.message).contains("72 байт")

        client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest("limit_password", "limit_password@example.com", "a".repeat(72)))
            .exchange()
            .expectStatus().isCreated
    }

    @Test
    fun `refusals of the security filters are readable`() {
        val unauthorized = client.get().uri("/session").exchange()
            .expectStatus().isUnauthorized
            .expectBody(ErrorResponse::class.java).returnResult().responseBody!!
        assertThat(unauthorized.message).isEqualTo("Нужен вход")

        val foreignOrigin = client.post().uri("/session").header("Origin", "http://evil.example")
            .contentType(MediaType.APPLICATION_JSON)
            .body(LoginRequest("somebody", "password123"))
            .exchange()
            .expectStatus().isForbidden
            .expectBody(ErrorResponse::class.java).returnResult().responseBody!!
        assertThat(foreignOrigin.message).isEqualTo("Недопустимый источник запроса")
    }

    @Test
    fun `a page opened over https through a TLS terminator may change data`() {
        val handle = "tls_" + UUID.randomUUID().toString().take(8)
        val setCookie = client.post().uri("/users")
            .header("Origin", "https://localhost:$port")
            .header("X-Forwarded-Proto", "https")
            .contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange()
            .expectStatus().isCreated
            .returnResult(String::class.java).responseHeaders.getFirst(HttpHeaders.SET_COOKIE)!!
        assertThat(setCookie).contains("Secure")

        // without the terminator's header the same Origin is a foreign one
        client.post().uri("/users")
            .header("Origin", "https://localhost:$port")
            .contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest("${handle}_2", "${handle}_2@example.com", "password123"))
            .exchange()
            .expectStatus().isForbidden
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
