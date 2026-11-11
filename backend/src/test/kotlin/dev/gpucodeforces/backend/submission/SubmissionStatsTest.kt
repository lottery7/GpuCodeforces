package dev.gpucodeforces.backend.submission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SubmissionStatsTest {
    private val repository = mock(SubmissionStatsRepository::class.java)
    private val stats = SubmissionStats(repository)
    private val userId = UUID.randomUUID()

    private fun day(offsetDays: Long): Instant = Instant.now().minus(java.time.Duration.ofDays(offsetDays))

    @Test
    fun `streak counts consecutive days ending today`() {
        given(repository.findAcceptedAt(userId, SubmissionStatus.ACCEPTED)).willReturn(
            listOf(day(0), day(1), day(2), day(4)),
        )

        assertThat(stats.streak(userId)).isEqualTo(3)
    }

    @Test
    fun `streak is zero when the latest submission is older than today`() {
        given(repository.findAcceptedAt(userId, SubmissionStatus.ACCEPTED)).willReturn(
            listOf(day(2), day(3)),
        )

        assertThat(stats.streak(userId)).isEqualTo(0)
    }

    @Test
    fun `streak is zero without accepted submissions`() {
        given(repository.findAcceptedAt(userId, SubmissionStatus.ACCEPTED)).willReturn(emptyList())

        assertThat(stats.streak(userId)).isEqualTo(0)
    }

    @Test
    fun `streak counts a run from yesterday when today is still empty`() {
        given(repository.findAcceptedAt(userId, SubmissionStatus.ACCEPTED)).willReturn(
            listOf(day(1), day(2)),
        )

        assertThat(stats.streak(userId)).isEqualTo(2)
    }

    @Test
    fun `streak counts on the UTC calendar`() {
        val now = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
        val todayAtUtc = now.atStartOfDay(ZoneOffset.UTC).toInstant()
        given(repository.findAcceptedAt(userId, SubmissionStatus.ACCEPTED)).willReturn(
            listOf(todayAtUtc, todayAtUtc.minusSeconds(1L * 24 * 3600)),
        )

        assertThat(stats.streak(userId)).isEqualTo(2)
    }
}