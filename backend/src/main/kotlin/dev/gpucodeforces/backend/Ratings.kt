package dev.gpucodeforces.backend

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

@Component
class Ratings(private val db: Database, private val service: PlatformService) {
    @Scheduled(fixedDelay = 5000)
    fun update() = db.tx {
        db.lock(5)
        db.update("DELETE FROM app_session WHERE expires_at < now()")
        if (db.count("SELECT count(*) FROM contest WHERE end_at <= now() AND NOT rated") == 0L) return@tx false
        // Rebuild in chronological order: a late successful recheck can change later expectations too.
        db.update("/* full-scan:rating-rebuild */ DELETE FROM rating_event")
        db.update("/* full-scan:rating-rebuild */ UPDATE app_user SET rating = NULL, rating_rank = NULL")
        db.update("/* full-scan:rating-rebuild */ UPDATE contest SET rated = false WHERE end_at <= now()")
        val ratings = mutableMapOf<String, Int>()
        val contests = db.rows(
            "/* full-scan:rating-rebuild */ SELECT id FROM contest WHERE end_at <= now() ORDER BY end_at, id",
        )
        for (contest in contests) {
            val id = contest["id"] as UUID
            if (db.count(
                    """
                        SELECT count(*) FROM submission WHERE contest_id = ? AND NOT example AND finished_at IS NULL
                    """.trimIndent(), id
                ) > 0
            ) break
            val standings = service.standings(id).filter { row ->
                val userId = (row["user"] as Map<*, *>)["id"].toString()
                db.count(
                    """
                        SELECT count(*) FROM submission WHERE contest_id = ? AND user_id = ? AND NOT example
                    """.trimIndent(), id, userId
                ) > 0
            }
            val participants =
                standings.map { (it["user"] as Map<*, *>)["id"].toString() to (it["rank"] as Number).toLong() }
            val changes = participants.associate { (userId, rank) ->
                val old = ratings[userId] ?: 1500
                val delta =
                    if (participants.size < 2) 0 else (32.0 * participants.filter { it.first != userId }
                        .sumOf { (other, otherRank) ->
                            val actual = if (rank == otherRank) 0.5 else if (rank < otherRank) 1.0 else 0.0
                            actual - 1.0 / (1.0 + 10.0.pow(((ratings[other] ?: 1500) - old) / 400.0))
                        } / (participants.size - 1)).roundToInt()
                userId to delta
            }
            changes.forEach { (userId, delta) ->
                val after = (ratings[userId] ?: 1500) + delta
                ratings[userId] = after
                db.update("INSERT INTO rating_event VALUES (?, ?, ?, ?)", id, userId, delta, after)
                db.update("UPDATE app_user SET rating = ? WHERE id = ?", after, userId)
            }
            db.update("UPDATE contest SET rated = true WHERE id = ?", id)
        }
        db.update(
            """
                /* full-scan:rating-rebuild */
                UPDATE app_user u SET rating_rank = r.position FROM (
                    SELECT id, rank() OVER (ORDER BY rating DESC) AS position
                    FROM app_user WHERE rating IS NOT NULL
                ) r WHERE r.id = u.id
            """.trimIndent(),
        )
        true
    }
}
