package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.submission.GuessSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.UUID

@Tag("integration")
@Testcontainers
class LegacyGuessMigrationIntegrationTest {
    @Test
    fun `legacy answers retain their snapshot while new cohorts enforce one attempt`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val jdbc = JdbcTemplate(dataSource)
        @Language("SQL")
        val schema = """
            CREATE SCHEMA gpucodeforces;
            CREATE TABLE gpucodeforces.problem_versions (id UUID PRIMARY KEY, format_data JSONB NOT NULL);
            CREATE TABLE gpucodeforces.submissions (
                id UUID PRIMARY KEY, user_id UUID NOT NULL,
                problem_version_id UUID NOT NULL REFERENCES gpucodeforces.problem_versions (id),
                contest_id UUID, guess_choice VARCHAR(10)
            );
            CREATE UNIQUE INDEX submissions_guess_practice_key
                ON gpucodeforces.submissions (user_id, problem_version_id)
                WHERE guess_choice IS NOT NULL AND contest_id IS NULL;
            CREATE UNIQUE INDEX submissions_guess_contest_key
                ON gpucodeforces.submissions (user_id, problem_version_id, contest_id)
                WHERE guess_choice IS NOT NULL AND contest_id IS NOT NULL;
        """.trimIndent()
        dataSource.connection.use { ScriptUtils.executeSqlScript(it, ByteArrayResource(schema.toByteArray())) }

        val userId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        val missingTimesVersion = UUID.randomUUID()
        val answerId = UUID.randomUUID()
        val missingTimesAnswer = UUID.randomUUID()
        @Language("JSON")
        val legacyFormat = """{"format":"guess","firstTimeMs":1.25,"secondTimeMs":2.50,"explanation":"Сохраненный разбор","benchmarkSynthetic":true}"""
        @Language("JSON")
        val noTimes = """{"format":"guess","firstTimeMs":null,"secondTimeMs":null}"""
        @Language("SQL")
        val insertVersion = "INSERT INTO gpucodeforces.problem_versions (id, format_data) VALUES (?, ?::jsonb)"
        jdbc.update(insertVersion, versionId, legacyFormat)
        jdbc.update(insertVersion, missingTimesVersion, noTimes)
        @Language("SQL")
        val insertLegacy = "INSERT INTO gpucodeforces.submissions (id, user_id, problem_version_id, guess_choice) VALUES (?, ?, ?, ?)"
        jdbc.update(insertLegacy, answerId, userId, versionId, "second")
        jdbc.update(insertLegacy, missingTimesAnswer, userId, missingTimesVersion, "first")

        dataSource.connection.use {
            ScriptUtils.executeSqlScript(it, ClassPathResource("db/migration/V13__benchmark_submission_context.sql"))
        }

        @Language("SQL")
        val readSnapshot = "SELECT guess_result::text FROM gpucodeforces.submissions WHERE id = ?"
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val snapshot = mapper.readValue(jdbc.queryForObject(readSnapshot, String::class.java, answerId)!!, GuessSnapshot::class.java)
        assertThat(snapshot.firstTimeMs).isEqualByComparingTo("1.25")
        assertThat(snapshot.secondTimeMs).isEqualByComparingTo("2.50")
        assertThat(snapshot.correctChoice()).isEqualTo("first")
        assertThat(snapshot.explanation).isEqualTo("Сохраненный разбор")
        assertThat(snapshot.synthetic).isTrue()
        assertThat(snapshot.benchmarkKey).isEqualTo("legacy-$versionId")
        assertThat(jdbc.queryForObject(readSnapshot, String::class.java, missingTimesAnswer)).isNull()
        @Language("SQL")
        val readChoice = "SELECT guess_choice FROM gpucodeforces.submissions WHERE id = ?"
        assertThat(jdbc.queryForObject(readChoice, String::class.java, answerId)).isEqualTo("second")

        @Language("SQL")
        val insertCurrent = "INSERT INTO gpucodeforces.submissions (id, user_id, problem_version_id, guess_choice, guess_benchmark_key) VALUES (?, ?, ?, ?, ?)"
        val currentKey = "$versionId:current-environment"
        assertThat(jdbc.update(insertCurrent, UUID.randomUUID(), userId, versionId, "first", currentKey)).isEqualTo(1)
        assertThatThrownBy { jdbc.update(insertCurrent, UUID.randomUUID(), userId, versionId, "second", currentKey) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(jdbc.queryForObject(readSnapshot, String::class.java, answerId)).isNotNull()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
