package dev.gpucodeforces.backend

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal

/** V14 on a database with data from before processors: everything becomes gpu-cuda and nothing is lost. */
@Tag("integration")
@Testcontainers
class ProcessorsMigrationIntegrationTest {
    private val dataSource by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
    private val jdbc by lazy { JdbcTemplate(dataSource) }

    private fun migrateTo(version: String) {
        Flyway.configure().dataSource(dataSource).defaultSchema("public").target(version).load().migrate()
    }

    @Test
    fun `existing submissions, contests, problems and ratings move to gpu-cuda`() {
        migrateTo("13")
        jdbc.execute(BEFORE)

        migrateTo("14")

        val submission = jdbc.queryForMap("SELECT processor, language, score, score_type, previous_best_score, metrics::text AS metrics FROM gpucodeforces.submissions WHERE id = '00000000-0000-0000-0000-000000000005'")
        assertThat(submission["processor"]).isEqualTo("gpu-cuda")
        assertThat(submission["language"]).isEqualTo("cuda")
        assertThat(submission["score"] as BigDecimal).isEqualByComparingTo("1.25")
        assertThat(submission["score_type"]).isEqualTo("TIME_MS")
        assertThat(submission["previous_best_score"] as BigDecimal).isEqualByComparingTo("2.5")
        assertThat(submission["metrics"]).isEqualTo("{}")
        val unjudged = jdbc.queryForMap("SELECT score, score_type FROM gpucodeforces.submissions WHERE id = '00000000-0000-0000-0000-000000000006'")
        assertThat(unjudged["score"]).isNull()
        assertThat(unjudged["score_type"]).isNull()

        assertThat(jdbc.queryForObject("SELECT processor FROM gpucodeforces.contests", String::class.java)).isEqualTo("gpu-cuda")
        val version = jdbc.queryForMap("SELECT processors::text AS processors, starters->'gpu-cuda'->>'cuda' AS cuda FROM gpucodeforces.problem_versions")
        assertThat(version["processors"]).isEqualTo("{gpu-cuda}")
        assertThat(version["cuda"]).isEqualTo("// starter")

        val ratings = jdbc.queryForList("SELECT user_id::text AS user_id, processor, rating, rank, rating_delta FROM gpucodeforces.user_ratings")
        assertThat(ratings).hasSize(1)
        assertThat(ratings.single()).containsEntry("user_id", "00000000-0000-0000-0000-000000000001")
            .containsEntry("processor", "gpu-cuda").containsEntry("rating", 1620).containsEntry("rank", 1).containsEntry("rating_delta", 120)
        assertThat(jdbc.queryForObject("SELECT processor FROM gpucodeforces.rating_history", String::class.java)).isEqualTo("gpu-cuda")
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")

        @Language("SQL")
        private const val BEFORE = """
            INSERT INTO gpucodeforces.users (id, handle, email, password_hash, rating, rank, rating_delta, created_at)
            VALUES ('00000000-0000-0000-0000-000000000001', 'rated', 'rated@example.com', 'h', 1620, 1, 120, now()),
                   ('00000000-0000-0000-0000-000000000002', 'newcomer', 'newcomer@example.com', 'h', NULL, NULL, NULL, now());
            INSERT INTO gpucodeforces.contests (id, title, start_at, end_at, gpu, rating_applied_at)
            VALUES ('00000000-0000-0000-0000-000000000003', 'Раунд', now() - interval '3 hours', now() - interval '1 hour', 'NVIDIA GeForce RTX 3060', now());
            UPDATE gpucodeforces.users SET rating_contest_id = '00000000-0000-0000-0000-000000000003' WHERE handle = 'rated';
            INSERT INTO gpucodeforces.rating_history (contest_id, user_id, rank, rating_delta, rating_after)
            VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 1, 120, 1620);
            INSERT INTO gpucodeforces.problems (id, current_version_id, created_at) VALUES ('vector_add', NULL, now());
            INSERT INTO gpucodeforces.problem_versions
                (id, problem_id, code, title, difficulty, tags, authors, draft, statement_markdown, starter_code, judge_spec, content_hash, judge_hash, revision, created_at)
            VALUES ('00000000-0000-0000-0000-000000000004', 'vector_add', '1', 'Сложение', 'easy', '{}', '{}', false, 'Условие', '// starter', '{}', 'c', 'j', NULL, now());
            UPDATE gpucodeforces.problems SET current_version_id = '00000000-0000-0000-0000-000000000004';
            INSERT INTO gpucodeforces.submissions
                (id, user_id, problem_id, problem_version_id, contest_id, code, status, time_ms, passed_tests, total_tests, diagnostic,
                 environment_markdown, judge_hash, gpu_profile, previous_best_time_ms, recheck_count, job_id, event_attempt, event_seq,
                 created_at, finished_at, updated_at)
            VALUES ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001', 'vector_add',
                    '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000003', 'code', 'accepted', 1.25, 5, 5, '',
                    '', 'j', 'rtx3060', 2.5, 0, 'job', 1, 7, now(), now(), now()),
                   ('00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000002', 'vector_add',
                    '00000000-0000-0000-0000-000000000004', NULL, 'code', 'queued', NULL, 0, NULL, '',
                    '', 'j', 'rtx3060', NULL, 0, NULL, 0, 0, now(), NULL, now());
        """
    }
}
