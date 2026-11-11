package dev.gpucodeforces.backend

import com.sun.net.httpserver.HttpServer
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.GetUserProfile200Response
import dev.gpucodeforces.backend.api.model.ListLatestSubmissions200Response
import dev.gpucodeforces.backend.api.model.ListProblemLeaders200ResponseInner
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.api.model.ListSubmissions200Response
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.RunAdminScript200Response
import dev.gpucodeforces.backend.api.model.RunAdminScriptRequest
import dev.gpucodeforces.backend.api.model.Submission
import dev.gpucodeforces.backend.api.model.SubmitSolutionRequest
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.backend.api.model.CreateHackRequest
import dev.gpucodeforces.backend.api.model.Hack
import dev.gpucodeforces.backend.api.model.VirtualParticipation
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.submission.HackService
import dev.gpucodeforces.backend.submission.SubmissionService
import dev.gpucodeforces.backend.submission.JudgingRunRepository
import dev.gpucodeforces.backend.rating.RatingFinalizationService
import org.springframework.jdbc.core.JdbcTemplate
import org.intellij.lang.annotations.Language
import dev.gpucodeforces.backend.problem.BenchmarkDefinition
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
import dev.gpucodeforces.backend.submission.MeasurementData
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistration
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.FinalDuelResult
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProcessorRun
import dev.gpucodeforces.judge.api.Progress
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.SubmissionStatus as JudgeStatus
import dev.gpucodeforces.judge.api.Topology
import dev.gpucodeforces.judge.api.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import dev.gpucodeforces.backend.problem.Problem as StoredProblem
import dev.gpucodeforces.backend.contest.Contest as StoredContest

