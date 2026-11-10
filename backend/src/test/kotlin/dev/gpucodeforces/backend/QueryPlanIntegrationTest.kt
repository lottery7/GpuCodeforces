package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.auth.SessionRepository
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
import dev.gpucodeforces.backend.rating.RatingHistoryRepository
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatsRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import dev.gpucodeforces.backend.user.UserRepository
import org.intellij.lang.annotations.Language
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import javax.sql.DataSource

@Tag("integration")
@Testcontainers
@SpringBootTest
@Transactional
class QueryPlanIntegrationTest {
    @Autowired lateinit var plans: QueryPlans
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var contests: ContestRepository
    @Autowired lateinit var registrations: ContestRegistrationRepository
    @Autowired lateinit var contestProblems: ContestProblemRepository
    @Autowired lateinit var problems: ProblemRepository
    @Autowired lateinit var versions: ProblemVersionRepository
    @Autowired lateinit var history: RatingHistoryRepository
    @Autowired lateinit var submissions: SubmissionRepository
    @Autowired lateinit var stats: SubmissionStatsRepository
    @Autowired lateinit var dataSource: DataSource

    private val id = UUID.randomUUID()
    private val now = Instant.now()

    @Test
    fun `authentication and profile lookups have index paths`() {
        plans.check { users.findByHandleIgnoreCaseOrEmailIgnoreCase("probe", "probe@example.com") }
        plans.check { users.existsByHandleIgnoreCase("probe") }
        plans.check { users.existsByEmailIgnoreCase("probe@example.com") }
        plans.check { sessions.findByTokenAndExpiresAtAfter(id, now) }
        plans.check { users.findById(id) }
        plans.check { users.findAllById(listOf(id)) }
        plans.check { history.findByIdUserId(id) }
    }

    @Test
    fun `contest and problem lookups have index paths`() {
        plans.check { contests.findById(id) }
        plans.check { registrations.existsByIdContestIdAndIdUserId(id, id) }
        plans.check { registrations.findByIdContestId(id) }
        plans.check { contestProblems.findByIdContestIdOrderByPosition(id) }
        plans.check { contestProblems.deleteByIdContestId(id) }
        plans.check { problems.findById("probe") }
        plans.check { versions.findById(id) }
        plans.check { versions.findByProblemIdAndContentHash("probe", "hash") }
        plans.check { contestProblems.repinUpcoming("probe", id) }
    }

    @Test
    fun `submission history statistics and recovery have index paths`() {
        plans.check { submissions.findById(id) }
        plans.check { submissions.findForUpdate(id) }
        plans.check { submissions.countByUserIdAndStatusIn(id, SubmissionStatus.PENDING) }
        plans.check { submissions.findUnsent(SubmissionStatus.QUEUED, now) }
        plans.check { submissions.findStuck(SubmissionStatus.PENDING, now) }
        plans.check { submissions.search(id, null, null, false, null) }
        plans.check { submissions.search(id, "probe", SubmissionStatus.ACCEPTED, true, null) }
        plans.check { submissions.search(id, "probe", SubmissionStatus.ACCEPTED, false, id) }
        plans.check { stats.findWithStatus(SubmissionStatus.ACCEPTED, listOf("probe", "other")) }
        plans.check { stats.findHeads(id, listOf("probe", "other")) }
        plans.check { stats.countProblemsWithStatus(id, SubmissionStatus.ACCEPTED) }
    }

    @Test
    fun `bulk operations explicitly allow scans of their complete input`() {
        plans.check(mapOf("contests" to "Listing and rating finalization currently read every contest")) {
            contests.findAll()
        }
        plans.check(mapOf("problems" to "Bank synchronization compares active ids with the imported set")) {
            problems.findActiveIds()
        }
        plans.check(mapOf("users" to "Rank recomputation updates every rated user and orders the entire rated population")) {
            users.recomputeRanks()
        }
    }

    @Test
    fun `substring search uses trigrams while short patterns have explicit exceptions`() {
        plans.check { users.searchByHandle("probe", 20, 0) }
        plans.check { users.countByHandle("probe") }
        plans.check { users.searchRated("probe", 20, 0) }
        plans.check { users.countRated("probe") }
        plans.check { users.pageByHandle(20, 0) }
        plans.check { users.pageRated(20, 0) }
        val short = mapOf("users" to "One- and two-character substrings cannot narrow a trigram index")
        plans.check(short) { users.searchShortByHandle("p", 20, 0) }
        plans.check(short) { users.countShortByHandle("p") }
        plans.check(short) { users.searchShortRated("p", 20, 0) }
        plans.check(short) { users.countShortRated("p") }
        val total = mapOf("users" to "An exact unfiltered total must count the whole population")
        plans.check(total) { users.count() }
        plans.check(total) { users.countByRatingIsNotNull() }
    }

    @Test
    fun `guard rejects an unindexed predicate and includes SQL and plan`() {
        assertThatThrownBy {
            plans.check {
                @Language("SQL") val unindexedQuery = "SELECT * FROM gpucodeforces.users WHERE password_hash = ?"
                jdbc.queryForList(unindexedQuery, "probe")
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unexpected full scan of [users]")
            .hasMessageContaining("password_hash = ?")
            .hasMessageContaining("Seq Scan")
    }

    @Test
    fun `guard rejects a full index traversal and a result exceeding its row budget`() {
        for (bitmap in listOf(true, false)) {
            jdbc.execute(if (bitmap) "SET LOCAL enable_bitmapscan = on" else "SET LOCAL enable_bitmapscan = off")
            assertThatThrownBy {
                plans.check {
                    @Language("SQL") val fullIndex = "SELECT id FROM gpucodeforces.users ORDER BY id"
                    jdbc.queryForList(fullIndex)
                }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Unexpected full scan of [users]")
                .hasMessageContaining("Index")
        }
        jdbc.execute("SET LOCAL enable_bitmapscan = on")
        assertThatThrownBy {
            plans.assertRows("generate_series", 2) {
                @Language("SQL") val tooManyRows = "SELECT * FROM generate_series(1, 3)"
                jdbc.queryForList(tooManyRows)
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Read 3 rows, budget is 2")
    }

    @Test
    @AllowFullScan("users", "password_hash =", "Guard self-test deliberately issues unindexed queries on a worker thread")
    fun `guard retains worker thread and batch violations until test completion`() {
        val guard = QueryPlans()
        guard.begin(emptyList())
        Executors.newSingleThreadExecutor().use { executor ->
            executor.submit {
                guard.connection(dataSource.connection).use { connection ->
                    connection.createStatement().use { statement ->
                        @Language("SQL") val select = "SELECT * FROM gpucodeforces.users WHERE password_hash = 'probe'"
                        statement.executeQuery(select).close()
                    }
                    @Language("SQL") val update = "UPDATE gpucodeforces.users SET rating = 0 WHERE password_hash = ?"
                    connection.prepareStatement(update).use { statement ->
                        statement.setString(1, "probe")
                        statement.addBatch()
                        statement.executeBatch()
                    }
                }
            }.get()
        }
        // No exception needed to escape the worker: the test itself must fail even if a server or
        // message consumer catches its own exceptions and returns a successful response.
        assertThatThrownBy { guard.finish() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unexpected full scan of [users]")
            .hasMessageContaining("SELECT *")
            .hasMessageContaining("UPDATE gpucodeforces.users")
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
