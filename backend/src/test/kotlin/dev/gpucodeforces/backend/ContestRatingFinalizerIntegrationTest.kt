package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.GetUserProfile200Response
import dev.gpucodeforces.backend.api.model.ListUsers200Response
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistration
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.rating.RatingFinalizationService
import dev.gpucodeforces.backend.rating.RatingHistoryRepository
import dev.gpucodeforces.backend.submission.Submission
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRating
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.judge.api.Processors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContestRatingFinalizerIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    @Autowired
    lateinit var contestRepository: ContestRepository

    @Autowired
    lateinit var contestRegistrationRepository: ContestRegistrationRepository

    @Autowired
    lateinit var contestProblemRepository: ContestProblemRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var ratingFinalizationService: RatingFinalizationService

    @Autowired
    lateinit var ratingHistoryRepository: RatingHistoryRepository

    @Autowired
    lateinit var submissionRepository: SubmissionRepository

    @Autowired
    lateinit var problemCatalog: ProblemCatalog

    private fun pastContest(title: String, endedAgo: Duration, processor: String = "gpu-cuda"): Contest {
        val now = Instant.now()
        // Built directly via the repository: ContestService.create rejects contests that start in the past.
        return contestRepository.save(
            Contest(UUID.randomUUID(), title, now.minus(endedAgo).minus(2, ChronoUnit.HOURS), now.minus(endedAgo), processor),
        )
    }

    private fun submit(contest: Contest, user: User, status: SubmissionStatus, minutesAfterStart: Long) {
        val version = problemCatalog.currentVersion("vector_add")!!
        val submission = Submission(
            id = UUID.randomUUID(),
            userId = user.id,
            problemId = version.problemId,
            problemVersionId = version.id,
            contestId = contest.id,
            code = "code",
            environmentMarkdown = "",
            judgeHash = version.judgeHash,
            processor = contest.processor,
            language = if (contest.processor == "rtl") "systemverilog" else "cuda",
            previousBestScore = null,
            createdAt = contest.startAt.plus(minutesAfterStart, ChronoUnit.MINUTES),
        )
        submission.status = status
        if (status == SubmissionStatus.ACCEPTED) {
            submission.score = BigDecimal.ONE
            submission.scoreType = Processors.get(contest.processor).scoreType
        }
        submissionRepository.save(submission)
    }

    @Test
    fun `only participants who submitted are rated, by the contest standings, once`() {
        val alice = userRepository.save(User("alice_cuda", "alice_cuda@example.com", "hash"))
        val bob = userRepository.save(User("bob_cuda", "bob_cuda@example.com", "hash"))
        val carol = userRepository.save(User("carol_cuda", "carol_cuda@example.com", "hash"))
        val contest = pastContest("Прошедший контест", Duration.ofHours(1))
        listOf(alice, bob, carol).forEach { contestRegistrationRepository.save(ContestRegistration(contest.id, it.id)) }
        submit(contest, alice, SubmissionStatus.ACCEPTED, 10)
        submit(contest, bob, SubmissionStatus.WRONG_ANSWER, 5)

        assertThat(ratingFinalizationService.finalize(contest.id, Instant.now())).isTrue()

        val aliceHistory = ratingHistoryRepository.findByIdUserId(alice.id).single()
        assertThat(aliceHistory.id.contestId).isEqualTo(contest.id)
        assertThat(aliceHistory.rank).isEqualTo(1)
        assertThat(aliceHistory.ratingDelta).isPositive()
        val bobHistory = ratingHistoryRepository.findByIdUserId(bob.id).single()
        assertThat(bobHistory.rank).isEqualTo(2)
        assertThat(bobHistory.ratingDelta).isNegative()
        // Carol registered but never submitted: she took no part and keeps no rating.
        assertThat(ratingHistoryRepository.findByIdUserId(carol.id)).isEmpty()
        assertThat(userRepository.findById(carol.id).orElseThrow().ratings).isEmpty()

        val aliceRating = userRepository.findById(alice.id).orElseThrow().ratings.getValue("gpu-cuda")
        assertThat(aliceRating.rating).isEqualTo(aliceHistory.ratingAfter)
        // Rank among all users rated on the processor; another test may have rated someone higher.
        assertThat(aliceRating.rank).isNotNull()
        assertThat(aliceRating.ratingContestId).isEqualTo(contest.id)
        assertThat(aliceHistory.processor).isEqualTo("gpu-cuda")
        assertThat(contestRepository.findById(contest.id).orElseThrow().ratingAppliedAt).isNotNull()

        // Re-running is a no-op: already applied, no duplicate rating_history rows.
        assertThat(ratingFinalizationService.finalize(contest.id, Instant.now())).isTrue()
        assertThat(ratingHistoryRepository.findByIdUserId(alice.id)).hasSize(1)

        // The profile endpoint surfaces this contest via rating_history.
        val profile = client.get().uri("/users/${alice.id}")
            .exchange().expectStatus().isOk
            .expectBody(GetUserProfile200Response::class.java)
            .returnResult().responseBody!!
        assertThat(profile.contests.map { it.id }).containsExactly(contest.id.toString())
        assertThat(profile.contests[0].ratingDelta).isEqualTo(aliceHistory.ratingDelta)
        assertThat(profile.contests[0].processor).isEqualTo("gpu-cuda")
        assertThat(profile.user.ratings.getValue("gpu-cuda").rating).isEqualTo(aliceHistory.ratingAfter)
    }

    @Test
    fun `ratings of two processors do not affect each other`() {
        val frank = userRepository.save(User("frank_rtl", "frank_rtl@example.com", "hash"))
        val gina = userRepository.save(User("gina_rtl", "gina_rtl@example.com", "hash"))
        val onGpu = pastContest("На GPU", Duration.ofHours(3))
        val onRtl = pastContest("Своё железо", Duration.ofHours(2), processor = "rtl")
        for (contest in listOf(onGpu, onRtl)) listOf(frank, gina).forEach { contestRegistrationRepository.save(ContestRegistration(contest.id, it.id)) }
        submit(onGpu, frank, SubmissionStatus.ACCEPTED, 10)
        submit(onGpu, gina, SubmissionStatus.WRONG_ANSWER, 10)
        submit(onRtl, gina, SubmissionStatus.ACCEPTED, 10)
        submit(onRtl, frank, SubmissionStatus.WRONG_ANSWER, 10)

        assertThat(ratingFinalizationService.finalize(onGpu.id, Instant.now())).isTrue()
        assertThat(ratingFinalizationService.finalize(onRtl.id, Instant.now())).isTrue()

        val frankRatings = userRepository.findById(frank.id).orElseThrow().ratings
        val ginaRatings = userRepository.findById(gina.id).orElseThrow().ratings
        assertThat(frankRatings.getValue("gpu-cuda").rating).isGreaterThan(frankRatings.getValue("rtl").rating)
        assertThat(ginaRatings.getValue("rtl").rating).isGreaterThan(ginaRatings.getValue("gpu-cuda").rating)
        // Each contest moved only its own rating: the first rated contest of each processor starts from scratch.
        assertThat(frankRatings.getValue("gpu-cuda").rating).isEqualTo(ginaRatings.getValue("rtl").rating)
        assertThat(frankRatings.getValue("rtl").ratingContestId).isEqualTo(onRtl.id)
        assertThat(ratingHistoryRepository.findByIdUserId(gina.id).associate { it.id.contestId to it.processor })
            .isEqualTo(mapOf(onGpu.id to "gpu-cuda", onRtl.id to "rtl"))

        val rtlRating = client.get().uri("/users?sort=rating&processor=rtl&q=rtl").exchange().expectStatus().isOk
            .expectBody(ListUsers200Response::class.java).returnResult().responseBody!!
        assertThat(rtlRating.items.map { it.handle }).containsExactly("gina_rtl", "frank_rtl")
        client.get().uri("/users?sort=rating").exchange().expectStatus().isBadRequest
    }

    @Test
    fun `rating waits for verdicts of submissions sent before the end`() {
        val dave = userRepository.save(User("dave_cuda", "dave_cuda@example.com", "hash"))
        val erin = userRepository.save(User("erin_cuda", "erin_cuda@example.com", "hash"))
        val contest = pastContest("Только что закончился", Duration.ofMinutes(1))
        listOf(dave, erin).forEach { contestRegistrationRepository.save(ContestRegistration(contest.id, it.id)) }
        submit(contest, dave, SubmissionStatus.ACCEPTED, 30)
        submit(contest, erin, SubmissionStatus.CHECKING, 119)

        assertThat(ratingFinalizationService.finalize(contest.id, Instant.now())).isFalse()
        assertThat(contestRepository.findById(contest.id).orElseThrow().ratingAppliedAt).isNull()
        assertThat(ratingHistoryRepository.findByIdUserId(dave.id)).isEmpty()

        // A verdict that never comes does not block the rating forever.
        val afterGrace = contest.endAt.plus(RatingFinalizationService.VERDICT_GRACE).plusSeconds(1)
        assertThat(ratingFinalizationService.finalize(contest.id, afterGrace)).isTrue()
        assertThat(ratingHistoryRepository.findByIdUserId(dave.id)).hasSize(1)
    }

    @Test
    fun `surrender rates both registered duel participants even when neither submitted code`() {
        fun player(): User {
            val handle = "forfeit_${UUID.randomUUID().toString().take(8)}"
            return userRepository.save(User(handle, "$handle@example.com", "hash").apply { ratings["gpu-cuda"] = UserRating(1800) })
        }
        val loser = player()
        val winner = player()
        val now = Instant.now()
        val contest = contestRepository.save(Contest(
            id = UUID.randomUUID(), title = "Дуэль со сдачей", startAt = now.minusSeconds(3600), endAt = now.plusSeconds(3600),
            processor = "rtl", format = "duel", challengerId = loser.id, opponentId = winner.id,
            benchmarkEnvironmentKey = "captured-environment",
        ))
        val version = problemCatalog.currentVersion("vector_add")!!
        contestProblemRepository.save(ContestProblem(contest.id, version.problemId, 0, version.id))
        listOf(loser, winner).forEach { contestRegistrationRepository.save(ContestRegistration(contest.id, it.id)) }
        contest.surrenderedUserId = loser.id
        contest.endAt = now.minusSeconds(1)
        contestRepository.save(contest)

        assertThat(ratingFinalizationService.finalize(contest.id, now)).isTrue()

        val winnerHistory = ratingHistoryRepository.findByIdUserId(winner.id).single()
        val loserHistory = ratingHistoryRepository.findByIdUserId(loser.id).single()
        assertThat(winnerHistory.rank).isEqualTo(1)
        assertThat(winnerHistory.ratingDelta).isPositive()
        assertThat(loserHistory.rank).isEqualTo(2)
        assertThat(loserHistory.ratingDelta).isNegative()
        assertThat(winnerHistory.processor).isEqualTo("rtl")
        assertThat(loserHistory.processor).isEqualTo("rtl")
        assertThat(userRepository.findById(winner.id).orElseThrow().ratings["rtl"]!!.rating).isEqualTo(winnerHistory.ratingAfter)
        assertThat(userRepository.findById(loser.id).orElseThrow().ratings["rtl"]!!.rating).isEqualTo(loserHistory.ratingAfter)
        assertThat(userRepository.findById(winner.id).orElseThrow().ratings["gpu-cuda"]!!.rating).isEqualTo(1800)
        assertThat(userRepository.findById(loser.id).orElseThrow().ratings["gpu-cuda"]!!.rating).isEqualTo(1800)
        assertThat(ratingFinalizationService.finalize(contest.id, now)).isTrue()
        assertThat(ratingHistoryRepository.findByIdUserId(winner.id)).hasSize(1)
        assertThat(ratingHistoryRepository.findByIdUserId(loser.id)).hasSize(1)
    }

    companion object {
        private val problems = TestProblems.withRtl()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.problems.directory") { problems.toString() }
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
