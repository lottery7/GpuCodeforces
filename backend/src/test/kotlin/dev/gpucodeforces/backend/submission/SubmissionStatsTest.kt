package dev.gpucodeforces.backend.submission

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

class SubmissionStatsTest {
    private val repository = mock(SubmissionStatsRepository::class.java)
    private val stats = SubmissionStats(repository)
    private val userId = UUID.randomUUID()

    private fun day(offsetDays: Long): Instant = Instant.now().minus(Duration.ofDays(offsetDays))

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

    @Test
    fun `duel pending count uses its exact time window while ordinary contests keep the full count`() {
        val contest = UUID.randomUUID()
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val end = start.plusSeconds(3600)
        given(repository.countInContextWithStatus(contest.toString(), SubmissionStatus.PENDING)).willReturn(9)
        given(repository.countInContestWithStatusDuring(contest, SubmissionStatus.PENDING, start, end)).willReturn(2)

        assertThat(stats.pendingInContest(contest, start, end)).isEqualTo(2)
        assertThat(stats.pendingInContest(contest)).isEqualTo(9)
    }

    @Test
    fun `finalized duel records ignore later verdicts but retain cutoff equality and legacy results`() {
        val user = UUID.randomUUID()
        val legacyUser = UUID.randomUUID()
        val contest = UUID.randomUUID()
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val end = start.plusSeconds(3600)
        val cutoff = end.plusSeconds(1800)
        val scope = ResultScope(contest, mapOf("task" to "hash"), "environment", startAt = start, endAt = end, finishedBefore = cutoff)
        fun accepted(score: String, finishedAt: Instant?, author: UUID = user) = AcceptedSubmission(
            UUID.randomUUID(), author, "task", contest, "hash", GPU, BigDecimal(score), start.plusSeconds(10), "environment", finishedAt = finishedAt,
        )
        val atCutoff = accepted("4", cutoff)
        val after = accepted("1", cutoff.plusNanos(1))
        val legacy = accepted("3", null, legacyUser)
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, listOf("task"))).willReturn(listOf(after, atCutoff, legacy))

        assertThat(stats.bestScore(user, "task", scope, GPU)).isEqualByComparingTo("4")
        assertThat(stats.leaders("task", scope, GPU, 20).map { it.submission }).containsExactly(legacy, atCutoff)
        assertThat(stats.bestScore(user, "task", scope.copy(finishedBefore = null), GPU)).isEqualByComparingTo("1")
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, scope.judgeHashes.keys)).willReturn(listOf(after, atCutoff, legacy))
        assertThat(stats.problemResults(scope, legacyUser, listOf(GPU)).getValue("task").solved).isTrue()
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, scope.judgeHashes.keys)).willReturn(listOf(after))
        assertThat(stats.problemResults(scope, user, listOf(GPU)).getValue("task").solved).isFalse()
    }

    @Test
    fun `duel record scope preserves its captured environment and deadline after the platform profile changes`() {
        val user = UUID.randomUUID()
        val contest = UUID.randomUUID()
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val end = start.plusSeconds(3600)
        val scope = ResultScope(contest, mapOf("task" to "hash"), "original-environment", startAt = start, endAt = end)
        fun accepted(time: String, at: Instant, environment: String) = AcceptedSubmission(
            UUID.randomUUID(), user, "task", contest, "hash", GPU, BigDecimal(time), at, environment,
        )
        val counted = accepted("4", start, "original-environment")
        val after = accepted("1", end, "original-environment")
        val before = accepted("1", start.minusNanos(1), "original-environment")
        val newEnvironment = accepted("1", start, "new-environment")
        given(repository.findWithStatus(SubmissionStatus.ACCEPTED, listOf("task"))).willReturn(listOf(counted, before, after, newEnvironment))

        assertThat(stats.bestScore(user, "task", scope, GPU)).isEqualByComparingTo("4")
        assertThat(stats.leaders("task", scope, GPU, 20).single().submission).isSameAs(counted)
    }

    @Test
    fun `records exclude previous hardware and mock results under the same profile`() {
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
        given(repository.findWithStatusInContext(SubmissionStatus.ACCEPTED, contest.toString())).willReturn(listOf(accepted))

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
        given(repository.findWithStatusInContext(SubmissionStatus.ACCEPTED, contest.toString())).willReturn(listOf(later, coding, guess))

        assertThat(stats.firstAcceptedInContest(contest))
            .containsEntry(user, mapOf("coding" to coding.createdAt, "guess" to guess.createdAt))
    }

    @Test
    fun `duel results use the fastest accepted pinned solution inside the contest window and captured cohort`() {
        val user = UUID.randomUUID()
        val contest = UUID.randomUUID()
        val version = UUID.randomUUID()
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val end = start.plusSeconds(3600)
        val scope = ResultScope(contest, mapOf("coding" to "hash"), "captured")
        fun submission(
            time: String?, at: Instant = start.plusSeconds(60), environment: String? = "captured",
            versionId: UUID = version, problem: String = "coding", hash: String = "hash", contestId: UUID = contest, processor: String = GPU,
        ) = AcceptedSubmission(UUID.randomUUID(), user, problem, contestId, hash, processor, time?.let(::BigDecimal), at, environment, problemVersionId = versionId)
        val early = submission("4", at = start)
        val later = submission("2", at = start.plusSeconds(300))
        val lastInstant = submission("1.5", at = end.minusNanos(1))
        val ignored = listOf(
            submission("0.01", at = start.minusNanos(1)), submission("0.02", at = end),
            submission("0.03", environment = "other"), submission("0.04", versionId = UUID.randomUUID()),
            submission("0.05", problem = "other"), submission("0.06", hash = "other"),
            submission("0.07", contestId = UUID.randomUUID()), submission("0.08", processor = "rtl"), submission(null),
        )
        given(repository.findWithStatusInContext(SubmissionStatus.ACCEPTED, contest.toString())).willReturn(ignored + early + later + lastInstant)

        assertThat(stats.duelBestScores(scope, version, GPU, start, end)).containsExactlyEntriesOf(mapOf(user to BigDecimal("1.5")))
    }

    private companion object {
        const val GPU = "gpu-cuda"
    }
}
