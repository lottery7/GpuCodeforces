package dev.gpucodeforces.backend

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class QueryPlanContextFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>, configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer = QueryPlanContext
}

private object QueryPlanContext : ContextCustomizer {
    override fun customizeContext(context: ConfigurableApplicationContext, mergedConfig: MergedContextConfiguration) {
        val registry = context.beanFactory as BeanDefinitionRegistry
        registry.registerBeanDefinition("queryPlanDataSources", RootBeanDefinition(QueryPlanDataSources::class.java))
    }
}

class QueryPlanTestListener : AbstractTestExecutionListener() {
    override fun beforeTestExecution(testContext: TestContext) {
        QueryPlanAudit.start(testContext.testClass.simpleName + "." + testContext.testMethod.name)
    }

    override fun afterTestExecution(testContext: TestContext) {
        QueryPlanAudit.finish()
    }
}

class QueryPlanDataSources : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
        if (bean is DataSource) CheckedDataSource(bean) else bean
}

private class CheckedDataSource(target: DataSource) : DelegatingDataSource(target) {
    override fun getConnection(): Connection = QueryPlanJdbc.connection(super.getConnection())
    override fun getConnection(username: String, password: String): Connection =
        QueryPlanJdbc.connection(super.getConnection(username, password))
}

internal data class Scan(
    val schema: String,
    val relation: String,
    val alias: String,
    val node: String,
    val index: String?,
    val indexCondition: Boolean,
    val filter: Boolean,
    val limited: Boolean,
    val estimatedRows: Long,
) {
    val full: Boolean get() = node.endsWith("Seq Scan") || node == "Sample Scan" ||
        node in setOf("Index Scan", "Index Only Scan", "Bitmap Index Scan") &&
        !indexCondition && !(limited && !filter && node != "Bitmap Index Scan")
}

internal object QueryPlanAudit {
    private data class Case(
        val name: String,
        val records: ConcurrentLinkedQueue<Map<String, Any?>> = ConcurrentLinkedQueue(),
        val violations: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    )
    private val active = AtomicReference<Case>()
    private val explicitCaller = ThreadLocal<String>()
    private val json = JsonMapper.builder().build()
    private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    private val forwarding = setOf("rows", "row", "count", "update", "tx", "lock")
    private val markers = Regex("/\\*\\s*full-scan:([a-z-]+)\\s*\\*/")
    private data class Policy(
        val tables: Set<String>, val reason: String,
        val indexes: Set<String>? = null,
    )
    private val policies = mapOf(
        "rating-rebuild" to Policy(setOf("app_user", "rating_event", "contest"),
            "Полный пересчёт истории рейтинга после поздних результатов требует всех завершённых соревнований."),
        "user-directory" to Policy(setOf("app_user"),
            "Точный размер каталога и поиск подстроки произвольной длины требуют проверки всех " +
                "подходящих пользователей."),
        "catalog" to Policy(setOf("source_repository", "source_problem"),
            "Полный набор тем и точный размер банка вычисляются по метаданным всех доступных задач."),
        "repository-reconciliation" to Policy(setOf("source_repository"),
            "Сверка полной установки GitHub читает и обновляет весь ограниченный конфигурацией список репозиториев."),
        "inflight-count" to Policy(setOf("submission"),
            "Точная вместимость очереди считается по частичному индексу незавершённых посылок, без истории.",
            indexes = setOf("submission_inflight_worker", "submission_inflight_user")),
        "worker-pool" to Policy(setOf("judge_worker"),
            "Диспетчеру нужен весь активный пул; частичный индекс исключает исторические записи тестировщиков.",
            indexes = setOf("judge_worker_alive")),
        "inbox-count" to Policy(setOf("judge_inbox"),
            "Точная проверка вместимости буфера требует всех ещё не обработанных сообщений; истории в нём нет."),
    )
    private val singletons = setOf("runtime_settings", "github_budget", "admin_bootstrap")

