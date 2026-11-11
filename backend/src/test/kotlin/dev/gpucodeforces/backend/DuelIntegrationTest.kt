package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.DuelStatus
import dev.gpucodeforces.backend.api.model.NotificationPage
import dev.gpucodeforces.backend.api.model.NotificationType
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.Problem
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.intellij.lang.annotations.Language
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import dev.gpucodeforces.backend.api.model.DuelRequest as DuelRequestApi

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DuelIntegrationTest {
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
    lateinit var jdbc: JdbcTemplate

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private data class Player(val user: User, val cookie: String)

    private fun player(): Player {
        val handle = "duel_${UUID.randomUUID().toString().take(8)}"
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(User::class.java).returnResult()
        return Player(result.responseBody!!, result.responseCookies.getFirst("session")!!.value)
    }

    private fun task(): Pair<String, String> {
        val template = catalog.currentVersion("vector_add")!!
        val id = "duel-${UUID.randomUUID()}"
        val topic = "Topic-${UUID.randomUUID()}"
        problems.save(Problem(id, source = "comparison"))
        val version = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = id, code = "D", title = "Общая задача", difficulty = "easy", tags = listOf(topic),
            authors = emptyList(), draft = false, statementMarkdown = "Условие", processors = template.processors, starters = template.starters,
            judgeSpec = template.judgeSpec, contentHash = UUID.randomUUID().toString(), judgeHash = template.judgeHash, revision = null,
        ))
        problems.save(problems.findById(id).orElseThrow().apply { currentVersionId = version.id })
        return id to topic
    }

    private fun create(player: Player, mode: String, topic: String, handle: String? = null): DuelRequestApi {
        val body = mutableMapOf<String, Any>("mode" to mode, "topics" to listOf(topic), "processor" to "gpu-cuda")
        if (handle != null) body["opponentHandle"] = handle
        return client.post().uri("/duels").contentType(MediaType.APPLICATION_JSON).cookie("session", player.cookie)
            .body(body).exchange().expectStatus().isCreated.expectBody(DuelRequestApi::class.java).returnResult().responseBody!!
    }

    private fun request(player: Player, id: String): DuelRequestApi =
        client.get().uri("/duels/$id").cookie("session", player.cookie).exchange().expectStatus().isOk
            .expectBody(DuelRequestApi::class.java).returnResult().responseBody!!

    private fun action(player: Player, id: String, action: String): DuelRequestApi =
        client.post().uri("/duels/$id/$action").cookie("session", player.cookie).exchange().expectStatus().isOk
            .expectBody(DuelRequestApi::class.java).returnResult().responseBody!!

    private fun notifications(player: Player): NotificationPage =
        client.get().uri("/notifications").cookie("session", player.cookie).exchange().expectStatus().isOk
            .expectBody(NotificationPage::class.java).returnResult().responseBody!!

    @Test
    fun `concurrent random searches create one immediate contest and register both players`() {
        val first = player()
        val second = player()
        val outsider = player()
        val (problemId, topic) = task()
        val before = contests.count()
        val firstCall = CompletableFuture.supplyAsync { create(first, "random", topic) }
        val secondCall = CompletableFuture.supplyAsync { create(second, "random", topic) }
        val firstId = firstCall.get(30, TimeUnit.SECONDS).id
        val secondId = secondCall.get(30, TimeUnit.SECONDS).id
        val firstRequest = request(first, firstId)
        val secondRequest = request(second, secondId)

        assertThat(firstRequest.status).isEqualTo(DuelStatus.MATCHED)
        assertThat(secondRequest.status).isEqualTo(DuelStatus.MATCHED)
        assertThat(firstRequest.contestId).isEqualTo(secondRequest.contestId)
        assertThat(contests.count()).isEqualTo(before + 1)
        for (person in listOf(first, second)) {
            val contest = client.get().uri("/contests/${firstRequest.contestId}").cookie("session", person.cookie)
                .exchange().expectStatus().isOk.expectBody(Contest::class.java).returnResult().responseBody!!
            assertThat(contest.registered).isTrue()
            assertThat(contest.problemIds).containsExactly(problemId)
            assertThat(contest.startAt.toInstant()).isBeforeOrEqualTo(Instant.now())
            val current = client.get().uri("/duels/current").cookie("session", person.cookie).exchange().expectStatus().isOk
                .expectBody(DuelRequestApi::class.java).returnResult().responseBody!!
            assertThat(current.contestId).isEqualTo(firstRequest.contestId)
            assertThat(notifications(person).items.single().type).isEqualTo(NotificationType.DUEL_STARTED)
        }
        client.get().uri("/duels/${firstRequest.id}").cookie("session", outsider.cookie).exchange().expectStatus().isForbidden
        client.post().uri("/duels").contentType(MediaType.APPLICATION_JSON).cookie("session", first.cookie)
            .body(mapOf("mode" to "random", "topics" to listOf(topic), "processor" to "gpu-cuda")).exchange().expectStatus().isBadRequest
    }

    @Test
    fun `acceptance is atomic idempotent and cancels the recipients own outgoing invitation`() {
        val inviter = player()
        val recipient = player()
        val third = player()
        val (_, topic) = task()
        val before = contests.count()
        val incoming = create(inviter, "invite", topic, recipient.user.handle.uppercase())
        client.get().uri("/duels/current").cookie("session", recipient.cookie).exchange().expectStatus().isNoContent
        val outgoing = create(recipient, "invite", topic, third.user.handle)
        val invitationNotification = notifications(recipient).items.single()
        assertThat(invitationNotification.duelStatus).isEqualTo(DuelStatus.INVITED)

        val firstAccept = CompletableFuture.supplyAsync { action(recipient, incoming.id, "accept") }
        val secondAccept = CompletableFuture.supplyAsync { action(recipient, incoming.id, "accept") }
        val accepted = firstAccept.get(30, TimeUnit.SECONDS)
        assertThat(secondAccept.get(30, TimeUnit.SECONDS).contestId).isEqualTo(accepted.contestId)
        assertThat(contests.count()).isEqualTo(before + 1)
        assertThat(request(recipient, outgoing.id).status).isEqualTo(DuelStatus.CANCELLED)
        assertThat(notifications(third).items.any { it.type == NotificationType.DUEL_CANCELLED }).isTrue()
        assertThat(notifications(inviter).items.count { it.type == NotificationType.DUEL_STARTED }).isEqualTo(1)
        val recipientNotifications = notifications(recipient)
        assertThat(recipientNotifications.items.count { it.type == NotificationType.DUEL_STARTED }).isEqualTo(1)
        assertThat(recipientNotifications.items.first { it.id == invitationNotification.id }.duelStatus).isEqualTo(DuelStatus.MATCHED)
        assertThat(recipientNotifications.items.first { it.id == invitationNotification.id }.contestId).isEqualTo(accepted.contestId)

        client.put().uri("/notifications/${invitationNotification.id}/read").cookie("session", third.cookie)
            .exchange().expectStatus().isNotFound
        client.put().uri("/notifications/${invitationNotification.id}/read").cookie("session", recipient.cookie)
            .exchange().expectStatus().isNoContent
        client.put().uri("/notifications/${invitationNotification.id}/read").cookie("session", recipient.cookie)
            .exchange().expectStatus().isNoContent
        assertThat(notifications(recipient).unreadCount).isEqualTo(recipientNotifications.unreadCount - 1)
        assertThat(notifications(third).items.map { it.id }).doesNotContain(invitationNotification.id)
        client.post().uri("/duels/${incoming.id}/accept").cookie("session", inviter.cookie).exchange().expectStatus().isForbidden
    }

    @Test
    fun `expiration cancellation and decline release the creator and protected routes require login`() {
        val creator = player()
        val opponent = player()
        val (_, topic) = task()
        client.post().uri("/duels").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("mode" to "random", "topics" to listOf(topic), "processor" to "gpu-cuda")).exchange().expectStatus().isUnauthorized
        client.get().uri("/notifications").exchange().expectStatus().isUnauthorized
        val queued = create(creator, "random", topic)
        @Language("SQL")
        val expire = "UPDATE gpucodeforces.duel_requests SET expires_at = now() - interval '1 second' WHERE id = ?"
        jdbc.update(expire, UUID.fromString(queued.id))
        assertThat(request(creator, queued.id).status).isEqualTo(DuelStatus.EXPIRED)
        client.get().uri("/duels/current").cookie("session", creator.cookie).exchange().expectStatus().isNoContent
        val replacement = create(creator, "random", topic)
        assertThat(action(creator, replacement.id, "cancel").status).isEqualTo(DuelStatus.CANCELLED)
        val invitation = create(creator, "invite", topic, opponent.user.handle)
        assertThat(action(opponent, invitation.id, "decline").status).isEqualTo(DuelStatus.DECLINED)
        client.get().uri("/duels/current").cookie("session", creator.cookie).exchange().expectStatus().isNoContent
        assertThat(notifications(creator).items.single().type).isEqualTo(NotificationType.DUEL_DECLINED)
        client.post().uri("/duels").contentType(MediaType.APPLICATION_JSON).cookie("session", creator.cookie)
            .body(mapOf("mode" to "invite", "topics" to listOf(topic), "processor" to "gpu-cuda", "opponentHandle" to creator.user.handle))
            .exchange().expectStatus().isBadRequest
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
