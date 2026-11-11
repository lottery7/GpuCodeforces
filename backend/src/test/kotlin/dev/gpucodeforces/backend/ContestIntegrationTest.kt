package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistration
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import dev.gpucodeforces.judge.api.ScoreType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import dev.gpucodeforces.backend.contest.Contest as StoredContest
import dev.gpucodeforces.backend.submission.Submission as StoredSubmission

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContestIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var contests: ContestRepository

    @Autowired
    lateinit var contestProblems: ContestProblemRepository

    @Autowired
    lateinit var registrations: ContestRegistrationRepository

    @Autowired
    lateinit var catalog: ProblemCatalog

    @Autowired
    lateinit var versions: ProblemVersionRepository

    @Autowired
    lateinit var submissions: SubmissionRepository

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun registerAndGetCookie(handle: String): Pair<User, String> {
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange()
            .expectStatus().isCreated
            .expectBody(User::class.java)
            .returnResult()
        val cookie = result.responseCookies.getFirst("session")!!.value
        return result.responseBody!! to cookie
    }

    private fun player() = registerAndGetCookie("rank_${UUID.randomUUID().toString().take(8)}")

    private fun duel(first: User, second: User): Pair<StoredContest, ProblemVersion> {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val contest = contests.save(StoredContest(
            id = UUID.randomUUID(), title = "Дуэль", startAt = now.minusSeconds(600), endAt = now.plusSeconds(3600),
            processor = "gpu-cuda", format = "duel", challengerId = UUID.fromString(first.id),
            opponentId = UUID.fromString(second.id), benchmarkEnvironmentKey = "captured-environment",
        ))
        val version = catalog.currentVersion("vector_add")!!
        contestProblems.save(ContestProblem(contest.id, version.problemId, 0, version.id))
        listOf(first, second).forEach { registrations.save(ContestRegistration(contest.id, UUID.fromString(it.id))) }
        return contest to version
    }

    private fun accepted(
        contest: StoredContest, version: ProblemVersion, user: User, time: String?, at: Instant,
        environment: String = "captured-environment", hash: String = version.judgeHash,
        status: SubmissionStatus = SubmissionStatus.ACCEPTED,
    ): StoredSubmission =
        submissions.save(StoredSubmission(
            id = UUID.randomUUID(), userId = UUID.fromString(user.id), problemId = version.problemId,
            problemVersionId = version.id, contestId = contest.id, code = "code", environmentMarkdown = "",
            judgeHash = hash, processor = "gpu-cuda", language = "cuda", previousBestScore = null, createdAt = at,
        ).apply {
            this.status = status
            score = time?.let(::BigDecimal)
            scoreType = score?.let { ScoreType.TIME_MS }
            benchmarkEnvironmentKey = environment
        })

    private fun standings(id: UUID): List<GetContestStandings200ResponseInner> = client.get().uri("/contests/$id/standings")
        .exchange().expectStatus().isOk.expectBody(Array<GetContestStandings200ResponseInner>::class.java)
        .returnResult().responseBody!!.toList()

    @Test
    fun `create, visibility before start, author-only update, registration and empty standings`() {
        val (author, authorCookie) = registerAndGetCookie("contest_author")
        val (_, outsiderCookie) = registerAndGetCookie("contest_outsider")

        val startAt = Instant.now().plus(1, ChronoUnit.DAYS)
        val endAt = startAt.plus(2, ChronoUnit.HOURS)
        val input = ContestInput(
            "Весенний раунд",
            startAt.atOffset(ZoneOffset.UTC),
            endAt.atOffset(ZoneOffset.UTC),
            setOf(author.id),
            setOf("vector_add", "matmul_host"),
            "gpu-cuda",
        )

        val created = client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", authorCookie)
            .body(input)
            .exchange()
            .expectStatus().isCreated
            .expectBody(Contest::class.java)
            .returnResult()
            .responseBody!!
        assertThat(created.problemIds).containsExactly("vector_add", "matmul_host")
        assertThat(created.registered).isFalse()
        assertThat(created.processor).isEqualTo("gpu-cuda")
        assertThat(client.get().uri("/contests?processor=gpu-cuda").exchange().expectBody(Array<Contest>::class.java).returnResult().responseBody!!.map { it.id })
            .contains(created.id)
        assertThat(client.get().uri("/contests?processor=rtl").exchange().expectBody(Array<Contest>::class.java).returnResult().responseBody!!).isEmpty()
        client.get().uri("/contests?processor=nope").exchange().expectStatus().isBadRequest

        // Anonymous visitors see no problem ids before the contest starts.
        val anonymousView = client.get().uri("/contests/${created.id}")
            .exchange().expectStatus().isOk.expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(anonymousView.problemIds).isEmpty()
        assertThat(anonymousView.problemCount).isEqualTo(2)

        // The author still sees the full ordered list before start.
        val authorView = client.get().uri("/contests/${created.id}")
            .cookie("session", authorCookie)
            .exchange().expectStatus().isOk.expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(authorView.problemIds).containsExactly("vector_add", "matmul_host")

        // A non-author cannot update the contest.
        client.put().uri("/contests/${created.id}").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", outsiderCookie)
            .body(input)
            .exchange().expectStatus().isForbidden

        // The author can update it, e.g. reorder problems.
        val reordered = input.problemIds(linkedSetOf("matmul_host", "vector_add"))
        val updated = client.put().uri("/contests/${created.id}").contentType(MediaType.APPLICATION_JSON)
            .cookie("session", authorCookie)
            .body(reordered)
            .exchange().expectStatus().isOk.expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(updated.problemIds).containsExactly("matmul_host", "vector_add")

        // mine=true only returns contests authored by the caller.
        val mine = client.get().uri("/contests?mine=true")
            .cookie("session", authorCookie)
            .exchange().expectStatus().isOk
            .expectBody(Array<Contest>::class.java).returnResult().responseBody!!
        assertThat(mine.map { it.id }).containsExactly(created.id)

        // Registration is available to anyone logged in, not just authors, and repeating it is a no-op.
        client.put().uri("/contests/${created.id}/registration").cookie("session", outsiderCookie)
            .exchange().expectStatus().isNoContent
        client.put().uri("/contests/${created.id}/registration").cookie("session", outsiderCookie)
            .exchange().expectStatus().isNoContent

        // No accepted submissions exist anywhere yet, and the contest hasn't started: standings are empty.
        val standings = client.get().uri("/contests/${created.id}/standings")
            .exchange().expectStatus().isOk
            .expectBody(Array<GetContestStandings200ResponseInner>::class.java).returnResult().responseBody!!
        assertThat(standings).isEmpty()
    }

    @Test
    fun `duel standings use later faster code only in the pinned version time window and environment`() {
        val (first, _) = player()
        val (second, _) = player()
        val (contest, version) = duel(first, second)
        accepted(contest, version, first, "4", contest.startAt.plusSeconds(10))
        accepted(contest, version, first, "2", contest.startAt.plusSeconds(120))
        accepted(contest, version, second, "1.5", contest.startAt.plusSeconds(60))
        accepted(contest, version, first, "0.01", contest.startAt.minusMillis(1))
        accepted(contest, version, first, "0.02", contest.endAt)
        accepted(contest, version, first, "0.03", contest.startAt.plusSeconds(30), environment = "other-environment")
        accepted(contest, version, first, "0.04", contest.startAt.plusSeconds(40), hash = "other-judge")
        accepted(contest, version, first, "0.05", contest.startAt.plusSeconds(50), status = SubmissionStatus.WRONG_ANSWER)
        val otherVersion = versions.save(ProblemVersion(
            id = UUID.randomUUID(), problemId = version.problemId, code = version.code, title = version.title,
            difficulty = version.difficulty, tags = version.tags, authors = version.authors, draft = false,
            statementMarkdown = "Другое условие", processors = version.processors, starters = version.starters, judgeSpec = version.judgeSpec,
            contentHash = UUID.randomUUID().toString(), judgeHash = version.judgeHash, revision = null,
        ))
        accepted(contest, otherVersion, first, "0.06", contest.startAt.plusSeconds(60))

        val rows = standings(contest.id)

        assertThat(rows.map { it.user.id }).containsExactly(second.id, first.id)
        assertThat(rows.map { it.rank }).containsExactly(1, 2)
        assertThat(rows.first().bestScore!!.value).isEqualByComparingTo("1.5")
        assertThat(rows.last().bestScore!!.value).isEqualByComparingTo("2")
        assertThat(rows.all { it.solved == 1 && it.totalSeconds == 0 && it.times.isEmpty() && it.surrendered == false }).isTrue()
        val standard = contests.save(StoredContest("Обычный контест", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), "gpu-cuda"))
        val list = client.get().uri("/contests").exchange().expectStatus().isOk.expectBody(Array<Contest>::class.java)
            .returnResult().responseBody!!
        assertThat(list.map { it.id }).contains(standard.id.toString()).doesNotContain(contest.id.toString())
        client.get().uri("/contests/${contest.id}").exchange().expectStatus().isOk
    }

    @Test
    fun `surrender is participant only idempotent and gives the opponent victory without accepted code`() {
        val (first, firstCookie) = player()
        val (second, secondCookie) = player()
        val (_, outsiderCookie) = player()
        val (contest, version) = duel(first, second)
        accepted(contest, version, first, "1", contest.startAt.plusSeconds(10))
        accepted(contest, version, second, null, contest.startAt.plusSeconds(20), status = SubmissionStatus.CHECKING)

        client.post().uri("/contests/${contest.id}/surrender").exchange().expectStatus().isUnauthorized
        client.post().uri("/contests/${contest.id}/surrender").cookie("session", outsiderCookie)
            .exchange().expectStatus().isForbidden
        repeat(2) {
            client.post().uri("/contests/${contest.id}/surrender").cookie("session", firstCookie)
                .exchange().expectStatus().isNoContent
        }
        client.post().uri("/contests/${contest.id}/surrender").cookie("session", secondCookie)
            .exchange().expectStatus().isBadRequest

        val detail = client.get().uri("/contests/${contest.id}").exchange().expectStatus().isOk
            .expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(detail.surrenderedUserId).isEqualTo(first.id)
        assertThat(detail.resultsPending).isFalse()
        assertThat(detail.endAt.toInstant()).isBeforeOrEqualTo(Instant.now())
        val rows = standings(contest.id)
        assertThat(rows.map { it.user.id }).containsExactly(second.id, first.id)
        assertThat(rows.map { it.rank }).containsExactly(1, 2)
        assertThat(rows.first().bestScore).isNull()
        assertThat(rows.first().solved).isZero()
        assertThat(rows.last().bestScore!!.value).isEqualByComparingTo("1")
        assertThat(rows.last().surrendered).isTrue()
    }

    @Test
    fun `finished duels expose pending verdicts until the submitted solution is judged`() {
        val (first, _) = player()
        val (second, _) = player()
        val (contest, version) = duel(first, second)
        val pending = accepted(contest, version, second, null, contest.startAt.plusSeconds(30), status = SubmissionStatus.CHECKING)
        contest.endAt = Instant.now().minusSeconds(1)
        contests.save(contest)

        val waiting = client.get().uri("/contests/${contest.id}").exchange().expectStatus().isOk
            .expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(waiting.resultsPending).isTrue()

        pending.status = SubmissionStatus.ACCEPTED
        pending.score = BigDecimal("1.5")
        pending.scoreType = ScoreType.TIME_MS
        submissions.save(pending)

        val finished = client.get().uri("/contests/${contest.id}").exchange().expectStatus().isOk
            .expectBody(Contest::class.java).returnResult().responseBody!!
        assertThat(finished.resultsPending).isFalse()
        val rows = standings(contest.id)
        assertThat(rows.first().user.id).isEqualTo(second.id)
        assertThat(rows.first().bestScore!!.value).isEqualByComparingTo("1.5")
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
