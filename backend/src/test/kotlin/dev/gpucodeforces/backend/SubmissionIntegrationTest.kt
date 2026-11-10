package dev.gpucodeforces.backend

import com.sun.net.httpserver.HttpServer
import dev.gpucodeforces.backend.api.model.CheckExample200Response
import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
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
import dev.gpucodeforces.backend.problem.BenchmarkDefinition
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
import dev.gpucodeforces.backend.submission.MeasurementData
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Progress
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
import org.springframework.transaction.support.TransactionTemplate
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
import java.util.UUID
import dev.gpucodeforces.backend.problem.Problem as StoredProblem

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

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun register(handle: String): Pair<User, String> {
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(User::class.java).returnResult()
        return result.responseBody!! to result.responseCookies.getFirst("session")!!.value
    }

    private fun submit(cookie: String, code: String, contestId: String? = null): Pair<Submission, SubmissionRequest> {
        val submission = client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(SubmitSolutionRequest("vector_add", code).contestId(contestId))
            .exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
        return submission to nextGatewayRequest()
    }

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

    private fun finished(verdict: Verdict, tests: Int, failedTest: Int? = null, time: Double = 1.5) = JudgeResult(
        verdict = verdict,
        failedTest = failedTest,
        message = if (failedTest != null) "Неверный ответ на тесте $failedTest" else null,
        tests = (1..(failedTest ?: tests)).map {
            TestResult(it, "t$it", it == 1, if (it == failedTest) verdict else Verdict.ACCEPTED, time, time, 1, null, null)
        },
        maxTimeMs = if (verdict == Verdict.ACCEPTED) time else null,
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
        assertThat(firstRequest.gpuProfile).isEqualTo("rtx3060")
        publish(event(firstRequest, 3, JUDGE_TESTING, progress = Progress(3, 5)))
        awaitStatus(first.id, "checking")
        publish(event(firstRequest, 1, JUDGE_COMPILING))
        publish(event(firstRequest, 9, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 5, 1), jobId = "another-job"))
        publish(event(firstRequest, 7, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5)))
        publish(event(firstRequest, 7, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 5)))
        val accepted = awaitStatus(first.id, "accepted", aliceCookie)
        assertThat(accepted.timeMs).isEqualByComparingTo(BigDecimal("1.5"))
        assertThat(accepted.passedTests).isEqualTo(5)
        assertThat(accepted.totalTests).isEqualTo(5)
        assertThat(accepted.comparable).isTrue()
        assertThat(accepted.code).isEqualTo("void solve() {}")
        assertThat(accepted.environmentMarkdown).contains("NVIDIA GeForce RTX 3060")
        assertThat(fetch(first.id, bobCookie).code).isNull()

        val (second, secondRequest) = submit(aliceCookie, "wrong")
        assertThat(second.previousBestTimeMs).isEqualByComparingTo(BigDecimal("1.5"))
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
        assertThat(vectorAdd.bestSubmissionId).isEqualTo(first.id)
        assertThat(vectorAdd.recordTimeMs).isEqualByComparingTo(BigDecimal("1.5"))
        val leaders = client.get().uri("/problems/vector_add/leaders").exchange()
            .expectBody(Array<ListProblemLeaders200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(leaders.map { it.user.handle to it.rank }).containsExactly("alice" to 1)

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
                .body(mapOf("code" to "void solve() {}"))
                .exchange().expectStatus().isOk.expectBody(CheckExample200Response::class.java).returnResult().responseBody!!
        }
        val exampleRequest = nextGatewayRequest()
        assertThat(exampleRequest.submissionId).startsWith("example-")
        assertThat(exampleRequest.problem.generator.source).contains("if test.get(\"sample\")")
        publish(event(exampleRequest, 3, JUDGE_FINISHED, result = finished(Verdict.WRONG_ANSWER, 1, 1)))
        val exampleResult = example.get(20, TimeUnit.SECONDS)
        assertThat(exampleResult.passed).isFalse()
        assertThat(exampleResult.diagnostic).contains("Неверный ответ")
        assertThat(gatewayRequests).isEmpty()

        // Contest: registration and a live contest are required.
        val startAt = Instant.now().plusSeconds(2)
        val contest = client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", aliceCookie)
            .body(ContestInput("Раунд", startAt.atOffset(ZoneOffset.UTC), startAt.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC), setOf(alice.id), setOf("vector_add")))
            .exchange().expectStatus().isCreated.expectBody(Contest::class.java).returnResult().responseBody!!
        client.put().uri("/contests/${contest.id}/registration").cookie("session", bobCookie).exchange().expectStatus().isNoContent
        Thread.sleep(Duration.between(Instant.now(), startAt).plusMillis(300).toMillis().coerceAtLeast(0))
        client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", aliceCookie)
            .body(SubmitSolutionRequest("vector_add", "x").contestId(contest.id)).exchange().expectStatus().isForbidden
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
    fun `optimization persists geometric measurements and reports invalid data as a recheckable error`() {
        val (_, cookie) = register("metrics_user")
        val source = problemCatalog.currentVersion("vector_add")!!
        val id = "geometric_vector_add"
        transactionTemplate.executeWithoutResult {
            val problem = problemRepository.save(StoredProblem(id))
            val version = versionRepository.save(
                ProblemVersion(
                    UUID.randomUUID(), id, "G", source.title, source.difficulty, source.tags, source.authors, false,
                    source.statementMarkdown, source.starterCode,
                    jsonMapper.writeValueAsString(problemCatalog.spec(source).copy(id = id)),
                    "geometric-content", "geometric-judge", null, format = "optimization",
                    formatData = jsonMapper.writeValueAsString(
                        ProblemFormatData(
                            format = "optimization", baselineTimeMs = BigDecimal("4"),
                            benchmark = BenchmarkDefinition("Два теста сложения векторов", "geometric_mean"),
                        ),
                    ),
                ),
            )
            problem.currentVersionId = version.id
        }
        fun send() = client.post().uri("/submissions").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(SubmitSolutionRequest(id, "void solve() {}"))
            .exchange().expectStatus().isAccepted.expectBody(Submission::class.java).returnResult().responseBody!!
        val measured = finished(Verdict.ACCEPTED, 2, time = 4.0).copy(
            tests = listOf(TestResult(1, "first", true, Verdict.ACCEPTED, 1.0, 1.0, 1), TestResult(2, "second", false, Verdict.ACCEPTED, 4.0, 4.0, 3)),
            gpu = GpuInfo("rtx3060", "Actual device", 0, "executor-1"), toolchain = "nvcc test",
        )
        val first = send()
        val firstRequest = nextGatewayRequest()
        publish(event(firstRequest, 1, JUDGE_FINISHED, result = measured))
        val accepted = awaitStatus(first.id, "accepted", cookie)
        assertThat(accepted.timeMs).isEqualByComparingTo("2.0")
        assertThat(accepted.measurement!!.aggregation.value).isEqualTo("geometric_mean")
        assertThat(accepted.measurement!!.gpuName).isEqualTo("Actual device")
        assertThat(accepted.measurement!!.testCount).isEqualTo(2)
        val stored = jsonMapper.readValue(submissionRepository.findById(UUID.fromString(first.id)).orElseThrow().measurement!!, MeasurementData::class.java)
        assertThat(stored.tests.map { it.timeMs.toPlainString() }).containsExactly("1.0", "4.0")
        assertThat(stored.tests.map { it.runs }).containsExactly(1, 3)
        assertThat(stored.toolchain).isEqualTo("nvcc test")
        assertThat(jsonMapper.writeValueAsString(fetch(first.id))).doesNotContain("toolchain", "\"tests\"", "nvcc test")

        val invalid = send()
        val invalidRequest = nextGatewayRequest()
        publish(event(invalidRequest, 1, JUDGE_FINISHED, result = measured.copy(tests = measured.tests.map { it.copy(timeMs = null) })))
        val failed = awaitStatus(invalid.id, "measurement_error", cookie)
        assertThat(failed.timeMs).isNull()
        assertThat(failed.measurement).isNull()
        assertThat(failed.diagnostic).contains("замер")
        client.post().uri("/submissions/${invalid.id}/recheck").cookie("session", cookie).exchange().expectStatus().isAccepted
        val recheckRequest = nextGatewayRequest()
        publish(event(recheckRequest, 1, JUDGE_FINISHED, result = measured))
        assertThat(awaitStatus(invalid.id, "accepted", cookie).timeMs).isEqualByComparingTo("2.0")
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
                        "gpuProfile" to request.gpuProfile,
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
            registry.add("spring.rabbitmq.listener.simple.auto-startup") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopGateway() {
            gateway.stop(0)
        }
    }
}