    fun start(name: String) {
        check(active.compareAndSet(null, Case(name))) { "Query plan audit requires sequential test execution" }
    }

    fun finish() {
        val case = active.getAndSet(null) ?: error("Query plan audit was not started")
        val directory = Path.of(System.getProperty("queryPlan.reportDir", "build/reports/query-plans"))
        Files.createDirectories(directory)
        val filename = case.name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jsonl"
        Files.newBufferedWriter(directory.resolve(filename)).use { writer ->
            case.records.forEach { writer.appendLine(json.writeValueAsString(it)) }
        }
        if (case.violations.isNotEmpty()) {
            throw AssertionError("Unexpected full scans in ${case.name}:\n" +
                case.violations.distinct().joinToString("\n\n") + "\nPlans: ${directory.resolve(filename)}")
        }
    }

    internal fun <T> checkFixture(block: () -> T): T {
        val previous = explicitCaller.get()
        explicitCaller.set("GuardRegression")
        try { return block() }
        finally {
            if (previous == null) explicitCaller.remove() else explicitCaller.set(previous)
        }
    }

    private fun applicationCaller(): String? = explicitCaller.get() ?: walker.walk { frames ->
        frames.filter { frame ->
            val type = frame.declaringClass
            type.name.startsWith("dev.gpucodeforces.backend.") &&
                type.protectionDomain?.codeSource?.location?.path?.let {
                    it.endsWith("/classes/kotlin/main/") || it.endsWith("/classes/java/main/")
                } == true && !(type == Database::class.java && frame.methodName.substringBefore('$') in forwarding)
        }.findFirst().map { it.className.substringAfterLast('.') + "." + it.methodName }.orElse(null)
    }

    fun inspect(connection: Connection, sql: String, bind: (PreparedStatement) -> Unit) {
        val case = active.get() ?: return
        val caller = applicationCaller() ?: return
        check(connection.metaData.databaseProductName == "PostgreSQL") { "Query plan audit requires PostgreSQL" }
        val clean = sql.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").trimStart()
        if (Regex("(?i)^(CALL|DO)\\b").containsMatchIn(clean)) {
            case.violations.add("$caller: procedural SQL cannot be checked with EXPLAIN\n$sql")
            return
        }
        if (!Regex("(?i)^(SELECT|WITH|INSERT|UPDATE|DELETE|MERGE|VALUES|TABLE)\\b").containsMatchIn(clean)) return
        val tag = markers.find(sql)?.groupValues?.get(1)
        if (tag != null && tag !in policies) case.violations.add("Unknown full-scan policy: $tag\n$sql")
        val autoCommit = connection.autoCommit
        if (autoCommit) connection.autoCommit = false
        val savepoint = connection.setSavepoint()
        try {
            val naturalPlan = explain(connection, sql, bind)
            val natural = scans(naturalPlan)
            val suspicious = natural.filter { it.full && !approved(it, tag) }
            var preferredPlan = if (suspicious.isEmpty()) null else {
                connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
                explain(connection, sql, bind)
            }
            if (preferredPlan != null && scans(preferredPlan).any { it.full && !approved(it, tag) }) {
                connection.createStatement().use { it.execute("SET LOCAL enable_indexscan = off") }
                val bitmap = explain(connection, sql, bind)
                if (scans(bitmap).count { it.full && !approved(it, tag) } <
                    scans(preferredPlan).count { it.full && !approved(it, tag) }
                ) preferredPlan = bitmap
            }
            val preferred = preferredPlan?.let(::scans).orEmpty()
            val failures = suspicious.filter { scan ->
                val indexed = preferred.any { it.schema == scan.schema && it.relation == scan.relation } &&
                    preferred.none {
                        it.schema == scan.schema && it.relation == scan.relation && it.full && !approved(it, tag)
                    }
                !indexed || relationBytes(connection, scan) > 65536
            }
            if (failures.isNotEmpty()) case.violations.add(
                "$caller: " + failures.joinToString { "${it.node} on ${it.schema}.${it.relation}" } + "\n$sql",
            )
            case.records.add(mapOf(
                "caller" to caller, "sql" to sql, "policy" to tag,
                "justification" to policies[tag]?.reason,
                "natural" to natural.map(::projection), "indexPreferred" to preferred.map(::projection),
                "naturalPlan" to safePlan(naturalPlan), "indexPreferredPlan" to preferredPlan?.let(::safePlan),
                "approvedScans" to natural.filter { it.full && approved(it, tag) }.map {
                    projection(it) + ("justification" to approvalReason(it, tag))
                },
                "unexpectedScans" to failures.map(::projection),
            ))
        } catch (error: Exception) {
            case.violations.add("$caller: EXPLAIN failed (${error.javaClass.simpleName})\n$sql")
        } finally {
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
            if (autoCommit) { connection.rollback(); connection.autoCommit = true }
        }
    }

