package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.ListProblems200Response
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
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurementView
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
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
import dev.gpucodeforces.backend.submission.Submission as StoredSubmission

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

    @Autowired
    lateinit var submissions: SubmissionRepository

    @Autowired
    lateinit var gpu: GpuProperties

    @MockitoBean
    lateinit var references: ReferenceMeasurements

    @BeforeEach
    fun measurementEnvironment() {
        given(references.environmentKey()).willReturn("environment-1")
    }

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
            format = "guess",
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
        measured(version, "environment-1", firstTimeMs)
        return version
    }

    private fun measured(version: ProblemVersion, key: String, firstTimeMs: BigDecimal) {
        val result = ReferenceMeasurementView(
            key = "${version.id}:$key", status = "ready", baselineTimeMs = null,
            firstTimeMs = firstTimeMs, secondTimeMs = BigDecimal("2.5"),
            measuredAt = Instant.now(), synthetic = false, diagnostic = null, environmentKey = key,
        )
        given(references.view(version)).willReturn(result)
        given(references.requireReady(version)).willReturn(result)
    }

    private fun submit(id: String, cookie: String, choice: String = "first", context: String = "practice"): Submission {
        val problem = client.get().uri("/problems/$id?context=$context").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        return client.post().uri("/problems/$id/guess?context=$context").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", cookie).body(mapOf("choice" to choice, "benchmarkKey" to problem.referenceMeasurement!!.key)).exchange().expectStatus().isOk
            .expectBody(Submission::class.java).returnResult().responseBody!!
    }

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
            .body(mapOf("choice" to "second", "benchmarkKey" to "${version.id}:environment-1")).exchange().expectStatus().isBadRequest

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
    fun `the same version gets a new attempt after an environment change and keeps its old answer snapshot`() {
        val (_, cookie) = register()
        val version = guessVersion()
        val previous = submit(version.problemId, cookie)
        assertThat(previous.guessResult!!.firstTimeMs).isEqualByComparingTo("1.25")

        given(references.environmentKey()).willReturn("environment-2")
        measured(version, "environment-2", BigDecimal("3.75"))

        client.post().uri("/problems/${version.problemId}/guess").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(mapOf("choice" to "first", "benchmarkKey" to "${version.id}:environment-1"))
            .exchange().expectStatus().isBadRequest

        val currentProblem = client.get().uri("/problems/${version.problemId}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(currentProblem.versionId).isEqualTo(version.id.toString())
        assertThat(currentProblem.guessSubmissionId).isNull()
        val current = submit(version.problemId, cookie, "second")
        assertThat(current.id).isNotEqualTo(previous.id)
        assertThat(current.status).isEqualTo(Submission.StatusEnum.ACCEPTED)
        assertThat(current.guessResult!!.firstTimeMs).isEqualByComparingTo("3.75")

        val old = client.get().uri("/submissions/${previous.id}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(Submission::class.java).returnResult().responseBody!!
        assertThat(old.guessResult!!.firstTimeMs).isEqualByComparingTo("1.25")
        assertThat(old.guessResult!!.correctChoice.value).isEqualTo("first")
    }

    @Test
    fun `a missing benchmark key is rejected without consuming an answer`() {
        val (_, cookie) = register()
        val version = guessVersion()

        client.post().uri("/problems/${version.problemId}/guess").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(mapOf("choice" to "first")).exchange().expectStatus().isBadRequest

        val problem = client.get().uri("/problems/${version.problemId}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(problem.guessSubmissionId).isNull()
        assertThat(submit(version.problemId, cookie).status).isEqualTo(Submission.StatusEnum.ACCEPTED)
    }

    @Test
    fun `optimization baseline and best results stay in the captured cohort after the environment changes`() {
        val (user, cookie) = register()
        val template = catalog.currentVersion("vector_add")!!
        val problemId = "optimization-${UUID.randomUUID()}"
        problems.save(Problem(problemId, source = "comparison"))
        val version = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = problemId, code = "O", title = "Optimization $problemId", difficulty = "easy",
            tags = emptyList(), authors = emptyList(), draft = false, statementMarkdown = "Ускорьте решение",
            starterCode = "void solve() {}", judgeSpec = template.judgeSpec, contentHash = UUID.randomUUID().toString(),
            judgeHash = UUID.randomUUID().toString(), revision = null, format = "optimization",
            formatData = jsonMapper.writeValueAsString(ProblemFormatData(format = "optimization")),
        ))
        problems.save(problems.findById(problemId).orElseThrow().apply { currentVersionId = version.id })
        val reference = ReferenceMeasurementView(
            key = "${version.id}:environment-A", status = "ready", baselineTimeMs = BigDecimal("20"),
            firstTimeMs = null, secondTimeMs = null, measuredAt = Instant.now(), synthetic = false,
            diagnostic = null, environmentKey = "environment-A",
        )
        given(references.view(version)).willReturn(reference)
        given(references.environmentKey()).willReturn("environment-B")
        fun accepted(time: String, environment: String) = submissions.saveAndFlush(
            StoredSubmission(
                UUID.randomUUID(), UUID.fromString(user.id), problemId, version.id, null,
                "void solve() {}", "GPU", version.judgeHash, gpu.profile, null,
            ).also {
                it.status = SubmissionStatus.ACCEPTED
                it.timeMs = BigDecimal(time)
                it.benchmarkEnvironmentKey = environment
            },
        )
        val captured = accepted("4", "environment-A")
        accepted("1", "environment-B")

        val detail = client.get().uri("/problems/$problemId").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(detail.baselineTimeMs).isEqualByComparingTo("20")
        assertThat(detail.bestTimeMs).isEqualByComparingTo("4")
        assertThat(detail.recordTimeMs).isEqualByComparingTo("4")
        assertThat(detail.bestSubmissionId).isEqualTo(captured.id.toString())

        val listed = client.get().uri("/problems?q=$problemId").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
            .items.single { it.id == problemId }
        assertThat(listed.baselineTimeMs).isEqualByComparingTo("20")
        assertThat(listed.bestTimeMs).isEqualByComparingTo("4")
        assertThat(listed.recordTimeMs).isEqualByComparingTo("4")
        assertThat(listed.bestSubmissionId).isEqualTo(captured.id.toString())
    }

    @Test
    fun `the generic contest API rejects scheduled duels`() {
        val (challenger, cookie) = register()
        val (opponent, _) = register()
        val start = Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        val input = ContestInput("Дуэль", start, start.plusHours(1), setOf(challenger.id), setOf("vector_add"))
            .format(ContestInput.FormatEnum.DUEL).opponentId(opponent.id)

        client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(input).exchange().expectStatus().isBadRequest
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
