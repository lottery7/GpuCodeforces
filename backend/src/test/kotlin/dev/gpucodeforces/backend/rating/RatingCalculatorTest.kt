package dev.gpucodeforces.backend.rating

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RatingCalculatorTest {
    @Test
    fun `single participant keeps their rating unchanged`() {
        val solo = UUID.randomUUID()
        val outcome = RatingCalculator.compute(listOf(ParticipantResult(solo, 1800, 1)))

        assertThat(outcome.getValue(solo)).isEqualTo(RatingOutcome(0, 1800))
    }

    @Test
    fun `two unrated participants tied at first place both settle on the default rating`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val outcome = RatingCalculator.compute(
            listOf(ParticipantResult(alice, null, 1), ParticipantResult(bob, null, 1)),
        )

        assertThat(outcome.getValue(alice)).isEqualTo(RatingOutcome(0, 1500))
        assertThat(outcome.getValue(bob)).isEqualTo(RatingOutcome(0, 1500))
    }

    @Test
    fun `winner of an equal-rated field gains, last place loses, deltas are zero-sum`() {
        val players = (1..4).map { UUID.randomUUID() }
        val results = players.mapIndexed { index, id -> ParticipantResult(id, 1500, index + 1) }

        val outcome = RatingCalculator.compute(results)

        assertThat(outcome.getValue(players[0]).delta).isGreaterThan(0)
        assertThat(outcome.getValue(players.last()).delta).isLessThan(0)
        assertThat(outcome.values.sumOf { it.delta }).isCloseTo(0, org.assertj.core.data.Offset.offset(players.size))
        // Better rank among equally-rated players must never earn a worse (or equal) delta.
        for (i in 0 until players.size - 1) {
            assertThat(outcome.getValue(players[i]).delta).isGreaterThan(outcome.getValue(players[i + 1]).delta)
        }
    }

    @Test
    fun `beating much higher-rated players yields a bigger gain than beating peers`() {
        val challenger = UUID.randomUUID()
        val strongField = listOf(
            ParticipantResult(challenger, 1500, 1),
            ParticipantResult(UUID.randomUUID(), 2400, 2),
            ParticipantResult(UUID.randomUUID(), 2400, 3),
        )
        val peerField = listOf(
            ParticipantResult(challenger, 1500, 1),
            ParticipantResult(UUID.randomUUID(), 1500, 2),
            ParticipantResult(UUID.randomUUID(), 1500, 3),
        )

        val strongOutcome = RatingCalculator.compute(strongField).getValue(challenger)
        val peerOutcome = RatingCalculator.compute(peerField).getValue(challenger)

        assertThat(strongOutcome.delta).isGreaterThan(peerOutcome.delta)
    }

    @Test
    fun `delta never exceeds the configured cap even for extreme upsets`() {
        val underdog = UUID.randomUUID()
        val field = listOf(ParticipantResult(underdog, 400, 1)) +
            (1..9).map { ParticipantResult(UUID.randomUUID(), 3000, it + 1) }

        val outcome = RatingCalculator.compute(field).getValue(underdog)

        assertThat(outcome.delta).isLessThanOrEqualTo(400)
    }
}
