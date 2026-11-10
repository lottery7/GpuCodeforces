package dev.gpucodeforces.backend

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

data class RuntimeValues(
    val requestsPerMinute: Int,
    val authRequestsPerMinute: Int,
    val callbackRequestsPerMinute: Int,
    val maxQueuedSubmissions: Int,
    val maxPendingPerUser: Int,
    val maxInFlight: Int,
    val judgeTimeoutSeconds: Long,
    val maxAttempts: Int,
    val inboxCapacity: Int,
    val pingIntervalSeconds: Long,
    val pingTimeoutSeconds: Long,
    val exampleWaitSeconds: Long,
    val discoveryIntervalSeconds: Long,
    val repositoryIntervalSeconds: Long,
    val githubRequestsPerMinute: Int,
) {
    fun judge(base: JudgeConfig) = base.copy(
        maxInFlight = maxInFlight,
        timeout = Duration.ofSeconds(judgeTimeoutSeconds),
        maxAttempts = maxAttempts,
        inboxCapacity = inboxCapacity,
        pingInterval = Duration.ofSeconds(pingIntervalSeconds),
        pingTimeout = Duration.ofSeconds(pingTimeoutSeconds),
        exampleWait = Duration.ofSeconds(exampleWaitSeconds),
        callbackRequestsPerMinute = callbackRequestsPerMinute,
    )

    fun github(base: GithubConfig) = base.copy(
        discoveryInterval = Duration.ofSeconds(discoveryIntervalSeconds),
        repositoryInterval = Duration.ofSeconds(repositoryIntervalSeconds),
        maxRequestsPerMinute = githubRequestsPerMinute,
    )

    fun validate(base: PlatformConfig) {
        if (requestsPerMinute !in 1..100000 ||
            authRequestsPerMinute !in 1..1000 ||
            callbackRequestsPerMinute !in 1..1000000 ||
            maxQueuedSubmissions !in 1..100000 ||
            maxPendingPerUser !in 1..maxQueuedSubmissions ||
            maxInFlight !in 1..3 ||
            judgeTimeoutSeconds !in 10..86400 ||
            maxAttempts !in 1..100 ||
            inboxCapacity !in 1..100000 ||
            pingIntervalSeconds !in 1..3600 ||
            pingTimeoutSeconds !in 1..300 ||
            Duration.ofSeconds(pingTimeoutSeconds) <= base.judge.httpTimeout ||
            exampleWaitSeconds !in 1..60 ||
            discoveryIntervalSeconds !in 10..86400 ||
            repositoryIntervalSeconds !in 10..86400 ||
            githubRequestsPerMinute !in 1..10000
        ) fail(400, "Недопустимые значения настроек или несовместимые ограничения.")
    }

    companion object {
        fun defaults(base: PlatformConfig) = RuntimeValues(
            base.requestsPerMinute, base.authRequestsPerMinute, base.judge.callbackRequestsPerMinute,
            base.maxQueuedSubmissions, base.maxPendingPerUser, base.judge.maxInFlight,
            base.judge.timeout.seconds, base.judge.maxAttempts, base.judge.inboxCapacity,
            base.judge.pingInterval.seconds, base.judge.pingTimeout.seconds, base.judge.exampleWait.seconds,
            base.github.discoveryInterval.seconds, base.github.repositoryInterval.seconds,
            base.github.maxRequestsPerMinute,
        )
    }
}

data class SettingsSnapshot(val version: Long, val values: RuntimeValues)
data class SettingsUpdate(val version: Long, val values: RuntimeValues)

@Component
class RuntimeSettings(private val db: Database, private val base: PlatformConfig) {
    private data class Cache(val snapshot: SettingsSnapshot, val loadedAt: Long)
    @Volatile
    private var cache: Cache? = null

    fun current(): SettingsSnapshot {
        val cached = cache
        if (cached != null && System.nanoTime() - cached.loadedAt < 1_000_000_000) return cached.snapshot
        return synchronized(this) {
            val fresh = cache
            if (fresh != null && System.nanoTime() - fresh.loadedAt < 1_000_000_000) {
                return@synchronized fresh.snapshot
            }
            val row = db.row("SELECT version, document FROM runtime_settings WHERE singleton")!!
            val document = row["document"].toString()
            val values = if (db.json.readTree(document).isEmpty) RuntimeValues.defaults(base)
            else db.json.readValue(document, RuntimeValues::class.java)
            val snapshot = SettingsSnapshot((row["version"] as Number).toLong(), values)
            cache = Cache(snapshot, System.nanoTime())
            snapshot
        }
    }

    @Synchronized
    fun invalidate() {
        cache = null
    }

    @Synchronized
    fun save(input: SettingsUpdate, actor: String): SettingsSnapshot {
        input.values.validate(base)
        db.tx {
            val changed = db.update(
                """
                    UPDATE runtime_settings SET document = ?::jsonb, version = version + 1
                    WHERE singleton AND version = ?
                """.trimIndent(), db.json.writeValueAsString(input.values), input.version,
            )
            if (changed == 0) fail(409, "Настройки уже изменились. Загрузите актуальные значения.")
            db.update(
                """
                    UPDATE platform_lease
                    SET next_discovery_at = least(next_discovery_at, now() + ? * interval '1 second')
                    WHERE name = 'github'
                """.trimIndent(),
                input.values.discoveryIntervalSeconds,
            )
            base.judge.workers.forEach { worker ->
                db.update(
                    """
                        UPDATE judge_worker SET next_ping_at = least(next_ping_at, now() + ? * interval '1 second')
                        WHERE id = ?
                    """.trimIndent(), input.values.pingIntervalSeconds, worker.id,
                )
            }
            audit(actor, "settings.update", "version=${input.version + 1}")
            true
        }
        invalidate()
        return current()
    }

    fun audit(actor: String, action: String, detail: String): UUID {
        val id = UUID.randomUUID()
        db.update(
            "INSERT INTO admin_audit(id, actor_id, action, detail) VALUES (?, ?, ?, ?)",
            id, actor, action, detail,
        )
        return id
    }
}
