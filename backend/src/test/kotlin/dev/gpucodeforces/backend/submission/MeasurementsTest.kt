package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.problem.BenchmarkDefinition
import dev.gpucodeforces.backend.problem.ProblemPackageReader
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.TimingMode
import dev.gpucodeforces.judge.api.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path

class MeasurementsTest {
    private val spec = ProblemPackageReader(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        .read(Path.of("../judge/problems/examples/vector_add")).spec

    private fun result(vararg times: Double) = JudgeResult(
        Verdict.ACCEPTED,
        tests = times.mapIndexed { index, time -> TestResult(index + 1, "test-${index + 1}", index == 0, Verdict.ACCEPTED, timeMs = time, runs = index + 1) },
        maxTimeMs = times.maxOrNull(),
        timingMode = TimingMode.WALL,
        gpu = GpuInfo("rtx3060", "NVIDIA GeForce RTX 3060", 0, "executor"),
        toolchain = "nvcc 12.8",
    )

    @Test
    fun `geometric mean of one and four milliseconds is two while legacy maximum remains four`() {
        val measured = result(1.0, 4.0)

        assertThat(captureMeasurement(measured, spec, BenchmarkDefinition.GEOMETRIC_MEAN, false).timeMs).isEqualByComparingTo("2.0")
        assertThat(captureMeasurement(measured, spec, BenchmarkDefinition.MAX, false).timeMs).isEqualByComparingTo("4.0")
    }

    @Test
    fun `a zero measurement has a zero geometric mean without taking its logarithm`() {
        assertThat(captureMeasurement(result(0.0, 4.0), spec, BenchmarkDefinition.GEOMETRIC_MEAN, false).timeMs).isZero()
    }

    @Test
    fun `keeps actual per test measurements and execution provenance`() {
        val capture = captureMeasurement(result(1.0, 4.0), spec, BenchmarkDefinition.GEOMETRIC_MEAN, false)

        assertThat(capture.data.aggregation).isEqualTo("geometric_mean")
        assertThat(capture.data.timingMode).isEqualTo("wall")
        assertThat(capture.data.gpuName).isEqualTo("NVIDIA GeForce RTX 3060")
        assertThat(capture.data.toolchain).isEqualTo("nvcc 12.8")
        assertThat(capture.data.tests.map { it.timeMs.toPlainString() }).containsExactly("1.0", "4.0")
        assertThat(capture.data.tests.map { it.runs }).containsExactly(1, 2)
        assertThat(capture.data.synthetic).isFalse()
        assertThat(captureMeasurement(result(1.0).copy(toolchain = "mock (no compiler, no GPU)"), spec, "max", false).data.synthetic).isTrue()
    }

    @Test
    fun `invalid or incomplete measurements cannot become accepted performance results`() {
        val valid = result(1.0, 4.0)
        val invalid = listOf(
            result(),
            result(Double.NaN),
            result(Double.POSITIVE_INFINITY),
            result(-1.0),
            valid.copy(tests = listOf(valid.tests[0].copy(timeMs = null), valid.tests[1])),
            valid.copy(tests = listOf(valid.tests[0], valid.tests[1].copy(index = 3))),
            valid.copy(maxTimeMs = null),
            valid.copy(timingMode = TimingMode.GPU_KERNELS),
            valid.copy(tests = listOf(valid.tests[0].copy(verdict = Verdict.WRONG_ANSWER), valid.tests[1])),
        )
        invalid.forEach { measured ->
            assertThatThrownBy { captureMeasurement(measured, spec, "geometric_mean", false) }.isInstanceOf(InvalidMeasurementException::class.java)
        }
        assertThatThrownBy { captureMeasurement(valid, spec, "geometric_mean", false, 3) }.isInstanceOf(InvalidMeasurementException::class.java)
    }

    @Test
    fun `logarithmic aggregation avoids multiplying very large durations`() {
        val capture = captureMeasurement(result(1e200, 1e200), spec, "geometric_mean", false)

        assertThat(capture.timeMs.toDouble()).isCloseTo(1e200, Percentage.withPercentage(0.000001))
    }
}
