package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.ContentLanguage
import dev.gpucodeforces.backend.processor.KnownTest
import dev.gpucodeforces.judge.api.CSignature
import dev.gpucodeforces.judge.api.CudaSignature
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.backend.api.model.Benchmark
import dev.gpucodeforces.backend.api.model.BenchmarkAggregation
import dev.gpucodeforces.backend.api.model.MeasurementTiming
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.RtlDataMode
import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.TimingMode
import java.math.BigDecimal

/** Unit of a score in Russian text, e.g. "мс" or "тактов". */
fun ScoreType.unit(): String = when (this) {
    ScoreType.TIME_MS -> "мс"
    ScoreType.CYCLES -> "тактов"
    ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS -> "тактов × глубины × транзисторов"
    ScoreType.WEIGHTED_INSTRUCTIONS -> "усл. тактов"
    ScoreType.INSTRUCTIONS -> "инструкций"
}

/** What "the limit" is called on a processor: "лимит времени", "лимит тактов". */
fun ScoreType.limitName(): String = when (this) {
    ScoreType.TIME_MS -> "лимит времени"
    ScoreType.CYCLES -> "лимит тактов"
    ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS -> "лимит тактов × глубины × транзисторов"
    ScoreType.WEIGHTED_INSTRUCTIONS -> "лимит условных тактов"
    ScoreType.INSTRUCTIONS -> "лимит инструкций"
}

private fun ScoreType.meaning(): String = when (this) {
    ScoreType.TIME_MS -> "время на GPU, мс"
    ScoreType.CYCLES -> "такты симулятора"
    ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS ->
        "такты до `done` × глубина логики (ячеек на самом длинном пути между регистрами) × транзисторы схемы после синтеза"
    ScoreType.WEIGHTED_INSTRUCTIONS -> "инструкции + 10 × промахи L1 + 100 × промахи последнего уровня кэша"
    ScoreType.INSTRUCTIONS -> "инструкции, выполненные ядром до остановки"
}

/**
 * A limit as the page shows it, the way the frontend shows it (`formatLimit` in frontend/src/processors.ts). Authors pick
 * round limits, and from a million on such a limit reads better as a power of ten: `3,5·10⁹`, `10¹³`. Any other keeps
 * all its digits; integers have no fraction.
 */
fun formatLimit(value: Double): String {
    val plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    val significant = plain.trimEnd('0')
    if (value < 1e6 || '.' in plain || significant.length > 3) return plain
    val power = "10" + (plain.length - 1).toString().map { SUPERSCRIPT_DIGITS[it - '0'] }.joinToString("")
    val mantissa = significant.first() + significant.drop(1).let { if (it.isEmpty()) "" else ",$it" }
    return if (mantissa == "1") power else "$mantissa·$power"
}

private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

/**
 * "Environment" block of the problem page for one processor: hardware or emulator, how the judge measures, the
 * limit and the tests of the processor. [tests] are null until the judge has reported them (see ProcessorTests).
 */
fun environmentMarkdown(spec: ProblemSpec, processor: ProcessorSpec, model: String, tests: List<KnownTest>?, aggregation: String = BenchmarkDefinition.MAX): String {
    val info = Processors.get(processor.id)
    // Validated with the problem: every declared processor has a limit.
    val limit = spec.scoreLimit(processor)!!
    val rows = buildList {
        add("| Процессор | ${info.title} (`${info.id}`) |")
        add("| Модель | $model |")
        add("| Языки | ${processor.languages.joinToString(", ") { info.language(it)!!.title }} |")
        add("| Score | ${info.scoreType.meaning()}; меньше — лучше |")
        add("| ${info.scoreType.limitName().replaceFirstChar(Char::uppercase)} | ${formatLimit(limit)} ${info.scoreType.unit()} на замерный тест |")
        when (info.id) {
            Processors.GPU_CUDA -> addAll(gpuRows(spec))
            Processors.CUDA_SIM -> {
                add("| Флаги nvcc | ${spec.compile.flags?.joinToString(" ") { "`$it`" } ?: "по умолчанию"} |")
                add("| Замер | один прогон в симуляторе, такты всех ядер `solve` |")
            }
            else -> add("| Замер | один прогон в эмуляторе на тест, результат не зависит от нагрузки машины |")
        }
        if (info.id == Processors.RTL) addAll(rtlRows(processor.rtl ?: RtlSettings()))
        // the memory of the Coral NPU core is the processor's, not a limit of the problem
        if (info.id == Processors.CORAL_NPU) add("| Память | 8 КБ кода и 32 КБ данных вместе с массивами теста |")
        else add("| Память | ${spec.limits.memoryLimitMb} МБ RAM сверх данных теста |")
    }
    val signature = when (info.id) {
        Processors.GPU_CUDA, Processors.CUDA_SIM -> "Сигнатура: `${CudaSignature.declaration(spec.signature)}`."
        Processors.RTL -> "Интерфейс модуля `${spec.signature.function}` — в шаблоне решения."
        else -> "Сигнатура: `${CSignature.declaration(spec.signature)}`."
    }
    val aggregate = if (aggregation == BenchmarkDefinition.GEOMETRIC_MEAN) "геометрическое среднее по замерным тестам" else "максимум по замерным тестам"
    return (listOf("| Параметр | Значение |", "| --- | --- |") + rows + listOf("", "Score посылки - $aggregate. $signature", "", testsMarkdown(tests)))
        .joinToString("\n")
}

