package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.problem.BenchmarkDefinition
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Verdict
import java.math.BigDecimal
import kotlin.math.exp
import kotlin.math.ln

data class MeasuredTest(val timeMs: BigDecimal, val runs: Int?)

data class MeasurementData(
    val aggregation: String,
    val timingMode: String,
    val gpuName: String?,
    val toolchain: String?,
    val synthetic: Boolean,
    val tests: List<MeasuredTest>,
)

class MeasurementCapture(val timeMs: BigDecimal, val data: MeasurementData)

class InvalidMeasurementException(message: String) : RuntimeException(message)

fun captureMeasurement(result: JudgeResult, spec: ProblemSpec, aggregation: String, synthetic: Boolean, expectedTestCount: Int? = null): MeasurementCapture {
    fun invalid(message: String): Nothing = throw InvalidMeasurementException(message)
    if (aggregation !in BenchmarkDefinition.AGGREGATIONS) invalid("Неизвестный способ подсчёта времени")
    if (result.verdict != Verdict.ACCEPTED || result.tests.isEmpty()) invalid("Нет результатов всех пройденных тестов")
    if (expectedTestCount != null && expectedTestCount != result.tests.size) invalid("Не хватает результатов объявленного набора тестов")
    if (result.tests.map { it.index } != (1..result.tests.size).toList()) invalid("Нарушен порядок результатов тестов")
    val values = result.tests.map { test ->
        val time = test.timeMs
        if (test.verdict != Verdict.ACCEPTED || time == null || !time.isFinite() || time < 0 || test.runs?.let { it < 1 } == true) {
            invalid("Некорректный замер времени теста")
        }
        time
    }
    val timing = result.timingMode ?: spec.timing.effectiveMode
    if (timing != spec.timing.effectiveMode) invalid("Способ замера не совпадает с условиями задачи")
    val maxTime = result.maxTimeMs
    if (maxTime == null || !maxTime.isFinite() || maxTime < 0 || maxTime != values.max()) invalid("Некорректный максимум времени тестов")
    val aggregate = when (aggregation) {
        BenchmarkDefinition.GEOMETRIC_MEAN -> if (values.any { it == 0.0 }) 0.0 else exp(values.sumOf { ln(it) } / values.size)
        else -> maxTime
    }
    if (!aggregate.isFinite()) invalid("Не удалось вычислить итоговое время")
    return MeasurementCapture(
        BigDecimal.valueOf(aggregate),
        MeasurementData(
            aggregation,
            timing.name.lowercase(),
            result.gpu?.name,
            result.toolchain,
            synthetic || result.toolchain?.startsWith("mock ") == true,
            result.tests.map { MeasuredTest(BigDecimal.valueOf(it.timeMs!!), it.runs) },
        ),
    )
}
