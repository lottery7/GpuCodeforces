package dev.gpucodeforces.backend.duel

import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.notification.Notification
import dev.gpucodeforces.backend.notification.NotificationRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.processor.ProcessorProperties
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
    private val lock = mock(MatchmakingLockRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val catalog = mock(ProblemCatalog::class.java)
    private val contests = mock(ContestRepository::class.java)
    private val contestProblems = mock(ContestProblemRepository::class.java)
    private val registrations = mock(ContestRegistrationRepository::class.java)
    private val notifications = mock(NotificationRepository::class.java)
    private val references = mock(ReferenceMeasurements::class.java)
    private val service = DuelService(requests, lock, users, catalog, contests, contestProblems, registrations, notifications, ProcessorCatalog(ProcessorProperties(enabled = listOf(GPU, RTL))), references)
    private val storedRequests = mutableMapOf<UUID, DuelRequest>()
    private val people = listOf(User("alice", "alice@example.com", "hash"), User("bob", "bob@example.com", "hash"), User("carol", "carol@example.com", "hash"))
    private val started = mutableListOf<Contest>()
    private val pinned = mutableListOf<ContestProblem>()
    private val messages = mutableListOf<Notification>()
    private val versions = mutableMapOf<UUID, ProblemVersion>()
    private var available = listOf(problem("coding"))

    @BeforeEach
    fun setup() {
        available.forEach { versions[it.id] = it }
        given(references.environmentKey(GPU)).willReturn("environment")
        given(references.environmentKey(RTL)).willReturn("rtl-environment")
        given(lock.acquire()).willReturn(MatchmakingLock(1))
        given(requests.save(any(DuelRequest::class.java))).willAnswer { call ->
            (call.arguments[0] as DuelRequest).also { storedRequests[it.id] = it }
        }
        given(requests.findById(any(UUID::class.java))).willAnswer { Optional.ofNullable(storedRequests[it.arguments[0]]) }
        given(requests.findByModeAndStatusOrderByCreatedAt(anyString(), anyString())).willAnswer { call ->
            storedRequests.values.filter { it.mode == call.arguments[0] && it.status == call.arguments[1] }.sortedBy { it.createdAt }
        }
        given(requests.findByStatusInAndExpiresAtLessThanEqual(anyValue<Collection<String>>(emptyList()), anyValue(Instant.EPOCH))).willAnswer { call ->
            val statuses = call.arguments[0] as Collection<*>
            val now = call.arguments[1] as Instant
            storedRequests.values.filter { it.status in statuses && !it.expiresAt.isAfter(now) }
        }
        given(requests.findByCreatorIdAndModeAndStatus(anyValue(UUID(0, 0)), anyString(), anyString())).willAnswer { call ->
            storedRequests.values.singleOrNull { it.creatorId == call.arguments[0] && it.mode == call.arguments[1] && it.status == call.arguments[2] }
        }
        given(requests.findByCreatorIdAndStatusInOrderByCreatedAtDesc(anyValue(UUID(0, 0)), anyValue<Collection<String>>(emptyList()))).willAnswer { call ->
            val statuses = call.arguments[1] as Collection<*>
            storedRequests.values.filter { it.creatorId == call.arguments[0] && it.status in statuses }.sortedByDescending { it.createdAt }
        }
        given(requests.findByContestId(anyValue(UUID(0, 0)))).willAnswer { call -> storedRequests.values.filter { it.contestId == call.arguments[0] } }
        given(requests.visibleTo(anyValue(UUID(0, 0)))).willAnswer { call ->
            storedRequests.values.filter { it.creatorId == call.arguments[0] || it.opponentId == call.arguments[0] }.sortedByDescending { it.createdAt }
        }
        given(users.findById(any(UUID::class.java))).willAnswer { call -> Optional.ofNullable(people.find { it.id == call.arguments[0] }) }
        given(users.findByHandleIgnoreCase(anyString())).willAnswer { call -> people.find { it.handle.equals(call.arguments[0] as String, true) } }
        given(catalog.currentVersions()).willAnswer { available }
        given(catalog.currentVersion(anyString())).willAnswer { call -> available.firstOrNull { it.problemId == call.arguments[0] } }
        given(catalog.versions(anyValue<Collection<UUID>>(emptyList()))).willAnswer { call ->
            (call.arguments[0] as Collection<*>).mapNotNull { versions[it] }.associateBy { it.id }
        }
        given(contests.save(any(Contest::class.java))).willAnswer { call -> (call.arguments[0] as Contest).also { started += it } }
        given(contests.findForUpdate(anyValue(UUID(0, 0)))).willAnswer { call -> started.find { it.id == call.arguments[0] } }
        given(contestProblems.save(any(ContestProblem::class.java))).willAnswer { call -> (call.arguments[0] as ContestProblem).also { pinned += it } }
        given(notifications.save(any(Notification::class.java))).willAnswer { call -> (call.arguments[0] as Notification).also { messages += it } }
    }

    private fun problem(id: String, difficulty: String = "easy", format: String = "standard", draft: Boolean = false, code: String = "1", title: String = id, processors: List<String> = listOf(GPU)) = ProblemVersion(
        id = UUID.randomUUID(), problemId = id, code = code, title = title, difficulty = difficulty, tags = emptyList(),
        authors = emptyList(), draft = draft, statementMarkdown = "Условие", processors = processors, starters = emptyMap(),
        judgeSpec = "{}", contentHash = "content", judgeHash = "judge", revision = null, format = format,
    )

    private inline fun <reified T : Any> anyValue(fallback: T): T {
        any(T::class.java)
        return fallback
    }

    @Test
    fun `random queues are idempotent and the same pair can start another room while playing`() {
        val first = service.create("random", null, null, GPU, people[0])
        assertThat(service.create("random", null, null, GPU, people[0]).id).isEqualTo(first.id)
        assertThat(started).isEmpty()
        val second = service.create("random", null, null, GPU, people[1])
        assertThat(first.contestId).isEqualTo(second.contestId)
        assertThat(started.single().benchmarkEnvironmentKey).isEqualTo("environment")
        assertThat(started.single().processor).isEqualTo(GPU)
        assertThat(service.current(people[0])).isNull()

        service.create("random", null, null, GPU, people[0])
        service.create("random", null, null, GPU, people[1])
        assertThat(started).hasSize(2)
        assertThat(started.last().processor).isEqualTo(GPU)
        assertThat(service.list(null, "active", 0, 20, people[0]).total).isEqualTo(2)
        assertThat(service.list(null, "active", 0, 20, people[1]).total).isEqualTo(2)
        assertThat(service.list(null, "all", 0, 20, people[2]).items).isEmpty()
    }

    @Test
    fun `random matching waits for a valid environment and resumes without another create`() {
        given(references.environmentKey(GPU)).willReturn(null)
        val first = service.create("random", null, null, GPU, people[0])
        val second = service.create("random", null, null, GPU, people[1])
        assertThat(first.status).isEqualTo("queued")
        assertThat(second.problemVersionId).isNull()
        assertThat(started).isEmpty()

        given(references.environmentKey(GPU)).willReturn("environment")
        service.expire()
        assertThat(first.status).isEqualTo("matched")
        assertThat(second.status).isEqualTo("matched")
        assertThat(pinned).hasSize(1)
    }

    @Test
    fun `selected invitation keeps its version and difficulty after the public problem changes`() {
        val selected = problem("selected", "medium")
        available = listOf(selected)
        versions[selected.id] = selected
        val invitation = service.create("invite", "BOB", selected.problemId, GPU, people[0])
        val replacement = problem("selected", "hard")
        available = listOf(replacement)
        versions[replacement.id] = replacement

        service.accept(invitation.id.toString(), people[1])
        assertThat(pinned.single().problemVersionId).isEqualTo(selected.id)
        assertThat(Duration.between(started.single().startAt, started.single().endAt)).isEqualTo(Duration.ofHours(2))
        assertThat(invitation.problemVersionId).isEqualTo(selected.id)
    }

    @Test
    fun `difficulty defines duration and accepting another invitation leaves other requests intact`() {
        available = listOf(problem("easy"), problem("medium", "medium"), problem("hard", "hard"))
        available.forEach { versions[it.id] = it }
        val queued = service.create("random", null, null, GPU, people[1])
        val outgoing = service.create("invite", "carol", null, GPU, people[1])
        available.forEach { selected ->
            val invitation = service.create("invite", "bob", selected.problemId, GPU, people[0])
            val accepted = service.accept(invitation.id.toString(), people[1])
            assertThat(service.accept(invitation.id.toString(), people[1]).contestId).isEqualTo(accepted.contestId)
        }
        assertThat(started.map { Duration.between(it.startAt, it.endAt).toHours() }).containsExactly(1L, 2L, 3L)
        assertThat(queued.status).isEqualTo("queued")
        assertThat(outgoing.status).isEqualTo("invited")
        assertThat(messages.count { it.type == "duel_started" }).isEqualTo(6)
    }

    @Test
    fun `only published coding tasks can be chosen and random requests cannot select a task`() {
        available = listOf(problem("draft", draft = true), problem("guess", format = "guess"), problem("coding"))
        assertThat(service.problems(null, null, null, 0, 20).items.map { it.problemId }).containsExactly("coding")
        assertThatThrownBy { service.create("invite", "bob", "draft", GPU, people[0]) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.create("invite", "bob", "guess", GPU, people[0]) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.create("random", null, "coding", GPU, people[0]) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.create("invite", "alice", null, GPU, people[0]) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `problem search filters before pagination and searches title id and code`() {
        available = listOf(
            problem("kernel-second", code = "B42", title = "Kernel B"),
            problem("kernel-draft", draft = true, code = "A", title = "Kernel draft"),
            problem("kernel-hard", "hard", code = "A", title = "Kernel hard"),
            problem("kernel-first", code = "A", title = "Kernel A"),
            problem("kernel-guess", format = "guess", code = "A", title = "Kernel guess"),
        )
        val page = service.problems(null, "  kErNeL  ", "easy", 1, 1)
        assertThat(page.total).isEqualTo(2)
        assertThat(page.items.map { it.problemId }).containsExactly("kernel-second")
        assertThat(service.problems(null, "kernel-FIRST", null, 0, 20).items.map { it.problemId }).containsExactly("kernel-first")
        assertThat(service.problems(null, "Kernel B", null, 0, 20).items.map { it.problemId }).containsExactly("kernel-second")
        assertThat(service.problems(null, "b42", null, 0, 20).items.map { it.problemId }).containsExactly("kernel-second")
        assertThat(service.problems(null, "  ", null, 20, 20).items).isEmpty()
        assertThat(service.problems(null, null, null, 0, 20).items.map { it.problemId }).containsExactly("kernel-first", "kernel-hard", "kernel-second")
        assertThatThrownBy { service.problems(null, null, "unknown", 0, 20) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.problems(null, null, null, -1, 20) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.problems(null, null, null, 0, 101) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `surrender ends only this room and the first participant to surrender cannot be overwritten`() {
        val first = service.create("random", null, null, GPU, people[0])
        val second = service.create("random", null, null, GPU, people[1])
        val contest = started.single()
        assertThatThrownBy { service.surrender(contest.id.toString(), people[2]) }.isInstanceOf(ForbiddenException::class.java)
        service.surrender(contest.id.toString(), people[0])
        service.surrender(contest.id.toString(), people[0])
        assertThatThrownBy { service.surrender(contest.id.toString(), people[1]) }.isInstanceOf(BadRequestException::class.java)
        assertThat(contest.surrenderedUserId).isEqualTo(people[0].id)
        assertThat(contest.endAt).isBeforeOrEqualTo(Instant.now())
        assertThat(first.expiresAt).isEqualTo(contest.endAt)
        assertThat(second.expiresAt).isEqualTo(contest.endAt)
        assertThat(messages.count { it.type == "duel_surrendered" }).isEqualTo(1)
        assertThat(service.list(null, "finished", 0, 20, people[0]).total).isEqualTo(1)
    }

    @Test
    fun `expired invitations cannot be accepted and unavailable runtime does not mutate an invitation`() {
        val invitation = service.create("invite", "bob", null, GPU, people[0])
        given(references.environmentKey(GPU)).willReturn(null)
        assertThatThrownBy { service.accept(invitation.id.toString(), people[1]) }.isInstanceOf(ServiceUnavailableException::class.java)
        assertThat(invitation.status).isEqualTo("invited")
        invitation.expiresAt = Instant.now().minusSeconds(1)
        assertThatThrownBy { service.accept(invitation.id.toString(), people[1]) }.isInstanceOf(BadRequestException::class.java)
        assertThat(invitation.status).isEqualTo("expired")
    }

    @Test
    fun `requests expiring while checking the runtime never start a room`() {
        given(references.environmentKey(GPU)).willReturn(null)
        val first = service.create("random", null, null, GPU, people[0])
        val second = service.create("random", null, null, GPU, people[1])
        given(references.environmentKey(GPU)).willAnswer {
            first.expiresAt = Instant.now().minusSeconds(1)
            second.expiresAt = Instant.now().minusSeconds(1)
            "environment"
        }
        service.expire()
        assertThat(started).isEmpty()
        assertThat(first.status).isEqualTo("expired")
        assertThat(second.status).isEqualTo("expired")

        val invitation = service.create("invite", "bob", null, GPU, people[0])
        given(references.environmentKey(GPU)).willAnswer {
            invitation.expiresAt = Instant.now().minusSeconds(1)
            "environment"
        }
        assertThatThrownBy { service.accept(invitation.id.toString(), people[1]) }.isInstanceOf(BadRequestException::class.java)
        assertThat(started).isEmpty()
    }

    @Test
    fun `random matching and problem lists stay on the requested processor`() {
        val shared = problem("shared", processors = listOf(GPU, RTL))
        available = listOf(shared, problem("gpu-only"), problem("disabled", processors = listOf("riscv-rvv")))
        given(references.environmentKey(GPU)).willReturn(null)
        val gpu = service.create("random", null, null, GPU, people[0])
        val rtl = service.create("random", null, null, RTL, people[1])
        assertThat(gpu.status).isEqualTo("queued")
        assertThat(rtl.status).isEqualTo("queued")
        assertThatThrownBy { service.create("random", null, null, RTL, people[0]) }.isInstanceOf(BadRequestException::class.java)

        val matched = service.create("random", null, null, RTL, people[2])
        assertThat(matched.contestId).isEqualTo(rtl.contestId)
        assertThat(gpu.status).isEqualTo("queued")
        assertThat(started.single().processor).isEqualTo(RTL)
        assertThat(started.single().benchmarkEnvironmentKey).isEqualTo("rtl-environment")
        assertThat(pinned.single().id.problemId).isEqualTo(shared.problemId)
        assertThat(service.list(GPU, "active", 0, 20, people[0]).items).containsExactly(gpu)
        assertThat(service.list(GPU, "active", 0, 20, people[1]).items).isEmpty()
        assertThat(service.list(RTL, "active", 0, 20, people[1]).items).containsExactly(rtl)
        assertThat(service.problems(RTL, null, null, 0, 20).items).containsExactly(shared)
        assertThat(service.problems(null, null, null, 0, 20).items.map { it.problemId }).doesNotContain("disabled")
        assertThatThrownBy { service.problems("riscv-rvv", null, null, 0, 20) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.list("riscv-rvv", "all", 0, 20, people[0]) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.create("invite", "bob", "gpu-only", RTL, people[0]) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `invitations to the same problem on different processors are separate and pin their environment`() {
        val shared = problem("shared", processors = listOf(GPU, RTL))
        available = listOf(shared)
        versions[shared.id] = shared
        val gpu = service.create("invite", "bob", shared.problemId, GPU, people[0])
        val rtl = service.create("invite", "bob", shared.problemId, RTL, people[0])
        assertThat(rtl.id).isNotEqualTo(gpu.id)
        assertThat(service.create("invite", "bob", shared.problemId, RTL, people[0]).id).isEqualTo(rtl.id)
        service.accept(rtl.id.toString(), people[1])
        assertThat(gpu.status).isEqualTo("invited")
        assertThat(started.single().processor).isEqualTo(RTL)
        assertThat(started.single().benchmarkEnvironmentKey).isEqualTo("rtl-environment")
        assertThat(pinned.single().problemVersionId).isEqualTo(shared.id)
    }

    private companion object {
        const val GPU = "gpu-cuda"
        const val RTL = "rtl"
    }
}
