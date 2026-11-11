package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.UnauthorizedException
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.processor.ProcessorProperties
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID
import dev.gpucodeforces.backend.api.model.ContestInput as ContestInputApi

class ContestServiceTest {
    private val contestRepository = mock(ContestRepository::class.java)
    private val contestProblemRepository = mock(ContestProblemRepository::class.java)
    private val contestRegistrationRepository = mock(ContestRegistrationRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val problemCatalog = mock(ProblemCatalog::class.java)
    private val service = ContestService(
        contestRepository,
        contestProblemRepository,
        contestRegistrationRepository,
        userRepository,
        problemCatalog,
        ProcessorCatalog(ProcessorProperties(enabled = listOf("gpu-cuda", "rtl"), models = mapOf("gpu-cuda" to "NVIDIA GeForce RTX 3060"))),
        mock(SubmissionStats::class.java),
    )

    private fun user(handle: String = "author") = User(handle, "$handle@example.com", "hash")

    private fun contestFixture(
        startAt: Instant = Instant.now().plus(1, ChronoUnit.DAYS),
        endAt: Instant = startAt.plus(1, ChronoUnit.HOURS),
        authors: MutableSet<User> = mutableSetOf(),
        processor: String = "gpu-cuda",
    ) = Contest(id = UUID.randomUUID(), title = "Контест", startAt = startAt, endAt = endAt, processor = processor, authors = authors)

    private fun input(
        authorIds: Set<String>,
        startAt: Instant = Instant.now().plus(1, ChronoUnit.DAYS),
        endAt: Instant = startAt.plus(1, ChronoUnit.HOURS),
        problemIds: Set<String> = setOf("vector-addition"),
        processor: String = "gpu-cuda",
    ) = ContestInputApi("Контест", startAt.atOffset(ZoneOffset.UTC), endAt.atOffset(ZoneOffset.UTC), authorIds, problemIds, processor)

    private fun version(problemId: String, processors: List<String>) = ProblemVersion(
        id = UUID.randomUUID(), problemId = problemId, code = "1", title = problemId, difficulty = "easy", tags = emptyList(),
        authors = emptyList(), draft = false, statementMarkdown = "", processors = processors,
        starters = processors.associateWith { emptyMap() }, judgeSpec = "{}", contentHash = "content", judgeHash = "judge", revision = null,
    )

    @Test
    fun `a contest takes only problems that support its processor, on a processor the platform offers`() {
        val author = user()
        given(userRepository.findById(author.id)).willReturn(Optional.of(author))
        given(problemCatalog.publishedVersions(setOf("vector-addition"))).willReturn(listOf(version("vector-addition", listOf("gpu-cuda"))))

        assertThatThrownBy { service.create(input(setOf(author.id.toString()), processor = "rtl"), author) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("нельзя сдавать на процессоре rtl")
        assertThatThrownBy { service.create(input(setOf(author.id.toString()), processor = "coral-npu"), author) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("coral-npu недоступен")
    }

    @Test
    fun `contests are filtered by processor`() {
        val gpu = contestFixture()
        val rtl = contestFixture(processor = "rtl")
        given(contestRepository.findAll()).willReturn(listOf(gpu, rtl))

        assertThat(service.list(null, null, mine = false, processor = "rtl", viewer = null)).containsExactly(rtl)
    }

    @Test
    fun `rules name the processor and its limit`() {
        val rules = service.buildRulesMarkdown(contestFixture(processor = "rtl"), emptyList(), viewer = null)
        assertThat(rules).contains("`rtl`").contains("лимит тактов × глубины × транзисторов")
        assertThat(service.buildRulesMarkdown(contestFixture(), emptyList(), viewer = null)).contains("NVIDIA GeForce RTX 3060").contains("лимит времени")
    }

    @Test
    fun `list with mine=true but no viewer requires login`() {
        assertThatThrownBy { service.list(null, null, mine = true, processor = null, viewer = null) }
            .isInstanceOf(UnauthorizedException::class.java)
    }

    @Test
    fun `list sorts ascending by default and descending for finished`() {
        val now = Instant.now()
        val earlyFinished = contestFixture(startAt = now.minus(2, ChronoUnit.DAYS), endAt = now.minus(1, ChronoUnit.DAYS))
        val lateFinished = contestFixture(startAt = now.minus(1, ChronoUnit.DAYS), endAt = now.minusSeconds(60))
        given(contestRepository.findAll()).willReturn(listOf(earlyFinished, lateFinished))

        val ascending = service.list(null, null, mine = false, processor = null, viewer = null)
        val descending = service.list(null, "finished", mine = false, processor = null, viewer = null)

        assertThat(ascending).containsExactly(earlyFinished, lateFinished)
        assertThat(descending).containsExactly(lateFinished, earlyFinished)
    }

    @Test
    fun `create rejects a start time that is not in the future`() {
        assertThatThrownBy {
            service.create(input(setOf(UUID.randomUUID().toString()), startAt = Instant.now().minusSeconds(1)), user())
        }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `create rejects an end time that is not after the start time`() {
        val startAt = Instant.now().plus(1, ChronoUnit.DAYS)

        assertThatThrownBy {
            service.create(input(setOf(UUID.randomUUID().toString()), startAt = startAt, endAt = startAt), user())
        }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `create rejects an author id that does not resolve to a user`() {
        val missingId = UUID.randomUUID()
        given(userRepository.findById(missingId)).willReturn(Optional.empty())

        assertThatThrownBy { service.create(input(setOf(missingId.toString())), user()) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `update is forbidden for a non-author`() {
        val author = user("author")
        val outsider = user("outsider")
        val contest = contestFixture(authors = mutableSetOf(author))
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))

        assertThatThrownBy { service.update(contest.id.toString(), input(setOf(author.id.toString())), outsider) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `update is forbidden once the contest has started`() {
        val author = user("author")
        val now = Instant.now()
        val contest = contestFixture(startAt = now.minusSeconds(60), endAt = now.plus(1, ChronoUnit.HOURS), authors = mutableSetOf(author))
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))

        assertThatThrownBy { service.update(contest.id.toString(), input(setOf(author.id.toString())), author) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `register rejects a finished contest`() {
        val now = Instant.now()
        val contest = contestFixture(startAt = now.minus(2, ChronoUnit.HOURS), endAt = now.minus(1, ChronoUnit.HOURS))
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))

        assertThatThrownBy { service.register(contest.id.toString(), user()) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `registering twice only inserts once`() {
        val participant = user()
        val contest = contestFixture()
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))
        given(contestRegistrationRepository.existsByIdContestIdAndIdUserId(contest.id, participant.id)).willReturn(true)

        service.register(contest.id.toString(), participant)

        verify(contestRegistrationRepository, never()).save(any(ContestRegistration::class.java))
    }

    @Test
    fun `standings are empty before the contest starts`() {
        val contest = contestFixture(startAt = Instant.now().plus(1, ChronoUnit.DAYS), endAt = Instant.now().plus(2, ChronoUnit.DAYS))
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))

        assertThat(service.standings(contest.id.toString())).isEmpty()
    }

    @Test
    fun `rules markdown hides the baseline table before start for non-authors only`() {
        val author = user("author")
        val contest = contestFixture(authors = mutableSetOf(author))
        val problem = ContestProblem(contest.id, "vector-addition", 0)

        val visitorMarkdown = service.buildRulesMarkdown(contest, listOf(problem), viewer = null)
        val authorMarkdown = service.buildRulesMarkdown(contest, listOf(problem), viewer = author)

        assertThat(visitorMarkdown).doesNotContain("vector-addition")
        assertThat(authorMarkdown).contains("vector-addition")
    }
}
