package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.DuelPage
import dev.gpucodeforces.backend.api.model.DuelStatus
import dev.gpucodeforces.backend.api.model.DuelProblemPage
import dev.gpucodeforces.backend.api.model.NotificationPage
import dev.gpucodeforces.backend.api.model.NotificationType
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.Problem
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import dev.gpucodeforces.backend.api.model.DuelRequest as DuelRequestApi

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DuelIntegrationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var catalog: ProblemCatalog
    @Autowired lateinit var problems: ProblemRepository
    @Autowired lateinit var versions: ProblemVersionRepository
    @Autowired lateinit var contests: ContestRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var jsonMapper: JsonMapper
    @MockitoBean lateinit var references: ReferenceMeasurements

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }
    private val http by lazy { HttpClient.newHttpClient() }
    private data class Player(val user: User, val cookie: String)

    @BeforeEach
    fun setup() {
        @Language("SQL")
        val cleanup = "UPDATE gpucodeforces.duel_requests SET status = 'cancelled' WHERE status IN ('queued', 'invited')"
        jdbc.update(cleanup)
        given(references.environmentKey(GPU)).willReturn(ENVIRONMENT)
        given(references.environmentKey(RTL)).willReturn(RTL_ENVIRONMENT)
    }

    private fun player(): Player {
        val handle = "duel_${UUID.randomUUID().toString().take(8)}"
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(User::class.java).returnResult()
        return Player(result.responseBody!!, result.responseCookies.getFirst("session")!!.value)
    }

    private fun task(difficulty: String, problemId: String = "duel-${UUID.randomUUID()}", draft: Boolean = false, format: String = "standard", code: String = "D", title: String = "Задача $difficulty", processor: String = GPU): ProblemVersion {
        val template = catalog.currentVersion("vector_add")!!
        val spec = catalog.spec(template)
        if (!problems.existsById(problemId)) problems.save(Problem(problemId, source = "comparison"))
        val version = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = problemId, code = code, title = title, difficulty = difficulty, tags = emptyList(),
            authors = emptyList(), draft = draft, statementMarkdown = "Условие $difficulty", processors = listOf(processor), starters = template.starters.filterKeys { it == processor },
            judgeSpec = jsonMapper.writeValueAsString(spec.copy(processors = spec.declaredProcessors().filter { it.id == processor })),
            contentHash = UUID.randomUUID().toString(), judgeHash = template.judgeHash, revision = null, format = format,
        ))
        problems.save(problems.findById(problemId).orElseThrow().apply { currentVersionId = version.id })
        return version
    }

    private fun create(player: Player, mode: String, handle: String? = null, problemId: String? = null, processor: String = GPU): DuelRequestApi {
        val body = mutableMapOf<String, Any>("mode" to mode, "processor" to processor)
        if (handle != null) body["opponentHandle"] = handle
        if (problemId != null) body["problemId"] = problemId
        return client.post().uri("/duels").contentType(MediaType.APPLICATION_JSON).cookie("session", player.cookie)
            .body(body).exchange().expectStatus().isCreated.expectBody(DuelRequestApi::class.java).returnResult().responseBody!!
    }

    private fun request(player: Player, id: String): DuelRequestApi =
        client.get().uri("/duels/$id").cookie("session", player.cookie).exchange().expectStatus().isOk
            .expectBody(DuelRequestApi::class.java).returnResult().responseBody!!

    private fun action(player: Player, id: String, action: String): DuelRequestApi =
        client.post().uri("/duels/$id/$action").cookie("session", player.cookie).exchange().expectStatus().isOk
            .expectBody(DuelRequestApi::class.java).returnResult().responseBody!!

    private fun list(player: Player, phase: String = "active", offset: Int = 0, limit: Int = 20, processor: String? = null): DuelPage =
        client.get().uri("/duels?phase=$phase&offset=$offset&limit=$limit${processor?.let { "&processor=$it" }.orEmpty()}").cookie("session", player.cookie)
            .exchange().expectStatus().isOk.expectBody(DuelPage::class.java).returnResult().responseBody!!

    private fun room(player: Player, id: String): Contest =
        client.get().uri("/contests/$id").cookie("session", player.cookie).exchange().expectStatus().isOk
            .expectBody(Contest::class.java).returnResult().responseBody!!

    private fun notifications(player: Player): NotificationPage =
        client.get().uri("/notifications").cookie("session", player.cookie).exchange().expectStatus().isOk
            .expectBody(NotificationPage::class.java).returnResult().responseBody!!

    private fun surrender(player: Player, id: String): Int = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port/api/contests/$id/surrender"))
            .header("Cookie", "session=${player.cookie}").timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    @Test
    @AllowFullScan("contests", "select count\\(\\*\\) from gpucodeforces\\.contests", "The test counts every contest to verify concurrent matches")
    fun `concurrent queues are idempotent and the same players can have multiple live rooms`() {
        val first = player()
        val second = player()
        val outsider = player()
        val before = contests.count()
        val firstCall = CompletableFuture.supplyAsync { create(first, "random") }
        val duplicateCall = CompletableFuture.supplyAsync { create(first, "random") }
        val queued = firstCall.get(30, TimeUnit.SECONDS)
        assertThat(duplicateCall.get(30, TimeUnit.SECONDS).id).isEqualTo(queued.id)
        val matched = create(second, "random")
        assertThat(request(first, queued.id).contestId).isEqualTo(matched.contestId)
        assertThat(room(first, matched.contestId!!).registered).isTrue()
        assertThat(room(second, matched.contestId!!).registered).isTrue()
        assertThat(contests.findById(UUID.fromString(matched.contestId)).orElseThrow().benchmarkEnvironmentKey).isEqualTo(ENVIRONMENT)
        client.get().uri("/duels/current").cookie("session", first.cookie).exchange().expectStatus().isNoContent

        val nextFirst = CompletableFuture.supplyAsync { create(first, "random") }
        val nextSecond = CompletableFuture.supplyAsync { create(second, "random") }
        val firstId = nextFirst.get(30, TimeUnit.SECONDS).id
        val secondId = nextSecond.get(30, TimeUnit.SECONDS).id
        assertThat(request(first, firstId).contestId).isEqualTo(request(second, secondId).contestId)
        assertThat(contests.count()).isEqualTo(before + 2)
        for (person in listOf(first, second)) {
            val own = list(person)
            assertThat(own.total).isEqualTo(2)
            assertThat(own.items.map { it.contestId }.toSet()).hasSize(2)
            assertThat(own.items.all { it.creator.id == person.user.id }).isTrue()
            assertThat(list(person, offset = 1, limit = 1).items).hasSize(1)
        }
        val ordinary = client.get().uri("/contests").cookie("session", first.cookie).exchange().expectStatus().isOk
            .expectBody(Array<Contest>::class.java).returnResult().responseBody!!
        assertThat(ordinary.none { it.format == Contest.FormatEnum.DUEL }).isTrue()
        assertThat(list(outsider).total).isZero()
        client.get().uri("/duels/${queued.id}").cookie("session", outsider.cookie).exchange().expectStatus().isForbidden
    }

    @Test
    @AllowFullScan("contests", "select count\\(\\*\\) from gpucodeforces\\.contests", "The test counts every contest to verify idempotent acceptance")
    fun `selected invitations pin their published version and use difficulty durations without cancelling other invitations`() {
        val inviter = player()
        val recipient = player()
        val third = player()
        val selected = task("medium")
        val eligible = client.get().uri("/duels/problems?q=${selected.problemId}").cookie("session", inviter.cookie).exchange().expectStatus().isOk
            .expectBody(DuelProblemPage::class.java).returnResult().responseBody!!
        assertThat(eligible.items.map { it.id }).containsExactly(selected.problemId)
        val incoming = create(inviter, "invite", recipient.user.handle.uppercase(), selected.problemId)
        assertThat(create(inviter, "invite", recipient.user.handle, selected.problemId).id).isEqualTo(incoming.id)
        assertThat(notifications(recipient).total).isEqualTo(1)
        val outgoing = create(recipient, "invite", third.user.handle)
        task("hard", selected.problemId)

        val firstAccept = CompletableFuture.supplyAsync { action(recipient, incoming.id, "accept") }
        val secondAccept = CompletableFuture.supplyAsync { action(recipient, incoming.id, "accept") }
        val accepted = firstAccept.get(30, TimeUnit.SECONDS)
        assertThat(secondAccept.get(30, TimeUnit.SECONDS).contestId).isEqualTo(accepted.contestId)
        assertThat(accepted.difficulty).isEqualTo(DuelRequestApi.DifficultyEnum.MEDIUM)
        assertThat(accepted.problemTitle).isEqualTo(selected.title)
        assertThat(Duration.between(room(inviter, accepted.contestId!!).startAt, room(inviter, accepted.contestId!!).endAt)).isEqualTo(Duration.ofHours(2))
        assertThat(request(recipient, outgoing.id).status).isEqualTo(DuelStatus.INVITED)

        for ((difficulty, hours) in listOf("easy" to 1L, "hard" to 3L)) {
            val problem = task(difficulty)
            val invitation = create(inviter, "invite", recipient.user.handle, problem.problemId)
            val started = action(recipient, invitation.id, "accept")
            val contest = room(recipient, started.contestId!!)
            assertThat(Duration.between(contest.startAt, contest.endAt)).isEqualTo(Duration.ofHours(hours))
        }
        assertThat(list(inviter).total).isEqualTo(3)
        assertThat(list(recipient).total).isEqualTo(4)
        val note = notifications(recipient).items.first { it.type == NotificationType.DUEL_INVITATION }
        client.put().uri("/notifications/${note.id}/read").cookie("session", third.cookie).exchange().expectStatus().isNotFound
        client.put().uri("/notifications/${note.id}/read").cookie("session", recipient.cookie).exchange().expectStatus().isNoContent
    }

    @Test
    fun `problem chooser supports search difficulty and pagination without private or guessing tasks`() {
        val viewer = player()
        val token = UUID.randomUUID().toString().take(8)
        val first = task("easy", "duel-$token-first", code = "${token}A", title = "Kernel $token Alpha")
        val second = task("easy", "duel-$token-second", code = "${token}B", title = "Kernel $token Beta")
        task("medium", "duel-$token-medium", code = "${token}C")
        task("easy", "duel-$token-draft", draft = true, code = "${token}D")
        task("easy", "duel-$token-guess", format = "guess", code = "${token}E")
        val page = client.get().uri("/duels/problems?q=$token&difficulty=easy&offset=1&limit=1").cookie("session", viewer.cookie)
            .exchange().expectStatus().isOk.expectBody(DuelProblemPage::class.java).returnResult().responseBody!!
        assertThat(page.total).isEqualTo(2)
        assertThat(page.items.map { it.id }).containsExactly(second.problemId)
        assertThat(page.items.single().code).isEqualTo(second.code)
        val byTitle = client.get().uri("/duels/problems?q={query}", "  kErNeL $token AlPhA  ").cookie("session", viewer.cookie)
            .exchange().expectStatus().isOk.expectBody(DuelProblemPage::class.java).returnResult().responseBody!!
        assertThat(byTitle.items.map { it.id }).containsExactly(first.problemId)
        val byCode = client.get().uri("/duels/problems?q=${second.code.uppercase()}").cookie("session", viewer.cookie)
            .exchange().expectStatus().isOk.expectBody(DuelProblemPage::class.java).returnResult().responseBody!!
        assertThat(byCode.items.map { it.id }).containsExactly(second.problemId)
        client.get().uri("/duels/problems?difficulty=unknown").cookie("session", viewer.cookie).exchange().expectStatus().isBadRequest
        client.get().uri("/duels/problems?offset=-1").cookie("session", viewer.cookie).exchange().expectStatus().isBadRequest
        client.get().uri("/duels/problems?limit=101").cookie("session", viewer.cookie).exchange().expectStatus().isBadRequest
        client.get().uri("/duels/problems").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `surrender is private and concurrent surrender cannot overwrite the first participant`() {
        val first = player()
        val second = player()
        val outsider = player()
        val queued = create(first, "random")
        val matched = create(second, "random")
        val id = matched.contestId!!
        assertThat(surrender(outsider, id)).isEqualTo(403)
        val firstCall = CompletableFuture.supplyAsync { surrender(first, id) }
        val secondCall = CompletableFuture.supplyAsync { surrender(second, id) }
        assertThat(listOf(firstCall.get(30, TimeUnit.SECONDS), secondCall.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(204, 400)
        val ended = room(first, id)
        val surrendered = if (ended.surrenderedUserId == first.user.id) first else second
        val opponent = if (surrendered === first) second else first
        assertThat(surrender(surrendered, id)).isEqualTo(204)
        assertThat(surrender(opponent, id)).isEqualTo(400)
        assertThat(room(first, id).surrenderedUserId).isEqualTo(surrendered.user.id)
        assertThat(request(first, queued.id).expiresAt).isEqualTo(ended.endAt)
        assertThat(request(second, matched.id).expiresAt).isEqualTo(ended.endAt)
        assertThat(list(first).total).isZero()
        assertThat(list(first, "finished").total).isEqualTo(1)
        assertThat(notifications(opponent).items.count { it.type == NotificationType.DUEL_SURRENDERED }).isEqualTo(1)
        client.post().uri("/contests/$id/virtual").cookie("session", first.cookie).exchange().expectStatus().isBadRequest
        client.get().uri("/problems?context=upsolve:$id").cookie("session", first.cookie).exchange().expectStatus().isBadRequest
        create(first, "random")
        assertThat(create(second, "random").status).isEqualTo(DuelStatus.MATCHED)
    }

    @Test
    fun `queues wait for hardware and invitation acceptance is retriable without it`() {
        val first = player()
        val second = player()
        given(references.environmentKey(GPU)).willReturn(null)
        val queued = create(first, "random")
        val peer = create(second, "random")
        assertThat(queued.status).isEqualTo(DuelStatus.QUEUED)
        assertThat(peer.problemId).isNull()
        given(references.environmentKey(GPU)).willReturn(ENVIRONMENT)
        assertThat(request(first, queued.id).status).isEqualTo(DuelStatus.MATCHED)
        val incoming = create(first, "invite", second.user.handle)
        given(references.environmentKey(GPU)).willReturn(null)
        client.post().uri("/duels/${incoming.id}/accept").cookie("session", second.cookie).exchange().expectStatus().isEqualTo(503)
        assertThat(request(first, incoming.id).status).isEqualTo(DuelStatus.INVITED)
        action(first, incoming.id, "cancel")
        client.post().uri("/duels").contentType(MediaType.APPLICATION_JSON).cookie("session", first.cookie)
            .body(mapOf("mode" to "random", "problemId" to "vector_add", "processor" to GPU)).exchange().expectStatus().isBadRequest
        client.get().uri("/duels").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `processor scopes matchmaking rooms and the searchable published problem pool`() {
        val first = player()
        val second = player()
        val third = player()
        val token = UUID.randomUUID().toString().take(8)
        val gpuTask = task("easy", "duel-$token-gpu")
        val rtlTask = task("medium", "duel-$token-rtl", processor = RTL)
        given(references.environmentKey(GPU)).willReturn(null)
        val gpu = create(first, "random")
        val rtl = create(second, "random", processor = RTL)
        assertThat(request(first, gpu.id).status).isEqualTo(DuelStatus.QUEUED)
        assertThat(request(second, rtl.id).status).isEqualTo(DuelStatus.QUEUED)
        val matched = create(third, "random", processor = RTL)
        assertThat(matched.processor).isEqualTo(RTL)
        assertThat(matched.contestId).isEqualTo(request(second, rtl.id).contestId)
        assertThat(room(second, matched.contestId!!).processor).isEqualTo(RTL)
        assertThat(contests.findById(UUID.fromString(matched.contestId)).orElseThrow().benchmarkEnvironmentKey).isEqualTo(RTL_ENVIRONMENT)
        assertThat(list(first, processor = GPU).total).isEqualTo(1)
        assertThat(list(second, processor = GPU).total).isZero()
        assertThat(list(second, processor = RTL).total).isEqualTo(1)
        val pool = client.get().uri("/duels/problems?processor=$RTL&q=$token").cookie("session", first.cookie)
            .exchange().expectStatus().isOk.expectBody(DuelProblemPage::class.java).returnResult().responseBody!!
        assertThat(pool.items.map { it.id }).containsExactly(rtlTask.problemId)
        client.post().uri("/duels").contentType(MediaType.APPLICATION_JSON).cookie("session", first.cookie)
            .body(mapOf("mode" to "invite", "processor" to RTL, "opponentHandle" to second.user.handle, "problemId" to gpuTask.problemId))
            .exchange().expectStatus().isBadRequest
        client.get().uri("/duels/problems?processor=unknown").cookie("session", first.cookie).exchange().expectStatus().isBadRequest
        client.get().uri("/duels?processor=unknown").cookie("session", first.cookie).exchange().expectStatus().isBadRequest
    }

    companion object {
        private const val GPU = "gpu-cuda"
        private const val RTL = "rtl"
        private val ENVIRONMENT = "e".repeat(64)
        private val RTL_ENVIRONMENT = "f".repeat(64)
        private val problemRoot = TestProblems.withRtl()

        @JvmStatic
        @DynamicPropertySource
        fun problemDirectory(registry: DynamicPropertyRegistry) {
            registry.add("platform.problems.directory") { problemRoot.toString() }
        }

        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