    private fun approved(scan: Scan, tag: String?): Boolean {
        if (scan.schema in setOf("pg_catalog", "information_schema")) return true
        if (scan.schema != "public") return false
        if (scan.relation in singletons || scan.relation == "flyway_schema_history") return true
        val policy = policies[tag] ?: return false
        return scan.relation in policy.tables && (policy.indexes == null || scan.index in policy.indexes)
    }

    private fun approvalReason(scan: Scan, tag: String?): String = when {
        scan.schema in setOf("pg_catalog", "information_schema") -> "Системные каталоги PostgreSQL, не данные приложения."
        scan.relation in singletons -> "PRIMARY KEY(singleton) и CHECK(singleton) допускают не больше одной строки."
        scan.relation == "flyway_schema_history" -> "Служебная история миграций, управляемая кодом, а не участниками."
        else -> policies.getValue(tag!!).reason
    }

    private fun projection(scan: Scan): Map<String, Any?> = mapOf(
        "schema" to scan.schema, "relation" to scan.relation, "node" to scan.node, "index" to scan.index,
        "alias" to scan.alias,
        "indexCondition" to scan.indexCondition, "filter" to scan.filter, "limited" to scan.limited,
        "estimatedRows" to scan.estimatedRows, "full" to scan.full,
    )

    private fun relationBytes(connection: Connection, scan: Scan): Long {
        connection.prepareStatement("SELECT pg_relation_size(to_regclass(?))").use { query ->
            query.setString(1, "\"${scan.schema.replace("\"", "\"\"")}\"." +
                "\"${scan.relation.replace("\"", "\"\"")}\"")
            query.executeQuery().use { result -> check(result.next()); return result.getLong(1) }
        }
    }

    private fun explain(
        connection: Connection, sql: String, bind: (PreparedStatement) -> Unit,
    ): JsonNode = connection.prepareStatement("EXPLAIN (FORMAT JSON, VERBOSE TRUE) $sql").use { query ->
        bind(query)
        query.queryTimeout = 5
        query.executeQuery().use { result ->
            check(result.next())
            json.readTree(result.getString(1)).path(0).path("Plan")
        }
    }

    private fun safePlan(node: JsonNode): Map<String, Any?> = buildMap {
        for (key in listOf("Node Type", "Parent Relationship", "Schema", "Relation Name", "Alias", "Index Name")) {
            if (node.has(key)) put(key, node.path(key).asString())
        }
        for (key in listOf("Plan Rows", "Plan Width", "Startup Cost", "Total Cost")) {
            if (node.has(key)) put(key, node.path(key).asDouble())
        }
        for (key in listOf("Index Cond", "Filter", "Recheck Cond", "Join Filter", "Hash Cond", "Merge Cond")) {
            put("has$key", node.has(key))
        }
        put("Plans", node.path("Plans").toList().map(::safePlan))
    }

    internal fun scans(root: JsonNode): List<Scan> {
        val result = mutableListOf<Scan>()
        fun visit(node: JsonNode, limited: Boolean, parentSchema: String = "", parentRelation: String = "") {
            val type = node.path("Node Type").asString()
            val schema = node.path("Schema").asString(parentSchema)
            val relation = node.path("Relation Name").asString(parentRelation)
            if (node.has("Relation Name") || type == "Bitmap Index Scan") result += Scan(
                schema, relation, node.path("Alias").asString(), type,
                node.path("Index Name").takeUnless { it.isMissingNode }?.asString(),
                node.has("Index Cond"), node.has("Filter"), limited, node.path("Plan Rows").asLong(),
            )
            node.path("Plans").forEach { child ->
                val sameFlow = child.path("Parent Relationship").asString() !in setOf("InitPlan", "SubPlan")
                val streaming = limited && type in setOf("Result", "LockRows") && !node.has("Filter")
                val bounded = sameFlow && (type == "Limit" || streaming)
                visit(child, bounded, schema, relation)
            }
        }
        visit(root, false)
        return result
    }
}

