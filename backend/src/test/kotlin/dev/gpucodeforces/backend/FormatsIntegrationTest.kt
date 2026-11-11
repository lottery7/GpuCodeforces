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
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.ScoreType
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

    @MockitoBean
    lateinit var references: ReferenceMeasurements

    @BeforeEach
    fun measurementEnvironment() {
        given(references.environmentKey(GPU)).willReturn("environment-1")
    }

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun register(): Pair<User, String> {
        val handle = "formats_${UUID.randomUUID().toString().take(8)}"
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(User::class.java).returnResult()
        return result.responseBody!! to result.responseCookies.getFirst("session")!!.value
    }

    /** A guess on [processor] (`gpu-cuda` or `rtl`), built on the `vector_add` example. */
    private fun guessVersion(
        problemId: String = "guess-${UUID.randomUUID()}", firstTimeMs: BigDecimal = BigDecimal("1.25"), processor: String = GPU,
    ): ProblemVersion {
        val template = catalog.currentVersion("vector_add")!!
        val judgeSpec = if (processor == GPU) {
            template.judgeSpec
        } else {
            jsonMapper.writeValueAsString(catalog.spec(template).copy(processors = listOf(ProcessorSpec(processor, listOf("systemverilog"), limit = 1e6))))
        }
        if (!problems.existsById(problemId)) problems.save(Problem(problemId, source = "comparison"))
        val format = ProblemFormatData(
            format = "guess",
            firstCode = "void first() {}", secondCode = "void second() {}", explanation = "Первый вариант быстрее",
        )
        val version = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = problemId, code = "G", title = "Угадай", difficulty = "easy",
            tags = emptyList(), authors = emptyList(), draft = false, statementMarkdown = "Какой код быстрее?",
            processors = listOf(processor), starters = emptyMap(), judgeSpec = judgeSpec, contentHash = UUID.randomUUID().toString(),
            judgeHash = UUID.randomUUID().toString(), revision = null, format = "guess",
            formatData = jsonMapper.writeValueAsString(format),
        ))
        problems.save(problems.findById(problemId).orElseThrow().apply { currentVersionId = version.id })
        measured(version, "environment-1", firstTimeMs, processor)
        return version
    }

    private fun measured(version: ProblemVersion, key: String, first: BigDecimal, processor: String = GPU) {
        val result = ReferenceMeasurementView(
            processor = processor, key = "${version.id}:$key", status = "ready",
            scoreType = if (processor == GPU) ScoreType.TIME_MS else ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS, baselineScore = null,
            firstScore = first, secondScore = BigDecimal("2.5"),
            measuredAt = Instant.now(), synthetic = false, diagnostic = null, environmentKey = key,
        )
        given(references.views(version)).willReturn(mapOf(processor to result))
        given(references.requireReady(version, processor)).willReturn(result)
    }

    private fun submit(id: String, cookie: String, choice: String = "first", context: String = "practice"): Submission {
        val problem = client.get().uri("/problems/$id?context=$context").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        return client.post().uri("/problems/$id/guess?context=$context").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", cookie).body(mapOf("choice" to choice, "benchmarkKey" to problem.processors.single().referenceMeasurement!!.key))
            .exchange().expectStatus().isOk
            .expectBody(Submission::class.java).returnResult().responseBody!!
    }

    private fun optimizationVersion(): ProblemVersion {
        val template = catalog.currentVersion("vector_add")!!
        val problemId = "optimization-${UUID.randomUUID()}"
        problems.save(Problem(problemId, source = "comparison"))
        val version = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = problemId, code = "O", title = "Optimization $problemId", difficulty = "easy",
            tags = emptyList(), authors = emptyList(), draft = false, statementMarkdown = "Ускорьте решение",
            processors = listOf(GPU), starters = mapOf(GPU to mapOf("cuda" to "void solve() {}")), judgeSpec = template.judgeSpec,
            contentHash = UUID.randomUUID().toString(), judgeHash = UUID.randomUUID().toString(), revision = null, format = "optimization",
            formatData = jsonMapper.writeValueAsString(ProblemFormatData(format = "optimization")),
        ))
        problems.save(problems.findById(problemId).orElseThrow().apply { currentVersionId = version.id })
        return version
    }

    @Test
    fun `guess answers are private immutable and concurrent retries share one submission`() {
        val (_, cookie) = register()
        val (_, otherCookie) = register()
        val version = guessVersion()
        val id = version.problemId
        val publicBody = client.get().uri("/problems/$id").exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!
        val publicJson = jsonMapper.readTree(publicBody)
        assertThat(publicJson.toString()).doesNotContain("firstScore", "secondScore")
        assertThat(publicJson.has("explanation")).isFalse()
        assertThat(publicJson.get("firstCode").asString()).isEqualTo("void first() {}")
        client.post().uri("/problems/$id/guess").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("choice" to "first")).exchange().expectStatus().isUnauthorized

        val first = CompletableFuture.supplyAsync { submit(id, cookie) }
        val second = CompletableFuture.supplyAsync { submit(id, cookie) }
        val result = first.get(30, TimeUnit.SECONDS)
        assertThat(second.get(30, TimeUnit.SECONDS).id).isEqualTo(result.id)
        assertThat(result.status).isEqualTo(Submission.StatusEnum.ACCEPTED)
        assertThat(result.score).isNull()
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
        val leaders = client.get().uri("/problems/$id/leaders?processor=$GPU").exchange().expectStatus().isOk
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
        assertThat(saved.guessResult!!.firstScore.value).isEqualByComparingTo("1.25")
    }

    @Test
    fun `a contest guess adds a solved problem and its submission time without a GPU measurement`() {
        val (user, cookie) = register()
        val version = guessVersion()
        val contest = contests.save(Contest("Угадай", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), GPU))
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
        assertThat(previous.guessResult!!.firstScore.value).isEqualByComparingTo("1.25")

        given(references.environmentKey(GPU)).willReturn("environment-2")
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
        assertThat(current.guessResult!!.firstScore.value).isEqualByComparingTo("3.75")

        val old = client.get().uri("/submissions/${previous.id}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(Submission::class.java).returnResult().responseBody!!
        assertThat(old.guessResult!!.firstScore.value).isEqualByComparingTo("1.25")
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
    @AllowFullScan("problems", "FROM gpucodeforces\\.problems p", "Exact catalogue totals and tags inspect current ids; only page metadata reaches the application")
    fun `optimization baseline and best results stay in the captured cohort after the environment changes`() {
        val (user, cookie) = register()
        val version = optimizationVersion()
        val problemId = version.problemId
        val reference = ReferenceMeasurementView(
            processor = GPU, key = "${version.id}:environment-A", status = "ready", scoreType = ScoreType.TIME_MS, baselineScore = BigDecimal("20"),
            firstScore = null, secondScore = null, measuredAt = Instant.now(), synthetic = false,
            diagnostic = null, environmentKey = "environment-A",
        )
        given(references.views(version)).willReturn(mapOf(GPU to reference))
        given(references.environmentKey(GPU)).willReturn("environment-B")
        fun accepted(time: String, environment: String) = submissions.saveAndFlush(
            StoredSubmission(
                UUID.randomUUID(), UUID.fromString(user.id), problemId, version.id, null,
                "void solve() {}", "GPU", version.judgeHash, GPU, "cuda", null,
            ).also {
                it.status = SubmissionStatus.ACCEPTED
                it.score = BigDecimal(time)
                it.scoreType = ScoreType.TIME_MS
                it.benchmarkEnvironmentKey = environment
            },
        )
        val captured = accepted("4", "environment-A")
        accepted("1", "environment-B")

        val detail = client.get().uri("/problems/$problemId").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        val onGpu = detail.processors.single()
        assertThat(onGpu.baselineScore!!.value).isEqualByComparingTo("20")
        assertThat(onGpu.bestScore!!.value).isEqualByComparingTo("4")
        assertThat(onGpu.recordScore!!.value).isEqualByComparingTo("4")
        assertThat(onGpu.bestSubmissionId).isEqualTo(captured.id.toString())

        val listed = client.get().uri("/problems?q=$problemId").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
            .items.single { it.id == problemId }.processors.single()
        assertThat(listed.baselineScore!!.value).isEqualByComparingTo("20")
        assertThat(listed.bestScore!!.value).isEqualByComparingTo("4")
        assertThat(listed.recordScore!!.value).isEqualByComparingTo("4")
        assertThat(listed.bestSubmissionId).isEqualTo(captured.id.toString())
    }

    @Test
    fun `finalized optimization cards and submission comparability exclude verdicts after the cutoff`() {
        val (user, cookie) = register()
        val (opponent, opponentCookie) = register()
        val version = optimizationVersion()
        val now = Instant.now()
        val cutoff = now.minusSeconds(60)
        val contest = contests.save(Contest(
            id = UUID.randomUUID(), title = "Завершенная дуэль", startAt = now.minusSeconds(7200), endAt = now.minusSeconds(3600),
            processor = GPU, format = "duel", challengerId = UUID.fromString(user.id), opponentId = UUID.fromString(opponent.id),
            benchmarkEnvironmentKey = "frozen-environment", ratingAppliedAt = cutoff,
        ))
        contestProblems.save(ContestProblem(contest.id, version.problemId, 0, version.id))
        val reference = ReferenceMeasurementView(
            processor = GPU, key = "${version.id}:frozen-environment", status = "ready", scoreType = ScoreType.TIME_MS,
            baselineScore = BigDecimal("20"), firstScore = null, secondScore = null, measuredAt = contest.startAt,
            synthetic = false, diagnostic = null, environmentKey = "frozen-environment",
        )
        given(references.viewInEnvironment(version, GPU, "frozen-environment")).willReturn(reference)
        fun accepted(author: User, score: String, finishedAt: Instant) = submissions.saveAndFlush(StoredSubmission(
            id = UUID.randomUUID(), userId = UUID.fromString(author.id), problemId = version.problemId, problemVersionId = version.id,
            contestId = contest.id, code = "void solve() {}", environmentMarkdown = "GPU", judgeHash = version.judgeHash,
            processor = GPU, language = "cuda", previousBestScore = null, createdAt = contest.startAt.plusSeconds(60),
        ).apply {
            status = SubmissionStatus.ACCEPTED
            this.score = BigDecimal(score)
            scoreType = ScoreType.TIME_MS
            benchmarkEnvironmentKey = "frozen-environment"
            this.finishedAt = finishedAt
        })
        val counted = accepted(user, "4", cutoff)
        val late = accepted(user, "0.1", cutoff.plusSeconds(1))
        accepted(opponent, "0.01", cutoff.plusSeconds(2))

        val detail = client.get().uri("/problems/${version.problemId}?context=${contest.id}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!.processors.single()
        assertThat(detail.baselineScore!!.value).isEqualByComparingTo("20")
        assertThat(detail.bestScore!!.value).isEqualByComparingTo("4")
        assertThat(detail.recordScore!!.value).isEqualByComparingTo("4")
        assertThat(detail.bestSubmissionId).isEqualTo(counted.id.toString())
        val listed = client.get().uri("/problems?context=${contest.id}").cookie("session", opponentCookie)
            .exchange().expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!.items.single().processors.single()
        assertThat(listed.bestScore).isNull()
        assertThat(listed.recordScore!!.value).isEqualByComparingTo("4")
        val unsolved = client.get().uri("/problems?context=${contest.id}&solved=false").cookie("session", opponentCookie)
            .exchange().expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        assertThat(unsolved.items.map { it.id }).containsExactly(version.problemId)
        assertThat(unsolved.items.single().solved).isFalse()
        val solved = client.get().uri("/problems?context=${contest.id}&solved=true").cookie("session", opponentCookie)
            .exchange().expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        assertThat(solved.items).isEmpty()
        assertThat(solved.total).isZero()
        val lateView = client.get().uri("/submissions/${late.id}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(Submission::class.java).returnResult().responseBody!!
        assertThat(lateView.comparable).isFalse()
        val countedView = client.get().uri("/submissions/${counted.id}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(Submission::class.java).returnResult().responseBody!!
        assertThat(countedView.comparable).isTrue()
    }

    @Test
    fun `the generic contest API rejects scheduled duels`() {
        val (challenger, cookie) = register()
        val (opponent, _) = register()
        val start = Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        val input = ContestInput("Дуэль", start, start.plusHours(1), setOf(challenger.id), setOf("vector_add"), GPU)
            .format(ContestInput.FormatEnum.DUEL).opponentId(opponent.id)

        client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(input).exchange().expectStatus().isBadRequest
    }

    @Test
    fun `a guess on a circuit compares cycles x depth x transistors of rtl and answers in that unit`() {
        val (_, cookie) = register()
        val version = guessVersion(firstTimeMs = BigDecimal("4096"), processor = "rtl")

        val problem = client.get().uri("/problems/${version.problemId}").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(problem.processors.map { it.id }).containsExactly("rtl")
        assertThat(problem.processorDetails.single().benchmark!!.timingMode).isNull()
        val answer = submit(version.problemId, cookie, "second")
        assertThat(answer.status).isEqualTo(Submission.StatusEnum.ACCEPTED)
        assertThat(answer.processor).isEqualTo("rtl")
        assertThat(answer.language).isEqualTo("systemverilog")
        assertThat(answer.guessResult!!.firstScore.type.value).isEqualTo("CYCLES_X_DEPTH_X_TRANSISTORS")
        assertThat(answer.guessResult!!.correctChoice.value).isEqualTo("second")
    }

    @Test
    fun `an optimization on two processors has a baseline and records of its own on each`() {
        val (user, cookie) = register()
        val template = catalog.currentVersion("vector_add")!!
        val problemId = "optimization-${UUID.randomUUID()}"
        problems.save(Problem(problemId, source = "comparison"))
        val spec = catalog.spec(template).copy(
            processors = listOf(
                ProcessorSpec(GPU, listOf("cuda")),
                ProcessorSpec("rtl", listOf("systemverilog"), limit = 1e12),
            ),
        )
        val version = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = problemId, code = "O", title = "Optimization $problemId", difficulty = "easy",
            tags = emptyList(), authors = emptyList(), draft = false, statementMarkdown = "Ускорьте решение",
            processors = listOf(GPU, "rtl"), starters = mapOf(GPU to mapOf("cuda" to "void solve() {}"), "rtl" to mapOf("systemverilog" to "module solve; endmodule")),
            judgeSpec = jsonMapper.writeValueAsString(spec), contentHash = UUID.randomUUID().toString(),
            judgeHash = UUID.randomUUID().toString(), revision = null, format = "optimization",
            formatData = jsonMapper.writeValueAsString(ProblemFormatData(format = "optimization")),
        ))
        problems.save(problems.findById(problemId).orElseThrow().apply { currentVersionId = version.id })
        fun reference(processor: String, type: ScoreType, baseline: String, environment: String) = ReferenceMeasurementView(
            processor = processor, key = "${version.id}:$environment", status = "ready", scoreType = type, baselineScore = BigDecimal(baseline),
            firstScore = null, secondScore = null, measuredAt = Instant.now(), synthetic = false, diagnostic = null, environmentKey = environment,
        )
        given(references.views(version)).willReturn(
            mapOf(GPU to reference(GPU, ScoreType.TIME_MS, "20", "environment-1"), "rtl" to reference("rtl", ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS, "8000", "rtl-key")),
        )
        submissions.saveAndFlush(
            StoredSubmission(UUID.randomUUID(), UUID.fromString(user.id), problemId, version.id, null, "module solve; endmodule", "RTL", version.judgeHash, "rtl", "systemverilog", null).also {
                it.status = SubmissionStatus.ACCEPTED
                it.score = BigDecimal("2000")
                it.scoreType = ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS
            },
        )

        val detail = client.get().uri("/problems/$problemId").cookie("session", cookie)
            .exchange().expectStatus().isOk.expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        val byProcessor = detail.processors.associateBy { it.id }
        assertThat(byProcessor.getValue(GPU).baselineScore!!.value).isEqualByComparingTo("20")
        assertThat(byProcessor.getValue(GPU).bestScore).isNull()
        assertThat(byProcessor.getValue("rtl").baselineScore!!.type.value).isEqualTo("CYCLES_X_DEPTH_X_TRANSISTORS")
        assertThat(byProcessor.getValue("rtl").baselineScore!!.value).isEqualByComparingTo("8000")
        assertThat(byProcessor.getValue("rtl").bestScore!!.value).isEqualByComparingTo("2000")
        val gpuBenchmark = detail.processorDetails.single { it.id == GPU }.benchmark!!
        assertThat(gpuBenchmark.timingMode).isNotNull()
        assertThat(detail.processorDetails.single { it.id == "rtl" }.benchmark!!.timingMode).isNull()
    }

    companion object {
        private const val GPU = "gpu-cuda"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
