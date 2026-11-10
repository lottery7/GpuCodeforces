package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.api.model.Benchmark
import dev.gpucodeforces.backend.api.model.BenchmarkAggregation
import dev.gpucodeforces.backend.api.model.MeasurementTiming
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.TimingMode

/** "Environment" block of the problem page: hardware and how the judge measures, derived from the judge spec. */
fun environmentMarkdown(spec: ProblemSpec, gpuName: String?): String {
    val timing = when (spec.timing.effectiveMode) {
        TimingMode.WALL -> "весь вызов `solve` до завершения запущенной им работы на GPU"
        TimingMode.GPU_KERNELS -> "время выполнения ядер, запущенных `solve`, без двойного подсчёта пересекающихся запусков"
    }
    return buildList {
        gpuName?.let { add("GPU: $it.") }
        add("Замер: $timing.")
        add("Прогрев: ${spec.timing.warmupRuns} запуск(а) на первом тесте. Обычно один замер на тест; при превышении лимита не более чем на 25% - до ${spec.timing.maxRuns} замеров с медианой.")
    }.joinToString("\n\n")
}

fun benchmark(spec: ProblemSpec, data: ProblemFormatData, gpuName: String?, synthetic: Boolean): Benchmark = Benchmark()
    .inputDescription(data.benchmark?.inputDescription ?: "Набор тестов задачи")
    .aggregation(BenchmarkAggregation.fromValue(data.aggregation()))
    .timingMode(MeasurementTiming.fromValue(spec.timing.effectiveMode.name.lowercase()))
    .warmupRuns(spec.timing.warmupRuns)
    .maxRuns(spec.timing.maxRuns)
    .gpuName(gpuName.takeUnless { synthetic || data.benchmarkSynthetic == true })
    .synthetic(synthetic || data.benchmarkSynthetic == true)
