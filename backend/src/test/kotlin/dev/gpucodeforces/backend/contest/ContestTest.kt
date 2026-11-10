package dev.gpucodeforces.backend.contest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ContestTest {
    private fun contest(startAt: Instant, endAt: Instant) =
        Contest(id = UUID.randomUUID(), title = "Контест", startAt = startAt, endAt = endAt, gpu = "NVIDIA GeForce RTX 4090")

    @Test
    fun `phase is upcoming strictly before start, live exactly at start, finished exactly at end`() {
        val start = Instant.now()
        val end = start.plus(1, ChronoUnit.HOURS)
        val contest = contest(start, end)

        assertThat(contest.phaseAt(start.minusSeconds(1))).isEqualTo(ContestPhase.UPCOMING)
        assertThat(contest.phaseAt(start)).isEqualTo(ContestPhase.LIVE)
        assertThat(contest.phaseAt(end.minusSeconds(1))).isEqualTo(ContestPhase.LIVE)
        assertThat(contest.phaseAt(end)).isEqualTo(ContestPhase.FINISHED)
    }
}
