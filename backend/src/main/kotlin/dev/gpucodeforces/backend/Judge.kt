package dev.gpucodeforces.backend

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class TestCallback(
    val submissionId: UUID,
    val attemptId: UUID,
    val testNumber: Int,
    val verdict: String,
    val timeMs: Double? = null,
    val diagnostic: String = "",
)

data class PongInput(val nonce: UUID)
private enum class Receipt { STORED, IGNORED, FULL }
data class JudgeTask(
    val submissionId: UUID,
    val attemptId: UUID,
    val participantId: String,
    val problemId: String,
    val revision: String,
    val code: String,
    val generator: String,
    val referenceSolution: String,
    val testCount: Int,
    val timeLimitSeconds: Double,
    val memoryLimitMb: Int,
    val environmentMarkdown: String,
    val mode: String,
    val environmentId: String,
    val absoluteTolerance: Double,
    val relativeTolerance: Double,
)

@Component
class Judge(
    private val db: Database,
    private val platform: PlatformConfig,
    private val live: RuntimeSettings,
) {
    private val config = platform.judge
    private val http =
        HttpClient.newBuilder().connectTimeout(config.httpTimeout).followRedirects(HttpClient.Redirect.NEVER).build()
    private val workers = config.workers.associateBy { it.id }
    private val log = LoggerFactory.getLogger(Judge::class.java)

    init {
        require(config.maxInFlight in 1..3 && config.maxAttempts > 0 && config.inboxCapacity > 0)
        require(!config.timeout.isNegative && !config.timeout.isZero && config.pingTimeout > config.httpTimeout)
        require(workers.size == config.workers.size && workers.size <= 32)
        workers.values.forEach {
            val uri = URI(it.url)
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && it.token.length >= 32)
            require(
                uri.scheme in setOf(
                    "http",
                    "https",
                ) && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null,
            )
        }
    }

    fun authenticate(workerId: String, authorization: String?): WorkerConfig {
        val worker = workers[workerId] ?: fail(401, "Неизвестный тестировщик.")
        if (!MessageDigest.isEqual((authorization ?: "").toByteArray(), "Bearer ${worker.token}".toByteArray())) fail(
            401,
            "Недопустимый ключ тестировщика.",
        )
        return worker
    }

    fun receive(workerId: String, result: TestCallback) {
        val receipt = db.tx {
            val config = live.current().values.judge(platform.judge)
            if (result.verdict !in terminalVerdicts ||
                result.testNumber < 1 ||
                result.diagnostic.toByteArray().size > 8192 ||
                result.timeMs?.let { !it.isFinite() || it !in 1e-9..1e12 } == true ||
                result.verdict == "accepted" && result.timeMs == null
            ) fail(
                400,
                "Некорректный результат теста.",
            )
            // Serialize heartbeat validation with expiry and reassignment of attempts.
            db.lock(1)
            val submission = db.judgeState(result.submissionId)
            // A successful acknowledgement stops retries of callbacks from expired attempts.
            if (submission.workerId != workerId ||
                submission.attemptId != result.attemptId ||
                submission.status !in pendingStatuses
            ) return@tx Receipt.IGNORED
            if (result.testNumber > submission.testCount) fail(400, "Номер теста вне диапазона.")
            confirmActivity(workerId, config)
            if (db.count(
                    "SELECT count(*) FROM test_result WHERE submission_id = ? AND test_number = ?",
                    result.submissionId,
                    result.testNumber,
                ) > 0
            ) return@tx Receipt.IGNORED
            val existing = db.row(
                "SELECT document FROM judge_inbox WHERE submission_id = ? AND attempt_id = ? AND test_number = ?",
                result.submissionId,
                result.attemptId,
                result.testNumber,
            )
            if (existing != null) {
                if (db.json.readValue(existing["document"].toString(), TestCallback::class.java) != result) {
                    fail(409, "Конфликт повторного результата.")
                }
                return@tx Receipt.IGNORED
            }
            extendDeadline(result.submissionId, result.attemptId, config)
            if (db.count("/* full-scan:inbox-count */ SELECT count(*) FROM judge_inbox") >= config.inboxCapacity) {
                return@tx Receipt.FULL
            }
            db.update(
                """
                    INSERT INTO judge_inbox(submission_id, attempt_id, test_number, document)
                    VALUES (?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING
                """.trimIndent(),
                result.submissionId,
                result.attemptId,
                result.testNumber,
                db.json.writeValueAsString(result),
            )
            Receipt.STORED
        }
        if (receipt == Receipt.FULL) fail(503, "Буфер заполнен; повторите запрос позже.")
    }

    private fun confirmActivity(workerId: String, config: JudgeConfig) {
        db.update(
            """
                UPDATE judge_worker SET alive = true, ping_nonce = NULL, ping_deadline = NULL, next_ping_at = ?
                WHERE id = ?
            """.trimIndent(), Timestamp.from(Instant.now().plus(config.pingInterval)), workerId,
        )
    }

    private fun extendDeadline(id: UUID, attemptId: UUID, config: JudgeConfig) {
        db.update(
            """
                UPDATE submission SET deadline = ? WHERE id = ? AND attempt_id = ? AND finished_at IS NULL
            """.trimIndent(), Timestamp.from(Instant.now().plus(config.timeout)), id, attemptId,
        )
    }

    fun pong(workerId: String, nonce: UUID) = db.tx {
        val config = live.current().values.judge(platform.judge)
        db.lock(1)
        db.update(
            """
                UPDATE judge_worker SET alive = true, ping_nonce = NULL, ping_deadline = NULL, next_ping_at = ? WHERE id
                = ? AND ping_nonce = ? AND ping_deadline > now()
            """.trimIndent(),
            Timestamp.from(Instant.now().plus(config.pingInterval)), workerId, nonce,
        )
    }

    @Scheduled(fixedDelay = 100)
    fun consume() {
        repeat(100) {
            val consumed = db.tx {
                val row = db.row(
                    """
                        SELECT * FROM judge_inbox ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                    """.trimIndent(),
                ) ?: return@tx false
                val callback = db.json.readValue(row["document"].toString(), TestCallback::class.java)
                db.row("SELECT id FROM submission WHERE id = ? FOR UPDATE", callback.submissionId)
                val s = db.judgeState(callback.submissionId)
                if (s.attemptId == callback.attemptId && s.status in pendingStatuses) {
                    db.update(
                        "INSERT INTO test_result VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        s.id,
                        callback.testNumber,
                        callback.verdict,
                        callback.timeMs,
                        callback.diagnostic,
                    )
                    val firstFailure = db.row(
                        """
                            SELECT * FROM test_result WHERE submission_id = ? AND verdict <> 'accepted'
                            ORDER BY test_number LIMIT 1
                        """.trimIndent(),
                        s.id,
                    )
                    val failure = firstFailure?.takeIf { row ->
                        db.count(
                            """
                                SELECT count(*) FROM test_result WHERE submission_id = ? AND verdict = 'accepted'
                                AND test_number < ?
                            """.trimIndent(),
                            s.id,
                            row["test_number"],
                        ) == (row["test_number"] as Int) - 1L
                    }
                    val passed = db.count(
                        """
                            SELECT count(*) FROM test_result WHERE submission_id = ? AND verdict = 'accepted'
                        """.trimIndent(),
                        s.id,
                    ).toInt()
                    val count = s.testCount
                    when {
                        failure != null -> db.update(
                            """
                                UPDATE submission SET status = ?, diagnostic = ?, finished_at = now(), deadline = NULL
                                WHERE id = ?
                            """.trimIndent(),
                            failure["verdict"],
                            failure["diagnostic"],
                            s.id,
                        )

                        passed == count -> {
                            val total = (db.row(
                                "SELECT sum(time_ms) AS total FROM test_result WHERE submission_id = ?",
                                s.id,
                            )!!["total"] as Number).toDouble()
                            if (!total.isFinite() || total <= 0) error("Invalid aggregate test time")
                            db.update(
                                """
                                    UPDATE submission SET status = 'accepted' , time_ms = ?, finished_at = now(),
                                    deadline = NULL WHERE id = ?
                                """.trimIndent(),
                                total,
                                s.id,
                            )
                        }

                        else -> db.update("UPDATE submission SET status = 'checking' WHERE id = ?", s.id)
                    }
                    if (s.contestId != null && (failure != null || passed == count)) {
                        db.lock(5)
                        db.update("UPDATE contest SET rated = false WHERE id = ?", s.contestId)
                    }
                }
                db.update("DELETE FROM judge_inbox WHERE id = ?", row["id"])
                true
            }
            if (!consumed) return
        }
    }

    private fun resetWorker(workerId: String, config: JudgeConfig) {
        db.update(
            """
                UPDATE judge_worker SET alive = false, ping_nonce = NULL, ping_deadline = NULL, next_ping_at = now()
                WHERE id = ?
            """.trimIndent(),
            workerId,
        )
        val rows = db.rows(
            """
                SELECT id, attempts FROM submission WHERE worker_id = ? AND finished_at IS NULL FOR UPDATE
            """.trimIndent(),
            workerId,
        )
        rows.forEach { row ->
            val exhausted = (row["attempts"] as Int) >= config.maxAttempts
            db.update("DELETE FROM test_result WHERE submission_id = ?", row["id"])
            db.update(
                """
                    UPDATE submission SET worker_id = NULL, attempt_id = NULL, deadline = NULL, status = ?,
                    finished_at = CASE WHEN ? THEN now() ELSE NULL END, diagnostic = ? WHERE id = ?
                """.trimIndent(),
                if (exhausted) "system_error" else "queued",
                exhausted,
                if (exhausted) "Тестировщики не ответили после нескольких попыток." else "",
                row["id"],
            )
        }
    }

    @Scheduled(fixedDelay = 500)
    fun dispatch() {
        val config = live.current().values.judge(platform.judge)
        val tasks = db.tx {
            db.lock(1)
            workers.keys.forEach { db.update("INSERT INTO judge_worker(id) VALUES (?) ON CONFLICT DO NOTHING", it) }
            val expired =
                db.rows(
                    """
                        SELECT DISTINCT worker_id FROM submission WHERE finished_at IS NULL AND deadline < now()
                        AND worker_id IS NOT NULL
                    """.trimIndent(),
                )
            expired.forEach { resetWorker(it["worker_id"].toString(), config) }
            db.rows(
                """
                    /* full-scan:worker-pool */ SELECT id FROM judge_worker WHERE alive
                """.trimIndent(),
            ).filter { it["id"] !in workers }.forEach { resetWorker(it["id"].toString(), config) }
            val claimed = mutableListOf<Pair<WorkerConfig, UUID>>()
            workers.values.forEach { worker ->
                if (db.count(
                        """
                            SELECT count(*) FROM judge_worker WHERE id = ? AND alive
                        """.trimIndent(),
                        worker.id,
                    ) == 0L
                ) return@forEach
                val busy = db.count(
                    """
                        SELECT count(*) FROM submission WHERE worker_id = ? AND finished_at IS NULL
                    """.trimIndent(),
                    worker.id,
                ).toInt()
                repeat((config.maxInFlight - busy).coerceAtLeast(0)) {
                    val next = db.row(
                        """
                            SELECT s.id FROM submission s
                            WHERE s.status = 'queued' AND s.worker_id IS NULL AND s.environment_id = ?
                            ORDER BY s.created_at, s.id FOR UPDATE OF s SKIP LOCKED LIMIT 1
                        """.trimIndent(),
                        worker.environmentId,
                    ) ?: return@repeat
                    val id = next["id"] as UUID
                    db.update(
                        """
                            UPDATE submission SET worker_id = ?, attempt_id = ?, attempts = attempts + 1, deadline = ?,
                            status = 'compiling' WHERE id = ?
                        """.trimIndent(),
                        worker.id,
                        UUID.randomUUID(),
                        Timestamp.from(Instant.now().plus(config.timeout)),
                        id,
                    )
                    claimed += worker to id
                }
            }
            claimed
        }
        tasks.forEach { (worker, id) ->
            val s = db.submission(id)
            if (s.workerId != worker.id || s.attemptId == null || s.status !in pendingStatuses) return@forEach
            val rev = db.revision(s.revisionId)
            val p = rev.problem
            val task = JudgeTask(
                id,
                s.attemptId,
                s.userId,
                s.problemId,
                rev.sha,
                s.code,
                p.generator,
                p.referenceSolution,
                if (s.example) 1 else p.testCount,
                p.timeLimitSeconds,
                p.memoryLimitMb,
                p.environmentMarkdown,
                if (s.example) "example" else "submission",
                p.environmentId,
                p.absoluteTolerance,
                p.relativeTolerance,
            )
            val assignedDeadline = db.judgeState(id).deadline
            val delivered = post(worker, "/tasks", task)
            db.tx<Unit> {
                db.lock(1)
                val current = db.judgeState(id)
                if (current.attemptId == s.attemptId && current.workerId == worker.id) {
                    if (delivered) {
                        confirmActivity(worker.id, config)
                        extendDeadline(id, s.attemptId, config)
                    } else if (current.status in pendingStatuses && current.deadline == assignedDeadline) {
                        resetWorker(worker.id, config)
                    }
                }
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    fun ping() {
        val config = live.current().values.judge(platform.judge)
        val requests = db.tx {
            db.lock(1)
            val now = Instant.now()
            db.rows("SELECT id FROM judge_worker WHERE ping_deadline < now()").forEach {
                val id = it["id"].toString()
                resetWorker(id, config)
                db.update(
                    """
                        UPDATE judge_worker SET next_ping_at = ? WHERE id = ?
                    """.trimIndent(),
                    Timestamp.from(now.plus(config.pingInterval)), id,
                )
            }
            workers.values.mapNotNull { worker ->
                val nonce = UUID.randomUUID()
                val updated = db.update(
                    """
                        UPDATE judge_worker SET ping_nonce = ?, ping_deadline = ?, next_ping_at = ? WHERE id = ?
                        AND ping_nonce IS NULL AND next_ping_at <= now()
                    """.trimIndent(),
                    nonce,
                    Timestamp.from(now.plus(config.pingTimeout)),
                    Timestamp.from(now.plus(config.pingInterval)),
                    worker.id,
                )
                if (updated > 0) worker to nonce else null
            }
        }
        requests.forEach { (worker, nonce) -> post(worker, "/ping", PongInput(nonce)) }
    }

    private fun post(worker: WorkerConfig, path: String, body: Any): Boolean {
        val request = HttpRequest.newBuilder(URI(worker.url.trimEnd('/') + path)).timeout(config.httpTimeout)
            .header("Authorization", "Bearer ${worker.token}").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(db.json.writeValueAsString(body))).build()
        return try {
            val response = boundedHttp(http, request, 4096, config.httpTimeout)
            response.statusCode() in 200..299
        } catch (error: IOException) {
            log.warn("Judge {} is unavailable: {}", worker.id, error.javaClass.simpleName)
            false
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }
}
