package dev.gpucodeforces.backend

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.springframework.web.server.ResponseStatusException
import org.mockito.Mockito
import tools.jackson.databind.JsonNode

@Tag("integration")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "platform.scheduling-enabled=false",
        "platform.secure-cookies=false",
        "platform.judge.inbox-capacity=2",
        "platform.judge.max-attempts=2",
        "platform.admin.bootstrap-token=first-admin-bootstrap-token-with-32-characters",
    ]
)
class PlatformIntegrationTest {
    @Autowired
    lateinit var db: Database

    @Autowired
    lateinit var service: PlatformService

    @Autowired
    lateinit var judge: Judge

    @Autowired
    lateinit var accounts: Accounts

    @Autowired
    lateinit var ratings: Ratings

    @Autowired
    lateinit var settings: RuntimeSettings

    @Value("\${local.server.port}")
    var port = 0
    private val client = HttpClient.newHttpClient()
    private val revId = UUID.randomUUID()
    private val problem = ProblemPackage(
        "A",
        "Vectors",
        "easy",
        listOf("CUDA"),
        "statement",
        "starter",
        "secret generator",
        "secret reference",
        2,
        1.0,
        128,
        10.0,
        "A100"
    )

    @BeforeEach
    fun seed() {
        db.jdbc.execute("TRUNCATE app_user, source_repository, problem_revision, contest, judge_worker CASCADE")
        messages.clear()
        db.update("UPDATE runtime_settings SET document = '{}'::jsonb, version = version + 1")
        settings.invalidate()
        for (id in listOf("alice", "bob")) {
            db.update(
                """
                    INSERT INTO app_user(id, handle, email, password_hash) VALUES (?, ?, ?, 'unused' )
                """.trimIndent(), id, id, "$id@example.com"
            )
            db.update(
                """
                    INSERT INTO app_session VALUES (?, ?, now() + interval '1 day' )
                """.trimIndent(), accounts.digest("$id-token"), id
            )
        }
        db.update("INSERT INTO source_repository(id, installation_id, full_name) VALUES (42, 1, 'owner/vectors')")
        db.update("INSERT INTO source_problem(id, repository_id, task_key) VALUES ('42', 42, 'legacy')")
        db.update(
            """
                INSERT INTO problem_revision(id, repository_id, problem_id, commit_sha, document)
                VALUES (?, 42, '42', ?, ?::jsonb)
            """.trimIndent(), revId, "a".repeat(40), db.json.writeValueAsString(problem)
        )
        db.update("UPDATE source_problem SET current_revision = ? WHERE id = '42'", revId)
    }

