package dev.gpucodeforces.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.flywaydb.core.Flyway
import java.util.UUID
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Tag("integration")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["platform.scheduling-enabled=false"]
)
class BackendApplicationIntegrationTest {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `application starts against PostgreSQL and applies Flyway migrations`() {
        val schema = jdbc.queryForObject(
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'gpucodeforces'",
            String::class.java,
        )
        assertThat(schema).isEqualTo("gpucodeforces")
        val migrations = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true",
            Long::class.java,
        )
        assertThat(migrations).isEqualTo(5L)
    }

    @Test
    fun `upgrade preserves legacy task identities revisions and submissions`() {
        val schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "")
        fun flyway(target: String) = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).target(target).load()
        flyway("2").migrate()
        val revision = UUID.randomUUID()
        val submission = UUID.randomUUID()
        jdbc.update("INSERT INTO $schema.source_repository(id, installation_id, full_name) VALUES (42, 1, 'old/repo')")
        jdbc.update(
            """
                INSERT INTO $schema.problem_revision(id, repository_id, commit_sha, document)
                VALUES (?, 42, ?, '{}'::jsonb)
            """.trimIndent(), revision, "a".repeat(40),
        )
        jdbc.update("UPDATE $schema.source_repository SET current_revision = ? WHERE id = 42", revision)
        jdbc.update(
            """
                INSERT INTO $schema.app_user(id, handle, email, password_hash)
                VALUES ('old-user', 'old_user', 'old@example.com', 'unused')
            """.trimIndent(),
        )
        jdbc.update(
            """
                INSERT INTO $schema.submission(id, user_id, problem_id, revision_id, code, test_count)
                VALUES (?, 'old-user', '42', ?, 'source', 2)
            """.trimIndent(), submission, revision,
        )
        flyway("5").migrate()
        val task = jdbc.queryForMap("SELECT * FROM $schema.source_problem WHERE id = '42'")
        assertThat(task["task_key"]).isEqualTo("legacy")
        assertThat(task["current_revision"]).isEqualTo(revision)
        val old = jdbc.queryForMap("SELECT * FROM $schema.submission WHERE id = ?", submission)
        assertThat(old["problem_id"]).isEqualTo("42")
        assertThat(old["revision_id"]).isEqualTo(revision)
        assertThat(jdbc.queryForObject("SELECT is_admin FROM $schema.app_user", Boolean::class.java)).isFalse()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
