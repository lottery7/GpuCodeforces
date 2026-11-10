package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.UnauthorizedException
import dev.gpucodeforces.backend.problem.ProblemCatalog
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
        GpuProperties(),
        mock(SubmissionStats::class.java),
    )

    private fun user(handle: String = "author") = User(handle, "$handle@example.com", "hash")

    private fun contestFixture(
        startAt: Instant = Instant.now().plus(1, ChronoUnit.DAYS),
        endAt: Instant = startAt.plus(1, ChronoUnit.HOURS),
        authors: MutableSet<User> = mutableSetOf(),
        format: String = "standard",
        challengerId: UUID? = null,
        opponentId: UUID? = null,
    ) = Contest(id = UUID.randomUUID(), title = "Контест", startAt = startAt, endAt = endAt,
        gpu = "NVIDIA GeForce RTX 4090", authors = authors, format = format, challengerId = challengerId, opponentId = opponentId)

    private fun input(
        authorIds: Set<String>,
        startAt: Instant = Instant.now().plus(1, ChronoUnit.DAYS),
        endAt: Instant = startAt.plus(1, ChronoUnit.HOURS),
        problemIds: Set<String> = setOf("vector-addition"),
    ) = ContestInputApi("Контест", startAt.atOffset(ZoneOffset.UTC), endAt.atOffset(ZoneOffset.UTC), authorIds, problemIds)

    @Test
    fun `list with mine=true but no viewer requires login`() {
        assertThatThrownBy { service.list(null, null, mine = true, viewer = null) }
            .isInstanceOf(UnauthorizedException::class.java)
    }

    @Test
    fun `list sorts ascending by default and descending for finished`() {
        val now = Instant.now()
        val earlyFinished = contestFixture(startAt = now.minus(2, ChronoUnit.DAYS), endAt = now.minus(1, ChronoUnit.DAYS))
        val lateFinished = contestFixture(startAt = now.minus(1, ChronoUnit.DAYS), endAt = now.minusSeconds(60))
        given(contestRepository.findAll()).willReturn(listOf(earlyFinished, lateFinished))

        val ascending = service.list(null, null, mine = false, viewer = null)
        val descending = service.list(null, "finished", mine = false, viewer = null)

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

    @Test
    fun `duel creation registers the challenger and keeps the invited opponent`() {
        val challenger = user("challenger")
        val opponent = user("opponent")
        given(userRepository.findById(challenger.id)).willReturn(Optional.of(challenger))
        given(userRepository.findById(opponent.id)).willReturn(Optional.of(opponent))
        given(contestRepository.save(any(Contest::class.java))).willAnswer { it.arguments[0] }

        val created = service.create(
            input(setOf(challenger.id.toString())).format(ContestInputApi.FormatEnum.DUEL).opponentId(opponent.id.toString()),
            challenger,
        )

        assertThat(created.challengerId).isEqualTo(challenger.id)
        assertThat(created.opponentId).isEqualTo(opponent.id)
        assertThat(created.format).isEqualTo("duel")
        verify(contestRegistrationRepository).save(ContestRegistration(created.id, challenger.id))
    }

    @Test
    fun `a duel only admits its two participants`() {
        val challenger = user("challenger")
        val opponent = user("opponent")
        val contest = contestFixture(format = "duel", challengerId = challenger.id, opponentId = opponent.id)
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))
        given(contestRepository.findForUpdate(contest.id)).willReturn(contest)

        assertThatThrownBy { service.register(contest.id.toString(), user("outsider")) }.isInstanceOf(ForbiddenException::class.java)
        service.register(contest.id.toString(), opponent)
        verify(contestRegistrationRepository).save(ContestRegistration(contest.id, opponent.id))
    }

    @Test
    fun `a duel rejects multiple problems and self invitations`() {
        val challenger = user()
        val duel = input(setOf(challenger.id.toString()), problemIds = setOf("first", "second"))
            .format(ContestInputApi.FormatEnum.DUEL).opponentId(UUID.randomUUID().toString())
        assertThatThrownBy { service.create(duel, challenger) }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("одна задача")

        duel.problemIds(setOf("first")).opponentId(challenger.id.toString())
        assertThatThrownBy { service.create(duel, challenger) }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("себя")
    }

    @Test
    fun `duel participants and format cannot be edited`() {
        val challenger = user()
        val opponent = user("opponent")
        val contest = contestFixture(authors = mutableSetOf(challenger), format = "duel", challengerId = challenger.id, opponentId = opponent.id)
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))

        assertThatThrownBy { service.update(contest.id.toString(), input(setOf(challenger.id.toString())), challenger) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("нельзя изменить")
    }

    @Test
    fun `a duel cannot grant another user author rights`() {
        val challenger = user()
        val opponent = user("opponent")
        val input = input(setOf(challenger.id.toString(), opponent.id.toString()))
            .format(ContestInputApi.FormatEnum.DUEL).opponentId(opponent.id.toString())
        assertThatThrownBy { service.create(input, challenger) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("Единственный автор")

        val contest = contestFixture(authors = mutableSetOf(challenger), format = "duel", challengerId = challenger.id, opponentId = opponent.id)
        given(contestRepository.findById(contest.id)).willReturn(Optional.of(contest))
        assertThatThrownBy { service.update(contest.id.toString(), input, challenger) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("Единственный автор")
    }
}