    private fun request(
        path: String,
        method: String = "GET",
        body: Any? = null,
        cookie: String? = "alice-token",
        auth: String? = null
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI("http://localhost:$port/api$path")).header("Origin", "http://localhost:5173")
        if (cookie != null) builder.header("Cookie", "session=$cookie")
        if (auth != null) builder.header("Authorization", auth)
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(
            builder.method(
                method,
                if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(
                    db.json.writeValueAsString(body)
                )
            ).build(), HttpResponse.BodyHandlers.ofString()
        )
    }

    private fun activate() {
        judge.dispatch()
        assertThat(messages).isEmpty()
        judge.ping()
        val ping = db.json.readTree(messages.remove().second)
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isZero()
        assertThat(
            request(
                "/internal/judges/one/pong",
                "POST",
                PongInput(UUID.fromString(ping.path("nonce").asString())),
                null,
                "Bearer $token"
            ).statusCode()
        ).isEqualTo(204)
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isEqualTo(1)
    }

    private fun assign(id: UUID): StoredSubmission {
        activate()
        judge.dispatch()
        return db.submission(id).also { assertThat(it.attemptId).isNotNull() }
    }

    private fun accepted(id: UUID, time: Double = 1.0) {
        val s = db.submission(id)
        for (number in 1..2) {
            judge.receive(s.workerId!!, TestCallback(id, s.attemptId!!, number, "accepted", time)); judge.consume()
        }
    }

    @Test
    fun `register login session logout and nullable initial rating work over HTTP`() {
        val created = request("/users", "POST", RegisterInput("new_user", "new@example.com", "strong-password"), null)
        assertThat(created.statusCode()).isEqualTo(201)
        val json = db.json.readTree(created.body())
        assertThat(json.path("rating").isNull).isTrue()
        assertThat(json.has("email")).isFalse()
        val token =
            created.headers().firstValue("Set-Cookie").orElseThrow().substringAfter("session=").substringBefore(';')
        assertThat(request("/session", cookie = token).statusCode()).isEqualTo(200)
        assertThat(request("/session", "DELETE", cookie = token).statusCode()).isEqualTo(204)
        assertThat(request("/session", cookie = token).statusCode()).isEqualTo(401)
        assertThat(
            request(
                "/session",
                "POST",
                LoginInput("new@example.com", "wrong"),
                null
            ).statusCode()
        ).isEqualTo(401)
        assertThat(request("/session", "POST", LoginInput("NEW_USER", "strong-password"), null).statusCode()).isEqualTo(
            200
        )
        assertThat(
            request(
                "/users",
                "POST",
                RegisterInput("NEW_USER", "other@example.com", "strong-password"),
                null
            ).statusCode()
        ).isEqualTo(400)
    }

    @Test
    fun `task metadata excludes private artifacts and callback buffers before processing`() {
        val task = request("/problems/42")
        assertThat(task.statusCode()).isEqualTo(200)
        assertThat(db.json.readTree(task.body()).path("testCount").asInt()).isEqualTo(2)
        assertThat(task.body()).doesNotContain("secret generator", "secret reference")
        val response = request("/submissions", "POST", SubmitInput("42", "participant source"))
        assertThat(response.statusCode()).isEqualTo(202)
        val id = UUID.fromString(db.json.readTree(response.body()).path("id").asString())
        val s = assign(id)
        val taskBody = db.json.readTree(messages.remove().second)
        assertThat(taskBody.path("generator").asString()).isEqualTo("secret generator")
        assertThat(taskBody.path("participantId").asString()).isEqualTo("alice")
        val callback = TestCallback(id, s.attemptId!!, 2, "accepted", 2.0)
        val endpoint = "/internal/judges/one/results"
        assertThat(request(endpoint, "POST", callback, null, "Bearer wrong").statusCode()).isEqualTo(401)
        assertThat(request(endpoint, "POST", callback, null, "Bearer $token").statusCode()).isEqualTo(202)
        assertThat(db.count("SELECT count(*) FROM test_result")).isZero()
        judge.consume()
        assertThat(db.submission(id).status).isEqualTo("checking")
        judge.receive("one", TestCallback(id, s.attemptId, 1, "accepted", 1.0)); judge.consume()
        assertThat(db.submission(id).status).isEqualTo("accepted")
        assertThat(db.submission(id).timeMs).isEqualTo(3.0)
        assertThat(request("/submissions/$id", cookie = "bob-token").body()).doesNotContain(
            "participant source",
            "\"diagnostic\""
        )
        assertThat(request("/submissions/$id").body()).contains("participant source", "testResults")
        assertThat(service.leaders("42", "practice", null)).hasSize(1)
    }

    @Test
    fun `caps worker in-flight jobs and inbox with idempotent callbacks`() {
        activate()
        val ids = (1..3).map { service.submit(SubmitInput("42", "code"), "alice") }
        judge.dispatch()
        assertThat(messages.count { it.first == "/tasks" }).isEqualTo(2)
        assertThat(db.submission(ids.last()).status).isEqualTo("queued")
        val first = db.submission(ids.first())
        val callback = TestCallback(first.id, first.attemptId!!, 1, "accepted", 1.0)
        judge.receive("one", callback); judge.receive("one", callback)
        assertThat(db.count("SELECT count(*) FROM judge_inbox")).isEqualTo(1)
        assertThatThrownBy { judge.receive("one", callback.copy(timeMs = 2.0)) }.hasMessageContaining("409")
        judge.receive("one", callback.copy(testNumber = 2))
        val second = db.submission(ids[1])
        assertThatThrownBy {
            judge.receive(
                "one",
                TestCallback(second.id, second.attemptId!!, 1, "accepted", 1.0)
            )
        }.hasMessageContaining("503")
        judge.consume(); judge.dispatch()
        assertThat(db.submission(ids.last()).status).isEqualTo("compiling")
    }

    @Test
    fun `timeout invalidates attempt and requires pong before worker recovery`() {
        val id = service.submit(SubmitInput("42", "code"), "alice")
        val old = assign(id)
        db.update("UPDATE submission SET deadline = now() - interval '1 second' WHERE id = ?", id)
        judge.dispatch()
        assertThat(db.submission(id).status).isEqualTo("queued")
        judge.receive("one", TestCallback(id, old.attemptId!!, 1, "accepted", 1.0)); judge.consume()
        assertThat(db.count("SELECT count(*) FROM test_result")).isZero()
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isZero()
        messages.clear(); judge.ping()
        val nonce = UUID.fromString(db.json.readTree(messages.remove().second).path("nonce").asString())
        judge.pong("one", UUID.randomUUID())
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isZero()
        judge.pong("one", nonce); judge.dispatch()
        assertThat(db.submission(id).attemptId).isNotEqualTo(old.attemptId)
        db.update("UPDATE submission SET deadline = now() - interval '1 second' WHERE id = ?", id)
        judge.dispatch()
        assertThat(db.submission(id).status).isEqualTo("system_error")
        service.recheck(id, "alice")
        assertThat(db.submission(id).status).isEqualTo("queued")
    }

    @Test
    fun `submission results confirm liveness cancel pending pong and extend the attempt deadline`() {
        val id = service.submit(SubmitInput("42", "code"), "alice")
        val assigned = assign(id)
        messages.clear()
        db.update(
            """
                UPDATE judge_worker SET alive = false, ping_nonce = ?, ping_deadline = now() - interval '1 second',
                next_ping_at = now() - interval '1 second'
            """.trimIndent(), UUID.randomUUID(),
        )
        db.update("UPDATE submission SET deadline = now() - interval '1 second' WHERE id = ?", id)
        val callback = TestCallback(id, assigned.attemptId!!, 1, "accepted", 1.0)
        judge.receive("other", callback)
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isZero()
        judge.receive("one", callback)
        val worker = db.row("SELECT * FROM judge_worker WHERE id = 'one'")!!
        assertThat(worker["alive"]).isEqualTo(true)
        assertThat(worker["ping_nonce"]).isNull()
        assertThat(worker["ping_deadline"]).isNull()
        val deadline = db.judgeState(id).deadline!!
        assertThat(deadline).isAfter(Instant.now())
        judge.receive("one", callback)
        assertThat(db.judgeState(id).deadline).isEqualTo(deadline)
        judge.ping()
        judge.dispatch()
        assertThat(messages).isEmpty()
        assertThat(db.submission(id).attemptId).isEqualTo(assigned.attemptId)
        assertThat(db.count("SELECT count(*) FROM test_result")).isZero()
        judge.consume()
        assertThat(db.submission(id).status).isEqualTo("checking")
        db.update("UPDATE judge_worker SET alive = false WHERE id = 'one'")
        judge.receive("one", callback)
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isEqualTo(1)
        assertThat(db.judgeState(id).deadline).isEqualTo(deadline)
    }

    @Test
    fun `successful task acceptance confirms liveness without waiting for pong`() {
        activate()
        val id = service.submit(SubmitInput("42", "code"), "alice")
        db.update(
            """
                UPDATE judge_worker SET ping_nonce = ?, ping_deadline = now() - interval '1 second',
                next_ping_at = now() - interval '1 second'
            """.trimIndent(), UUID.randomUUID(),
        )
        judge.dispatch()
        assertThat(db.submission(id).status).isEqualTo("compiling")
        assertThat(db.row("SELECT ping_nonce FROM judge_worker WHERE id = 'one'")!!["ping_nonce"]).isNull()
        messages.clear()
        judge.ping()
        assertThat(messages).isEmpty()
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isEqualTo(1)
    }

    @Test
    fun `valid results still confirm liveness when the result buffer is full`() {
        activate()
        val first = service.submit(SubmitInput("42", "code"), "alice")
        val second = service.submit(SubmitInput("42", "code"), "alice")
        judge.dispatch()
        val attempt = db.submission(first).attemptId!!
        judge.receive("one", TestCallback(first, attempt, 1, "accepted", 1.0))
        judge.receive("one", TestCallback(first, attempt, 2, "accepted", 1.0))
        db.update("UPDATE judge_worker SET alive = false, ping_nonce = ?", UUID.randomUUID())
        assertThatThrownBy {
            judge.receive("one", TestCallback(second, db.submission(second).attemptId!!, 1, "accepted", 1.0))
        }.hasMessageContaining("503")
        assertThat(db.count("SELECT count(*) FROM judge_inbox")).isEqualTo(2)
        assertThat(db.count("SELECT count(*) FROM judge_worker WHERE alive")).isEqualTo(1)
        assertThat(db.row("SELECT ping_nonce FROM judge_worker WHERE id = 'one'")!!["ping_nonce"]).isNull()
    }

    @Test
    fun `failed test finalizes after preceding results and retry preserves submission identity`() {
        val id = service.submit(SubmitInput("42", "code"), "alice")
        val s = assign(id)
        judge.receive(
            "one",
            TestCallback(id, s.attemptId!!, 2, "measurement_error", diagnostic = "measurement failed")
        ); judge.consume()
        assertThat(db.submission(id).status).isEqualTo("checking")
        judge.receive("one", TestCallback(id, s.attemptId, 1, "accepted", 1.0)); judge.consume()
        assertThat(db.submission(id).status).isEqualTo("measurement_error")
        assertThatThrownBy { service.recheck(id, "bob") }.hasMessageContaining("403")
        service.recheck(id, "alice")
        assertThat(db.submission(id).createdAt).isEqualTo(s.createdAt)
        assertThat(db.submission(id).revisionId).isEqualTo(s.revisionId)
        assertThat(db.count("SELECT count(*) FROM test_result WHERE submission_id = ?", id)).isZero()
    }

    @Test
    fun `contest snapshots access registration separate records and rating work`() {
        val input = ContestInput(
            "Round",
            Instant.now().plusSeconds(3600),
            Instant.now().plusSeconds(7200),
            listOf("alice"),
            listOf("42")
        )
        val contest = service.saveContest(null, input, "alice")
        assertThatThrownBy { service.problem("42", contest.toString(), "bob") }.hasMessageContaining("403")
        assertThat(service.problem("42", contest.toString(), "alice")["testCount"]).isEqualTo(2)
        assertThat(service.contestDto(contest, "bob")["problemIds"]).isEqualTo(emptyList<String>())
        service.saveContest(contest, input.copy(authorIds = listOf("bob")), "alice")
        assertThatThrownBy { service.saveContest(contest, input, "alice") }.hasMessageContaining("403")
        service.register(contest, "alice"); service.register(contest, "alice")
        assertThat(db.count("SELECT count(*) FROM contest_registration WHERE contest_id = ?", contest)).isEqualTo(1)
        val newer = UUID.randomUUID()
        db.update(
            """
                INSERT INTO problem_revision(id, repository_id, problem_id, commit_sha, document)
                VALUES (?, 42, '42', ?, ?::jsonb)
            """.trimIndent(), newer, "b".repeat(40), db.json.writeValueAsString(problem.copy(title = "Changed"))
        )
        db.update("UPDATE source_problem SET current_revision = ? WHERE id = '42'", newer)
        assertThat(service.problem("42", contest.toString(), "bob")["title"]).isEqualTo("Vectors")
        db.update(
            """
                UPDATE contest SET start_at = now() - interval '1 hour' , end_at = now() + interval '1 hour'
                WHERE id = ?
            """.trimIndent(), contest
        )
        assertThatThrownBy {
            service.submit(
                SubmitInput("42", "code", contest.toString()),
                "bob"
            )
        }.hasMessageContaining("403")
        val id = service.submit(SubmitInput("42", "code", contest.toString()), "alice")
        assign(id); accepted(id)
        assertThat(service.leaders("42", "practice", "alice")).isEmpty()
        assertThat(service.leaders("42", contest.toString(), "alice")).hasSize(1)
        assertThat(service.standings(contest).first()["speedup"]).isEqualTo(5.0)
        db.update("UPDATE contest SET end_at = now() - interval '1 second' WHERE id = ?", contest)
        ratings.update()
        assertThat(service.user("alice")["rating"]).isEqualTo(1500)
        assertThat(service.profile("alice")["solvedCount"]).isEqualTo(1L)
    }

    @Test
    fun `cached ratings and ranks are rebuilt after a late successful recheck`() {
        val contest = service.saveContest(null, ContestInput(
            "Rank rebuild", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
            listOf("alice"), listOf("42"),
        ), "alice")
        service.register(contest, "alice")
        service.register(contest, "bob")
        db.update(
            """
                UPDATE contest SET start_at = now() - interval '1 hour', end_at = now() + interval '1 hour'
                WHERE id = ?
            """.trimIndent(), contest,
        )
        val alice = service.submit(SubmitInput("42", "source", contest.toString()), "alice")
        val bob = service.submit(SubmitInput("42", "source", contest.toString()), "bob")
        val attempt = assign(alice).attemptId!!
        judge.receive("one", TestCallback(alice, attempt, 1, "measurement_error"))
        judge.consume()
        accepted(bob, 2.0)
        db.update("UPDATE contest SET end_at = now() - interval '1 second' WHERE id = ?", contest)
        ratings.update()
        assertThat((service.user("alice")["rank"] as Number).toLong()).isEqualTo(2)
        assertThat(service.user("alice")["rating"]).isEqualTo(1484)
        service.recheck(alice, "alice")
        judge.dispatch()
        accepted(alice, 1.0)
        ratings.update()
        assertThat((service.user("alice")["rank"] as Number).toLong()).isEqualTo(1)
        assertThat(service.user("alice")["rating"]).isEqualTo(1516)
        assertThat((service.user("bob")["rank"] as Number).toLong()).isEqualTo(2)
    }

    @Test
    fun `examples are queued independently and excluded from public history`() {
        val id = service.submit(SubmitInput("42", "code"), "alice", true)
        val s = assign(id)
        judge.receive("one", TestCallback(id, s.attemptId!!, 1, "accepted", 1.0)); judge.consume()
        assertThat(db.submission(id).status).isEqualTo("accepted")
        assertThat(service.submissions(emptyMap(), "alice")["total"]).isEqualTo(0L)
        assertThat(service.leaders("42", "practice", "alice")).isEmpty()
        assertThat(request("/submissions/$id").statusCode()).isEqualTo(404)
    }

    @Test
    fun `concurrent submissions cannot bypass per-user queue bound`() {
        val results = Executors.newFixedThreadPool(8).use { executor ->
            executor.invokeAll((1..20).map {
                Callable {
                    try {
                        service.submit(SubmitInput("42", "code"), "alice"); true
                    } catch (error: ResponseStatusException) {
                        if (error.statusCode.value() != 429) throw error; false
                    }
                }
            }).map { it.get() }
        }
        assertThat(results.count { it }).isEqualTo(10)
        assertThat(db.count("SELECT count(*) FROM submission WHERE finished_at IS NULL")).isEqualTo(10)
    }

    @Test
    fun `timed out job is reassigned to another live worker with a fresh attempt`() {
        val url = "http://127.0.0.1:${worker.address.port}"
        val pool = Judge(
            db,
            PlatformConfig(
                judge = JudgeConfig(
                    workers = listOf(
                        WorkerConfig("one", url, token),
                        WorkerConfig("two", url, token)
                    )
                )
            ),
            settings,
        )
        pool.dispatch()
        db.update("UPDATE judge_worker SET alive = true")
        val id = service.submit(SubmitInput("42", "code"), "alice")
        pool.dispatch()
        val first = db.submission(id)
        assertThat(first.workerId).isEqualTo("one")
        db.update("UPDATE submission SET deadline = now() - interval '1 second' WHERE id = ?", id)
        pool.dispatch()
        val second = db.submission(id)
        assertThat(second.workerId).isEqualTo("two")
        assertThat(second.attemptId).isNotEqualTo(first.attemptId)
        pool.receive("one", TestCallback(id, first.attemptId!!, 1, "accepted", 1.0))
        pool.consume()
        assertThat(db.count("SELECT count(*) FROM test_result")).isZero()
        for (number in 1..2) {
            pool.receive("two", TestCallback(id, second.attemptId!!, number, "accepted", 1.0)); pool.consume()
        }
        assertThat(db.submission(id).status).isEqualTo("accepted")
    }

    @Test
    fun `github import preserves previous revision on rejection and removes uninstalled repositories`() {
        val api = Mockito.mock(GithubApi::class.java)
        val config = PlatformConfig(github = GithubConfig(appId = "test"))
        val monitor = GithubMonitor(db, config, api, PackageReader(config, db.json), settings)
        val installations = jsonDocument(
            db.json, """
                [{"id":1,"suspended_at":null}]
            """.trimIndent()
        ).toList()
        val repositories = jsonDocument(db.json, """[{"id":42,"full_name":"owner/vectors","size":1}]""").toList()
        Mockito.`when`(api.appToken()).thenReturn("jwt")
        Mockito.`when`(api.list("/app/installations", "jwt", 20)).thenReturn(installations)
        Mockito.`when`(api.installationToken(1)).thenReturn("installation-token")
        Mockito.`when`(api.list("/installation/repositories", "installation-token", 20, "repositories"))
            .thenReturn(repositories)
        val sha = "c".repeat(40)
        val treeSha = "d".repeat(40)
        val base = "/repos/owner/vectors"
        Mockito.`when`(api.request("$base/commits/master", "installation-token"))
            .thenReturn(db.json.valueToTree(mapOf("sha" to sha, "commit" to mapOf("tree" to mapOf("sha" to treeSha)))))
        val (tree, blobs) = PackageReaderTest().packageTree()
        Mockito.`when`(api.request("$base/git/trees/$treeSha?recursive=1", "installation-token")).thenReturn(tree)
        blobs.forEach { (hash, blob) ->
            Mockito.`when`(api.request("$base/git/blobs/$hash", "installation-token")).thenReturn(blob)
        }
        db.update(
            """
                UPDATE platform_lease
                SET expires_at = now() - interval '1 second',
                    next_discovery_at = now() - interval '1 second'
            """.trimIndent()
        )
        monitor.tick()
        val imported = db.row(
            """
                SELECT current_revision FROM source_problem WHERE id = '42'
            """.trimIndent()
        )!!["current_revision"] as UUID
        assertThat(imported).isNotEqualTo(revId)
        assertThat(db.revision(imported).sha).isEqualTo(sha)
        assertThat(db.revision(imported).problem.generator).isEqualTo("generator")
        assertThat(db.count("SELECT count(*) FROM problem_revision")).isEqualTo(2)
        val rejectedSha = "e".repeat(40)
        Mockito.`when`(api.request("$base/commits/master", "installation-token")).thenReturn(
            db.json.valueToTree(
                mapOf(
                    "sha" to rejectedSha,
                    "commit" to mapOf("tree" to mapOf("sha" to treeSha))
                )
            )
        )
        Mockito.`when`(api.request("$base/git/trees/$treeSha?recursive=1", "installation-token")).thenReturn(
            jsonDocument(
                db.json, """
                    {
                      "truncated": true,
                      "tree": []
                    }
                """.trimIndent()
            )
        )
        db.update("UPDATE source_repository SET checked_at = now() - interval '1 hour'")
        monitor.tick()
        assertThat(
            db.row(
                """
                    SELECT current_revision FROM source_problem WHERE id = '42'
                """.trimIndent()
            )!!["current_revision"]
        ).isEqualTo(imported)
        assertThat(db.row("SELECT error FROM source_repository WHERE id = 42")!!["error"]).isNotNull()
        assertThat(db.count("SELECT count(*) FROM problem_revision")).isEqualTo(2)
        Mockito.`when`(api.list("/app/installations", "jwt", 20)).thenReturn(emptyList())
        db.update("UPDATE platform_lease SET next_discovery_at = now() - interval '1 second'")
        monitor.tick()
        assertThat(db.count("SELECT count(*) FROM source_repository WHERE enabled")).isZero()
        assertThat(db.revision(imported).sha).isEqualTo(sha)
    }

    @Test
    fun `administration requires a role and bootstrap can only be claimed once`() {
        assertThat(request("/admin/settings", cookie = null).statusCode()).isEqualTo(401)
        assertThat(request("/admin/settings").statusCode()).isEqualTo(403)
        assertThat(request("/admin/console", "POST", ConsoleInput("1 + 1")).statusCode()).isEqualTo(403)
        assertThat(request("/admin/bootstrap", "POST", BootstrapInput("wrong")).statusCode()).isEqualTo(403)
        val secret = "first-admin-bootstrap-token-with-32-characters"
        assertThat(request("/admin/bootstrap", "POST", BootstrapInput(secret)).statusCode()).isEqualTo(200)
        assertThat(accounts.isAdmin("alice")).isTrue()
        assertThat(accounts.isAdmin("bob")).isFalse()
        assertThat(request("/admin/bootstrap", "POST", BootstrapInput(secret), "bob-token").statusCode())
            .isEqualTo(409)
        assertThat(request("/admin/settings", cookie = "bob-token").statusCode()).isEqualTo(403)
        db.update("UPDATE app_user SET is_admin = false WHERE id = 'alice'")
        assertThat(request("/admin/settings").statusCode()).isEqualTo(403)
        assertThat(db.count("SELECT count(*) FROM admin_audit WHERE action = 'admin.bootstrap'")).isEqualTo(1)
    }

    @Test
    fun `groovy console exposes application context captures output and reports compilation failures`() {
        db.update("UPDATE app_user SET is_admin = true WHERE id = 'alice'")
        val script = ConsoleInput(
            """
                println ctx.getBean('platformService').user('alice').handle
                println ctx.getBean('platformService').db.count('SELECT 1')
                return 6 * 7
            """.trimIndent(),
        )
        val success = request("/admin/console", "POST", script)
        assertThat(success.statusCode()).isEqualTo(200)
        val result = db.json.readTree(success.body())
        assertThat(result.path("output").asString()).contains("alice")
        assertThat(result.path("result").asString()).isEqualTo("42")
        val invalid = request("/admin/console", "POST", ConsoleInput("def ="))
        assertThat(invalid.statusCode()).isEqualTo(422)
        assertThat(db.json.readTree(invalid.body()).path("error").asString()).isNotBlank()
        assertThat(db.count("SELECT count(*) FROM admin_audit WHERE action = 'console.start'")).isEqualTo(2)
    }

    @Test
    fun `admin page is served locally and console requests require the configured origin`() {
        val page = client.send(HttpRequest.newBuilder(URI("http://localhost:$port/admin")).build(),
            HttpResponse.BodyHandlers.ofString())
        assertThat(page.statusCode()).isEqualTo(200)
        assertThat(page.body()).contains("/admin/app.js", "Groovy-консоль", "settings-form")
        assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow())
            .contains("frame-ancestors 'none'")
        db.update("UPDATE app_user SET is_admin = true WHERE id = 'alice'")
        val forged = HttpRequest.newBuilder(URI("http://localhost:$port/api/admin/console"))
            .header("Cookie", "session=alice-token").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(db.json.writeValueAsString(ConsoleInput("1 + 1")))).build()
        assertThat(client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403)
    }

    @Test
    fun `runtime settings persist validate versions and immediately affect submission limits`() {
        db.update("UPDATE app_user SET is_admin = true WHERE id = 'alice'")
        val initial = settings.current()
        val changed = initial.values.copy(maxPendingPerUser = 1, maxInFlight = 1, repositoryIntervalSeconds = 15)
        val update = SettingsUpdate(initial.version, changed)
        val saved = request("/admin/settings", "PUT", update)
        assertThat(saved.statusCode()).isEqualTo(200)
        assertThat(settings.current().values).isEqualTo(changed)
        val observer = RuntimeSettings(db, PlatformConfig())
        assertThat(observer.current().values).isEqualTo(changed)
        assertThat(request("/admin/settings", "PUT", update).statusCode()).isEqualTo(409)
        val invalid = SettingsUpdate(initial.version + 1, changed.copy(maxInFlight = 4))
        assertThat(request("/admin/settings", "PUT", invalid).statusCode()).isEqualTo(400)
        assertThat(settings.current().version).isEqualTo(initial.version + 1)
        service.submit(SubmitInput("42", "code"), "alice")
        assertThatThrownBy { service.submit(SubmitInput("42", "code"), "alice") }.hasMessageContaining("429")
        assertThat(db.count("SELECT count(*) FROM admin_audit WHERE action = 'settings.update'")).isEqualTo(1)
    }

    @Test
    fun `index imports multiple tasks atomically removes tasks and restores an older commit`() {
        val api = Mockito.mock(GithubApi::class.java)
        val config = PlatformConfig(github = GithubConfig(appId = "test"))
        val monitor = GithubMonitor(db, config, api, PackageReader(config, db.json), settings)
        Mockito.`when`(api.appToken()).thenReturn("jwt")
        Mockito.`when`(api.list("/app/installations", "jwt", 20)).thenReturn(
            jsonDocument(db.json, """[{"id":1,"suspended_at":null}]""").toList(),
        )
        Mockito.`when`(api.installationToken(1)).thenReturn("token")
        Mockito.`when`(api.list("/installation/repositories", "token", 20, "repositories")).thenReturn(
            jsonDocument(db.json, """[{"id":42,"full_name":"owner/vectors","size":1}]""").toList(),
        )
        val fixture = PackageReaderTest()
        val tasks = mapOf("one" to fixture.manifest, "two" to fixture.manifest.copy(title = "Second"))
        val (tree1, blobs1) = fixture.packageTree(tasks = tasks)
        val (tree2, blobs2) = fixture.packageTree(tasks = tasks - "two")
        val (tree3, blobs3) = fixture.packageTree(tasks = emptyMap())
        val base = "/repos/owner/vectors"
        val first = "b".repeat(40)
        val second = "c".repeat(40)
        val empty = "d".repeat(40)
        val capped = "e".repeat(40)
        fun commit(sha: String): JsonNode = db.json.valueToTree(
            mapOf("sha" to sha, "commit" to mapOf("tree" to mapOf("sha" to sha))),
        )
        Mockito.`when`(api.request("$base/commits/master", "token"))
            .thenReturn(commit(first), commit(second), commit(first), commit(empty), commit(empty),
                commit(capped), commit(capped))
        for ((sha, tree) in listOf(first to tree1, second to tree2, empty to tree3, capped to tree1)) {
            Mockito.`when`(api.request("$base/git/trees/$sha?recursive=1", "token")).thenReturn(tree)
        }
        (blobs1 + blobs2 + blobs3).forEach { (sha, blob) ->
            Mockito.`when`(api.request("$base/git/blobs/$sha", "token")).thenReturn(blob)
        }
        db.update(
            """
                UPDATE platform_lease SET expires_at = now() - interval '1 second',
                next_discovery_at = now() - interval '1 second'
            """.trimIndent(),
        )
        fun poll() {
            db.update("UPDATE source_repository SET checked_at = now() - interval '1 hour'")
            monitor.tick()
        }
        poll()
        assertThat(db.count("SELECT count(*) FROM source_problem WHERE enabled")).isEqualTo(2)
        assertThat(service.problem("42:two", "practice", "alice")["title"]).isEqualTo("Second")
        assertThat(request("/problems/42%3Atwo").statusCode()).isEqualTo(200)
        val page = db.json.readTree(request("/problems?limit=1&offset=1").body())
        assertThat(page.path("total").asLong()).isEqualTo(2)
        assertThat(page.path("items").size()).isEqualTo(1)
        assertThat(page.path("items").path(0).path("id").asString()).isEqualTo("42:two")
        val filtered = db.json.readTree(request("/problems?q=Second&tag=CUDA&solved=false").body())
        assertThat(filtered.path("total").asLong()).isEqualTo(1)
        assertThat(db.json.readTree(request("/problems?difficulty=hard").body()).path("total").asLong()).isZero()
        assertThat(db.json.readTree(request("/problems?sort=bestTime").body()).path("total").asLong()).isEqualTo(2)
        val contest = service.saveContest(null, ContestInput(
            "Two tasks", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
            listOf("alice"), listOf("42:two", "42:one"),
        ), "alice")
        val ordered = db.json.readTree(request("/problems?context=$contest&sort=code&limit=1").body())
        assertThat(ordered.path("items").path(0).path("id").asString()).isEqualTo("42:two")
        poll()
        assertThat(db.count("SELECT count(*) FROM source_problem WHERE enabled")).isEqualTo(1)
        assertThatThrownBy { service.problem("42:two", "practice", "alice") }.hasMessageContaining("404")
        assertThat(service.problem("42:two", contest.toString(), "alice")["title"]).isEqualTo("Second")
        poll()
        assertThat(db.count("SELECT count(*) FROM source_problem WHERE enabled")).isEqualTo(2)
        assertThat(db.count("SELECT count(*) FROM problem_revision")).isEqualTo(4)
        poll()
        assertThat(db.count("SELECT count(*) FROM source_problem WHERE enabled")).isZero()
        poll()
        assertThat(db.count("SELECT count(*) FROM repository_revision")).isEqualTo(3)
        assertThat(db.count("SELECT count(*) FROM problem_revision")).isEqualTo(4)
        val tinyBytes = config.copy(github = config.github.copy(maxStoredBytesPerRepository = 1))
        db.update("UPDATE source_repository SET checked_at = now() - interval '1 hour'")
        GithubMonitor(db, tinyBytes, api, PackageReader(tinyBytes, db.json), settings).tick()
        assertThat(db.row("SELECT error FROM source_repository WHERE id = 42")!!["error"].toString())
            .contains("Stored repository data exceeds size limit")
        val tinyKeys = config.copy(github = config.github.copy(
            maxTasksPerRepository = 2, maxStoredTasksPerRepository = 2,
        ))
        db.update("UPDATE source_repository SET checked_at = now() - interval '1 hour'")
        GithubMonitor(db, tinyKeys, api, PackageReader(tinyKeys, db.json), settings).tick()
        assertThat(db.row("SELECT error FROM source_repository WHERE id = 42")!!["error"].toString())
            .contains("Stored task count exceeds limit")
        assertThat(db.count("SELECT count(*) FROM source_problem WHERE enabled")).isZero()
        assertThat(db.count("SELECT count(*) FROM repository_revision")).isEqualTo(3)
    }

    companion object {
        private val token = "test-worker-token-with-at-least-32-characters"
        private val messages = ConcurrentLinkedQueue<Pair<String, String>>()
        private val worker = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                messages += exchange.requestURI.path to exchange.requestBody.bufferedReader().readText()
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            start()
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun config(registry: DynamicPropertyRegistry) {
            registry.add("platform.judge.workers[0].id") { "one" }
            registry.add("platform.judge.workers[0].url") { "http://127.0.0.1:${worker.address.port}" }
            registry.add("platform.judge.workers[0].token") { token }
        }
    }
}
