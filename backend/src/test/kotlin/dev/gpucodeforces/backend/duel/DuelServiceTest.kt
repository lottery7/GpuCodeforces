package dev.gpucodeforces.backend.duel

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.notification.Notification
import dev.gpucodeforces.backend.notification.NotificationRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

class DuelServiceTest {
    private val requests = mock(DuelRequestRepository::class.java)
    private val activeUsers = mock(ActiveDuelRepository::class.java)
    private val lock = mock(MatchmakingLockRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val catalog = mock(ProblemCatalog::class.java)
    private val contests = mock(ContestRepository::class.java)
    private val contestProblems = mock(ContestProblemRepository::class.java)
    private val registrations = mock(ContestRegistrationRepository::class.java)
    private val notifications = mock(NotificationRepository::class.java)
    private val service = DuelService(requests, activeUsers, lock, users, catalog, contests, contestProblems, registrations, notifications, GpuProperties())
    private val storedRequests = mutableMapOf<UUID, DuelRequest>()
    private val slots = mutableMapOf<UUID, ActiveDuel>()
    private val people = listOf(User("alice", "alice@example.com", "hash"), User("bob", "bob@example.com", "hash"), User("carol", "carol@example.com", "hash"))
    private val started = mutableListOf<Contest>()
    private val pinned = mutableListOf<ContestProblem>()
    private val messages = mutableListOf<Notification>()

    @BeforeEach
    fun setup() {
        given(lock.acquire()).willReturn(MatchmakingLock(1))
        given(requests.save(any(DuelRequest::class.java))).willAnswer { call ->
            (call.arguments[0] as DuelRequest).also { storedRequests[it.id] = it }
        }
        given(requests.findById(any(UUID::class.java))).willAnswer { Optional.ofNullable(storedRequests[it.arguments[0]]) }
        given(requests.findAllById(any())).willAnswer { call ->
            (call.arguments[0] as Iterable<*>).mapNotNull { storedRequests[it] }
        }
        given(requests.findByModeAndStatusOrderByCreatedAt(anyString(), anyString())).willAnswer { call ->
            storedRequests.values.filter { it.mode == call.arguments[0] && it.status == call.arguments[1] }.sortedBy { it.createdAt }
        }
        given(requests.findByStatusInAndExpiresAtLessThanEqual(anyValue<Collection<String>>(emptyList()), anyValue(Instant.EPOCH))).willAnswer { call ->
            val statuses = call.arguments[0] as Collection<*>
            val now = call.arguments[1] as Instant
            storedRequests.values.filter { it.status in statuses && !it.expiresAt.isAfter(now) }
        }
        given(activeUsers.findById(any(UUID::class.java))).willAnswer { Optional.ofNullable(slots[it.arguments[0]]) }
        given(activeUsers.findByRequestIdIsNotNull()).willAnswer { slots.values.filter { it.requestId != null } }
        given(activeUsers.save(any(ActiveDuel::class.java))).willAnswer { call ->
            (call.arguments[0] as ActiveDuel).also { slots[it.userId] = it }
        }
        given(users.findById(any(UUID::class.java))).willAnswer { call -> Optional.ofNullable(people.find { it.id == call.arguments[0] }) }
        given(users.findByHandleIgnoreCase(anyString())).willAnswer { call -> people.find { it.handle.equals(call.arguments[0] as String, true) } }
        given(catalog.currentVersions()).willReturn(listOf(problem("cuda", listOf("CUDA")), problem("memory", listOf("Memory"))))
        given(contests.save(any(Contest::class.java))).willAnswer { call -> (call.arguments[0] as Contest).also { started += it } }
        given(contestProblems.save(any(ContestProblem::class.java))).willAnswer { call -> (call.arguments[0] as ContestProblem).also { pinned += it } }
        given(notifications.save(any(Notification::class.java))).willAnswer { call -> (call.arguments[0] as Notification).also { messages += it } }
    }

    private fun problem(id: String, tags: List<String>, format: String = "standard", draft: Boolean = false) = ProblemVersion(
        id = UUID.randomUUID(), problemId = id, code = "1", title = id, difficulty = "easy", tags = tags,
        authors = emptyList(), draft = draft, statementMarkdown = "Условие", starterCode = "void solve() {}",
        judgeSpec = "{}", contentHash = "content", judgeHash = "judge", revision = null, format = format,
    )

    private inline fun <reified T : Any> anyValue(fallback: T): T {
        any(T::class.java)
        return fallback
    }

    @Test
    fun `compatible random requests start one immediate duel with a pinned common coding task`() {
        val first = service.create("random", listOf("CUDA"), null, people[0])
        assertThat(first.status).isEqualTo("queued")
        val second = service.create("random", listOf("cuda"), null, people[1])

        assertThat(first.status).isEqualTo("matched")
        assertThat(second.status).isEqualTo("matched")
        assertThat(second.contestId).isEqualTo(first.contestId)
        assertThat(started).hasSize(1)
        assertThat(pinned.single().id.problemId).isEqualTo("cuda")
        assertThat(pinned.single().problemVersionId).isNotNull()
        assertThat(Duration.between(started.single().startAt, started.single().endAt)).isEqualTo(Duration.ofMinutes(30))
        assertThat(messages.map { it.userId }).containsExactlyInAnyOrder(people[0].id, people[1].id)
    }

    @Test
    fun `disjoint topics keep both requests waiting and one user cannot queue twice`() {
        val first = service.create("random", listOf("CUDA"), null, people[0])
        val second = service.create("random", listOf("Memory"), null, people[1])

        assertThat(first.status).isEqualTo("queued")
        assertThat(second.status).isEqualTo("queued")
        assertThat(started).isEmpty()
        assertThatThrownBy { service.create("random", emptyList(), null, people[0]) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `an incoming invitation does not reserve its recipient`() {
        service.create("invite", listOf("CUDA"), "BOB", people[0])
        val ownSearch = service.create("random", listOf("CUDA"), null, people[1])

        assertThat(ownSearch.status).isEqualTo("queued")
        assertThat(slots[people[1].id]!!.requestId).isEqualTo(ownSearch.id)
        assertThat(messages.single().type).isEqualTo("duel_invitation")
        assertThat(messages.single().userId).isEqualTo(people[1].id)
    }

    @Test
    fun `accepting an invitation cancels the recipients own outgoing invitation and only starts once`() {
        val incoming = service.create("invite", listOf("CUDA"), "bob", people[0])
        val outgoing = service.create("invite", listOf("CUDA"), "carol", people[1])
        val accepted = service.accept(incoming.id.toString(), people[1])
        val repeated = service.accept(incoming.id.toString(), people[1])

        assertThat(outgoing.status).isEqualTo("cancelled")
        assertThat(accepted.status).isEqualTo("matched")
        assertThat(repeated.contestId).isEqualTo(accepted.contestId)
        assertThat(started).hasSize(1)
        assertThat(slots[people[1].id]!!.requestId).isEqualTo(incoming.id)
        assertThat(messages.filter { it.type == "duel_started" }).hasSize(2)
        assertThat(messages.any { it.userId == people[2].id && it.type == "duel_cancelled" }).isTrue()
    }

    @Test
    fun `only the recipient accepts or declines and only the creator cancels`() {
        val invite = service.create("invite", emptyList(), "bob", people[0])
        assertThatThrownBy { service.accept(invite.id.toString(), people[0]) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.decline(invite.id.toString(), people[2]) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.cancel(invite.id.toString(), people[1]) }.isInstanceOf(ForbiddenException::class.java)

        service.decline(invite.id.toString(), people[1])
        assertThat(invite.status).isEqualTo("declined")
        assertThat(slots[people[0].id]!!.requestId).isNull()
        assertThat(messages.last().type).isEqualTo("duel_declined")
    }

    @Test
    fun `expired searches release their user and cannot be accepted`() {
        val invite = service.create("invite", emptyList(), "bob", people[0])
        invite.expiresAt = Instant.now().minusSeconds(1)

        val replacement = service.create("random", listOf("CUDA"), null, people[0])
        assertThat(invite.status).isEqualTo("expired")
        assertThat(replacement.status).isEqualTo("queued")
        assertThatThrownBy { service.accept(invite.id.toString(), people[1]) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `drafts and guessing tasks do not enter matchmaking`() {
        given(catalog.currentVersions()).willReturn(listOf(
            problem("draft", listOf("Private"), draft = true), problem("guess", listOf("Guess"), format = "guess"),
            problem("coding", listOf("CUDA")),
        ))
        assertThat(service.topics()).containsExactly("CUDA")
        assertThatThrownBy { service.create("random", listOf("Guess"), null, people[0]) }.isInstanceOf(BadRequestException::class.java)
        assertThat(started).isEmpty()
    }
}
