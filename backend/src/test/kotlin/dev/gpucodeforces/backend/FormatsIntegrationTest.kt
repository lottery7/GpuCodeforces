package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.Submission
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistration
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.Problem
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import dev.gpucodeforces.backend.api.model.Contest as ContestApi

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FormatsIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var catalog: ProblemCatalog

    @Autowired
    lateinit var problems: ProblemRepository

    @Autowired
    lateinit var versions: ProblemVersionRepository

    @Autowired
    lateinit var contests: ContestRepository

    @Autowired
    lateinit var contestProblems: ContestProblemRepository

    @Autowired
    lateinit var registrations: ContestRegistrationRepository

    @Autowired
    lateinit var jsonMapper: JsonMapper

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun register(): Pair<User, String> {
        val handle = "formats_${UUID.randomUUID().toString().take(8)}"
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(User::class.java).returnResult()
        return result.responseBody!! to result.responseCookies.getFirst("session")!!.value
    }

    private fun guessVersion(problemId: String = "guess-${UUID.randomUUID()}", firstTimeMs: BigDecimal = BigDecimal("1.25")): ProblemVersion {
        val template = catalog.currentVersion("vector_add")!!
        if (!problems.existsById(problemId)) problems.save(Problem(problemId, source = "comparison"))
        val format = ProblemFormatData(
            format = "guess", firstTimeMs = firstTimeMs, secondTimeMs = BigDecimal("2.5"),
            firstCode = "void first() {}", secondCode = "void second() {}", explanation = "Первый вариант быстрее",
        )
        val version = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = problemId, code = "G", title = "Угадай", difficulty = "easy",
            tags = emptyList(), authors = emptyList(), draft = false, statementMarkdown = "Какой код быстрее?",
            starterCode = "", judgeSpec = template.judgeSpec, contentHash = UUID.randomUUID().toString(),
            judgeHash = UUID.randomUUID().toString(), revision = null, format = "guess",
            formatData = jsonMapper.writeValueAsString(format),
        ))
        problems.save(problems.findById(problemId).orElseThrow().apply { currentVersionId = version.id })
        return version
    }

    private fun submit(id: String, cookie: String, choice: String = "first", context: String = "practice"): Submission =
        client.post().uri("/problems/$id/guess?context=$context").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", cookie).body(mapOf("choice" to choice)).exchange().expectStatus().isOk
            .expectBody(Submission::class.java).returnResult().responseBody!!

    @Test
    fun `guess answers are private immutable and concurrent retries share one submission`() {
        val (_, cookie) = register()
        val (_, otherCookie) = register()
        val version = guessVersion()
        val id = version.problemId
        val publicBody = client.get().uri("/problems/$id").exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!
        val publicJson = jsonMapper.readTree(publicBody)
        assertThat(publicJson.has("firstTimeMs")).isFalse()
        assertThat(publicJson.has("explanation")).isFalse()
        assertThat(publicJson.get("firstCode").asString()).isEqualTo("void first() {}")
        client.post().uri("/problems/$id/guess").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("choice" to "first")).exchange().expectStatus().isUnauthorized

        val first = CompletableFuture.supplyAsync { submit(id, cookie) }
        val second = CompletableFuture.supplyAsync { submit(id, cookie) }
        val result = first.get(30, TimeUnit.SECONDS)
        assertThat(second.get(30, TimeUnit.SECONDS).id).isEqualTo(result.id)
        assertThat(result.status).isEqualTo(Submission.StatusEnum.ACCEPTED)
        assertThat(result.timeMs).isNull()
        assertThat(result.comparable).isFalse()
        assertThat(result.guessResult!!.correctChoice.value).isEqualTo("first")
        client.post().uri("/problems/$id/guess").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(mapOf("choice" to "second")).exchange().expectStatus().isBadRequest

        val outsider = client.get().uri("/submissions/${result.id}").cookie("session", otherCookie)
            .exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!
        val outsiderJson = jsonMapper.readTree(outsider)
        assertThat(outsiderJson.has("guessResult")).isFalse()
        assertThat(outsiderJson.has("code")).isFalse()
        assertThat(outsiderJson.get("format").asString()).isEqualTo("guess")
        val detail = client.get().uri("/problems/$id").cookie("session", cookie).exchange().expectStatus().isOk
            .expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(detail.guessSubmissionId).isEqualTo(result.id)

        client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(mapOf("problemId" to id, "code" to "void solve() {}" )).exchange().expectStatus().isBadRequest
        client.post().uri("/problems/$id/example").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(mapOf("code" to "void solve() {}" )).exchange().expectStatus().isBadRequest
        val leaders = client.get().uri("/problems/$id/leaders").exchange().expectStatus().isOk
            .expectBody().returnResult().responseBody!!
        assertThat(jsonMapper.readTree(leaders).size()).isZero()

        guessVersion(id, BigDecimal("3.75"))
        val fresh = client.get().uri("/problems/$id").cookie("session", cookie).exchange().expectStatus().isOk
            .expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(fresh.guessSubmissionId).isNull()
        val freshResult = submit(id, cookie, "second")
        assertThat(freshResult.id).isNotEqualTo(result.id)
        assertThat(freshResult.status).isEqualTo(Submission.StatusEnum.ACCEPTED)
        val saved = client.get().uri("/submissions/${result.id}").cookie("session", cookie).exchange().expectStatus().isOk
            .expectBody(Submission::class.java).returnResult().responseBody!!
        assertThat(saved.guessResult!!.firstTimeMs).isEqualByComparingTo("1.25")
    }

    @Test
    fun `a contest guess adds a solved problem and its submission time without a GPU measurement`() {
        val (user, cookie) = register()
        val version = guessVersion()
        val contest = contests.save(Contest("Угадай", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "GPU"))
        contestProblems.save(ContestProblem(contest.id, version.problemId, 0, version.id))
        registrations.save(ContestRegistration(contest.id, UUID.fromString(user.id)))

        submit(version.problemId, cookie, context = contest.id.toString())

        val rows = client.get().uri("/contests/${contest.id}/standings").exchange().expectStatus().isOk
            .expectBody(Array<GetContestStandings200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(rows).hasSize(1)
        assertThat(rows.single().solved).isEqualTo(1)
        assertThat(rows.single().times).containsOnlyKeys(version.problemId)
        assertThat(rows.single().totalSeconds).isGreaterThanOrEqualTo(0)
        assertThat(rows.single().times.getValue(version.problemId)).isEqualTo(rows.single().totalSeconds)
    }

    @Test
    fun `a duel registers its challenger and admits only its fixed opponent`() {
        val (challenger, cookie) = register()
        val (opponent, opponentCookie) = register()
        val (outsider, outsiderCookie) = register()
        val start = Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        val input = ContestInput("Дуэль", start, start.plusHours(1), setOf(challenger.id), setOf("vector_add"))
            .format(ContestInput.FormatEnum.DUEL).opponentId(opponent.id)

        val duel = client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(input).exchange().expectStatus().isCreated.expectBody(ContestApi::class.java).returnResult().responseBody!!
        assertThat(duel.registered).isTrue()
        assertThat(duel.challengerId).isEqualTo(challenger.id)
        assertThat(duel.opponentId).isEqualTo(opponent.id)
        assertThat(duel.format).isEqualTo(ContestApi.FormatEnum.DUEL)
        assertThat(duel.authors.map { it.id }).containsExactly(challenger.id)
        assertThat(duel.problemIds).containsExactly("vector_add")

        client.put().uri("/contests/${duel.id}/registration").cookie("session", opponentCookie)
            .exchange().expectStatus().isNoContent
        client.put().uri("/contests/${duel.id}/registration").cookie("session", outsiderCookie)
            .exchange().expectStatus().isForbidden
        val participantView = client.get().uri("/contests/${duel.id}").cookie("session", opponentCookie)
            .exchange().expectStatus().isOk.expectBody(ContestApi::class.java).returnResult().responseBody!!
        assertThat(participantView.registered).isTrue()

        client.put().uri("/contests/${duel.id}").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(input.opponentId(outsider.id)).exchange().expectStatus().isBadRequest
        client.put().uri("/contests/${duel.id}").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(input.opponentId(opponent.id).authorIds(setOf(challenger.id, outsider.id)))
            .exchange().expectStatus().isBadRequest
        client.put().uri("/contests/${duel.id}").contentType(MediaType.APPLICATION_JSON).cookie("session", opponentCookie)
            .body(input.authorIds(setOf(challenger.id))).exchange().expectStatus().isForbidden
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
