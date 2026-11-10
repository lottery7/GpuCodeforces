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
    fun `correct guesses count as solved without entering GPU records`() {
        val user = UUID.randomUUID()
        val contest = UUID.randomUUID()
        val scope = ResultScope(contest, mapOf("guess" to "hash"), "gpu")
        val accepted = AcceptedSubmission(UUID.randomUUID(), user, "guess", contest, "hash", "gpu", null, Instant.now())
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, scope.judgeHashes.keys)).willReturn(listOf(accepted))
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, listOf("guess"))).willReturn(listOf(accepted))
        given(repository.findWithStatusInContest(SubmissionStatus.ACCEPTED, contest)).willReturn(listOf(accepted))

        assertThat(stats.firstAcceptedInContest(contest)).containsEntry(user, mapOf("guess" to accepted.createdAt))
        assertThat(stats.bestTime(user, "guess", contest, "hash", "gpu")).isNull()
        assertThat(stats.leaders("guess", scope, 20)).isEmpty()
        val result = stats.problemResults(scope, user).getValue("guess")
        assertThat(result.solved).isTrue()
        assertThat(result.best).isNull()
        assertThat(result.record).isNull()
    }

    @Test
    fun `contest solve times use the first submission time for coding and guesses`() {
        val user = UUID.randomUUID()
        val contest = UUID.randomUUID()
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val coding = AcceptedSubmission(UUID.randomUUID(), user, "coding", contest, "hash", "gpu", BigDecimal("2.5"), start.plusSeconds(60))
        val later = AcceptedSubmission(UUID.randomUUID(), user, "coding", contest, "hash", "gpu", BigDecimal("1.25"), start.plusSeconds(120))
        val guess = AcceptedSubmission(UUID.randomUUID(), user, "guess", contest, "hash", "gpu", null, start.plusSeconds(90))
        given(repository.findWithStatusInContest(SubmissionStatus.ACCEPTED, contest)).willReturn(listOf(later, coding, guess))

        assertThat(stats.firstAcceptedInContest(contest))
            .containsEntry(user, mapOf("coding" to coding.createdAt, "guess" to guess.createdAt))
    }
}
