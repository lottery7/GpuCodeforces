package dev.gpucodeforces.backend

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

@Tag("integration")
@Testcontainers
class QueryPlanGuardIntegrationTest {
    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, "test", "test")

    @BeforeEach
    fun prepare() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS plan_probe")
                statement.execute(
                    """
                        CREATE TABLE plan_probe(id integer PRIMARY KEY, lookup text, hits integer DEFAULT 0)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                        INSERT INTO plan_probe(id, lookup) SELECT i, md5(i::text) FROM generate_series(1, 10000) i
                    """.trimIndent(),
                )
                statement.execute("ANALYZE plan_probe")
            }
        }
    }

    private fun audit(block: (Connection) -> Unit): Path {
        val name = "GuardRegression." + UUID.randomUUID()
        QueryPlanAudit.start(name)
        try {
            connection().use { raw ->
                QueryPlanJdbc.connection(raw).use { checked -> QueryPlanAudit.checkFixture { block(checked) } }
            }
        } finally { QueryPlanAudit.finish() }
        return Path.of(System.getProperty("queryPlan.reportDir", "build/reports/query-plans"), "$name.jsonl")
    }

    private fun query(connection: Connection, @Language("SQL") sql: String, value: String) {
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result -> while (result.next()) result.getObject(1) }
        }
    }

    @Test
    fun `unindexed predicates fail and adding the real index makes the same query pass`() {
        assertThatThrownBy { audit { query(it, "SELECT * FROM plan_probe WHERE lookup = ?", "value") } }
            .isInstanceOf(AssertionError::class.java).hasMessageContaining("plan_probe")
        connection().use {
            it.createStatement().use { sql -> sql.execute("CREATE INDEX probe_lookup ON plan_probe(lookup)") }
        }
        audit { query(it, "SELECT * FROM plan_probe WHERE lookup = ?", "value") }
    }

    @Test
    fun `small-table planner choices require an indexed alternative and do not hide missing indexes`() {
        connection().use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE plan_probe")
                it.execute("INSERT INTO plan_probe(id, lookup) VALUES (1, 'one'), (2, 'two')")
                it.execute("ANALYZE plan_probe")
            }
        }
        val report = audit { query(it, "SELECT * FROM plan_probe WHERE id = ?::integer", "1") }
        assertThat(Files.readString(report)).contains("Seq Scan", "Index Scan")
        assertThatThrownBy { audit { query(it, "SELECT * FROM plan_probe WHERE lookup = ?", "one") } }
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `full index walks cannot disguise table-wide work and policies cannot exempt unrelated tables`() {
        assertThatThrownBy { audit {
            it.createStatement().use { sql -> sql.executeQuery("SELECT count(*) FROM plan_probe") }
        } }
            .isInstanceOf(AssertionError::class.java)
        assertThatThrownBy { audit {
            it.createStatement().use { sql -> sql.executeQuery("/* full-scan:catalog */ SELECT * FROM plan_probe") }
        } }.isInstanceOf(AssertionError::class.java)
        assertThatThrownBy { audit {
            it.createStatement().use { sql -> sql.executeQuery("/* full-scan:unknown */ SELECT 1") }
        } }.isInstanceOf(AssertionError::class.java).hasMessageContaining("Unknown full-scan policy")
    }

    @Test
    fun `explain does not execute mutations twice and restores transaction and planner settings`() {
        connection().use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE plan_probe")
                it.execute("INSERT INTO plan_probe(id) VALUES (1), (2)")
                it.execute("ANALYZE plan_probe")
            }
        }
        val report = audit { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.executeUpdate("INSERT INTO plan_probe(id) VALUES (10001)") }
            connection.prepareStatement("UPDATE plan_probe SET hits = hits + 1 WHERE id = ?").use {
                it.setInt(1, 10001)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            assertThat(connection.autoCommit).isFalse()
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW enable_seqscan").use {
                    it.next(); assertThat(it.getString(1)).isEqualTo("on")
                }
                statement.executeQuery("SELECT hits FROM plan_probe WHERE id = 10001").use {
                    it.next(); assertThat(it.getInt(1)).isEqualTo(1)
                }
            }
            connection.commit()
        }
        assertThat(Files.readString(report)).contains("Seq Scan", "Index Scan")
    }

    @Test
    fun `prepared batches preserve individual bindings and statement batches are audited`() {
        audit { connection ->
            connection.prepareStatement("UPDATE plan_probe SET hits = hits + 1 WHERE id = ?").use {
                for (id in 1..3) { it.setInt(1, id); it.addBatch() }
                assertThat(it.executeBatch()).containsExactly(1, 1, 1)
            }
        }
        assertThatThrownBy { audit { connection ->
            connection.createStatement().use {
                it.addBatch("UPDATE plan_probe SET hits = hits + 1")
                it.executeBatch()
            }
        } }.isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `reports never contain bound values from index conditions`() {
        val secret = "must-not-appear-in-the-plan-report"
        val report = audit { query(it, "SELECT * FROM plan_probe WHERE id = length(?)", secret) }
        assertThat(Files.readString(report)).doesNotContain(secret).contains("indexCondition")
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")
    }
}
