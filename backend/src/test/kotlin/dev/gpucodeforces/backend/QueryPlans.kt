package dev.gpucodeforces.backend

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.intellij.lang.annotations.Language
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.ResultSet
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

/** Explains actual repository SQL with the same JDBC bindings, including Hibernate's enum conversions. */
class QueryPlans {
    private val active = ThreadLocal<MutableList<Pair<String, JsonNode>>>()
    private val mapper = JsonMapper.builder().build()
    private val rowBudget = ThreadLocal<RowBudget>()
    private val naturalPlan = ThreadLocal<Boolean>()
    @Volatile private var test: TestQueries? = null

    private class TestQueries(val exceptions: List<AllowFullScan>) {
        val failures = ConcurrentLinkedQueue<String>()
    }

    private class RowBudget(val sql: Regex, val maximum: Int) {
        var rows = 0
        var queries = 0
    }

    fun checkNatural(allowedScans: Map<String, String> = emptyMap(), action: () -> Unit) {
        naturalPlan.set(true)
        try {
            check(allowedScans, action)
        } finally {
            naturalPlan.remove()
        }
    }

    fun assertRows(@Language("RegExp") sql: String, maximum: Int, action: () -> Unit) {
        check(rowBudget.get() == null)
        val budget = RowBudget(Regex(sql, RegexOption.IGNORE_CASE), maximum)
        rowBudget.set(budget)
        try {
            action()
        } finally {
            rowBudget.remove()
        }
        check(budget.queries > 0) { "No queries matched $sql" }
        check(budget.rows <= maximum) { "Read ${budget.rows} rows, budget is $maximum for $sql" }
    }

    fun begin(exceptions: List<AllowFullScan>) {
        require(exceptions.all { it.reason.isNotBlank() && it.sql.isNotBlank() && it.maxTableRows >= 0 })
        check(test == null) { "Tests sharing a DataSource must not run concurrently" }
        test = TestQueries(exceptions)
    }

    fun finish() {
        val completed = test ?: error("Query checking was not started")
        test = null
        check(completed.failures.isEmpty()) { completed.failures.joinToString("\n\n") }
    }

    fun check(allowedScans: Map<String, String> = emptyMap(), action: () -> Unit) {
        require(allowedScans.values.all { it.isNotBlank() }) { "Every scan exception needs a reason" }
        check(active.get() == null) { "Nested plan checks are not supported" }
        val plans = mutableListOf<Pair<String, JsonNode>>()
        active.set(plans)
        try {
            action()
        } finally {
            active.remove()
        }
        check(plans.isNotEmpty()) { "No SQL was checked; the query may have been served from the JPA cache" }
        for ((sql, plan) in plans) {
            val scans = scans(plan)
            check(scans.all { it in allowedScans }) {
                "Unexpected full scan of ${scans - allowedScans.keys}\nSQL: $sql\nPlan: $plan"
            }
        }
    }

    private fun scans(node: JsonNode, bounded: Boolean = false): Set<String> = buildSet {
        val type = node.path("Node Type").asString()
        val indexScan = type in setOf("Index Scan", "Index Only Scan", "Parallel Index Scan", "Parallel Index Only Scan")
        if (type in setOf("Seq Scan", "Parallel Seq Scan") ||
            (type == "Bitmap Heap Scan" && !node.has("Recheck Cond")) ||
            (indexScan && !node.has("Index Cond") && (!bounded || node.has("Filter")))) {
            add(node.path("Relation Name").asString())
        }
        // Sorts and aggregates consume their whole input even when a parent requests one row.
        val childBounded = when (type) {
            "Limit" -> true
            "Sort", "Incremental Sort", "Aggregate", "Hash", "WindowAgg", "Materialize" -> false
            else -> bounded
        }
        node.forEach { addAll(scans(it, childBounded)) }
    }

    private fun trackResult(sql: String, value: Any?): Any? {
        val budget = rowBudget.get()
        if (value !is ResultSet || budget == null || !budget.sql.containsMatchIn(sql)) return value
        budget.queries++
        return proxy(ResultSet::class.java, value) { method, args ->
            val returned = invoke(value, method, args)
            if (method.name == "next" && returned == true) budget.rows++
            returned
        }
    }