/** The whole submission path: REST -> judge gateway (stub) -> events through RabbitMQ -> results everywhere. */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rabbit: RabbitTemplate

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @Autowired lateinit var contests: ContestRepository
    @Autowired lateinit var hacks: HackService
    @Autowired lateinit var submissionService: SubmissionService
    @Autowired lateinit var ratingFinalizer: RatingFinalizationService
    @Autowired lateinit var judgingRuns: JudgingRunRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired
    lateinit var problemCatalog: ProblemCatalog

    @Autowired
    lateinit var problemRepository: ProblemRepository

    @Autowired
    lateinit var versionRepository: ProblemVersionRepository

    @Autowired
    lateinit var submissionRepository: SubmissionRepository

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired lateinit var contestProblems: ContestProblemRepository
    @Autowired lateinit var registrations: ContestRegistrationRepository

    @MockitoBean
    lateinit var references: ReferenceMeasurements

    @BeforeEach
    fun environment() {
        given(references.environmentKey("gpu-cuda")).willReturn("submission-test-environment")
        given(references.environmentKeyFor(any(JudgeResult::class.java) ?: JudgeResult(Verdict.ACCEPTED))).willReturn("submission-test-environment")
    }

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun register(handle: String): Pair<User, String> {
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(User::class.java).returnResult()
        return result.responseBody!! to result.responseCookies.getFirst("session")!!.value
    }

    private fun submit(
        cookie: String,
        code: String,
        contestId: String? = null,
        processor: String = "gpu-cuda",
        language: String = "cuda",
    ): Pair<Submission, SubmissionRequest> {
        val submission = client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(SubmitSolutionRequest("vector_add", code, processor, language).contestId(contestId))
            .exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
        return submission to nextGatewayRequest()
    }

    private fun rejectedSubmission(cookie: String, problemId: String, processor: String, language: String, contestId: String? = null) =
        client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(SubmitSolutionRequest(problemId, "x", processor, language).contestId(contestId))
            .exchange().expectStatus().isBadRequest

    private fun fetch(id: String, cookie: String? = null): Submission =
        client.get().uri("/submissions/$id").apply { if (cookie != null) cookie("session", cookie) }
            .exchange().expectStatus().isOk.expectBody(Submission::class.java).returnResult().responseBody!!

    private fun awaitStatus(id: String, status: String, cookie: String? = null): Submission {
        val deadline = Instant.now().plusSeconds(15)
        while (true) {
            val submission = fetch(id, cookie)
            if (submission.status.value == status || Instant.now().isAfter(deadline)) {
                assertThat(submission.status.value).isEqualTo(status)
                return submission
            }
            Thread.sleep(100)
        }
    }

    private fun publish(event: JudgeEvent) {
        val properties = MessageProperties().apply { contentType = MessageProperties.CONTENT_TYPE_JSON }
        rabbit.send(Topology.EVENTS_EXCHANGE, Topology.eventRoutingKey(event.status), Message(jsonMapper.writeValueAsBytes(event), properties))
    }

    private fun event(
        request: SubmissionRequest,
        seq: Int,
        status: JudgeStatus,
        progress: Progress? = null,
        result: JudgeResult? = null,
        jobId: String = jobIdOf(request),
    ) = JudgeEvent(request.submissionId, 1, seq, status, Instant.now(), "executor-1", progress, result, jobId)

    private fun finished(
        verdict: Verdict,
        tests: Int,
        failedTest: Int? = null,
        time: Double = 1.5,
        type: ScoreType = ScoreType.TIME_MS,
        metrics: Map<String, Double> = emptyMap(),
        processor: String = if (type == ScoreType.TIME_MS) "gpu-cuda" else "rtl",
    ) = JudgeResult(
        verdict = verdict,
        failedTest = failedTest,
        message = if (failedTest != null) "Неверный ответ на тесте $failedTest" else null,
        tests = (1..(failedTest ?: tests)).map {
            TestResult(
                it, "t$it", it == 1, if (it == failedTest) verdict else Verdict.ACCEPTED, score = Score(time, type),
                // the last test only checks the answer
                measured = it != tests, wallTimeMs = time, runs = 1,
            )
        },
        score = if (verdict == Verdict.ACCEPTED) Score(time, type) else null,
        metrics = metrics,
        processor = ProcessorRun(processor, "test toolchain", "test model", "executor-1"),
    )

    private fun latestSubmissions(query: String): List<Submission> = client.get().uri("/submissions/latest?$query").exchange()
        .expectStatus().isOk.expectBody(ListLatestSubmissions200Response::class.java).returnResult().responseBody!!.items

    @Test
    @AllowFullScan("problems", "FROM gpucodeforces\\.problems p", "Exact catalogue totals and tags inspect current ids; only page metadata reaches the application")
    fun `submit, judge events through RabbitMQ, history, results, recheck, contest, example and console`() {
        val (alice, aliceCookie) = register("alice")
        val (bob, bobCookie) = register("bob")

        // Accepted, with events out of order, repeated, and from a job the gateway did not answer with.
        val (first, firstRequest) = submit(aliceCookie, "void solve() {}")
        assertThat(first.status.value).isEqualTo("queued")
        assertThat(firstRequest.submissionId).isEqualTo(first.id)
        assertThat(firstRequest.problem.id).isEqualTo("vector_add")
        assertThat(firstRequest.problem.generator.source).contains("def tests()")
        assertThat(firstRequest.processor).isEqualTo("gpu-cuda")
        assertThat(firstRequest.language).isEqualTo("cuda")
        publish(event(firstRequest, 2, JUDGE_TESTING, progress = Progress(0, 5)))
        assertThat(awaitStatus(first.id, "checking", aliceCookie).passedTests).isZero()
        publish(event(firstRequest, 3, JUDGE_TESTING, progress = Progress(3, 5)))
        publish(event(firstRequest, 1, JUDGE_COMPILING))
        publish(event(firstRequest, 9, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 5, 1), jobId = "another-job"))
        publish(event(firstRequest, 7, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5)))
        publish(event(firstRequest, 7, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5)))
        val accepted = awaitStatus(first.id, "accepted", aliceCookie)
        assertThat(accepted.score!!.value).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(accepted.score!!.type.value).isEqualTo("TIME_MS")
        assertThat(accepted.processor).isEqualTo("gpu-cuda")
        assertThat(accepted.passedTests).isEqualTo(5)
        assertThat(accepted.totalTests).isEqualTo(5)
        assertThat(accepted.comparable).isTrue()
        assertThat(accepted.code).isEqualTo("void solve() {}")
        assertThat(accepted.environmentMarkdown).contains("NVIDIA GeForce RTX 3060")
        assertThat(fetch(first.id, bobCookie).code).isNull()

        val (second, secondRequest) = submit(aliceCookie, "wrong")
        assertThat(second.previousBestScore!!.value).isEqualByComparingTo(BigDecimal("1.5"))
        publish(event(secondRequest, 4, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 5, 2)))
        assertThat(awaitStatus(second.id, "wrong_answer", aliceCookie).diagnostic).contains("Неверный ответ на тесте 2")

        val history = client.get().uri("/submissions?context=practice").cookie("session", aliceCookie).exchange()
            .expectStatus().isOk.expectBody(ListSubmissions200Response::class.java).returnResult().responseBody!!
        assertThat(history.items.map { it.id }).containsExactly(second.id, first.id)
        assertThat(history.items.first().code).isNull()
        client.get().uri("/submissions").exchange().expectStatus().isUnauthorized
        assertThat(
            client.get().uri("/submissions?userId=${alice.id}&status=accepted").exchange()
                .expectBody(ListSubmissions200Response::class.java).returnResult().responseBody!!.total,
        ).isEqualTo(1)

        // Latest submissions of all users, newest first, without code, limited by limit.
        val latest = latestSubmissions("limit=2")
        assertThat(latest.map { it.id }).containsExactly(second.id, first.id)
        assertThat(latest.first().code).isNull()
        assertThat(latest.first().user.id).isEqualTo(alice.id)
        assertThat(latestSubmissions("offset=1&limit=1").map { it.id }).containsExactly(first.id)

        val bank = client.get().uri("/problems").cookie("session", aliceCookie).exchange()
            .expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        assertThat(bank.solvedCount).isEqualTo(1)
        assertThat(bank.totalCount).isEqualTo(bank.items.size)
        assertThat(bank.streak).isEqualTo(1)
        val vectorAdd = bank.items.single { it.id == "vector_add" }
        // the package of vector_add takes custom tests, matmul_host does not: the contest editor shows it
        assertThat(vectorAdd.hacksAllowed).isTrue()
        assertThat(bank.items.single { it.id == "matmul_host" }.hacksAllowed).isFalse()
        assertThat(vectorAdd.solved).isTrue()
        assertThat(vectorAdd.solvedCount).isEqualTo(1)
        assertThat(vectorAdd.lastStatus).isEqualTo("wrong_answer")
        val onGpu = vectorAdd.processors.single { it.id == "gpu-cuda" }
        assertThat(onGpu.bestSubmissionId).isEqualTo(first.id)
        assertThat(onGpu.recordScore!!.value).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(vectorAdd.processors.single { it.id == "rtl" }.recordScore).isNull()
        val solvedPage = client.get().uri("/problems?solved=true&sort=bestScore&processor=gpu-cuda&limit=1").cookie("session", aliceCookie).exchange()
            .expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        assertThat(solvedPage.total).isEqualTo(1)
        assertThat(solvedPage.items.map { it.id }).containsExactly("vector_add")
        val unsolvedPage = client.get().uri("/problems?solved=false&limit=1").cookie("session", aliceCookie).exchange()
            .expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        assertThat(unsolvedPage.items.map { it.id }).containsExactly("matmul_host")
        val leaders = client.get().uri("/problems/vector_add/leaders?processor=gpu-cuda").exchange()
            .expectBody(Array<ListProblemLeaders200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(leaders.map { it.user.handle to it.rank }).containsExactly("alice" to 1)
        client.get().uri("/problems/vector_add/leaders").exchange().expectStatus().isBadRequest

        // Another processor: its own score type, metrics and records; the problem page learns its tests.
        rejectedSubmission(bobCookie, "vector_add", "coral-npu", "c")
        rejectedSubmission(bobCookie, "vector_add", "rtl", "cuda")
        rejectedSubmission(bobCookie, "matmul_host", "rtl", "systemverilog")
        val (onRtl, rtlRequest) = submit(bobCookie, "module solve; endmodule", processor = "rtl", language = "systemverilog")
        assertThat(rtlRequest.processor).isEqualTo("rtl")
        assertThat(rtlRequest.language).isEqualTo("systemverilog")
        publish(
            event(
                rtlRequest, 6, JUDGE_FINISHED,
                result = finished(Verdict.ACCEPTED, 3, time = 4096.0, type = ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS, metrics = mapOf("cycles" to 64.0, "cells" to 64.0)),
            ),
        )
        val rtlAccepted = awaitStatus(onRtl.id, "accepted", bobCookie)
        assertThat(rtlAccepted.score!!.type.value).isEqualTo("CYCLES_X_DEPTH_X_TRANSISTORS")
        assertThat(rtlAccepted.metrics).containsEntry("cells", BigDecimal("64.0"))
        val rtlLeaders = client.get().uri("/problems/vector_add/leaders?processor=rtl").exchange()
            .expectBody(Array<ListProblemLeaders200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(rtlLeaders.map { it.user.handle }).containsExactly("bob")
        val rtlPage = client.get().uri("/problems/vector_add").exchange()
            .expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(rtlPage.processorDetails.single { it.id == "rtl" }.environmentMarkdown)
            .contains("1. `t1` (пример) — замерный").contains("3. `t3` — только проверка ответа")

        // A judge failure can be rechecked by the author only, as a new judge job.
        val (third, thirdRequest) = submit(aliceCookie, "flaky")
        publish(event(thirdRequest, 2, JUDGE_FINISHED, result = finished(Verdict.INTERNAL_ERROR, 0)))
        awaitStatus(third.id, "system_error")
        client.post().uri("/submissions/${third.id}/recheck").cookie("session", bobCookie).exchange().expectStatus().isForbidden
        client.post().uri("/submissions/${third.id}/recheck").cookie("session", aliceCookie).exchange().expectStatus().isAccepted
        val recheckRequest = nextGatewayRequest()
        assertThat(recheckRequest.submissionId).isEqualTo("${third.id}.r1")
        publish(event(thirdRequest, 3, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 5, 1)))
        publish(event(recheckRequest, 5, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5, time = 3.0)))
        awaitStatus(third.id, "accepted")

        // Example check waits for the verdict of a job that runs only the sample tests.
        val example = CompletableFuture.supplyAsync {
            client.post().uri("/problems/vector_add/example").contentType(MediaType.APPLICATION_JSON).cookie("session", aliceCookie)
                .body(mapOf("code" to "void solve() {}", "processor" to "gpu-cuda", "language" to "cuda"))
                .exchange().expectStatus().isOk.expectBody(CheckExample200Response::class.java).returnResult().responseBody!!
        }
        val exampleRequest = nextGatewayRequest()
        assertThat(exampleRequest.submissionId).startsWith("example-")
        assertThat(exampleRequest.problem.generator.source).contains("if test.get(\"sample\")")
        assertThat(exampleRequest.processor).isEqualTo("gpu-cuda")
        publish(event(exampleRequest, 3, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 1, 1)))
        val exampleResult = example.get(20, TimeUnit.SECONDS)
        assertThat(exampleResult.passed).isFalse()
        assertThat(exampleResult.diagnostic).contains("Неверный ответ")
        assertThat(gatewayRequests).isEmpty()

        // Contests: registration and a live contest are required; one runs on gpu-cuda, another on rtl.
        val startAt = Instant.now().plusSeconds(2)
        fun contest(title: String, processor: String) = client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", aliceCookie)
            .body(ContestInput(title, startAt.atOffset(ZoneOffset.UTC), startAt.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC), setOf(alice.id), setOf("vector_add"), processor))
            .exchange().expectStatus().isCreated.expectBody(Contest::class.java).returnResult().responseBody!!
        val contest = contest("Раунд", "gpu-cuda")
        val rtlContest = contest("Раунд на схемах", "rtl")
        for (id in listOf(contest.id, rtlContest.id)) client.put().uri("/contests/$id/registration").cookie("session", bobCookie).exchange().expectStatus().isNoContent
        Thread.sleep(Duration.between(Instant.now(), startAt).plusMillis(300).toMillis().coerceAtLeast(0))
        client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", aliceCookie)
            .body(SubmitSolutionRequest("vector_add", "x", "gpu-cuda", "cuda").contestId(contest.id)).exchange().expectStatus().isForbidden
        rejectedSubmission(bobCookie, "vector_add", "gpu-cuda", "cuda", rtlContest.id)
        val (contestSubmission, contestRequest) = submit(bobCookie, "fast", contest.id)
        publish(event(contestRequest, 4, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5, time = 2.0)))
        assertThat(awaitStatus(contestSubmission.id, "accepted").contestTitle).isEqualTo("Раунд")
        val standings = client.get().uri("/contests/${contest.id}/standings").exchange()
            .expectBody(Array<GetContestStandings200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(standings.map { it.user.id }).containsExactly(bob.id)
        assertThat(standings.single().solved).isEqualTo(1)
        // Solve time runs from the start to when the accepted submission was sent, not to its verdict.
        val sentAfterStart = Duration.between(contest.startAt.toInstant(), contestSubmission.createdAt.toInstant()).seconds.toInt()
        assertThat(standings.single().times).containsExactlyEntriesOf(mapOf("vector_add" to sentAfterStart))
        assertThat(standings.single().totalSeconds).isEqualTo(sentAfterStart)

        // A contest on another processor ranks the same way: a problem is solved within its score limit.
        val (rtlContestSubmission, rtlContestRequest) = submit(bobCookie, "module solve; endmodule", rtlContest.id, "rtl", "systemverilog")
        publish(event(rtlContestRequest, 4, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 3, time = 50000.0, type = ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS)))
        awaitStatus(rtlContestSubmission.id, "accepted")
        val rtlStandings = client.get().uri("/contests/${rtlContest.id}/standings").exchange()
            .expectBody(Array<GetContestStandings200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(rtlStandings.single().solved).isEqualTo(1)
        val (overLimit, overLimitRequest) = submit(bobCookie, "module solve; endmodule // slow", rtlContest.id, "rtl", "systemverilog")
        publish(event(overLimitRequest, 4, JUDGE_FINISHED, result = finished(Verdict.TIME_LIMIT_EXCEEDED, 3, failedTest = 2, type = ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS)))
        awaitStatus(overLimit.id, "time_limit")
        assertThat(client.get().uri("/contests/${rtlContest.id}").exchange().expectBody(Contest::class.java).returnResult().responseBody!!.rulesMarkdown)
            .contains("`rtl`").contains("лимит тактов × глубины × транзисторов")

        val profile = client.get().uri("/users/${alice.id}").exchange()
            .expectBody(GetUserProfile200Response::class.java).returnResult().responseBody!!
        assertThat(profile.solvedCount).isEqualTo(1)

        // Groovy console for administrators only.
        val script = RunAdminScriptRequest("out.println(ctx.getBean('problemSyncService').class.simpleName); 1 + 1")
        client.post().uri("/admin/console").contentType(MediaType.APPLICATION_JSON).cookie("session", bobCookie).body(script)
            .exchange().expectStatus().isForbidden
        val console = client.post().uri("/admin/console").contentType(MediaType.APPLICATION_JSON).cookie("session", aliceCookie).body(script)
            .exchange().expectStatus().isOk.expectBody(RunAdminScript200Response::class.java).returnResult().responseBody!!
        assertThat(console.result).isEqualTo("2")
        assertThat(console.output).contains("ProblemSyncService")
        assertThat(console.error).isNull()
    }

    @Test
    @AllowFullScan("user_ratings", "UPDATE gpucodeforces\\.user_ratings r SET rank", "Finalizing a contest recomputes the ranks of every rated user")
    fun `hacks rejudge every accepted solution before finalization and training modes stay isolated`() {
        val (author, authorCookie) = register("hack_author")
        val (victim, victimCookie) = register("hack_victim")
        val start = Instant.now().plusSeconds(1)
        val contest = client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", authorCookie)
            .body(
                ContestInput(
                    "Взломы и тренировка", start.atOffset(ZoneOffset.UTC), start.plusSeconds(3600).atOffset(ZoneOffset.UTC), setOf(author.id),
                    setOf("vector_add"), "gpu-cuda",
                ).hackDurationMinutes(60),
            )
            .exchange().expectStatus().isCreated.expectBody(Contest::class.java).returnResult().responseBody!!
        for (cookie in listOf(authorCookie, victimCookie)) client.put().uri("/contests/${contest.id}/registration").cookie("session", cookie).exchange().expectStatus().isNoContent
        Thread.sleep(Duration.between(Instant.now(), start).plusMillis(100).toMillis().coerceAtLeast(0))
        val (target, firstRequest) = submit(victimCookie, "target", contest.id)
        val (backup, backupRequest) = submit(victimCookie, "backup", contest.id)
        publish(event(firstRequest, 1, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5, time = 1.0)))
        publish(event(backupRequest, 1, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5, time = 2.0)))
        awaitStatus(target.id, "accepted")
        awaitStatus(backup.id, "accepted")
        assertThat(fetch(target.id, authorCookie).code).isNull()
        val entity = contests.findById(UUID.fromString(contest.id)).orElseThrow()
        entity.endAt = Instant.now().minusMillis(1)
        entity.startAt = entity.endAt.minusSeconds(3600)
        entity.hackEndAt = Instant.now().plusSeconds(3600)
        contests.saveAndFlush(entity)
        assertThat(fetch(target.id, authorCookie).code).isEqualTo("target")

        @Language("JSON") val input = """{"scalars":{"N":1},"inputs":{"A":[1],"B":[2]}}"""
        client.post().uri("/hacks").contentType(MediaType.APPLICATION_JSON).cookie("session", victimCookie)
            .body(CreateHackRequest(target.id, input)).exchange().expectStatus().isForbidden
        val hack = client.post().uri("/hacks").contentType(MediaType.APPLICATION_JSON).cookie("session", authorCookie)
            .body(CreateHackRequest(target.id, input)).exchange().expectStatus().isAccepted.expectBody(Hack::class.java).returnResult().responseBody!!
        val hackRequest = nextGatewayRequest()
        assertThat(hackRequest.problem.customOnly).isTrue()
        assertThat(hackRequest.problem.customTests.single().inputs.keys).containsExactlyInAnyOrder("A", "B")
        publish(event(hackRequest, 1, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 1, 1)))
        val deadline = Instant.now().plusSeconds(10)
        while (hacks.list(entity.id).single().status == "queued" && Instant.now().isBefore(deadline)) Thread.sleep(50)
        assertThat(hacks.list(entity.id).single().status).isEqualTo("successful")
        publish(event(hackRequest, 1, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 1, 1)))
        assertThat(hacks.list(entity.id)).hasSize(1)

        entity.hackEndAt = Instant.now().minusMillis(1)
        contests.saveAndFlush(entity)
        ratingFinalizer.finalize(entity.id, Instant.now())
        assertThat(contests.findById(entity.id).orElseThrow().finalizedAt).isNull()
        submissionService.rejudgeExpandedSuites()
        val rejudges = listOf(nextGatewayRequest(), nextGatewayRequest())
        assertThat(rejudges.map { it.source }).containsExactlyInAnyOrder("target", "backup")
        rejudges.forEach { request ->
            assertThat(request.problem.customTests).hasSize(1)
            assertThat(request.problem.customOnly).isFalse()
            publish(event(request, 1, JUDGE_FINISHED, result = if (request.source == "target") finished(Verdict.WRONG_ANSWER, 6, 6) else finished(Verdict.ACCEPTED, 6, time = 2.0)))
        }
        awaitStatus(target.id, "wrong_answer")
        awaitStatus(backup.id, "accepted")
        assertThat(judgingRuns.findBySubmissionIdIn(setOf(UUID.fromString(target.id), UUID.fromString(backup.id)))).hasSize(2)
        ratingFinalizer.finalize(entity.id, Instant.now())
        assertThat(contests.findById(entity.id).orElseThrow().finalizedAt).isNotNull()

        fun training(context: String): Pair<Submission, SubmissionRequest> {
            val sent = client.post().uri("/submissions").cookie("session", authorCookie).contentType(MediaType.APPLICATION_JSON)
                .body(SubmitSolutionRequest("vector_add", "training", "gpu-cuda", "cuda").context(context))
                .exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
            return sent to nextGatewayRequest()
        }
        val (upsolve, upsolveRequest) = training("upsolve:${contest.id}")
        assertThat(upsolve.context).isEqualTo("upsolve:${contest.id}")
        assertThat(upsolveRequest.problem.customTests).hasSize(1)
        publish(event(upsolveRequest, 1, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 6, time = 0.5)))
        awaitStatus(upsolve.id, "accepted")

        fun startVirtual() = client.post().uri("/contests/${contest.id}/virtual").cookie("session", authorCookie)
            .exchange().expectStatus().isCreated.expectBody(VirtualParticipation::class.java).returnResult().responseBody!!
        val virtual = startVirtual()
        assertThat(startVirtual().id).isEqualTo(virtual.id)
        client.get().uri("/virtual/${virtual.id}").cookie("session", victimCookie).exchange().expectStatus().isForbidden
        val (virtualSubmission, virtualRequest) = training("virtual:${virtual.id}")
        client.get().uri("/submissions/${virtualSubmission.id}").cookie("session", victimCookie).exchange().expectStatus().isForbidden
        // the public feed keeps a virtual attempt as private as the submission page does
        assertThat(latestSubmissions("limit=100").map { it.id }).doesNotContain(virtualSubmission.id)
        @Language("SQL") val expire = "UPDATE gpucodeforces.virtual_participations SET started_at = now() - interval '2 hours', ends_at = now() - interval '1 second' WHERE id = ?"
        jdbc.update(expire, UUID.fromString(virtual.id))
        client.post().uri("/submissions").cookie("session", authorCookie).contentType(MediaType.APPLICATION_JSON)
            .body(SubmitSolutionRequest("vector_add", "late", "gpu-cuda", "cuda").context("virtual:${virtual.id}"))
            .exchange().expectStatus().isBadRequest
        publish(event(virtualRequest, 1, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 6, time = 0.25)))
        awaitStatus(virtualSubmission.id, "accepted", authorCookie)
        val result = client.get().uri("/virtual/${virtual.id}").cookie("session", authorCookie).exchange()
            .expectStatus().isOk.expectBody(VirtualParticipation::class.java).returnResult().responseBody!!
        assertThat(result.solved).isEqualTo(1)
        assertThat(result.pending).isFalse()
        val standings = client.get().uri("/contests/${contest.id}/standings").exchange().expectStatus().isOk
            .expectBody(Array<GetContestStandings200ResponseInner>::class.java).returnResult().responseBody!!
        // upsolving and the virtual attempt left the official table alone: the hacked solution fell, the backup stands
        assertThat(standings.single { it.user.id == author.id }.solved).isZero()
        assertThat(standings.single { it.user.id == victim.id }.solved).isEqualTo(1)
        assertThat(result.times.keys).containsExactly("vector_add")
        val next = startVirtual()
        assertThat(next.id).isNotEqualTo(virtual.id)
        assertThat(next.solved).isZero()
    }

    @Test
    fun `optimization persists geometric measurements and reports invalid data as a recheckable error`() {
        val (_, cookie) = register("metrics_user")
        val source = problemCatalog.currentVersion("vector_add")!!
        val id = "geometric_vector_add"
        transactionTemplate.executeWithoutResult {
            val problem = problemRepository.save(StoredProblem(id))
            val version = versionRepository.save(
                ProblemVersion(
                    UUID.randomUUID(), id, "G", source.title, source.difficulty, source.tags, source.authors, false,
                    source.statementMarkdown, source.processors, source.starters,
                    jsonMapper.writeValueAsString(problemCatalog.spec(source).copy(id = id)),
                    "geometric-content", "geometric-judge", null, format = "optimization",
                    formatData = jsonMapper.writeValueAsString(
                        ProblemFormatData(
                            format = "optimization",
                            benchmark = BenchmarkDefinition("Два теста сложения векторов", "geometric_mean"),
                        ),
                    ),
                ),
            )
            problem.currentVersionId = version.id
        }
        fun send() = client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(SubmitSolutionRequest(id, "void solve() {}", "gpu-cuda", "cuda"))
            .exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
        val measured = finished(Verdict.ACCEPTED, 2, time = 4.0).copy(
            tests = listOf(
                TestResult(1, "first", true, Verdict.ACCEPTED, score = Score(1.0, ScoreType.TIME_MS), wallTimeMs = 1.0, runs = 1),
                TestResult(2, "second", false, Verdict.ACCEPTED, score = Score(4.0, ScoreType.TIME_MS), wallTimeMs = 4.0, runs = 3),
            ),
            gpu = GpuInfo("rtx3060", "Actual device", 0, "executor-1"),
            processor = ProcessorRun("gpu-cuda", "nvcc test", "Actual device", "executor-1"),
        )
        val first = send()
        val firstRequest = nextGatewayRequest()
        publish(event(firstRequest, 1, JUDGE_FINISHED, result = measured))
        val accepted = awaitStatus(first.id, "accepted", cookie)
        assertThat(accepted.score!!.value).isEqualByComparingTo("2.0")
        assertThat(accepted.measurement!!.aggregation.value).isEqualTo("geometric_mean")
        assertThat(accepted.measurement!!.model).isEqualTo("Actual device")
        assertThat(accepted.measurement!!.testCount).isEqualTo(2)
        val stored = jsonMapper.readValue(submissionRepository.findById(UUID.fromString(first.id)).orElseThrow().measurement!!, MeasurementData::class.java)
        assertThat(stored.tests.map { it.value.toPlainString() }).containsExactly("1.0", "4.0")
        assertThat(stored.tests.map { it.runs }).containsExactly(1, 3)
        assertThat(stored.toolchain).isEqualTo("nvcc test")
        assertThat(jsonMapper.writeValueAsString(fetch(first.id))).doesNotContain("toolchain", "\"tests\"", "nvcc test")

        val invalid = send()
        val invalidRequest = nextGatewayRequest()
        publish(event(invalidRequest, 1, JUDGE_FINISHED, result = measured.copy(tests = measured.tests.map { it.copy(score = null) })))
        val failed = awaitStatus(invalid.id, "measurement_error", cookie)
        assertThat(failed.score).isNull()
        assertThat(failed.measurement).isNull()
        assertThat(failed.diagnostic).contains("замер")
        client.post().uri("/submissions/${invalid.id}/recheck").cookie("session", cookie).exchange().expectStatus().isAccepted
        val recheckRequest = nextGatewayRequest()
        publish(event(recheckRequest, 1, JUDGE_FINISHED, result = measured))
        assertThat(awaitStatus(invalid.id, "accepted", cookie).score!!.value).isEqualByComparingTo("2.0")
    }

    @Test
    fun `a duel refuses new measurements from a different environment and resumes when its environment returns`() {
        val (alice, cookie) = register("duel_cohort_alice")
        val (bob, _) = register("duel_cohort_bob")
        val version = problemCatalog.currentVersion("vector_add")!!
        val room = contests.save(StoredContest(
            UUID.randomUUID(), "Проверка среды дуэли", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "gpu-cuda",
            format = "duel", challengerId = UUID.fromString(alice.id), opponentId = UUID.fromString(bob.id),
            benchmarkEnvironmentKey = "frozen-duel-environment",
        ))
        contestProblems.save(ContestProblem(room.id, version.problemId, 0, version.id))
        registrations.saveAll(listOf(ContestRegistration(room.id, UUID.fromString(alice.id)), ContestRegistration(room.id, UUID.fromString(bob.id))))
        val body = SubmitSolutionRequest(version.problemId, "void solve() {}", "gpu-cuda", "cuda").contestId(room.id.toString())

        client.post().uri("/submissions").cookie("session", cookie).contentType(MediaType.APPLICATION_JSON)
            .body(body).exchange().expectStatus().isEqualTo(503)

        given(references.environmentKey("gpu-cuda")).willReturn("frozen-duel-environment")
        client.post().uri("/submissions").cookie("session", cookie).contentType(MediaType.APPLICATION_JSON)
            .body(body).exchange().expectStatus().isAccepted
        assertThat(nextGatewayRequest().problem.id).isEqualTo(problemCatalog.spec(version).id)
    }

    @Test
    fun `submission time is captured before a runtime query crosses the duel deadline`() {
        val handle = "arrival_${UUID.randomUUID().toString().take(8)}"
        val (alice, cookie) = register(handle)
        val (bob, _) = register("${handle}_b")
        val version = problemCatalog.currentVersion("vector_add")!!
        val room = contests.save(StoredContest(
            UUID.randomUUID(), "Отправка до таймера", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "gpu-cuda",
            format = "duel", challengerId = UUID.fromString(alice.id), opponentId = UUID.fromString(bob.id),
            benchmarkEnvironmentKey = "arrival-environment",
        ))
        contestProblems.save(ContestProblem(room.id, version.problemId, 0, version.id))
        registrations.saveAll(listOf(ContestRegistration(room.id, UUID.fromString(alice.id)), ContestRegistration(room.id, UUID.fromString(bob.id))))
        val deadline = AtomicReference<Instant>()
        given(references.environmentKey("gpu-cuda")).willAnswer {
            if (deadline.get() == null) {
                val at = Instant.now()
                deadline.set(at)
                transactionTemplate.executeWithoutResult { contests.findForUpdate(room.id)!!.endAt = at }
            }
            "arrival-environment"
        }
        val submission = client.post().uri("/submissions").cookie("session", cookie).contentType(MediaType.APPLICATION_JSON)
            .body(SubmitSolutionRequest(version.problemId, "void solve() {}", "gpu-cuda", "cuda").contestId(room.id.toString()))
            .exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
        nextGatewayRequest()
        assertThat(submissionRepository.findById(UUID.fromString(submission.id)).orElseThrow().createdAt).isBefore(deadline.get())
    }

    @Test
    fun `finalization rejects slow admission and rechecks while late verdicts keep their application time`() {
        val handle = "final_${UUID.randomUUID().toString().take(8)}"
        val (alice, cookie) = register(handle)
        val (bob, _) = register("${handle}_b")
        val aliceId = UUID.fromString(alice.id)
        val bobId = UUID.fromString(bob.id)
        val version = problemCatalog.currentVersion("vector_add")!!
        val room = contests.save(StoredContest(
            UUID.randomUUID(), "Зафиксированная дуэль", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "gpu-cuda",
            format = "duel", challengerId = aliceId, opponentId = bobId, benchmarkEnvironmentKey = "final-environment",
        ))
        contestProblems.save(ContestProblem(room.id, version.problemId, 0, version.id))
        registrations.saveAll(listOf(ContestRegistration(room.id, aliceId), ContestRegistration(room.id, bobId)))
        given(references.environmentKey("gpu-cuda")).willReturn("final-environment")
        given(references.environmentKeyFor(any(JudgeResult::class.java) ?: JudgeResult(Verdict.ACCEPTED))).willReturn("final-environment")
        val body = SubmitSolutionRequest(version.problemId, "void solve() {}", "gpu-cuda", "cuda").contestId(room.id.toString())
        fun send(): Submission = client.post().uri("/submissions").cookie("session", cookie).contentType(MediaType.APPLICATION_JSON)
            .body(body).exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
        val error = send()
        val errorRequest = nextGatewayRequest()
        publish(event(errorRequest, 1, JUDGE_FINISHED, result = finished(Verdict.INTERNAL_ERROR, 0)))
        awaitStatus(error.id, "system_error", cookie)
        val late = send()
        val lateRequest = nextGatewayRequest()
        val cutoff = AtomicReference<Instant>()
        given(references.environmentKey("gpu-cuda")).willAnswer {
            if (cutoff.get() == null) {
                val at = Instant.now()
                cutoff.set(at)
                transactionTemplate.executeWithoutResult {
                    contests.findForUpdate(room.id)!!.apply {
                        endAt = at
                        ratingAppliedAt = at
                        finalDuelResults = listOf(FinalDuelResult(aliceId, null, 1, 0), FinalDuelResult(bobId, null, 1, 0))
                    }
                }
            }
            "final-environment"
        }
        client.post().uri("/submissions").cookie("session", cookie).contentType(MediaType.APPLICATION_JSON)
            .body(body).exchange().expectStatus().isBadRequest
        assertThat(submissionRepository.search(aliceId, version.problemId, null, room.id.toString())).hasSize(2)
        client.post().uri("/submissions/${error.id}/recheck").cookie("session", cookie).exchange().expectStatus().isBadRequest
        publish(event(lateRequest, 1, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 3)).copy(timestamp = cutoff.get().minusSeconds(1)))
        val accepted = awaitStatus(late.id, "accepted", cookie)
        assertThat(accepted.comparable).isFalse()
        assertThat(submissionRepository.findById(UUID.fromString(late.id)).orElseThrow().finishedAt).isAfter(cutoff.get())
    }

    @Test
    fun `ended duel code stays private to its participants while ordinary contest code becomes public`() {
        val handle = "privacy_${UUID.randomUUID().toString().take(8)}"
        val (author, authorCookie) = register(handle)
        val (opponent, opponentCookie) = register("${handle}_b")
        val (_, outsiderCookie) = register("${handle}_c")
        val authorId = UUID.fromString(author.id)
        val opponentId = UUID.fromString(opponent.id)
        val version = problemCatalog.currentVersion("vector_add")!!
        val source = "void solve() { const int privateValue = 42; }"
        fun room(duel: Boolean): StoredContest {
            val contest = contests.save(StoredContest(
                UUID.randomUUID(), "Проверка доступа к коду", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "gpu-cuda",
                format = if (duel) "duel" else "standard", challengerId = authorId.takeIf { duel }, opponentId = opponentId.takeIf { duel },
                benchmarkEnvironmentKey = "submission-test-environment".takeIf { duel },
            ))
            contestProblems.save(ContestProblem(contest.id, version.problemId, 0, version.id))
            registrations.saveAll(listOf(ContestRegistration(contest.id, authorId), ContestRegistration(contest.id, opponentId)))
            return contest
        }
        fun accepted(contest: StoredContest): String {
            val submission = client.post().uri("/submissions").cookie("session", authorCookie).contentType(MediaType.APPLICATION_JSON)
                .body(SubmitSolutionRequest(version.problemId, source, "gpu-cuda", "cuda").contestId(contest.id.toString()))
                .exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
            val request = nextGatewayRequest()
            publish(event(request, 1, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 3)))
            awaitStatus(submission.id, "accepted", authorCookie)
            return submission.id
        }
        val duel = room(true)
        val duelSubmission = accepted(duel)
        assertThat(fetch(duelSubmission, authorCookie).code).isEqualTo(source)
        assertThat(fetch(duelSubmission, opponentCookie).code).isNull()
        transactionTemplate.executeWithoutResult { contests.findForUpdate(duel.id)!!.endAt = Instant.now() }
        assertThat(fetch(duelSubmission, authorCookie).code).isEqualTo(source)
        val opponentView = fetch(duelSubmission, opponentCookie)
        assertThat(opponentView.code).isEqualTo(source)
        assertThat(opponentView.sourcePublic).isTrue()
        for (cookie in listOf(null, outsiderCookie)) {
            val view = fetch(duelSubmission, cookie)
            assertThat(view.code).isNull()
            assertThat(view.sourcePublic).isFalse()
        }
        val ordinary = room(false)
        val ordinarySubmission = accepted(ordinary)
        transactionTemplate.executeWithoutResult { contests.findForUpdate(ordinary.id)!!.endAt = Instant.now() }
        val publicView = fetch(ordinarySubmission)
        assertThat(publicView.code).isEqualTo(source)
        assertThat(publicView.sourcePublic).isTrue()
    }

    companion object {
        private val JUDGE_COMPILING = JudgeStatus.COMPILING
        private val JUDGE_TESTING = JudgeStatus.TESTING
        private val JUDGE_FINISHED = JudgeStatus.FINISHED

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")

        @Container
        @ServiceConnection
        @JvmStatic
        val rabbitmq = RabbitMQContainer("rabbitmq:4.1.8-management")

        private val gatewayRequests = LinkedBlockingQueue<SubmissionRequest>()
        private val gatewayJson = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        /** Stands in for the judge gateway: records requests and accepts them as job `job-<submissionId>`. */
        private val gateway: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/submissions") { exchange ->
                // Like the real gateway, which limits the request size before reading it.
                if (exchange.requestHeaders.getFirst("Content-Length") == null) {
                    exchange.sendResponseHeaders(411, -1)
                    exchange.close()
                    return@createContext
                }
                val request = gatewayJson.readValue(exchange.requestBody.readAllBytes(), SubmissionRequest::class.java)
                gatewayRequests += request
                val body = gatewayJson.writeValueAsBytes(
                    mapOf(
                        "submissionId" to request.submissionId,
                        "processor" to request.processor,
                        "language" to request.language,
                        "status" to "QUEUED",
                        "queuedAt" to Instant.now().toString(),
                        "jobId" to jobIdOf(request),
                    ),
                )
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(202, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        private fun jobIdOf(request: SubmissionRequest) = "job-${request.submissionId}"

        private fun nextGatewayRequest(): SubmissionRequest = gatewayRequests.poll(10, TimeUnit.SECONDS)!!

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.judge.gateway-url") { "http://127.0.0.1:${gateway.address.port}" }
            registry.add("platform.admin.handles") { "alice" }
            registry.add("platform.problems.directory") { TestProblems.withRtl().toString() }
            registry.add("spring.rabbitmq.listener.simple.auto-startup") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopGateway() {
            gateway.stop(0)
        }
    }
}
