package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.user.User
import org.intellij.lang.annotations.Language
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

class ProblemMetadata(
    val problemId: String,
    val code: String,
    val title: String,
    val difficulty: String,
    val tags: List<String>,
    val judgeHash: String,
)

class ProblemPage(val items: List<ProblemMetadata>, val total: Int, val tags: List<String>)

/** Listing never selects statement, starter code or judge JSON, including historical snapshots. */
@Repository
class ProblemListing(private val jdbc: NamedParameterJdbcTemplate) {
    fun page(
        context: ProblemContext,
        viewer: User?,
        subjectId: UUID?,
        query: String?,
        difficulty: String?,
        tag: String?,
        solved: Boolean?,
        sort: String,
        gpuProfile: String,
        offset: Int,
        limit: Int,
    ): ProblemPage {
        if (sort !in setOf("code", "difficulty", "bestTime")) throw BadRequestException("Неизвестная сортировка")
        if (difficulty != null && difficulty !in ProblemPackageReader.DIFFICULTIES) throw BadRequestException("Неизвестная сложность")
        val contestId = (context as? ProblemContext.InContest)?.contest?.id
        val parameters = MapSqlParameterSource()
            .addValue("contestId", contestId)
            .addValue("contextKey", context.key)
            .addValue("viewer", viewer?.handle)
            .addValue("subjectId", subjectId)
            .addValue("gpu", gpuProfile)
            .addValue("query", query?.trim()?.lowercase().orEmpty())
            .addValue("difficulty", difficulty)
            .addValue("tag", tag)
            .addValue("solved", solved)
            .addValue("offset", offset)
            .addValue("limit", limit)
        // A lookup per current/pinned id prevents a hash join from reading the growing version
        // history. LIMIT is inside the lateral lookup as well as on the final API page.
        @Language("SQL") val source = if (contestId == null) """
            FROM gpucodeforces.problems p
            JOIN LATERAL (
                SELECT id, problem_id, code, title, difficulty, tags, authors, draft, judge_hash
                FROM gpucodeforces.problem_versions WHERE id = p.current_version_id LIMIT 1
            ) v ON true
            WHERE p.current_version_id IS NOT NULL
              AND (NOT v.draft OR EXISTS (SELECT 1 FROM unnest(v.authors) a WHERE lower(a) = lower(CAST(:viewer AS text))))
        """ else """
            FROM gpucodeforces.contest_problems cp
            LEFT JOIN gpucodeforces.problems p ON p.id = cp.problem_id
            JOIN LATERAL (
                SELECT id, problem_id, code, title, difficulty, tags, authors, draft, judge_hash
                FROM gpucodeforces.problem_versions WHERE id = coalesce(cp.problem_version_id, p.current_version_id) LIMIT 1
            ) v ON true
            WHERE cp.contest_id = CAST(:contestId AS uuid)
        """
        @Language("SQL") val solvedExpression = """
            EXISTS (SELECT 1 FROM gpucodeforces.submissions s
                    WHERE s.problem_id = v.problem_id AND s.user_id = CAST(:subjectId AS uuid)
                      AND s.status = 'accepted'
                      AND (:contextKey = 'practice' OR s.context_key = :contextKey))
        """
        @Language("SQL") val filters = """
            AND (CAST(:query AS text) = '' OR strpos(lower(v.code || ' ' || v.title), :query) > 0)
            AND (CAST(:difficulty AS text) IS NULL OR v.difficulty = :difficulty)
            AND (CAST(:tag AS text) IS NULL OR EXISTS (SELECT 1 FROM unnest(v.tags) t WHERE lower(t) = lower(:tag)))
            AND (CAST(:solved AS boolean) IS NULL OR ($solvedExpression) = :solved)
        """
        @Language("SQL") val bestTime = """
            (SELECT s.time_ms FROM gpucodeforces.submissions s
             WHERE s.problem_id = v.problem_id AND s.user_id = CAST(:subjectId AS uuid)
               AND s.status = 'accepted' AND s.judge_hash = v.judge_hash AND s.gpu_profile = :gpu
               AND s.context_key = :contextKey
             ORDER BY s.time_ms LIMIT 1)
        """
        @Language("SQL") val order = when {
            contestId != null -> "cp.position"
            sort == "difficulty" -> "CASE v.difficulty WHEN 'easy' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, v.code, v.problem_id"
            sort == "bestTime" -> "$bestTime NULLS LAST, v.code, v.problem_id"
            else -> "v.code, v.problem_id"
        }
        @Language("SQL") val pageSql = """
            SELECT v.problem_id, v.code, v.title, v.difficulty, v.tags, v.judge_hash
            $source $filters ORDER BY $order LIMIT :limit OFFSET :offset
        """
        val items = jdbc.query(pageSql, parameters) { row, _ ->
            ProblemMetadata(row.getString("problem_id"), row.getString("code"), row.getString("title"),
                row.getString("difficulty"), strings(row, "tags"), row.getString("judge_hash"))
        }
        @Language("SQL") val countSql = "SELECT count(*) $source $filters"
        val total = jdbc.queryForObject(countSql, parameters, Int::class.java)!!
        // Tags belong to the visible context, independent of search filters and page boundaries.
        @Language("SQL") val tagsSql = "SELECT DISTINCT unnest(v.tags) AS tag $source ORDER BY tag"
        val tags = jdbc.query(tagsSql, parameters) { row, _ -> row.getString("tag") }
        return ProblemPage(items, total, tags)
    }

    private fun strings(row: ResultSet, column: String): List<String> {
        val array = row.getArray(column)
        return try {
            array.resultSet.use { elements -> buildList { while (elements.next()) add(elements.getString(2)) } }
        } finally {
            array.free()
        }
    }
}
