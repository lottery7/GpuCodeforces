package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.CreateComparisonRequest
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.GuessChoice
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.SubmitGuessRequest
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemSyncService
import dev.gpucodeforces.backend.problem.environmentMarkdown
import dev.gpucodeforces.backend.submission.Submission
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID
import dev.gpucodeforces.backend.api.model.Problem as ProblemApi
import dev.gpucodeforces.backend.api.model.User as UserApi
import dev.gpucodeforces.backend.api.model.Submission as SubmissionApi

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComparisonIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var submissions: SubmissionRepository

    @Autowired
    lateinit var catalog: ProblemCatalog

    @Autowired
    lateinit var problems: ProblemRepository

    @Autowired
    lateinit var sync: ProblemSyncService

    @Autowired
    lateinit var gpu: GpuProperties

    @Autowired
    lateinit var mapper: JsonMapper

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    @Test
    fun `publishes owned submissions as a playable guess that survives repository synchronization`() {
        val registration = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest("comparison_author", "comparison@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(UserApi::class.java).returnResult()
        val authorId = UUID.fromString(registration.responseBody!!.id)
        val cookie = registration.responseCookies.getFirst("session")!!.value
        val source = catalog.currentVersion("vector_add")!!
        val environment = environmentMarkdown(catalog.spec(source), gpu.name)
        fun accepted(code: String, time: String) = submissions.saveAndFlush(
            Submission(UUID.randomUUID(), authorId, source.problemId, source.id, null, code, environment, source.judgeHash, gpu.profile, null).also {
                it.status = SubmissionStatus.ACCEPTED
                it.timeMs = BigDecimal(time)
            },
        )
        val first = accepted("void first() {}", "1.2")
        val second = accepted("void second() {}", "2.4")
        val request = CreateComparisonRequest(first.id.toString(), second.id.toString(), "Первый вариант использует меньше обращений к памяти")

        client.post().uri("/problems/vector_add/comparisons").contentType(MediaType.APPLICATION_JSON)
            .body(request).exchange().expectStatus().isUnauthorized

        val comparison = client.post().uri("/problems/vector_add/comparisons").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", cookie).body(request).exchange().expectStatus().isCreated
            .expectBody(ProblemApi::class.java).returnResult().responseBody!!
        assertThat(comparison.format.value).isEqualTo("guess")
        assertThat(comparison.id).startsWith("comparison-")
        assertThat(problems.findById(comparison.id).orElseThrow().source).isEqualTo("comparison")

        sync.sync()

        val details = client.get().uri("/problems/${comparison.id}").exchange().expectStatus().isOk
            .expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(details.firstCode).isEqualTo(first.code)
        assertThat(details.secondCode).isEqualTo(second.code)
        assertThat(mapper.writeValueAsString(details)).doesNotContain("firstTimeMs", "secondTimeMs", request.explanation)
        assertThat(problems.findById(comparison.id).orElseThrow().currentVersionId).isNotNull()

        val answer = client.post().uri("/problems/${comparison.id}/guess").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", cookie).body(SubmitGuessRequest(GuessChoice.FIRST))
            .exchange().expectStatus().isOk.expectBody(SubmissionApi::class.java).returnResult().responseBody!!
        assertThat(answer.status.value).isEqualTo("accepted")
        assertThat(answer.guessResult!!.explanation).isEqualTo(request.explanation)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
