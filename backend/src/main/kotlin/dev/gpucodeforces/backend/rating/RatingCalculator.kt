package dev.gpucodeforces.backend.rating

import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ParticipantResult(val userId: UUID, val ratingBefore: Int?, val rank: Int)

data class RatingOutcome(val delta: Int, val ratingAfter: Int)

private const val DEFAULT_RATING = 1500
private const val MAX_DELTA = 400
private const val MIN_SEARCH_RATING = 1
private const val MAX_SEARCH_RATING = 8000
private const val SEARCH_ITERATIONS = 30

/**
 * A simplified version of the Codeforces rating formula: for each participant, binary-search the
 * rating at which their "seed" (sum of pairwise win probabilities against the field) would match
 * the geometric mean of that seed and their actual rank, then move halfway there. Unrated
 * participants get the target rating directly (no halving), matching Codeforces' treatment of
 * first contests. A global zero-sum correction (subtract the mean delta from everyone) stands in
 * for Codeforces' more elaborate top-sqrt(n) inflation adjustment.
 */
object RatingCalculator {
    fun compute(results: List<ParticipantResult>): Map<UUID, RatingOutcome> {
        if (results.size < 2) {
            return results.associate { it.userId to RatingOutcome(0, it.ratingBefore ?: DEFAULT_RATING) }
        }
        val ratings = results.associate { it.userId to (it.ratingBefore ?: DEFAULT_RATING) }
        val rawDeltas = results.associate { participant ->
            val seed = seedOf(participant.userId, results, ratings)
            val midRank = sqrt(seed * participant.rank)
            val target = targetRatingFor(participant.userId, midRank, results, ratings)
            val delta = if (participant.ratingBefore == null) target - DEFAULT_RATING else (target - participant.ratingBefore) / 2
            participant.userId to delta
        }
        val mean = rawDeltas.values.average()
        return results.associate { participant ->
            val before = participant.ratingBefore ?: DEFAULT_RATING
            val delta = (rawDeltas.getValue(participant.userId) - mean).roundToInt().coerceIn(-MAX_DELTA, MAX_DELTA)
            participant.userId to RatingOutcome(delta, before + delta)
        }
    }

    /** Expected rank: 1 plus the chance each other participant beats this one, at their current ratings. */
    private fun seedOf(userId: UUID, all: List<ParticipantResult>, ratings: Map<UUID, Int>): Double {
        val ownRating = ratings.getValue(userId)
        return 1.0 + all.asSequence()
            .filter { it.userId != userId }
            .sumOf { probabilityBeats(ratings.getValue(it.userId), ownRating) }
    }

    /** Probability that a player rated [challenger] beats one rated [opponent]. */
    private fun probabilityBeats(challenger: Int, opponent: Int): Double = 1.0 / (1.0 + 10.0.pow((opponent - challenger) / 400.0))

    /** Binary search the rating R for which seedOf(userId) with rating R equals targetSeed; seed decreases as R grows. */
    private fun targetRatingFor(userId: UUID, targetSeed: Double, all: List<ParticipantResult>, ratings: Map<UUID, Int>): Int {
        val others = all.filter { it.userId != userId }.map { ratings.getValue(it.userId) }
        var low = MIN_SEARCH_RATING
        var high = MAX_SEARCH_RATING
        repeat(SEARCH_ITERATIONS) {
            val mid = (low + high) / 2
            val seed = 1.0 + others.sumOf { probabilityBeats(it, mid) }
            if (seed > targetSeed) low = mid else high = mid
        }
        return low
    }
}
