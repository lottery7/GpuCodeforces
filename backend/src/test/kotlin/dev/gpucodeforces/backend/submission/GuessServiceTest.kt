package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.problem.ContextProblem
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemContext
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemService
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.util.UUID

class GuessServiceTest {
    private val problemService = mock(ProblemService::class.java)
    private val catalog = mock(ProblemCatalog::class.java)
    private val submissions = mock(SubmissionService::class.java)
    private val repository = mock(SubmissionRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val author = User("guesser", "guesser@example.com", "hash")
    private val version = ProblemVersion(
        id = UUID.randomUUID(), problemId = "guess", code = "1", title = "Угадай", difficulty = "easy",
        tags = emptyList(), authors = emptyList(), draft = false, statementMarkdown = "Выберите код",
        starterCode = "", judgeSpec = "{}", contentHash = "content", judgeHash = "judge", revision = null, format = "guess",
    )
    private val service = GuessService(problemService, catalog, submissions, repository, users, GpuProperties())

    @BeforeEach
    fun setup() {
        given(problemService.context("practice", author)).willReturn(ProblemContext.Practice)
        given(problemService.problem("guess", ProblemContext.Practice, author)).willReturn(ContextProblem(version, ProblemContext.Practice))
        given(users.findForUpdate(author.id)).willReturn(author)
        given(catalog.format(version)).willReturn(
            ProblemFormatData(format = "guess", firstTimeMs = BigDecimal("1.0"), secondTimeMs = BigDecimal("2.0")),
        )
        given(repository.save(any(Submission::class.java))).willAnswer { it.arguments[0] }
    }

    @Test
    fun `a correct guess is saved as accepted without a GPU measurement`() {
        val result = service.submit("guess", "practice", "first", author)

        assertThat(result.status).isEqualTo(SubmissionStatus.ACCEPTED)
        assertThat(result.guessChoice).isEqualTo("first")
        assertThat(result.timeMs).isNull()
        assertThat(result.problemVersionId).isEqualTo(version.id)
        assertThat(result.finishedAt).isNotNull()
        verify(users).findForUpdate(author.id)
        verify(submissions).submissionContext(null, author)
    }

    @Test
    fun `a wrong guess is saved as wrong answer`() {
        assertThat(service.submit("guess", "practice", "second", author).status).isEqualTo(SubmissionStatus.WRONG_ANSWER)
    }

    @Test
    fun `equal times use numeric comparison regardless of decimal scale`() {
        given(catalog.format(version)).willReturn(
            ProblemFormatData(format = "guess", firstTimeMs = BigDecimal("1.00"), secondTimeMs = BigDecimal("1.0")),
        )

        assertThat(service.submit("guess", "practice", "equal", author).status).isEqualTo(SubmissionStatus.ACCEPTED)
    }

    @Test
    fun `a repeated choice returns the immutable prior attempt`() {
        val previous = service.submit("guess", "practice", "first", author)
        given(repository.findGuess(author.id, version.id, null)).willReturn(previous)

        assertThat(service.submit("guess", "practice", "first", author)).isSameAs(previous)
        assertThatThrownBy { service.submit("guess", "practice", "second", author) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("изменить")
    }

    @Test
    fun `a forbidden contest submission does not save an answer`() {
        given(submissions.submissionContext(null, author)).willThrow(ForbiddenException("Нет регистрации"))

        assertThatThrownBy { service.submit("guess", "practice", "first", author) }.isInstanceOf(ForbiddenException::class.java)
        verify(repository, never()).save(any(Submission::class.java))
    }

    @Test
    fun `unknown choices are rejected before locking or saving`() {
        assertThatThrownBy { service.submit("guess", "practice", "third", author) }.isInstanceOf(BadRequestException::class.java)
        verify(users, never()).findForUpdate(author.id)
        verify(repository, never()).save(any(Submission::class.java))
    }
}
