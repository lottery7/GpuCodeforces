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
import dev.gpucodeforces.backend.api.model.CreateHackRequest
import dev.gpucodeforces.backend.api.model.Hack
import dev.gpucodeforces.backend.api.model.VirtualParticipation
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.submission.HackService
import dev.gpucodeforces.backend.submission.SubmissionService
import dev.gpucodeforces.backend.submission.JudgingRunRepository
import dev.gpucodeforces.backend.rating.RatingFinalizationService
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import org.intellij.lang.annotations.Language
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

    @Autowired lateinit var contests: ContestRepository
    @Autowired lateinit var hacks: HackService
    @Autowired lateinit var submissionService: SubmissionService
    @Autowired lateinit var ratingFinalizer: RatingFinalizationService
    @Autowired lateinit var judgingRuns: JudgingRunRepository
    @Autowired lateinit var jdbc: JdbcTemplate

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
        val solvedPage = client.get().uri("/problems?solved=true&sort=bestTime&limit=1").cookie("session", aliceCookie).exchange()
            .expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        assertThat(solvedPage.total).isEqualTo(1)
        assertThat(solvedPage.items.map { it.id }).containsExactly("vector_add")
        val unsolvedPage = client.get().uri("/problems?solved=false&limit=1").cookie("session", aliceCookie).exchange()
            .expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!
        assertThat(unsolvedPage.items.map { it.id }).containsExactly("matmul_host")
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

        // Contest: registration and a live contest are required; the practice record is the baseline.
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
        assertThat(standings.single().speedup).isEqualByComparingTo(BigDecimal("0.75"))
        assertThat(standings.single().times["vector_add"]).isEqualByComparingTo(BigDecimal("2.0"))

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
    @AllowFullScan("users", "UPDATE gpucodeforces\\.users u SET rank", "Finalizing a contest recomputes global user ranks")
    fun `hacks rejudge every accepted solution before finalization and training modes stay isolated`() {
        val (author, authorCookie) = register("hack_author")
        val (_, victimCookie) = register("hack_victim")
        val start = Instant.now().plusSeconds(1)
        val contest = client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", authorCookie)
            .body(ContestInput("Взломы и тренировка", start.atOffset(ZoneOffset.UTC), start.plusSeconds(3600).atOffset(ZoneOffset.UTC), setOf(author.id), setOf("vector_add")).hackDurationMinutes(60))
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
                .body(SubmitSolutionRequest("vector_add", "training").context(context))
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
        @Language("SQL") val expire = "UPDATE gpucodeforces.virtual_participations SET started_at = now() - interval '2 hours', ends_at = now() - interval '1 second' WHERE id = ?"
        jdbc.update(expire, UUID.fromString(virtual.id))
        client.post().uri("/submissions").cookie("session", authorCookie).contentType(MediaType.APPLICATION_JSON)
            .body(SubmitSolutionRequest("vector_add", "late").context("virtual:${virtual.id}"))
            .exchange().expectStatus().isBadRequest
        publish(event(virtualRequest, 1, JUDGE_FINISHED, result = finished(Verdict.ACCEPTED, 6, time = 0.25)))
        awaitStatus(virtualSubmission.id, "accepted", authorCookie)
        val result = client.get().uri("/virtual/${virtual.id}").cookie("session", authorCookie).exchange()
            .expectStatus().isOk.expectBody(VirtualParticipation::class.java).returnResult().responseBody!!
        assertThat(result.solved).isEqualTo(1)
        assertThat(result.pending).isFalse()
        val standings = client.get().uri("/contests/${contest.id}/standings").exchange().expectStatus().isOk
            .expectBody(Array<GetContestStandings200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(standings.single { it.user.id == author.id }.solved).isZero()
        assertThat(standings.flatMap { it.times.values }.single()).isEqualByComparingTo(BigDecimal("2.0"))
        val next = startVirtual()
        assertThat(next.id).isNotEqualTo(virtual.id)
        assertThat(next.solved).isZero()
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
