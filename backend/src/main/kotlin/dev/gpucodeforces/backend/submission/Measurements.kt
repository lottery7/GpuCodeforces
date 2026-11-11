package dev.gpucodeforces.backend.submission

import com.fasterxml.jackson.annotation.JsonAlias
import dev.gpucodeforces.backend.problem.BenchmarkDefinition
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Verdict
import java.math.BigDecimal
import kotlin.math.exp
import kotlin.math.ln

/** Score of one measured test; rows written before processors carry it as `timeMs`. */
data class MeasuredTest(@param:JsonAlias("timeMs") val value: BigDecimal, val runs: Int?)

/**
 * How a score was measured, kept with the submission: results are compared only under the same conditions. Rows
 * written before processors are times on the real GPU: [processor] and [scoreType] default to that, `gpuName` is [model].
 */
data class MeasurementData(
    val aggregation: String,
    /** `gpu-cuda` only. */
    val timingMode: String?,
    @param:JsonAlias("gpuName") val model: String?,
    val toolchain: String?,
    val synthetic: Boolean,
    val tests: List<MeasuredTest>,
    val processor: String = Processors.GPU_CUDA,
    val scoreType: ScoreType = ScoreType.TIME_MS,
)

class MeasurementCapture(val score: BigDecimal, val data: MeasurementData)

class InvalidMeasurementException(message: String) : RuntimeException(message)

/**
 * The score of an accepted result under the problem's aggregation: the maximum over the measured tests, as the
 * judge's own score, or their geometric mean for the formats (docs/backend/formats.md). Tests the generator marked
 * `measure: false` are only checked. Any processor: the scores are in its type.
 */
fun captureMeasurement(result: JudgeResult, spec: ProblemSpec, aggregation: String, synthetic: Boolean, expectedTestCount: Int? = null): MeasurementCapture {
    fun invalid(message: String): Nothing = throw InvalidMeasurementException(message)
    if (aggregation !in BenchmarkDefinition.AGGREGATIONS) invalid("Неизвестный способ подсчёта результата")
    if (result.verdict != Verdict.ACCEPTED || result.tests.isEmpty()) invalid("Нет результатов всех пройденных тестов")
    if (expectedTestCount != null && expectedTestCount != result.tests.size) invalid("Не хватает результатов объявленного набора тестов")
    if (result.tests.map { it.index } != (1..result.tests.size).toList()) invalid("Нарушен порядок результатов тестов")
    val run = result.processor ?: invalid("Неизвестно, на каком процессоре проверено решение")
    val scoreType = Processors.find(run.id)?.scoreType ?: invalid("Неизвестный процессор ${run.id}")
    val measured = result.tests.filter { it.measured }
    if (measured.isEmpty()) invalid("Нет замерных тестов")
    val values = measured.map { test ->
        val value = test.score?.takeIf { it.type == scoreType }?.value
        if (test.verdict != Verdict.ACCEPTED || value == null || !value.isFinite() || value < 0 || test.runs?.let { it < 1 } == true) {
            invalid("Некорректный замер теста")
        }
        value
    }
    val timing = if (run.id == Processors.GPU_CUDA) {
        val timing = result.timingMode ?: spec.timing.effectiveMode
        if (timing != spec.timing.effectiveMode) invalid("Способ замера не совпадает с условиями задачи")
        timing
    } else {
        null
    }
    val max = result.score?.takeIf { it.type == scoreType }?.value
    if (max == null || !max.isFinite() || max < 0 || max != values.max()) invalid("Некорректный максимум по тестам")
    val aggregate = when (aggregation) {
        BenchmarkDefinition.GEOMETRIC_MEAN -> if (values.any { it == 0.0 }) 0.0 else exp(values.sumOf { ln(it) } / values.size)
        else -> max
    }
    if (!aggregate.isFinite()) invalid("Не удалось вычислить итоговый результат")
    return MeasurementCapture(
        BigDecimal.valueOf(aggregate),
        MeasurementData(
            aggregation = aggregation,
            timingMode = timing?.name?.lowercase(),
            model = result.gpu?.name ?: run.model,
            toolchain = run.version,
            synthetic = synthetic || run.version.startsWith("mock "),
            tests = measured.map { MeasuredTest(BigDecimal.valueOf(it.score!!.value), it.runs) },
            processor = run.id,
            scoreType = scoreType,
        ),
    )
}
