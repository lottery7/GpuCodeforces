package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.user.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ContestMapperTest {
    private val author = User("author", "author@example.com", "hash")

    private fun contest(startAt: Instant, endAt: Instant) =
        Contest(
            id = UUID.randomUUID(),
            title = "Контест",
            startAt = startAt,
            endAt = endAt,
            processor = "gpu-cuda",
            authors = mutableSetOf(author),
        )

    private fun problems(contestId: UUID) = listOf(
        ContestProblem(contestId, "matrix-multiply", 1),
        ContestProblem(contestId, "vector-addition", 0),
    )

    @Test
    fun `anonymous visitors see no problem ids before the contest starts`() {
        val now = Instant.now()
        val contest = contest(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS))

        val api = contest.toApiModel(problems(contest.id), viewer = null, registered = false, now = now)

        assertThat(api.problemIds).isEmpty()
        assertThat(api.problemCount).isEqualTo(2)
    }

    @Test
    fun `authors see the ordered problem list before the contest starts`() {
        val now = Instant.now()
        val contest = contest(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS))

        val api = contest.toApiModel(problems(contest.id), viewer = author, registered = false, now = now)

        assertThat(api.problemIds).containsExactly("vector-addition", "matrix-multiply")
    }

    @Test
    fun `everyone sees the ordered problem list once the contest is live`() {
        val now = Instant.now()
        val contest = contest(now.minusSeconds(60), now.plus(1, ChronoUnit.HOURS))

        val api = contest.toApiModel(problems(contest.id), viewer = null, registered = false, now = now)

        assertThat(api.problemIds).containsExactly("vector-addition", "matrix-multiply")
    }

    @Test
    fun `registered flag and rules markdown pass through untouched`() {
        val now = Instant.now()
        val contest = contest(now.minusSeconds(60), now.plus(1, ChronoUnit.HOURS))

        val api = contest.toApiModel(emptyList(), viewer = null, registered = true, now = now, rulesMarkdown = "## Правила")

        assertThat(api.registered).isTrue()
        assertThat(api.rulesMarkdown).isEqualTo("## Правила")
    }

    @Test
    fun `rules markdown is omitted by default`() {
        val now = Instant.now()
        val contest = contest(now.minusSeconds(60), now.plus(1, ChronoUnit.HOURS))

        val api = contest.toApiModel(emptyList(), viewer = null, registered = false, now = now)

        assertThat(api.rulesMarkdown).isNull()
    }
}
