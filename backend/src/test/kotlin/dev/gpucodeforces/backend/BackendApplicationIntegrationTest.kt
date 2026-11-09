package dev.gpucodeforces.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        assertThat(migrations).isEqualTo(1L)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