private fun gpuRows(spec: ProblemSpec): List<String> {
    val timing = when (spec.timing.effectiveMode) {
        TimingMode.WALL -> "весь вызов `solve` до завершения запущенной им работы на GPU"
        TimingMode.GPU_KERNELS -> "время выполнения ядер, запущенных `solve`, без двойного подсчёта пересекающихся запусков"
    }
    val memory = spec.signature.arrays.joinToString(", ") { "${it.name}: ${if (it.memory == MemorySpace.HOST) "память CPU" else "память GPU"}" }
    return listOf(
        "| Архитектура | ${spec.compile.arch ?: "архитектура GPU"} |",
        "| Флаги nvcc | ${spec.compile.flags?.joinToString(" ") { "`$it`" } ?: "по умолчанию"} |",
        "| Замер | $timing |",
        "| Прогрев | ${spec.timing.warmupRuns} запуск(а) на первом тесте |",
        "| Повторы около лимита | до ${spec.timing.maxRuns}, берётся медиана |",
        "| Память GPU | ${spec.limits.gpuMemoryLimitMb?.let { "$it МБ" } ?: "вся"} сверх данных теста |",
        "| Расположение данных | $memory |",
    )
}

private fun rtlRows(settings: RtlSettings): List<String> = listOf(
    "| Данные | ${if (settings.dataMode == RtlDataMode.MEMORY) "память судьи с портами у каждого массива" else "поток: элемент за такт"} |",
) + if (settings.dataMode == RtlDataMode.MEMORY) listOf("| Портов чтения | ${settings.readPorts} у каждого читаемого массива |") else emptyList()

private fun testsMarkdown(tests: List<KnownTest>?): String {
    if (tests == null) {
        return "Тесты: список появится после первой принятой посылки на этом процессоре. Замерные — все тесты, " +
            "кроме тех, где автор оставил только проверку ответа."
    }
    val lines = tests.mapIndexed { index, test ->
        val kind = if (test.measured) "замерный" else "только проверка ответа"
        "${index + 1}. `${test.name}`${if (test.sample) " (пример)" else ""} — $kind"
    }
    return (listOf("Тесты этого процессора:", "") + lines).joinToString("\n")
}

/**
 * How results of a format are compared on [processor]: the aggregation over the measured tests, and on the real GPU
 * also how a test is timed. [model] is hidden in the test mode, where nothing runs.
 */
fun benchmark(
    spec: ProblemSpec, data: ProblemFormatData, processor: String, model: String?, synthetic: Boolean, language: ContentLanguage,
): Benchmark {
    val gpu = processor == Processors.GPU_CUDA
    return Benchmark()
        .inputDescription(data.benchmark?.inputDescription(language) ?: "Набор тестов задачи")
        .aggregation(BenchmarkAggregation.fromValue(data.aggregation()))
        .timingMode(if (gpu) MeasurementTiming.fromValue(spec.timing.effectiveMode.name.lowercase()) else null)
        .warmupRuns(if (gpu) spec.timing.warmupRuns else null)
        .maxRuns(if (gpu) spec.timing.maxRuns else null)
        .model(model.takeUnless { synthetic })
        .synthetic(synthetic)
}