    fun connection(connection: Connection): Connection = proxy(Connection::class.java, connection) { method, args ->
        val result = invoke(connection, method, args)
        if (method.name == "prepareStatement" && result is PreparedStatement && args[0] is String) {
            statement(connection, result, args[0] as String)
        } else if (method.name == "createStatement" && result is Statement) {
            proxy(Statement::class.java, result) { operation, values ->
                if (operation.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch") &&
                    values.firstOrNull() is String) {
                    explain(connection, values[0] as String, emptyList())
                }
                trackResult(values.firstOrNull() as? String ?: "", invoke(result, operation, values))
            }
        } else result
    }

    private fun statement(connection: Connection, statement: PreparedStatement, @Language("SQL") sql: String): PreparedStatement {
        val bindings = mutableMapOf<Int, Pair<Method, Array<out Any?>>>()
        return proxy(PreparedStatement::class.java, statement) { method, args ->
            if (method.name.startsWith("set") && args.firstOrNull() is Int && args.size >= 2) {
                bindings[args[0] as Int] = method to args.copyOf()
            }
            if (method.name == "clearParameters") bindings.clear()
            if (method.name in setOf("executeQuery", "executeUpdate", "executeLargeUpdate", "execute", "addBatch")) {
                explain(connection, sql, bindings.values.toList())
            }
            trackResult(sql, invoke(statement, method, args))
        }
    }

    private fun explain(connection: Connection, @Language("SQL") sql: String, bindings: List<Pair<Method, Array<out Any?>>>) {
        val local = active.get()
        val current = test
        if (local == null && current == null) return
        // Transaction control, DDL and connection probes have no application query plan.
        if (!sql.trimStart().matches(Regex("(?is)^(select|with|insert|update|delete|merge)\\b.*"))) return
        val plan = connection.createStatement().use { settings ->
            val previous = settings.executeQuery("SHOW enable_seqscan").use { rows -> rows.next(); rows.getString(1) }
            // Tiny fixtures favour Seq Scan. Ask whether an index path exists, on every thread and
            // connection, and restore the setting before running the actual statement.
            if (naturalPlan.get() != true) settings.execute("SET enable_seqscan = off")
            try {
                connection.prepareStatement("EXPLAIN (FORMAT JSON) $sql").use { explain ->
                    bindings.forEach { (setter, values) -> invoke(explain, setter, values) }
                    explain.executeQuery().use { rows ->
                        check(rows.next()) { "EXPLAIN returned no plan for $sql" }
                        mapper.readTree(rows.getString(1))
                    }
                }
            } finally {
                settings.execute(if (previous == "on") "SET enable_seqscan = on" else "SET enable_seqscan = off")
            }
        }
        if (local != null) {
            local += sql to plan
        } else if (current != null) {
            val unexpected = scans(plan).filter { table ->
                current.exceptions.none { exception ->
                    exception.table == table && Regex(exception.sql, RegexOption.IGNORE_CASE).containsMatchIn(sql) &&
                        (exception.maxTableRows == 0 || smallTable(connection, table, exception.maxTableRows))
                }
            }
            if (unexpected.isNotEmpty()) {
                current.failures += "Unexpected full scan of $unexpected\nSQL: $sql\nPlan: $plan"
            }
        }
    }

    private fun smallTable(connection: Connection, table: String, maximum: Int): Boolean {
        require(table.matches(Regex("[a-z_]+")))
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM gpucodeforces.\"$table\" LIMIT ${maximum.toLong() + 1}").use { rows ->
                var count = 0
                while (rows.next()) count++
                count <= maximum
            }
        }
    }

    private fun <T : Any> proxy(type: Class<T>, target: T, call: (Method, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(target::class.java.classLoader, arrayOf(type)) { _, method, args ->
            call(method, args ?: emptyArray())
        })

    private fun invoke(target: Any, method: Method, args: Array<out Any?>): Any? = try {
        method.invoke(target, *args)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
}

@TestConfiguration(proxyBeanMethods = false)
class QueryPlanConfiguration {
    companion object {
        @Bean
        @JvmStatic
        fun queryPlans() = QueryPlans()

        @Bean
        @JvmStatic
        fun explainDataSources(plans: QueryPlans): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is DataSource) object : DelegatingDataSource(bean) {
                    override fun getConnection(): Connection = plans.connection(bean.connection)
                    override fun getConnection(username: String, password: String): Connection =
                        plans.connection(bean.getConnection(username, password))
                } else bean
        }
    }
}
