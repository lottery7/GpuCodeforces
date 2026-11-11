package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.problem.BenchmarkDefinition
import dev.gpucodeforces.backend.problem.ProblemPackageReader
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProcessorRun
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
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
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val spec = ProblemPackageReader(mapper).read(Path.of("../judge/problems/examples/vector_add")).spec

    private fun result(vararg times: Double) = JudgeResult(
        Verdict.ACCEPTED,
        tests = times.mapIndexed { index, time ->
            TestResult(index + 1, "test-${index + 1}", index == 0, Verdict.ACCEPTED, score = Score(time, ScoreType.TIME_MS), runs = index + 1)
        },
        score = times.maxOrNull()?.let { Score(it, ScoreType.TIME_MS) },
        timingMode = TimingMode.WALL,
        gpu = GpuInfo("rtx3060", "NVIDIA GeForce RTX 3060", 0, "executor"),
        processor = ProcessorRun("gpu-cuda", "nvcc 12.8", "NVIDIA GeForce RTX 3060", "executor"),
    )

    /** An `rtl` result: cycles x depth x transistors per test, no timing of the GPU. */
    private fun rtl(vararg scores: Double, measured: List<Boolean> = scores.map { true }) = JudgeResult(
        Verdict.ACCEPTED,
        tests = scores.mapIndexed { index, value ->
            TestResult(index + 1, "test-${index + 1}", false, Verdict.ACCEPTED, score = Score(value, ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS), measured = measured[index])
        },
        score = scores.filterIndexed { index, _ -> measured[index] }.maxOrNull()?.let { Score(it, ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS) },
        processor = ProcessorRun("rtl", "Yosys 0.70+286, Verilator 5.053", "Verilator 5.053 + Yosys 0.70+286", "emu"),
    )

    @Test
    fun `geometric mean of one and four milliseconds is two while legacy maximum remains four`() {
        val measured = result(1.0, 4.0)

        assertThat(captureMeasurement(measured, spec, BenchmarkDefinition.GEOMETRIC_MEAN, false).score).isEqualByComparingTo("2.0")
        assertThat(captureMeasurement(measured, spec, BenchmarkDefinition.MAX, false).score).isEqualByComparingTo("4.0")
    }

    @Test
    fun `an emulated processor aggregates its own scores over the measured tests only`() {
        val measured = rtl(100.0, 400.0, 1e9, measured = listOf(true, true, false))

        val mean = captureMeasurement(measured, spec, BenchmarkDefinition.GEOMETRIC_MEAN, false)
        assertThat(mean.score.toDouble()).isCloseTo(200.0, Percentage.withPercentage(1e-9))
        assertThat(mean.data.processor).isEqualTo("rtl")
        assertThat(mean.data.scoreType).isEqualTo(ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS)
        assertThat(mean.data.timingMode).isNull()
        assertThat(mean.data.model).isEqualTo("Verilator 5.053 + Yosys 0.70+286")
        assertThat(mean.data.tests).hasSize(2)
        assertThat(captureMeasurement(measured, spec, BenchmarkDefinition.MAX, false).score).isEqualByComparingTo("400.0")
        // a score of another processor's type is not a measurement of this one
        val foreign = measured.copy(tests = measured.tests.map { it.copy(score = Score(it.score!!.value, ScoreType.CYCLES)) })
        assertThatThrownBy { captureMeasurement(foreign, spec, "max", false) }.isInstanceOf(InvalidMeasurementException::class.java)
    }

    @Test
    fun `a zero measurement has a zero geometric mean without taking its logarithm`() {
        assertThat(captureMeasurement(result(0.0, 4.0), spec, BenchmarkDefinition.GEOMETRIC_MEAN, false).score).isZero()
    }

    @Test
    fun `keeps actual per test measurements and execution provenance`() {
        val capture = captureMeasurement(result(1.0, 4.0), spec, BenchmarkDefinition.GEOMETRIC_MEAN, false)

        assertThat(capture.data.aggregation).isEqualTo("geometric_mean")
        assertThat(capture.data.timingMode).isEqualTo("wall")
        assertThat(capture.data.model).isEqualTo("NVIDIA GeForce RTX 3060")
        assertThat(capture.data.toolchain).isEqualTo("nvcc 12.8")
        assertThat(capture.data.tests.map { it.value.toPlainString() }).containsExactly("1.0", "4.0")
        assertThat(capture.data.tests.map { it.runs }).containsExactly(1, 2)
        assertThat(capture.data.synthetic).isFalse()
        val mock = result(1.0).let { it.copy(processor = it.processor!!.copy(version = "mock (no compiler, no GPU, no emulator)")) }
        assertThat(captureMeasurement(mock, spec, "max", false).data.synthetic).isTrue()
    }

    @Test
    fun `measurements stored before processors are read as times on the real GPU`() {
        val legacy = """{"aggregation":"max","timingMode":"wall","gpuName":"NVIDIA GeForce RTX 3060","toolchain":"nvcc","synthetic":false,
            "tests":[{"timeMs":1.5,"runs":1}]}"""
        val data = mapper.readValue(legacy, MeasurementData::class.java)
        assertThat(data.model).isEqualTo("NVIDIA GeForce RTX 3060")
        assertThat(data.tests.single().value).isEqualByComparingTo("1.5")
        assertThat(data.processor).isEqualTo("gpu-cuda")
        assertThat(data.scoreType).isEqualTo(ScoreType.TIME_MS)
    }

    @Test
    fun `invalid or incomplete measurements cannot become accepted performance results`() {
        val valid = result(1.0, 4.0)
        val invalid = listOf(
            result(),
            result(Double.NaN),
            result(Double.POSITIVE_INFINITY),
            result(-1.0),
            valid.copy(tests = listOf(valid.tests[0].copy(score = null), valid.tests[1])),
            valid.copy(tests = listOf(valid.tests[0], valid.tests[1].copy(index = 3))),
            valid.copy(score = null),
            valid.copy(processor = null),
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

        assertThat(capture.score.toDouble()).isCloseTo(1e200, Percentage.withPercentage(0.000001))
    }
}
