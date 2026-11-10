package dev.gpucodeforces.backend

import org.springframework.stereotype.Service
import org.intellij.lang.annotations.Language
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ContestInput(
    val title: String,
    val startAt: Instant,
    val endAt: Instant,
    val authorIds: List<String>,
    val problemIds: List<String>,
)

data class SubmitInput(val problemId: String, val code: String, val contestId: String? = null)
data class ExampleInput(val code: String)

@Service
class PlatformService(
    private val db: Database,
    private val config: PlatformConfig,
    private val live: RuntimeSettings,
) {
    fun user(id: String): Map<String, Any?> {
        val row = db.row(
            """
                SELECT u.id, u.handle, u.rating, u.rating_rank AS rank, e.delta, e.contest_id
                FROM app_user u LEFT JOIN LATERAL (
                    SELECT e.* FROM rating_event e
                    JOIN LATERAL (SELECT end_at FROM contest WHERE id = e.contest_id OFFSET 0) c ON true
                    WHERE e.user_id = u.id ORDER BY c.end_at DESC, e.contest_id DESC LIMIT 1
                ) e ON true WHERE u.id = ?
            """.trimIndent(),
            id,
        ) ?: fail(404, "Участник не найден.")
        return mapOf(
            "id" to id,
            "handle" to row["handle"],
            "rating" to row["rating"],
            "rank" to row["rank"],
            "ratingDelta" to row["delta"],
            "ratingContestId" to row["contest_id"]?.toString(),
        )
    }

    fun users(q: String, sort: String, offset: Int, limit: Int): Map<String, Any> {
        page(offset, limit)
        if (sort !in setOf("handle", "rating")) fail(400, "Неизвестная сортировка.")
        val ordering = if (sort == "rating") "rating DESC, lower(handle), id" else "lower(handle), id"
        val filter = if (sort == "rating") "AND rating IS NOT NULL" else ""
        @Language("SQL") val base = "WITH ratings AS (SELECT id, handle, rating FROM app_user)"
        val args = arrayOf("%${escapeLike(q.trim())}%")
        val total = db.count(
            "/* full-scan:user-directory */ $base SELECT count(*) FROM ratings WHERE handle ILIKE ? $filter", *args,
        )
        val ids =
            db.rows(
                """
                    /* full-scan:user-directory */
                    $base SELECT id FROM ratings WHERE handle ILIKE ? $filter ORDER BY $ordering LIMIT ? OFFSET ?
                """.trimIndent(),
                *args, limit, offset,
            )
        return mapOf("items" to ids.map { user(it["id"].toString()) }, "total" to total)
    }

    fun isAuthor(contestId: UUID, userId: String?): Boolean =
        userId != null && db.count(
            """
                SELECT count(*) FROM contest_author WHERE contest_id = ? AND user_id = ?
            """.trimIndent(),
            contestId, userId,
        ) > 0

    fun contestRow(id: UUID): Map<String, Any?> = db.row(
        """
            SELECT * FROM contest WHERE id = ?
        """.trimIndent(),
        id,
    ) ?: fail(404, "Соревнование не найдено.")

    fun phase(row: Map<String, Any?>): String = when {
        Instant.now().isBefore((row["start_at"] as Timestamp).toInstant()) -> "upcoming"
        !Instant.now().isBefore((row["end_at"] as Timestamp).toInstant()) -> "finished"
        else -> "live"
    }

    fun revision(problemId: String, context: String, viewer: String?): Revision {
        val row = if (context == "practice") db.row(
            """
                SELECT p.current_revision AS revision_id FROM source_problem p
                JOIN source_repository r ON r.id = p.repository_id WHERE p.id = ? AND p.enabled AND r.enabled
            """.trimIndent(),
            problemId,
        )
        else {
            val contestId = uuid(context)
            if (phase(contestRow(contestId)) == "upcoming" && !isAuthor(contestId, viewer)) fail(
                403,
                "Задачи откроются после начала соревнования.",
            )
            db.row(
                """
                    SELECT revision_id FROM contest_problem WHERE contest_id = ? AND problem_id = ?
                """.trimIndent(),
                contestId, problemId,
            )
        }
        return db.revision(row?.get("revision_id") as UUID? ?: fail(404, "Задача не найдена."))
    }

    private fun best(problemId: String, revision: UUID, context: UUID?, userId: String? = null): Map<String, Any?>? =
        db.row(
            """
                SELECT id, time_ms FROM submission WHERE NOT example AND status = 'accepted' AND problem_id = ?
                AND revision_id = ?
                AND contest_id IS NOT DISTINCT FROM ?::uuid AND (?::text IS NULL OR user_id = ?)
                ORDER BY time_ms, created_at, id LIMIT 1
            """.trimIndent(),
            problemId, revision, context, userId, userId,
        )

    fun problemDto(revision: RevisionSummary, context: String, target: String?): Map<String, Any?> {
        val contestId = if (context == "practice") null else uuid(context)
        val p = revision.problem
        val best = if (target == null) null else best(revision.problemId, revision.id, contestId, target)
        val last = if (target == null) null else db.row(
            """
                SELECT id, status FROM submission WHERE NOT example AND problem_id = ? AND user_id = ?
                AND contest_id IS NOT DISTINCT FROM ?::uuid ORDER BY created_at DESC, id DESC LIMIT 1
            """.trimIndent(),
            revision.problemId, target, contestId,
        )
        val solvedSql = """
            SELECT count(DISTINCT user_id) FROM submission WHERE NOT example AND status = 'accepted' AND problem_id = ?
            AND (?::uuid IS NULL OR contest_id = ?::uuid) AND (?::text IS NULL OR user_id = ?)
        """.trimIndent()
        val solved = target != null && db.count(solvedSql, revision.problemId, contestId, contestId, target, target) > 0
        return mapOf(
            "id" to revision.problemId,
            "code" to p.code,
            "title" to p.title,
            "difficulty" to p.difficulty,
            "tags" to p.tags,
            "revision" to revision.sha,
            "testCount" to p.testCount,
            "solved" to solved,
            "solvedCount" to db.count(solvedSql, revision.problemId, contestId, contestId, null, null),
            "lastSubmissionId" to last?.get("id")?.toString(),
            "lastStatus" to last?.get("status"),
            "bestSubmissionId" to best?.get("id")?.toString(),
            "bestTimeMs" to best?.get("time_ms"),
            "recordTimeMs" to best(revision.problemId, revision.id, contestId)?.get("time_ms"),
        )
    }

    fun problems(params: Map<String, String>, viewer: String?): Map<String, Any> {
        val offset = number(params, "offset", 0)
        val limit = number(params, "limit", 20)
        page(offset, limit)
        val context = params["context"] ?: "practice"
        val target = params["userId"] ?: viewer
        if (target != null) user(target)
        val contestId = context.takeUnless { it == "practice" }?.let(::uuid)
        if (contestId != null && phase(contestRow(contestId)) == "upcoming" && !isAuthor(contestId, viewer)) {
            fail(403, "Задачи пока недоступны.")
        }
        val difficulty = params["difficulty"]
        if (difficulty != null && difficulty !in setOf("easy", "medium", "hard")) fail(400, "Некорректная сложность.")
        val solved = params["solved"]?.let { it.toBooleanStrictOrNull() ?: fail(400, "Некорректный фильтр решения.") }
        val sort = params["sort"] ?: "code"
        if (sort !in setOf("code", "difficulty", "bestTime")) fail(400, "Некорректная сортировка.")
        @Language("SQL") val available = """
            WITH available AS (
                SELECT p.id AS problem_id, p.current_revision AS revision_id, 0 AS position
                FROM source_problem p JOIN source_repository r ON r.id = p.repository_id
                WHERE ?::uuid IS NULL AND p.enabled AND r.enabled AND p.current_revision IS NOT NULL
                UNION ALL
                SELECT problem_id, revision_id, position FROM contest_problem WHERE contest_id = ?::uuid
            )
        """.trimIndent()
        @Language("SQL") val selected = """
            $available
            /* full-scan:catalog */
            SELECT a.problem_id, a.revision_id FROM available a
            JOIN LATERAL (SELECT metadata FROM problem_revision WHERE id = a.revision_id OFFSET 0) v ON true
            WHERE ((v.metadata->>'code') || ' ' || (v.metadata->>'title')) ILIKE ?
            AND (?::text IS NULL OR v.metadata->>'difficulty' = ?)
            AND (?::text IS NULL OR jsonb_exists(v.metadata->'tags', ?))
            AND (?::boolean IS NULL OR EXISTS (
                SELECT 1 FROM submission s WHERE s.problem_id = a.problem_id AND s.user_id = ?
                AND s.status = 'accepted' AND NOT s.example AND (?::uuid IS NULL OR s.contest_id = ?::uuid)
            ) = ?::boolean)
        """.trimIndent()
        val args = arrayOf<Any?>(
            contestId, contestId, "%${escapeLike(params["q"].orEmpty().trim())}%",
            difficulty, difficulty, params["tag"], params["tag"], solved, target, contestId, contestId, solved,
        )
        val ordering = when {
            contestId != null -> "a.position"
            sort == "difficulty" ->
                "CASE v.metadata->>'difficulty' WHEN 'easy' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END"
            sort == "bestTime" -> """
                (SELECT min(s.time_ms) FROM submission s WHERE s.problem_id = a.problem_id
                AND s.revision_id = a.revision_id AND s.user_id = ? AND s.contest_id IS NULL
                AND s.status = 'accepted' AND NOT s.example) NULLS LAST
            """.trimIndent()
            else -> "v.metadata->>'code'"
        }
        val orderArgs = if (contestId == null && sort == "bestTime") arrayOf<Any?>(target) else emptyArray()
        val total = db.count("SELECT count(*) FROM ($selected) filtered", *args)
        val ids = db.rows(
            "$selected ORDER BY $ordering, v.metadata->>'code', a.problem_id LIMIT ? OFFSET ?",
            *args, *orderArgs, limit, offset,
        )
        val tags = db.rows(
            """
                /* full-scan:catalog */
                $available SELECT DISTINCT jsonb_array_elements_text(v.metadata->'tags') AS tag
                FROM available a
                JOIN LATERAL (SELECT metadata FROM problem_revision WHERE id = a.revision_id OFFSET 0) v ON true
                ORDER BY tag
            """.trimIndent(),
            contestId, contestId,
        ).map { it["tag"].toString() }
        val items = ids.map { problemDto(db.summary(it["revision_id"] as UUID), context, target) }
        return mapOf("items" to items, "total" to total, "tags" to tags)
    }

    fun problem(id: String, context: String, viewer: String?): Map<String, Any?> {
        val revision = revision(id, context, viewer)
        val p = revision.problem
        return problemDto(db.summary(revision.id), context, viewer) + mapOf(
            "statementMarkdown" to p.statementMarkdown,
            "starterCode" to p.starterCode,
            "timeLimitSeconds" to p.timeLimitSeconds,
            "memoryLimitMb" to p.memoryLimitMb,
            "environmentMarkdown" to p.environmentMarkdown,
        )
    }

    fun leaders(id: String, context: String, viewer: String?): List<Map<String, Any?>> {
        val rev = revision(id, context, viewer)
        val contestId = if (context == "practice") null else uuid(context)
        val rows = db.rows(
            """
                WITH best AS (SELECT DISTINCT ON (user_id) user_id, id, time_ms FROM submission
                WHERE NOT example AND status = 'accepted' AND problem_id = ? AND revision_id = ? AND contest_id IS NOT
                DISTINCT FROM ?::uuid
                ORDER BY user_id, time_ms, created_at, id)
                SELECT *, rank() OVER (ORDER BY time_ms) AS rank FROM best ORDER BY time_ms, user_id
            """.trimIndent(),
            id, rev.id, contestId,
        )
        return rows.map {
            mapOf(
                "rank" to it["rank"],
                "user" to user(it["user_id"].toString()),
                "timeMs" to it["time_ms"],
                "submissionId" to it["id"].toString(),
            )
        }
    }

    fun submit(input: SubmitInput, userId: String, example: Boolean = false): UUID = db.tx {
        val values = live.current().values
        db.lock(1)
        if (input.code.isBlank() || input.code.toByteArray().size > config.maxCodeBytes) fail(
            400,
            "Пустое или слишком большое решение.",
        )
        val context = input.contestId ?: "practice"
        val rev = revision(input.problemId, context, userId)
        if (config.judge.workers.none { it.environmentId == rev.problem.environmentId }) fail(
            503,
            "Для этой среды не настроены тестировщики.",
        )
        val contestId = input.contestId?.let(::uuid)
        if (contestId != null && !example) {
            db.row("SELECT id FROM contest WHERE id = ? FOR UPDATE", contestId)
            if (phase(contestRow(contestId)) != "live") fail(400, "Соревнование не идет.")
            if (db.count(
                    """
                        SELECT count(*) FROM contest_registration WHERE contest_id = ? AND user_id = ?
                    """.trimIndent(),
                    contestId, userId,
                ) == 0L
            ) fail(
                403,
                "Нужна регистрация в соревновании.",
            )
        }
        if (db.count(
                "/* full-scan:inflight-count */ SELECT count(*) FROM submission WHERE finished_at IS NULL",
            ) >= values.maxQueuedSubmissions
        ) fail(
            503,
            "Очередь заполнена.",
        )
        if (db.count(
                """
                    SELECT count(*) FROM submission WHERE user_id = ? AND finished_at IS NULL
                """.trimIndent(),
                userId,
            ) >= values.maxPendingPerUser
        ) fail(
            429,
            "Слишком много незавершенных посылок.",
        )
        val id = UUID.randomUUID()
        db.update(
            """
                INSERT INTO submission(id, user_id, problem_id, revision_id, contest_id, code, example, test_count,
                    environment_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            userId,
            input.problemId,
            rev.id,
            contestId,
            input.code,
            example,
            if (example) 1 else rev.problem.testCount,
            rev.problem.environmentId,
        )
        id
    }

    fun submissionDto(id: UUID, viewer: String?, detail: Boolean = false): Map<String, Any?> {
        val s = db.submission(id)
        if (s.example) fail(404, "Посылка не найдена.")
        val rev = db.revision(s.revisionId)
        val current = if (s.contestId == null) db.row(
            """
                SELECT p.current_revision AS revision_id FROM source_problem p
                JOIN source_repository r ON r.id = p.repository_id WHERE p.id = ? AND p.enabled AND r.enabled
            """.trimIndent(),
            s.problemId,
        )
        else db.row(
            """
                SELECT revision_id FROM contest_problem WHERE contest_id = ? AND problem_id = ?
            """.trimIndent(),
            s.contestId, s.problemId,
        )
        val dto = mutableMapOf<String, Any?>(
            "id" to id.toString(), "problemId" to s.problemId, "problemTitle" to rev.problem.title,
            "revision" to rev.sha, "user" to user(s.userId), "contestId" to s.contestId?.toString(),
            "contestTitle" to s.contestId?.let { contestRow(it)["title"] }, "createdAt" to s.createdAt,
            "status" to s.status, "timeMs" to s.timeMs, "comparable" to (current?.get("revision_id") == s.revisionId),
        )
        if (detail) {
            val tests = db.rows(
                """
                    SELECT test_number, verdict, time_ms, diagnostic FROM test_result WHERE submission_id = ?
                    ORDER BY test_number
                """.trimIndent(),
                id,
            )
            dto["passedTests"] = tests.count { it["verdict"] == "accepted" }
            dto["totalTests"] = rev.problem.testCount
            dto["testResults"] = tests.map { row ->
                buildMap<String, Any?> {
                    put("testNumber", row["test_number"]); put("verdict", row["verdict"]); put("timeMs", row["time_ms"])
                    if (viewer == s.userId) put("diagnostic", row["diagnostic"])
                }
            }
            dto["environmentMarkdown"] = rev.problem.environmentMarkdown
            dto["previousBestTimeMs"] = db.row(
                """
                    SELECT min(time_ms) AS time FROM submission WHERE user_id = ? AND problem_id = ? AND revision_id = ?
                    AND contest_id IS NOT DISTINCT FROM ?::uuid AND NOT example AND status = 'accepted'
                    AND created_at < ?
                """.trimIndent(),
                s.userId, s.problemId, s.revisionId, s.contestId, Timestamp.from(s.createdAt),
            )?.get("time")
            if (viewer == s.userId) {
                dto["code"] = s.code; dto["diagnostic"] = s.diagnostic
            }
        }
        return dto
    }

    fun submissions(params: Map<String, String>, viewer: String?): Map<String, Any> {
        val target = params["userId"] ?: viewer ?: fail(401, "Укажите участника или войдите.")
        user(target)
        val offset = number(params, "offset", 0)
        val limit = number(params, "limit", 20); page(offset, limit)
        val status = params["status"]
        if (status != null && status !in pendingStatuses + terminalVerdicts) fail(400, "Неизвестный вердикт.")
        val context = params["context"] ?: "all"
        val contestId = if (context !in setOf("all", "practice")) uuid(context) else null
        if (contestId != null) contestRow(contestId)
        val where = """
            FROM submission WHERE NOT example AND user_id = ? AND (?::text IS NULL OR problem_id = ?)
            AND (?::text IS NULL OR status = ?) AND (? OR contest_id IS NOT DISTINCT FROM ?::uuid)
        """.trimIndent()
        val args =
            arrayOf<Any?>(target, params["problemId"], params["problemId"], status, status, context == "all", contestId)
        val total = db.count("SELECT count(*) $where", *args)
        val ids = db.rows("SELECT id $where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", *args, limit, offset)
        return mapOf("items" to ids.map { submissionDto(it["id"] as UUID, viewer) }, "total" to total)
    }

    fun recheck(id: UUID, userId: String) = db.tx {
        val values = live.current().values
        db.lock(1)
        db.row("SELECT id FROM submission WHERE id = ? FOR UPDATE", id)
        val s = db.submission(id)
        if (s.userId != userId || s.example) fail(403, "Повторная проверка доступна только автору.")
        if (s.status !in setOf("system_error", "measurement_error")) fail(400, "Повторная проверка недоступна.")
        if (db.count(
                "/* full-scan:inflight-count */ SELECT count(*) FROM submission WHERE finished_at IS NULL",
            ) >= values.maxQueuedSubmissions ||
            db.count(
                "SELECT count(*) FROM submission WHERE user_id = ? AND finished_at IS NULL",
                userId,
            ) >= values.maxPendingPerUser
        ) fail(429, "Очередь заполнена.")
        db.update("DELETE FROM test_result WHERE submission_id = ?", id)
        db.update(
            """
                UPDATE submission SET status = 'queued' , finished_at = NULL, worker_id = NULL, attempt_id = NULL,
                attempts = 0, deadline = NULL, time_ms = NULL, diagnostic = '' WHERE id = ?
            """.trimIndent(),
            id,
        )
        if (s.contestId != null) {
            db.lock(5); db.update("UPDATE contest SET rated = false WHERE id = ?", s.contestId)
        }
    }

    fun contestDto(id: UUID, viewer: String?, detail: Boolean = false): Map<String, Any?> {
        val row = contestRow(id)
        val visible = phase(row) != "upcoming" || isAuthor(id, viewer)
        val problems = db.rows(
            """
                SELECT problem_id, revision_id FROM contest_problem WHERE contest_id = ? ORDER BY position
            """.trimIndent(),
            id,
        )
        val authors =
            db.rows(
                """
                    SELECT user_id FROM contest_author WHERE contest_id = ? ORDER BY user_id
                """.trimIndent(),
                id,
            ).map { user(it["user_id"].toString()) }
        return buildMap {
            put("id", id.toString())
            put("title", row["title"])
            put("startAt", (row["start_at"] as Timestamp).toInstant())
            put("endAt", (row["end_at"] as Timestamp).toInstant())
            put("authors", authors)
            put("problemIds", if (visible) problems.map { it["problem_id"] } else emptyList<String>())
            put("problemCount", problems.size)
            put(
                "registered",
                viewer != null && db.count(
                    """
                        SELECT count(*) FROM contest_registration WHERE contest_id = ? AND user_id = ?
                    """.trimIndent(),
                    id, viewer,
                ) > 0,
            )
            put("gpu", "GPU тестировщика; среда зафиксирована в задачах")
            if (detail) put(
                "rulesMarkdown",
                "Места определяются числом решенных задач, затем суммой ускорений относительно эталонов. " +
                    "Время задачи — сумма времени ее тестов.\n\n" + if (visible) problems.joinToString(
                    "\n\n",
                ) {
                    val rev = db.revision(it["revision_id"] as UUID)
                    "${rev.problem.title}: эталон ${rev.problem.referenceTimeMs} мс.\n" +
                        rev.problem.environmentMarkdown
                } else "Задачи откроются после начала.",
            )
        }
    }

    fun contests(q: String, phase: String?, mine: Boolean, viewer: String?): List<Map<String, Any?>> {
        if (mine && viewer == null) fail(401, "Необходимо войти.")
        if (phase != null && phase !in setOf("upcoming", "live", "finished")) fail(
            400,
            "Некорректная фаза соревнования.",
        )
        return db.rows("SELECT id FROM contest WHERE title ILIKE ? ORDER BY start_at, id", "%${escapeLike(q.trim())}%")
            .map { it["id"] as UUID }
            .filter { (!mine || isAuthor(it, viewer)) && (phase == null || phase(contestRow(it)) == phase) }
            .let { if (phase == "finished") it.reversed() else it }.map { contestDto(it, viewer) }
    }

    fun saveContest(id: UUID?, input: ContestInput, userId: String): UUID = db.tx {
        db.lock(2)
        if (id != null) {
            db.row("SELECT id FROM contest WHERE id = ? FOR UPDATE", id)
            if (!isAuthor(id, userId)) fail(403, "Изменять контест могут только авторы.")
            if (phase(contestRow(id)) != "upcoming") fail(400, "Соревнование уже началось.")
        }
        if (input.title.isBlank() ||
            input.title.length > 100 ||
            input.startAt <= Instant.now() ||
            input.endAt <= input.startAt ||
            input.endAt > input.startAt.plusSeconds(30 * 86400)
        ) fail(400, "Проверьте название и даты соревнования.")
        if (input.authorIds.isEmpty() ||
            input.authorIds.size > 20 ||
            input.authorIds.distinct().size != input.authorIds.size ||
            input.problemIds.isEmpty() ||
            input.problemIds.size > 100 ||
            input.problemIds.distinct().size != input.problemIds.size
        ) fail(400, "Проверьте авторов и задачи.")
        input.authorIds.forEach(::user)
        val revisions = input.problemIds.map { problemId ->
            if (id == null) revision(problemId, "practice", userId) else {
                val old = db.row(
                    """
                        SELECT revision_id FROM contest_problem WHERE contest_id = ? AND problem_id = ?
                    """.trimIndent(),
                    id, problemId,
                )
                old?.let { db.revision(it["revision_id"] as UUID) } ?: revision(problemId, "practice", userId)
            }
        }
        if (revisions.map { it.problem.environmentId }.distinct().size != 1) fail(
            400,
            "Все задачи соревнования должны использовать одну среду GPU.",
        )
        val contestId = id ?: UUID.randomUUID()
        db.update(
            """
                INSERT INTO contest(id, title, start_at, end_at) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET title
                = excluded.title, start_at = excluded.start_at, end_at = excluded.end_at
            """.trimIndent(),
            contestId,
            input.title.trim(),
            Timestamp.from(input.startAt),
            Timestamp.from(input.endAt),
        )
        db.update("DELETE FROM contest_author WHERE contest_id = ?", contestId)
        db.update("DELETE FROM contest_problem WHERE contest_id = ?", contestId)
        input.authorIds.forEach { db.update("INSERT INTO contest_author VALUES (?, ?)", contestId, it) }
        revisions.forEachIndexed { index, rev ->
            db.update(
                "INSERT INTO contest_problem VALUES (?, ?, ?, ?)",
                contestId,
                rev.problemId,
                rev.id,
                index,
            )
        }
        contestId
    }

    fun register(id: UUID, userId: String) = db.tx {
        db.row("SELECT id FROM contest WHERE id = ? FOR UPDATE", id)
        if (phase(contestRow(id)) == "finished") fail(400, "Соревнование завершено.")
        db.update("INSERT INTO contest_registration VALUES (?, ?) ON CONFLICT DO NOTHING", id, userId)
    }

    fun standings(id: UUID): List<Map<String, Any?>> {
        if (phase(contestRow(id)) == "upcoming") return emptyList()
        val rows = db.rows(
            """
                WITH best AS (
                    SELECT s.user_id, s.problem_id, min(s.time_ms) AS time_ms, (r.document->> 'referenceTimeMs'
                    )::float8 AS reference
                    FROM submission s JOIN contest_problem p ON p.contest_id = s.contest_id
                    AND p.problem_id = s.problem_id AND p.revision_id = s.revision_id
                    JOIN problem_revision r ON r.id = p.revision_id
                    WHERE s.contest_id = ? AND s.status = 'accepted' AND NOT s.example GROUP BY s.user_id, s.problem_id,
                    r.id
                ), scores AS (
                    SELECT reg.user_id, count(b.problem_id) AS solved, coalesce(sum(b.reference / b.time_ms),
                    0) AS speedup
                    FROM contest_registration reg LEFT JOIN best b ON b.user_id = reg.user_id WHERE reg.contest_id = ?
                    GROUP BY reg.user_id
                ) SELECT *, rank() OVER (ORDER BY solved DESC, speedup DESC) AS rank FROM scores ORDER BY solved DESC,
                speedup DESC, user_id
            """.trimIndent(),
            id, id,
        )
        return rows.map { row ->
            val times = db.rows(
                """
                    SELECT s.problem_id, min(s.time_ms) AS time FROM submission s JOIN contest_problem p
                    ON p.contest_id = s.contest_id AND p.problem_id = s.problem_id AND p.revision_id = s.revision_id
                    WHERE s.contest_id = ? AND s.user_id = ? AND s.status = 'accepted' AND NOT s.example
                    GROUP BY s.problem_id
                """.trimIndent(),
                id, row["user_id"],
            )
            mapOf(
                "rank" to row["rank"],
                "user" to user(row["user_id"].toString()),
                "solved" to row["solved"],
                "speedup" to row["speedup"],
                "times" to times.associate { it["problem_id"].toString() to it["time"] },
            )
        }
    }

    fun profile(id: String): Map<String, Any?> {
        val person = user(id)
        val contests = db.rows(
            """
                SELECT c.* FROM contest c JOIN contest_registration r ON r.contest_id = c.id WHERE r.user_id = ? ORDER
                BY c.start_at DESC
            """.trimIndent(),
            id,
        ).map { row ->
            val contestId = row["id"] as UUID
            val event = db.row("SELECT * FROM rating_event WHERE contest_id = ? AND user_id = ?", contestId, id)
            val rank = standings(contestId).firstOrNull { (it["user"] as Map<*, *>)["id"] == id }?.get("rank")
            mapOf(
                "id" to contestId.toString(),
                "title" to row["title"],
                "phase" to phase(row),
                "rank" to rank,
                "ratingDelta" to event?.get("delta"),
                "ratingAfter" to event?.get("rating_after"),
            )
        }
        return mapOf(
            "user" to person,
            "solvedCount" to db.count(
                """
                    SELECT count(DISTINCT problem_id) FROM submission WHERE user_id = ? AND status = 'accepted' AND NOT
                    example
                """.trimIndent(),
                id,
            ),
            "contests" to contests,
        )
    }

    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    fun number(params: Map<String, String>, name: String, default: Int): Int =
        params[name]?.toIntOrNull() ?: if (params.containsKey(name)) fail(
            400,
            "Некорректный параметр $name.",
        ) else default

    private fun page(offset: Int, limit: Int) {
        if (offset < 0 || limit !in 1..100) fail(400, "Некорректная пагинация.")
    }
}
