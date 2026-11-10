package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.problem.ProblemContext
import dev.gpucodeforces.backend.problem.ProblemListing
import dev.gpucodeforces.backend.problem.ProblemService
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserService
import dev.gpucodeforces.backend.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language
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
import java.util.UUID

@Tag("integration")
@Testcontainers
@SpringBootTest
@Transactional
class QueryScalingIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var plans: QueryPlans
    @Autowired lateinit var listing: ProblemListing
    @Autowired lateinit var problems: ProblemService
    @Autowired lateinit var contests: ContestService
    @Autowired lateinit var users: UserService
    @Autowired lateinit var userRepository: UserRepository

    @Test
    @AllowFullScan("users", "UPDATE gpucodeforces\\.users u SET rank", "Exact global ranks require ordering every rated user; unchanged ranks must not be written")
    fun `rank recomputation writes only changed ranks and preserves ties`() {
        val first = userRepository.saveAndFlush(User("rankfirst", "first@example.com", "hash").also { it.rating = 1500 })
        val tied = userRepository.saveAndFlush(User("ranktied", "tied@example.com", "hash").also { it.rating = 1500 })
        val last = userRepository.saveAndFlush(User("ranklast", "last@example.com", "hash").also { it.rating = 1200 })
        assertThat(userRepository.recomputeRanks()).isEqualTo(3)
        assertThat(userRepository.findById(first.id).orElseThrow().rank).isEqualTo(1)
        assertThat(userRepository.findById(tied.id).orElseThrow().rank).isEqualTo(1)
        assertThat(userRepository.findById(last.id).orElseThrow().rank).isEqualTo(3)
        assertThat(userRepository.recomputeRanks()).isZero()
    }

    @Test
    @AllowFullScan("problems", "FROM gpucodeforces\\.problems p", "Catalogue totals and tags inspect current ids; metadata pages are bounded and version history uses key lookups")
    fun `catalogue pages and single problem reads stay bounded as version history grows`() {
        @Language("SQL") val problemRows = """
            INSERT INTO gpucodeforces.problems (id, created_at)
            SELECT 'scale-' || n, now() FROM generate_series(1, 40) n
        """
        jdbc.execute(problemRows)
        @Language("SQL") val versionRows = """
            INSERT INTO gpucodeforces.problem_versions
                (id, problem_id, code, title, difficulty, tags, authors, draft, statement_markdown,
                 starter_code, judge_spec, content_hash, judge_hash, created_at)
            SELECT gen_random_uuid(), 'scale-' || p, lpad(p::text, 3, '0'), 'Scale ' || p, 'easy',
                   ARRAY['scaling'], ARRAY['author'], false, repeat('statement', 2000), 'code', '{}', h::text, 'hash', now()
            FROM generate_series(1, 40) p CROSS JOIN generate_series(0, 99) h
        """
        jdbc.execute(versionRows)
        for (n in 1..40) {
            @Language("SQL") val publish = """
                UPDATE gpucodeforces.problems SET current_version_id =
                    (SELECT id FROM gpucodeforces.problem_versions WHERE problem_id = ? AND content_hash = '0')
                WHERE id = ?
            """
            jdbc.update(publish, "scale-$n", "scale-$n")
        }
        jdbc.execute("ANALYZE gpucodeforces.problems")
        jdbc.execute("ANALYZE gpucodeforces.problem_versions")
        plans.assertRows("SELECT v\\.problem_id", 3) {
            val page = listing.page(ProblemContext.Practice, null, null, "Scale", "easy", "scaling", null, "code", "gpu", 10, 3)
            assertThat(page.total).isEqualTo(40)
            assertThat(page.items.map { it.problemId }).containsExactly("scale-11", "scale-12", "scale-13")
            assertThat(page.tags).contains("scaling")
        }
        plans.assertRows("judge_spec", 1) {
            plans.checkNatural(mapOf("problems" to "Forty current ids fit in one page; the growing version history must still use its primary key")) {
                assertThat(problems.problem("scale-17", ProblemContext.Practice, null).version.problemId).isEqualTo("scale-17")
            }
        }
    }

    @Test
    @AllowFullScan("contests", "from gpucodeforces\\.contests .*order by", "An unfiltered list intentionally returns all contests; filtered lists below have no exception in their scoped checks")
    @AllowFullScan("users", "lower\\(.*handle.*like", "This contest fixture has one author; selective user search is tested separately on 10000 users", maxTableRows = 1)
    fun `contest filters and ordering are applied before results reach the application`() {
        val author = UUID.randomUUID()
        @Language("SQL") val insertUser = "INSERT INTO gpucodeforces.users (id, handle, email, password_hash, created_at) VALUES (?, 'scaleauthor', 'scale@example.com', 'hash', now())"
        jdbc.update(insertUser, author)
        @Language("SQL") val contestRows = """
            INSERT INTO gpucodeforces.contests (id, title, start_at, end_at, gpu)
            SELECT gen_random_uuid(), 'archive ' || n, now() - n * interval '1 day', now() - n * interval '1 day' + interval '1 hour', 'gpu'
            FROM generate_series(1, 1000) n
        """
        jdbc.execute(contestRows)
        val upcoming = UUID.randomUUID()
        @Language("SQL") val insertUpcoming = "INSERT INTO gpucodeforces.contests (id, title, start_at, end_at, gpu) VALUES (?, 'future', now() + interval '1 day', now() + interval '2 days', 'gpu')"
        jdbc.update(insertUpcoming, upcoming)
        @Language("SQL") val addAuthor = "INSERT INTO gpucodeforces.contest_authors (contest_id, user_id) VALUES (?, ?)"
        jdbc.update(addAuthor, upcoming, author)
        jdbc.execute("ANALYZE gpucodeforces.contests")
        val viewer = User(author, "scaleauthor", "scale@example.com", "hash")
        plans.assertRows("from gpucodeforces\\.contests", 1) {
            plans.check {
                assertThat(contests.list(null, "upcoming", true, viewer).map { it.id }).containsExactly(upcoming)
            }
        }
        plans.assertRows("from gpucodeforces\\.contests", 1) {
            plans.check(mapOf("users" to "Only the single author may be scanned; the 1001 contests must be filtered by index")) {
                assertThat(contests.list("scaleauthor", "upcoming", false, null).map { it.id }).containsExactly(upcoming)
            }
        }
        val all = contests.list(null, null, false, null)
        assertThat(all.map { it.startAt }).isSorted()
        val finished = contests.list(null, "finished", false, null)
        assertThat(finished).hasSize(1000)
        assertThat(finished.map { it.startAt }).isSortedAccordingTo(reverseOrder())
    }

    @Test
    @AllowFullScan("users", "select count\\(.*from gpucodeforces\\.users", "An exact total without a search must count the complete user population")
    @AllowFullScan("users", "strpos\\(lower\\(handle\\)", "One- and two-character substrings do not provide useful trigrams")
    fun `user pages and selective substring search have separate query paths`() {
        @Language("SQL") val userRows = """
            INSERT INTO gpucodeforces.users (id, handle, email, password_hash, rating, rank, created_at)
            SELECT gen_random_uuid(), 'user' || lpad(n::text, 6, '0'), n || '@example.com', 'hash', n, 10001 - n, now()
            FROM generate_series(1, 10000) n
        """
        jdbc.execute(userRows)
        @Language("SQL") val flushTrigrams = "SELECT gin_clean_pending_list('gpucodeforces.users_handle_trgm_idx'::regclass)"
        jdbc.queryForObject(flushTrigrams, Long::class.java)
        jdbc.execute("ANALYZE gpucodeforces.users")
        for (rated in listOf(false, true)) {
            plans.assertRows("SELECT \\* FROM gpucodeforces\\.users", 5) {
                assertThat(users.search(null, rated, 0, 5).items).hasSize(5)
            }
            plans.checkNatural {
                assertThat(users.search("009876", rated, 0, 5).items.map { it.handle }).containsExactly("user009876")
            }
            assertThat(users.search("u", rated, 0, 5).items).hasSize(5)
        }
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
