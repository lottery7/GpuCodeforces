package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContestIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun registerAndGetCookie(handle: String): Pair<User, String> {
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange()
            .expectStatus().isCreated
            .expectBody(User::class.java)
            .returnResult()
        val cookie = result.responseCookies.getFirst("session")!!.value
        return result.responseBody!! to cookie
    }

    @Test
    fun `create, visibility before start, author-only update, registration and empty standings`() {
        val (author, authorCookie) = registerAndGetCookie("contest_author")
        val (_, outsiderCookie) = registerAndGetCookie("contest_outsider")

        val startAt = Instant.now().plus(1, ChronoUnit.DAYS)
        val endAt = startAt.plus(2, ChronoUnit.HOURS)
        val input = ContestInput(
            "Весенний раунд",
            startAt.atOffset(ZoneOffset.UTC),
            endAt.atOffset(ZoneOffset.UTC),
            setOf(author.id),
            setOf("vector_add", "matmul_host"),
            "gpu-cuda",
        )

        val created = client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", authorCookie)
            .body(input)
            .exchange()
            .expectStatus().isCreated
            .expectBody(Contest::class.java)
            .returnResult()
            .responseBody!!
        assertThat(created.problemIds).containsExactly("vector_add", "matmul_host")
        assertThat(created.registered).isFalse()
        assertThat(created.processor).isEqualTo("gpu-cuda")
        assertThat(client.get().uri("/contests?processor=gpu-cuda").exchange().expectBody(Array<Contest>::class.java).returnResult().responseBody!!.map { it.id })
            .contains(created.id)
        assertThat(client.get().uri("/contests?processor=rtl").exchange().expectBody(Array<Contest>::class.java).returnResult().responseBody!!).isEmpty()
        client.get().uri("/contests?processor=nope").exchange().expectStatus().isBadRequest

        // Anonymous visitors see no problem ids before the contest starts.
        val anonymousView = client.get().uri("/contests/${created.id}")
            .exchange().expectStatus().isOk.expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(anonymousView.problemIds).isEmpty()
        assertThat(anonymousView.problemCount).isEqualTo(2)

        // The author still sees the full ordered list before start.
        val authorView = client.get().uri("/contests/${created.id}")
            .cookie("session", authorCookie)
            .exchange().expectStatus().isOk.expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(authorView.problemIds).containsExactly("vector_add", "matmul_host")

        // A non-author cannot update the contest.
        client.put().uri("/contests/${created.id}").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", outsiderCookie)
            .body(input)
            .exchange().expectStatus().isForbidden

        // The author can update it, e.g. reorder problems.
        val reordered = input.problemIds(linkedSetOf("matmul_host", "vector_add"))
        val updated = client.put().uri("/contests/${created.id}").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", authorCookie)
            .body(reordered)
            .exchange().expectStatus().isOk.expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(updated.problemIds).containsExactly("matmul_host", "vector_add")

        // mine=true only returns contests authored by the caller.
        val mine = client.get().uri("/contests?mine=true")
            .cookie("session", authorCookie)
            .exchange().expectStatus().isOk
            .expectBody(Array<Contest>::class.java).returnResult().responseBody!!
        assertThat(mine.map { it.id }).containsExactly(created.id)

        // Registration is available to anyone logged in, not just authors, and repeating it is a no-op.
        client.put().uri("/contests/${created.id}/registration").cookie("session", outsiderCookie)
            .exchange().expectStatus().isNoContent
        client.put().uri("/contests/${created.id}/registration").cookie("session", outsiderCookie)
            .exchange().expectStatus().isNoContent

        // No accepted submissions exist anywhere yet, and the contest hasn't started: standings are empty.
        val standings = client.get().uri("/contests/${created.id}/standings")
            .exchange().expectStatus().isOk
            .expectBody(Array<GetContestStandings200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(standings).isEmpty()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
