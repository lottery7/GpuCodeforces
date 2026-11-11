package dev.gpucodeforces.backend

import com.sun.net.httpserver.HttpServer
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.GetUserProfile200Response
import dev.gpucodeforces.backend.api.model.ListProblemLeaders200ResponseInner
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.api.model.ListSubmissions200Response
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.RunAdminScript200Response
import dev.gpucodeforces.backend.api.model.RunAdminScriptRequest
import dev.gpucodeforces.backend.api.model.Submission
import dev.gpucodeforces.backend.api.model.SubmitSolutionRequest
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
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
    )

    @Test
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

        val bank = client.get().uri("/problems").cookie("session", aliceCookie).exchange()
            .expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        val vectorAdd = bank.items.single { it.id == "vector_add" }
        assertThat(vectorAdd.solved).isTrue()
        assertThat(vectorAdd.solvedCount).isEqualTo(1)
        assertThat(vectorAdd.lastStatus).isEqualTo("wrong_answer")
        val onGpu = vectorAdd.processors.single { it.id == "gpu-cuda" }
        assertThat(onGpu.bestSubmissionId).isEqualTo(first.id)
        assertThat(onGpu.recordScore!!.value).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(vectorAdd.processors.single { it.id == "rtl" }.recordScore).isNull()
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
