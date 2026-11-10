package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurementView
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
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class GuessServiceTest {
    private val problemService = mock(ProblemService::class.java)
    private val catalog = mock(ProblemCatalog::class.java)
    private val submissions = mock(SubmissionService::class.java)
    private val repository = mock(SubmissionRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val references = mock(ReferenceMeasurements::class.java)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val author = User("guesser", "guesser@example.com", "hash")
    private val version = ProblemVersion(
        id = UUID.randomUUID(), problemId = "guess", code = "1", title = "Угадай", difficulty = "easy",
        tags = emptyList(), authors = emptyList(), draft = false, statementMarkdown = "Выберите код",
        starterCode = "", judgeSpec = "{}", contentHash = "content", judgeHash = "judge", revision = null, format = "guess",
    )
    private val originalKey = "${version.id}:environment-1"
    private val service = GuessService(problemService, catalog, submissions, repository, users, GpuProperties(), references, mapper)

    private fun ready(key: String = originalKey, first: String = "1.0", second: String = "2.0", environmentKey: String = "environment-1") = ReferenceMeasurementView(
        key = key, status = "ready", baselineTimeMs = null,
        firstTimeMs = BigDecimal(first), secondTimeMs = BigDecimal(second), measuredAt = Instant.now(),
        synthetic = false, diagnostic = null, environmentKey = environmentKey,
    )

    private fun submit(choice: String = "first", benchmarkKey: String? = originalKey) =
        service.submit("guess", "practice", choice, author, benchmarkKey)

    @BeforeEach
    fun setup() {
        given(problemService.context("practice", author)).willReturn(ProblemContext.Practice)
        given(problemService.problem("guess", ProblemContext.Practice, author)).willReturn(ContextProblem(version, ProblemContext.Practice))
        given(users.findForUpdate(author.id)).willReturn(author)
        given(catalog.format(version)).willReturn(
            ProblemFormatData(format = "guess", firstTimeMs = BigDecimal("99.0"), secondTimeMs = BigDecimal("1.0"), explanation = "Измеренный результат"),
        )
        given(references.requireReady(version)).willReturn(ready())
        given(repository.save(any(Submission::class.java))).willAnswer { it.arguments[0] }
    }

    @Test
    fun `a correct guess is saved as accepted without a GPU measurement`() {
        val result = submit()

        assertThat(result.status).isEqualTo(SubmissionStatus.ACCEPTED)
        assertThat(result.guessChoice).isEqualTo("first")
        assertThat(result.timeMs).isNull()
        assertThat(result.problemVersionId).isEqualTo(version.id)
        assertThat(result.finishedAt).isNotNull()
        assertThat(result.guessBenchmarkKey).isEqualTo(originalKey)
        assertThat(result.benchmarkEnvironmentKey).isEqualTo("environment-1")
        val snapshot = mapper.readValue(result.guessResult!!, GuessSnapshot::class.java)
        assertThat(snapshot.firstTimeMs).isEqualByComparingTo("1.0")
        assertThat(snapshot.secondTimeMs).isEqualByComparingTo("2.0")
        assertThat(snapshot.correctChoice()).isEqualTo("first")
        verify(users).findForUpdate(author.id)
        verify(submissions).submissionContext(null, author)
    }

    @Test
    fun `a wrong guess is saved as wrong answer`() {
        assertThat(submit("second").status).isEqualTo(SubmissionStatus.WRONG_ANSWER)
    }

    @Test
    fun `equal times use numeric comparison regardless of decimal scale`() {
        given(references.requireReady(version)).willReturn(ready(first = "1.00", second = "1.0"))

        assertThat(submit("equal").status).isEqualTo(SubmissionStatus.ACCEPTED)
    }

    @Test
    fun `a repeated choice returns the immutable prior attempt`() {
        val previous = submit()
        given(repository.findGuess(author.id, version.id, null, originalKey)).willReturn(previous)

        assertThat(submit()).isSameAs(previous)
        assertThatThrownBy { submit("second") }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("изменить")
    }

    @Test
    fun `a forbidden contest submission does not save an answer`() {
        given(submissions.submissionContext(null, author)).willThrow(ForbiddenException("Нет регистрации"))

        assertThatThrownBy { submit() }.isInstanceOf(ForbiddenException::class.java)
        verify(repository, never()).save(any(Submission::class.java))
    }

    @Test
    fun `unknown choices are rejected before locking or saving`() {
        assertThatThrownBy { submit("third") }.isInstanceOf(BadRequestException::class.java)
        verify(users, never()).findForUpdate(author.id)
        verify(repository, never()).save(any(Submission::class.java))
    }

    @Test
    fun `pending reference measurements do not consume an answer attempt`() {
        given(references.requireReady(version)).willThrow(ServiceUnavailableException("Замер еще не готов"))

        assertThatThrownBy { submit() }.isInstanceOf(ServiceUnavailableException::class.java)
        verify(repository, never()).save(any(Submission::class.java))
    }

    @Test
    fun `a new environment key allows a new attempt without rewriting the earlier measured answer`() {
        val previous = submit()
        val nextKey = "${version.id}:environment-2"
        given(repository.findGuess(author.id, version.id, null, originalKey)).willReturn(previous)
        given(references.requireReady(version)).willReturn(ready(key = nextKey, first = "3.0", second = "2.0", environmentKey = "environment-2"))

        val current = submit("second", nextKey)

        assertThat(current.id).isNotEqualTo(previous.id)
        assertThat(current.status).isEqualTo(SubmissionStatus.ACCEPTED)
        assertThat(current.guessBenchmarkKey).isEqualTo(nextKey)
        assertThat(current.benchmarkEnvironmentKey).isEqualTo("environment-2")
        val old = mapper.readValue(previous.guessResult!!, GuessSnapshot::class.java)
        assertThat(old.benchmarkKey).isEqualTo(originalKey)
        assertThat(old.firstTimeMs).isEqualByComparingTo("1.0")
        assertThat(old.correctChoice()).isEqualTo("first")
    }

    @Test
    fun `stale or missing comparison keys do not consume an attempt in the new environment`() {
        val nextKey = "${version.id}:environment-2"
        given(references.requireReady(version)).willReturn(ready(key = nextKey, environmentKey = "environment-2"))

        assertThatThrownBy { submit() }.isInstanceOf(BadRequestException::class.java).hasMessageContaining("изменились")
        assertThatThrownBy { submit(benchmarkKey = null) }.isInstanceOf(BadRequestException::class.java)
        verify(repository, never()).save(any(Submission::class.java))
    }
}
