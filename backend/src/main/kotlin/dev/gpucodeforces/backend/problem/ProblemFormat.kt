package dev.gpucodeforces.backend.problem

import java.math.BigDecimal

data class ProblemFormatData(
    val format: String = STANDARD,
    val baselineTimeMs: BigDecimal? = null,
    val firstTimeMs: BigDecimal? = null,
    val secondTimeMs: BigDecimal? = null,
    val explanation: String = "",
    val firstCode: String? = null,
    val secondCode: String? = null,
    val benchmark: BenchmarkDefinition? = null,
    val benchmarkSynthetic: Boolean? = null,
) {
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
    val inputDescription: String,
    val aggregation: String = MAX,
) {
    companion object {
        const val MAX = "max"
        const val GEOMETRIC_MEAN = "geometric_mean"
        val AGGREGATIONS = setOf(MAX, GEOMETRIC_MEAN)
    }
}