internal object QueryPlanJdbc {
    private data class Binding(val method: Method, val arguments: Array<out Any?>)
    private val setters = setOf(
        "setNull", "setBoolean", "setByte", "setShort", "setInt", "setLong", "setFloat", "setDouble",
        "setBigDecimal", "setString", "setBytes", "setDate", "setTime", "setTimestamp", "setObject",
        "setArray", "setURL", "setRowId", "setNString", "setSQLXML",
    )

    fun connection(target: Connection): Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java),
    ) { _, method, args ->
        val value = invoke(target, method, args)
        if (value is Statement && method.name in setOf("prepareStatement", "prepareCall", "createStatement")) {
            statement(target, value, if (method.name == "createStatement") null else args?.firstOrNull() as String?)
        } else value
    } as Connection

    private fun statement(connection: Connection, target: Statement, sql: String?): Statement {
        val parameters = linkedMapOf<Int, Binding>()
        val batches = mutableListOf<List<Binding>>()
        val rawBatches = mutableListOf<String>()
        val kind = when (target) {
            is CallableStatement -> CallableStatement::class.java
            is PreparedStatement -> PreparedStatement::class.java
            else -> Statement::class.java
        }
        return Proxy.newProxyInstance(kind.classLoader, arrayOf(kind)) { _, method, args ->
            when {
                method.name in setters || method.name.startsWith("set") && args?.size?.let { it >= 2 } == true ->
                    parameters[args!![0] as Int] = Binding(method, args.copyOf())
                method.name == "clearParameters" -> parameters.clear()
                method.name == "addBatch" && sql != null -> batches += parameters.values.toList()
                method.name == "addBatch" && sql == null -> rawBatches += args!![0] as String
                method.name == "clearBatch" -> { batches.clear(); rawBatches.clear() }
            }
            if (method.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")) {
                val query = sql ?: args?.firstOrNull() as String?
                if (query != null) QueryPlanAudit.inspect(connection, query) { explain ->
                    bind(explain, parameters.values)
                }
            }
            if (method.name in setOf("executeBatch", "executeLargeBatch") && sql != null) {
                batches.forEach { values ->
                    QueryPlanAudit.inspect(connection, sql) { explain ->
                        bind(explain, values)
                    }
                }
                batches.clear()
            }
            if (method.name in setOf("executeBatch", "executeLargeBatch") && sql == null) {
                rawBatches.forEach { QueryPlanAudit.inspect(connection, it) { } }
                rawBatches.clear()
            }
            invoke(target, method, args)
        } as Statement
    }

    private fun bind(statement: PreparedStatement, bindings: Collection<Binding>) {
        bindings.forEach {
            check(it.arguments.none { value -> value is java.io.InputStream || value is java.io.Reader }) {
                "Stream parameters cannot be replayed safely for EXPLAIN"
            }
            invoke(statement, it.method, it.arguments)
        }
    }

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
