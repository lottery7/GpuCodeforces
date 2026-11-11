package dev.gpucodeforces.backend.submission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SubmissionStatsTest {
    private val repository = mock(SubmissionStatsRepository::class.java)
    private val stats = SubmissionStats(repository)

    @Test
    fun `records on the real GPU exclude previous hardware and mock results`() {
        val user = UUID.randomUUID()
        val scope = ResultScope(null, mapOf("optimization" to "hash"), "real-current")
        fun submission(time: String, environment: String?) = AcceptedSubmission(
            UUID.randomUUID(), user, "optimization", null, "hash", GPU, BigDecimal(time), Instant.now(), environment,
        )
        val current = submission("4", "real-current")
        val previousGpu = submission("1", "real-previous")
        val mock = submission("0.1", "mock")
        val legacy = submission("0.01", null)
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, listOf("optimization"))).willReturn(listOf(legacy, mock, previousGpu, current))
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, scope.judgeHashes.keys)).willReturn(listOf(legacy, mock, previousGpu, current))

        assertThat(stats.bestScore(user, "optimization", null, "hash", GPU, "real-current")).isEqualByComparingTo("4")
        assertThat(stats.problemResults(scope, user, listOf(GPU)).getValue("optimization").best[GPU]).isSameAs(current)
        assertThat(stats.leaders("optimization", scope, GPU, 20).single().submission).isSameAs(current)
    }

    @Test
    fun `an emulated processor gives the same numbers everywhere, so its records ignore the GPU environment`() {
        val user = UUID.randomUUID()
        val scope = ResultScope(null, mapOf("adder" to "hash"), "real-current")
        val older = AcceptedSubmission(UUID.randomUUID(), user, "adder", null, "hash", "rtl", BigDecimal("4096"), Instant.now(), "rtl-key")
        val best = AcceptedSubmission(UUID.randomUUID(), user, "adder", null, "hash", "rtl", BigDecimal("1024"), Instant.now(), null)
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, scope.judgeHashes.keys)).willReturn(listOf(older, best))
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, listOf("adder"))).willReturn(listOf(older, best))

        assertThat(stats.problemResults(scope, user, listOf("rtl")).getValue("adder").record["rtl"]).isSameAs(best)
        assertThat(stats.bestScore(user, "adder", null, "hash", "rtl", "real-current")).isEqualByComparingTo("1024")
    }

    @Test
    fun `correct guesses count as solved without entering records`() {
        val user = UUID.randomUUID()
        val contest = UUID.randomUUID()
        val scope = ResultScope(contest, mapOf("guess" to "hash"))
        val accepted = AcceptedSubmission(UUID.randomUUID(), user, "guess", contest, "hash", GPU, null, Instant.now())
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, scope.judgeHashes.keys)).willReturn(listOf(accepted))
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, listOf("guess"))).willReturn(listOf(accepted))
        given(repository.findWithStatusInContest(SubmissionStatus.ACCEPTED, contest)).willReturn(listOf(accepted))

        assertThat(stats.firstAcceptedInContest(contest)).containsEntry(user, mapOf("guess" to accepted.createdAt))
        assertThat(stats.bestScore(user, "guess", contest, "hash", GPU)).isNull()
        assertThat(stats.leaders("guess", scope, GPU, 20)).isEmpty()
        val result = stats.problemResults(scope, user, listOf(GPU)).getValue("guess")
        assertThat(result.solved).isTrue()
        assertThat(result.best).isEmpty()
        assertThat(result.record).isEmpty()
    }

    @Test
    fun `contest solve times use the first submission time for coding and guesses`() {
        val user = UUID.randomUUID()
        val contest = UUID.randomUUID()
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val coding = AcceptedSubmission(UUID.randomUUID(), user, "coding", contest, "hash", GPU, BigDecimal("2.5"), start.plusSeconds(60))
        val later = AcceptedSubmission(UUID.randomUUID(), user, "coding", contest, "hash", GPU, BigDecimal("1.25"), start.plusSeconds(120))
        val guess = AcceptedSubmission(UUID.randomUUID(), user, "guess", contest, "hash", GPU, null, start.plusSeconds(90))
        given(repository.findWithStatusInContest(SubmissionStatus.ACCEPTED, contest)).willReturn(listOf(later, coding, guess))

        assertThat(stats.firstAcceptedInContest(contest))
            .containsEntry(user, mapOf("coding" to coding.createdAt, "guess" to guess.createdAt))
    }

    private companion object {
        const val GPU = "gpu-cuda"
    }
}
