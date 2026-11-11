package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.user.User
import org.intellij.lang.annotations.Language
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/** One page of a listing: the versions shown, how many problems match, and the tags of the whole context. */
class ProblemPage(val versionIds: List<UUID>, val total: Int, val tags: List<String>)

/** What a listing is narrowed and ordered by; the caller has validated the values. */
class ProblemFilter(
    val query: String?,
    val difficulty: String?,
    val tag: String?,
    val format: String?,
    val solved: Boolean?,
    /** Only problems that can be solved on it in the context; with [SORT_BEST_SCORE], the processor of the scores. */
    val processor: String?,
    val sort: String,
    /** For [SORT_BEST_SCORE] on the real GPU: the environment whose results count, see ResultScope. */
    val gpuEnvironmentKey: String?,
)

/**
 * Finds the page in the database, so a listing reads neither the whole bank nor the statements, starters and judge
 * JSON of the versions it does not show, including historical snapshots.
 */
@Repository
class ProblemListing(private val jdbc: NamedParameterJdbcTemplate) {
    /** [subjectId] is the user whose solutions `solved` and the best score refer to. */
    fun page(context: ProblemContext, viewer: User?, subjectId: UUID?, filter: ProblemFilter, offset: Int, limit: Int): ProblemPage {
        val contest = (context as? ProblemContext.InContest)?.contest
        val parameters = MapSqlParameterSource()
            .addValue("contestId", contest?.id)
            .addValue("contextKey", context.key)
            .addValue("viewer", viewer?.handle)
            .addValue("subjectId", subjectId)
            .addValue("query", filter.query?.trim()?.lowercase().orEmpty())
            .addValue("difficulty", filter.difficulty)
            .addValue("tag", filter.tag)
            .addValue("format", filter.format)
            .addValue("solved", filter.solved)
            .addValue("processor", filter.processor)
            // a contest runs on one processor: its problems cannot be solved on another one there
            .addValue("processorOffered", contest == null || filter.processor == null || contest.processor == filter.processor)
            .addValue("environmentKey", filter.gpuEnvironmentKey)
            .addValue("offset", offset)
            .addValue("limit", limit)
        // A lookup per current/pinned id prevents a hash join from reading the growing version
        // history. LIMIT is inside the lateral lookup as well as on the final API page.
        @Language("SQL") val source = if (contest == null) """
            FROM gpucodeforces.problems p
            JOIN LATERAL (
                SELECT id, problem_id, code, title, difficulty, tags, authors, draft, judge_hash, format, processors
                FROM gpucodeforces.problem_versions WHERE id = p.current_version_id LIMIT 1
            ) v ON true
            WHERE p.current_version_id IS NOT NULL
              AND (NOT v.draft OR EXISTS (SELECT 1 FROM unnest(v.authors) a WHERE lower(a) = lower(CAST(:viewer AS text))))
        """ else """
            FROM gpucodeforces.contest_problems cp
            LEFT JOIN gpucodeforces.problems p ON p.id = cp.problem_id
            JOIN LATERAL (
                SELECT id, problem_id, code, title, difficulty, tags, authors, draft, judge_hash, format, processors
                FROM gpucodeforces.problem_versions WHERE id = coalesce(cp.problem_version_id, p.current_version_id) LIMIT 1
            ) v ON true
            WHERE cp.contest_id = CAST(:contestId AS uuid)
        """
        // Practice counts a solution in any context, a contest only its own (SubmissionStats.problemResults).
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
            AND (CAST(:format AS text) IS NULL OR v.format = :format)
            AND (CAST(:processor AS text) IS NULL OR (:processorOffered AND :processor = ANY (v.processors)))
            AND (CAST(:solved AS boolean) IS NULL OR ($solvedExpression) = :solved)
        """
        // The subject's best accepted score on the processor in this context and judge configuration.
        @Language("SQL") val bestScore = """
            (SELECT s.score FROM gpucodeforces.submissions s
             WHERE s.problem_id = v.problem_id AND s.user_id = CAST(:subjectId AS uuid)
               AND s.status = 'accepted' AND s.judge_hash = v.judge_hash AND s.processor = :processor
               AND s.context_key = :contextKey AND s.score IS NOT NULL
               AND (CAST(:environmentKey AS text) IS NULL OR s.benchmark_environment_key = :environmentKey)
             ORDER BY s.score LIMIT 1)
        """
        @Language("SQL") val order = when {
            contest != null -> "cp.position"
            filter.sort == SORT_DIFFICULTY -> "CASE v.difficulty WHEN 'easy' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, v.code, v.problem_id"
            filter.sort == SORT_BEST_SCORE -> "$bestScore NULLS LAST, v.code, v.problem_id"
            else -> "v.code, v.problem_id"
        }
        @Language("SQL") val pageSql = "SELECT v.id $source $filters ORDER BY $order LIMIT :limit OFFSET :offset"
        val versionIds = jdbc.query(pageSql, parameters) { row, _ -> row.getObject("id", UUID::class.java) }
        @Language("SQL") val countSql = "SELECT count(*) $source $filters"
        val total = jdbc.queryForObject(countSql, parameters, Int::class.java)!!
        // Tags belong to the visible context, independent of search filters and page boundaries.
        @Language("SQL") val tagsSql = "SELECT DISTINCT unnest(v.tags) AS tag $source ORDER BY tag"
        val tags = jdbc.query(tagsSql, parameters) { row, _ -> row.getString("tag") }
        return ProblemPage(versionIds, total, tags)
    }

    companion object {
        const val SORT_CODE = "code"
        const val SORT_DIFFICULTY = "difficulty"
        const val SORT_BEST_SCORE = "bestScore"
        val SORTS = setOf(SORT_CODE, SORT_DIFFICULTY, SORT_BEST_SCORE)
    }
}
