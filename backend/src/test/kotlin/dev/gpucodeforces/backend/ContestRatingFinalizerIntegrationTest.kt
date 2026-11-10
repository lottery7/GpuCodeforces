package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.GetUserProfile200Response
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestRegistration
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.rating.RatingFinalizationService
import dev.gpucodeforces.backend.rating.RatingHistoryRepository
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
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
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var ratingFinalizationService: RatingFinalizationService

    @Autowired
    lateinit var ratingHistoryRepository: RatingHistoryRepository

    @Test
    @AllowFullScan("users", "UPDATE gpucodeforces\\.users u SET rank", "Finalization recomputes ranks for the entire rated population")
    fun `finalizing a past contest ties registered participants at the default rating, once`() {
        val alice = userRepository.save(User("alice_cuda", "alice_cuda@example.com", "hash"))
        val bob = userRepository.save(User("bob_cuda", "bob_cuda@example.com", "hash"))
        val now = Instant.now()
        // Built directly via the repository: ContestService.create rejects contests that start in the past.
        val contest = contestRepository.save(
            Contest(
                id = UUID.randomUUID(),
                title = "Прошедший контест",
                startAt = now.minus(2, ChronoUnit.HOURS),
                endAt = now.minus(1, ChronoUnit.HOURS),
                gpu = "NVIDIA GeForce RTX 4090",
            ),
        )
        contestRegistrationRepository.save(ContestRegistration(contest.id, alice.id))
        contestRegistrationRepository.save(ContestRegistration(contest.id, bob.id))

        ratingFinalizationService.finalize(contest.id, Instant.now())

        // Standings tie everyone at rank 1 until the submissions module lands (step 3 is still blocked on it),
        // so two unrated participants tied for first settle on the default rating with zero delta.
        val aliceHistory = ratingHistoryRepository.findByIdUserId(alice.id).single()
        assertThat(aliceHistory.id.contestId).isEqualTo(contest.id)
        assertThat(aliceHistory.rank).isEqualTo(1)
        assertThat(aliceHistory.ratingDelta).isEqualTo(0)
        assertThat(aliceHistory.ratingAfter).isEqualTo(1500)

        val reloadedAlice = userRepository.findById(alice.id).orElseThrow()
        assertThat(reloadedAlice.rating).isEqualTo(1500)
        assertThat(reloadedAlice.rank).isEqualTo(1)
        assertThat(reloadedAlice.ratingContestId).isEqualTo(contest.id)

        assertThat(contestRepository.findById(contest.id).orElseThrow().ratingAppliedAt).isNotNull()

        // Re-running is a no-op: already applied, no duplicate rating_history rows.
        ratingFinalizationService.finalize(contest.id, Instant.now())
        assertThat(ratingHistoryRepository.findByIdUserId(alice.id)).hasSize(1)

        // The profile endpoint surfaces this contest via rating_history.
        val profile = client.get().uri("/users/${alice.id}")
            .exchange().expectStatus().isOk
            .expectBody(GetUserProfile200Response::class.java)
            .returnResult().responseBody!!
        assertThat(profile.contests).hasSize(1)
        assertThat(profile.contests[0].id).isEqualTo(contest.id.toString())
        assertThat(profile.contests[0].ratingDelta).isEqualTo(0)
        assertThat(profile.contests[0].ratingAfter).isEqualTo(1500)
        assertThat(profile.user.rating).isEqualTo(1500)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
