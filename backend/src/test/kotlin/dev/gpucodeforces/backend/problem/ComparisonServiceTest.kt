package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.submission.Submission
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import dev.gpucodeforces.backend.user.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ComparisonServiceTest {
    private val submissions = mock(SubmissionRepository::class.java)
    private val versions = mock(ProblemVersionRepository::class.java)
    private val problems = mock(ProblemRepository::class.java)
    private val contests = mock(ContestRepository::class.java)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val gpu = GpuProperties()
    private val author = User("author", "author@example.com", "hash")
    private val sample = ProblemPackageReader(mapper).read(Path.of("../judge/problems/examples/vector_add"))
    private val service = ComparisonService(submissions, versions, problems, contests, gpu, mapper)

    init {
        given(problems.save(any(Problem::class.java))).willAnswer { it.getArgument<Problem>(0) }
        given(versions.save(any(ProblemVersion::class.java))).willAnswer { it.getArgument<ProblemVersion>(0) }
    }

    private fun version(
        judgeHash: String = sample.judgeHash,
        draft: Boolean = false,
        format: String = ProblemFormatData.STANDARD,
        judgeSpec: String = mapper.writeValueAsString(sample.spec),
    ) = ProblemVersion(
        UUID.randomUUID(), sample.id, sample.frontMatter.code, sample.frontMatter.title,
        sample.frontMatter.difficulty, sample.frontMatter.tags, sample.frontMatter.authors,
        draft, sample.statementMarkdown, sample.starterCode, judgeSpec, sample.contentHash,
        judgeHash, null, format = format,
    ).also { given(versions.findById(it.id)).willReturn(Optional.of(it)) }

    private fun submission(
        source: ProblemVersion,
        userId: UUID = author.id,
        contestId: UUID? = null,
        profile: String = gpu.profile,
        timeMs: BigDecimal? = BigDecimal("1.2"),
        status: SubmissionStatus = SubmissionStatus.ACCEPTED,
        problemId: String = source.problemId,
        guessChoice: String? = null,
    ) = Submission(
        UUID.randomUUID(), userId, problemId, source.id, contestId, "void solve() {}",
        "GPU environment", source.judgeHash, profile, null, guessChoice = guessChoice,
    ).also {
        it.timeMs = timeMs
        it.status = status
        given(submissions.findForUpdate(it.id)).willReturn(it)
    }

    private fun create(first: Submission, second: Submission, explanation: String = "Причина разницы") =
        service.create(sample.id, first.id.toString(), second.id.toString(), explanation, author)

    @Test
    fun `publishes a comparison from two owned historical CUDA versions`() {
        val firstVersion = version()
        val secondVersion = version()
        val first = submission(firstVersion)
        val second = submission(secondVersion, timeMs = BigDecimal("2.4"))

        val result = create(first, second, "  Причина разницы  ")
        val data = mapper.readValue(result.formatData, ProblemFormatData::class.java)

        assertThat(result.problemId).startsWith("comparison-")
        assertThat(result.format).isEqualTo(ProblemFormatData.GUESS)
        assertThat(result.draft).isFalse()
        assertThat(result.authors).containsExactly(author.handle)
        assertThat(result.statementMarkdown).isEqualTo(firstVersion.statementMarkdown)
        assertThat(result.judgeSpec).isEqualTo(firstVersion.judgeSpec)
        assertThat(data.firstCode).isEqualTo(first.code)
        assertThat(data.secondCode).isEqualTo(second.code)
        assertThat(data.firstTimeMs).isEqualByComparingTo("1.2")
        assertThat(data.secondTimeMs).isEqualByComparingTo("2.4")
        assertThat(data.explanation).isEqualTo("Причина разницы")
        assertThat(data.correctChoice()).isEqualTo("first")
    }

    @Test
    fun `does not expose or publish another users submission`() {
        val version = version()
        val own = submission(version)
        val foreign = submission(version, userId = UUID.randomUUID())

        assertThatThrownBy { create(own, foreign) }.isInstanceOf(NotFoundApiException::class.java)
    }

    @Test
    fun `rejects duplicate submissions and missing explanation`() {
        val version = version()
        val first = submission(version)
        val second = submission(version)

        assertThatThrownBy { create(first, first) }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { create(first, second, "  ") }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `requires accepted positive measurements of this problem on the current GPU`() {
        val version = version()
        val first = submission(version)
        val invalid = listOf(
            submission(version, status = SubmissionStatus.WRONG_ANSWER),
            submission(version, timeMs = null),
            submission(version, timeMs = BigDecimal.ZERO),
            submission(version, profile = "different-gpu"),
            submission(version, problemId = "other-problem"),
            submission(version, guessChoice = "first"),
        )

        invalid.forEach { second ->
            assertThatThrownBy { create(first, second) }.isInstanceOf(BadRequestException::class.java)
        }
    }

    @Test
    fun `rejects different judge configurations even if the submissions are accepted`() {
        val first = submission(version())
        val second = submission(version(judgeHash = "different-hash"))

        assertThatThrownBy { create(first, second) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `checks actual judge specs and source visibility`() {
        val first = submission(version())
        val changedSpec = mapper.writeValueAsString(sample.spec.copy(version = "different-version"))
        val incompatible = listOf(
            submission(version(judgeSpec = changedSpec)),
            submission(version(draft = true)),
            submission(version(format = ProblemFormatData.GUESS)),
        )

        incompatible.forEach { second ->
            assertThatThrownBy { create(first, second) }.isInstanceOf(BadRequestException::class.java)
        }
    }

    @Test
    fun `contest code becomes publishable only after the contest ends`() {
        val now = Instant.now()
        val contest = Contest("Контест", now.minusSeconds(60), now.plusSeconds(3600), gpu.name)
        given(contests.findById(contest.id)).willReturn(Optional.of(contest))
        val version = version()
        val first = submission(version)
        val second = submission(version, contestId = contest.id)

        assertThatThrownBy { create(first, second) }.isInstanceOf(BadRequestException::class.java)

        contest.endAt = now.minusSeconds(1)
        assertThat(create(first, second).format).isEqualTo(ProblemFormatData.GUESS)
    }
}
