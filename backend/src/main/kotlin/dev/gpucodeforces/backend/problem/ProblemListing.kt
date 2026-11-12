package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.user.User
import org.intellij.lang.annotations.Language
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID
import java.sql.Timestamp

/**
 * One page of a listing: the versions shown, how many problems match, and the tags of the whole context.
 * [poolSize] and [solvedCount] describe the context before filtering: every problem in it and those the subject solved.
 */
class ProblemPage(val versionIds: List<UUID>, val total: Int, val tags: List<String>, val poolSize: Int, val solvedCount: Int)

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
        val duel = contest?.takeIf { it.format == "duel" && context.key == it.id.toString() }
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
            .addValue("environmentKey", duel?.benchmarkEnvironmentKey ?: filter.gpuEnvironmentKey)
            .addValue("duelStart", duel?.startAt?.let(Timestamp::from))
            .addValue("duelEnd", duel?.endAt?.let(Timestamp::from))
            .addValue("duelFinishedBefore", duel?.ratingAppliedAt?.let(Timestamp::from))
            .addValue("duelProcessor", duel?.processor)
            .addValue("offset", offset)
            .addValue("limit", limit)
        // A lookup per current/pinned id prevents a hash join from reading the growing version
        // history. LIMIT is inside the lateral lookup as well as on the final API page.
        @Language("SQL") val source = if (contest == null) """
            FROM gpucodeforces.problems p
            JOIN LATERAL (
                SELECT id, problem_id, code, title, title_en, difficulty, tags, authors, draft, judge_hash, format, processors
                FROM gpucodeforces.problem_versions WHERE id = p.current_version_id LIMIT 1
            ) v ON true
            WHERE p.current_version_id IS NOT NULL
              AND (NOT v.draft OR EXISTS (SELECT 1 FROM unnest(v.authors) a WHERE lower(a) = lower(CAST(:viewer AS text))))
        """ else """
            FROM gpucodeforces.contest_problems cp
            LEFT JOIN gpucodeforces.problems p ON p.id = cp.problem_id
            JOIN LATERAL (
                SELECT id, problem_id, code, title, title_en, difficulty, tags, authors, draft, judge_hash, format, processors
                FROM gpucodeforces.problem_versions WHERE id = coalesce(cp.problem_version_id, p.current_version_id) LIMIT 1
            ) v ON true
            WHERE cp.contest_id = CAST(:contestId AS uuid)
        """
        // Practice counts a solution in any context, a contest only its own (SubmissionStats.problemResults).
        @Language("SQL") val solvedExpression = """
            EXISTS (SELECT 1 FROM gpucodeforces.submissions s
                    WHERE s.problem_id = v.problem_id AND s.user_id = CAST(:subjectId AS uuid)
                      AND s.status = 'accepted'
                      AND (:contextKey = 'practice' OR s.context_key = :contextKey)
                      AND (CAST(:duelStart AS timestamptz) IS NULL OR (
                          s.created_at >= :duelStart AND s.created_at < :duelEnd
                          AND s.judge_hash = v.judge_hash AND s.processor = :duelProcessor
                          AND (:duelProcessor <> 'gpu-cuda' OR s.benchmark_environment_key = :environmentKey)
                          AND (CAST(:duelFinishedBefore AS timestamptz) IS NULL OR s.finished_at IS NULL OR s.finished_at <= :duelFinishedBefore))))
        """
        @Language("SQL") val filters = """
            AND (CAST(:query AS text) = '' OR strpos(lower(v.code || ' ' || v.title || ' ' || coalesce(v.title_en, '')), :query) > 0)
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
               AND (CAST(:duelStart AS timestamptz) IS NULL OR (s.created_at >= :duelStart AND s.created_at < :duelEnd))
               AND (CAST(:duelFinishedBefore AS timestamptz) IS NULL OR s.finished_at IS NULL OR s.finished_at <= :duelFinishedBefore)
             ORDER BY s.score LIMIT 1)
        """
        // The order of bankCodeOrder: a code of the bank is a number, and "2" goes before "10".
        @Language("SQL") val byCode = "CASE WHEN v.code ~ '^[0-9]{1,18}\$' THEN CAST(v.code AS bigint) END NULLS LAST, v.code, v.problem_id"
        @Language("SQL") val order = when {
            contest != null -> "cp.position"
            filter.sort == SORT_DIFFICULTY -> "CASE v.difficulty WHEN 'easy' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, $byCode"
            filter.sort == SORT_BEST_SCORE -> "$bestScore NULLS LAST, $byCode"
            else -> byCode
        }
        @Language("SQL") val pageSql = "SELECT v.id $source $filters ORDER BY $order LIMIT :limit OFFSET :offset"
        val versionIds = jdbc.query(pageSql, parameters) { row, _ -> row.getObject("id", UUID::class.java) }
        @Language("SQL") val countSql = "SELECT count(*) $source $filters"
        val total = jdbc.queryForObject(countSql, parameters, Int::class.java)!!
        // Tags belong to the visible context, independent of search filters and page boundaries.
        @Language("SQL") val tagsSql = "SELECT DISTINCT unnest(v.tags) AS tag $source ORDER BY tag"
        val tags = jdbc.query(tagsSql, parameters) { row, _ -> row.getString("tag") }
        @Language("SQL") val progressSql = "SELECT count(*) AS pool, count(*) FILTER (WHERE $solvedExpression) AS solved $source"
        val (poolSize, solvedCount) = jdbc.queryForObject(progressSql, parameters) { row, _ -> row.getInt("pool") to row.getInt("solved") }!!
        return ProblemPage(versionIds, total, tags, poolSize, solvedCount)
    }

    companion object {
        const val SORT_CODE = "code"
        const val SORT_DIFFICULTY = "difficulty"
        const val SORT_BEST_SCORE = "bestScore"
        val SORTS = setOf(SORT_CODE, SORT_DIFFICULTY, SORT_BEST_SCORE)
    }
}

/** Order of problem codes in the bank: numbers by their value, so "2" goes before "10"; any other code after them, as text. */
val bankCodeOrder: Comparator<String> = compareBy<String> { it.toLongOrNull() == null }.thenBy { it.toLongOrNull() ?: 0 }.thenBy { it }
