package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.ContentLanguage
import java.math.BigDecimal

data class ProblemFormatData(
    val format: String = STANDARD,
    val baselineTimeMs: BigDecimal? = null,
    val firstTimeMs: BigDecimal? = null,
    val secondTimeMs: BigDecimal? = null,
    /** Why one code of a guess is cheaper, in Russian. */
    val explanation: String = "",
    /** The same in English; null in a comparison a user published and in versions imported before two languages. */
    val explanationEn: String? = null,
    val firstCode: String? = null,
    val secondCode: String? = null,
    val benchmark: BenchmarkDefinition? = null,
    val benchmarkSynthetic: Boolean? = null,
) {
    fun explanation(language: ContentLanguage): String = explanationEn?.takeIf { language == ContentLanguage.EN } ?: explanation

    fun aggregation(): String = if (format == STANDARD) BenchmarkDefinition.MAX else benchmark?.aggregation ?: BenchmarkDefinition.MAX

    companion object {
        const val STANDARD = "standard"
        const val OPTIMIZATION = "optimization"
        const val GUESS = "guess"
        val FORMATS = setOf(STANDARD, OPTIMIZATION, GUESS)
        val CHOICES = setOf("first", "second", "equal")
    }
}

data class BenchmarkDefinition(
    /** What the measured tests are, in Russian. */
    val inputDescription: String,
    val aggregation: String = MAX,
    /** The same in English; null in versions imported before two languages. */
    val inputDescriptionEn: String? = null,
) {
    fun inputDescription(language: ContentLanguage): String = inputDescriptionEn?.takeIf { language == ContentLanguage.EN } ?: inputDescription


    companion object {
        const val MAX = "max"
        const val GEOMETRIC_MEAN = "geometric_mean"
        val AGGREGATIONS = setOf(MAX, GEOMETRIC_MEAN)
    }
}
