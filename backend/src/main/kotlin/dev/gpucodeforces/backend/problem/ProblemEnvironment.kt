package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.judge.api.CudaSignature
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.TimingMode

/** "Environment" block of the problem page: hardware and how the judge measures, derived from the judge spec. */
fun environmentMarkdown(spec: ProblemSpec, gpuName: String): String {
    val timing = when (spec.timing.effectiveMode) {
        TimingMode.WALL -> "весь вызов `solve` до завершения запущенной им работы на GPU"
        TimingMode.GPU_KERNELS -> "суммарное время ядер, запущенных `solve`"
    }
    val memory = spec.signature.arrays.joinToString(", ") { "${it.name}: ${if (it.memory == MemorySpace.HOST) "память CPU" else "память GPU"}" }
    return listOf(
        "| Параметр | Значение |",
        "| --- | --- |",
        "| GPU | $gpuName |",
        "| Архитектура | ${spec.compile.arch ?: "архитектура GPU"} |",
        "| Флаги nvcc | ${spec.compile.flags?.joinToString(" ") { "`$it`" } ?: "по умолчанию"} |",
        "| Замер | $timing |",
        "| Прогрев | ${spec.timing.warmupRuns} запуск(а) на первом тесте |",
        "| Повторы около лимита | до ${spec.timing.maxRuns}, берётся медиана |",
        "| Лимит времени | ${spec.limits.timeLimitMs} мс на вызов |",
        "| Память | ${spec.limits.memoryLimitMb} МБ RAM, GPU — ${spec.limits.gpuMemoryLimitMb?.let { "$it МБ" } ?: "вся"} сверх данных теста |",
        "| Расположение данных | $memory |",
        "",
        "Время посылки — максимум по тестам. Сигнатура: `${CudaSignature.declaration(spec.signature)}`.",
    ).joinToString("\n")
}
