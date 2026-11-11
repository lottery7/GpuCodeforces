package dev.gpucodeforces.backend.benchmark

import dev.gpucodeforces.backend.submission.JudgeExecutorView
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProcessorRun
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ReferenceEnvironmentTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val gpuName = "Configured GPU"
    private val properties = JudgeProperties()

    private fun executor(
        name: String = "GPU A", id: String = "executor", version: String = "v1", healthy: Boolean = true, alive: Boolean = true,
        lastSeen: Instant = now, toolchain: String = "nvcc test",
    ) = JudgeExecutorView(alive, lastSeen, ExecutorHeartbeat(
        id, "host", listOf(ProcessorStatus(GPU, toolchain, name, healthy, false, 1)), version, now.minusSeconds(3600), now,
        listOf(GpuSlotStatus(0, name, healthy, false)), 0, 1,
    ))

    private fun emulator(id: String = "emu", toolchain: String = "Yosys 0.70+286, Verilator 5.053", healthy: Boolean = true) =
        JudgeExecutorView(true, now, ExecutorHeartbeat(
            id, "host", listOf(ProcessorStatus(RTL, toolchain, "Verilator + Yosys", healthy, false, 2)), "v1", now.minusSeconds(3600), now,
            emptyList(), 0, 2,
        ))

    private fun gpu(views: List<JudgeExecutorView>, judge: JudgeProperties = properties) = referenceEnvironments(views, judge, gpuName, now)[GPU]

    @Test
    fun `hardware changes on the real GPU change the fingerprint while identical extra executors do not`() {
        val original = gpu(listOf(executor()))!!
        val replaced = gpu(listOf(executor("GPU B")))!!
        val expanded = gpu(listOf(executor(), executor(id = "second")))!!
        val upgraded = gpu(listOf(executor(version = "v2")))!!

        assertThat(replaced.key).isNotEqualTo(original.key)
        assertThat(expanded.key).isEqualTo(original.key)
        assertThat(upgraded.key).isNotEqualTo(original.key)
    }

    @Test
    fun `unavailable stale unhealthy or mixed runtimes do not expose an environment`() {
        assertThat(gpu(emptyList())).isNull()
        assertThat(gpu(listOf(executor(alive = false)))).isNull()
        assertThat(gpu(listOf(executor(healthy = false)))).isNull()
        assertThat(gpu(listOf(executor(lastSeen = now.minusSeconds(30))))).isNull()
        assertThat(gpu(listOf(executor(), executor("GPU B", "second")))).isNull()
        assertThat(gpu(listOf(executor(), executor(id = "second", version = "v2")))).isNull()
        assertThat(gpu(listOf(executor("Mock GPU")))).isNull()
    }

    @Test
    fun `result provenance must match an actual healthy GPU and the runtime mode`() {
        val environment = gpu(listOf(executor()))!!
        val result = JudgeResult(
            Verdict.ACCEPTED, gpu = GpuInfo("profile", "GPU A", 0, "executor"), processor = ProcessorRun(GPU, "nvcc test", "GPU A", "executor"),
        )
        assertThat(environment.accepts(result)).isTrue()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(name = "GPU B")))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(executorId = "unknown")))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(index = 1)))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(throttled = true)))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = null))).isFalse()
        assertThat(environment.accepts(result.copy(processor = result.processor!!.copy(version = "mock (no compiler, no GPU)")))).isFalse()
        assertThat(environment.accepts(result.copy(processor = null))).isFalse()
        assertThat(environment.accepts(result.copy(processor = result.processor!!.copy(version = "nvcc other")))).isFalse()
        assertThat(environment.accepts(result.copy(processor = result.processor!!.copy(id = RTL)))).isFalse()
    }

    @Test
    fun `mock environments only accept explicit mock results`() {
        val mockProperties = JudgeProperties(executionMode = "mock")
        val mock = "mock (no compiler, no GPU, no emulator)"
        val environment = gpu(listOf(executor("Mock GPU", toolchain = mock)), mockProperties)!!
        val result = JudgeResult(
            Verdict.ACCEPTED, gpu = GpuInfo("profile", "Mock GPU", 0, "executor"), processor = ProcessorRun(GPU, mock, "Mock GPU", "executor"),
        )
        assertThat(environment.accepts(result)).isTrue()
        assertThat(environment.accepts(result.copy(processor = result.processor!!.copy(version = "nvcc 12.8")))).isFalse()
        assertThat(gpu(listOf(executor()), mockProperties)).isNull()
    }

    @Test
    fun `toolchain changes on the real GPU invalidate cached references and mixed toolchains remain pending`() {
        val original = gpu(listOf(executor()))!!
        val updated = gpu(listOf(executor(toolchain = "nvcc newer")))!!
        assertThat(updated.key).isNotEqualTo(original.key)
        assertThat(gpu(listOf(executor(toolchain = "")))).isNull()
        assertThat(gpu(listOf(executor(), executor(id = "second", toolchain = "nvcc newer")))).isNull()
    }

    @Test
    fun `an emulated processor has one environment of its own, whatever executor and pinned version measures it`() {
        val one = referenceEnvironments(listOf(emulator()), properties, gpuName, now).getValue(RTL)
        val two = referenceEnvironments(listOf(emulator(), emulator(id = "second")), properties, gpuName, now).getValue(RTL)
        assertThat(two.key).isEqualTo(one.key)
        assertThat(one.key).isNotEqualTo(gpu(listOf(executor()))!!.key)
        // only the GPU processor has an environment where only a GPU executor runs
        assertThat(referenceEnvironments(listOf(executor()), properties, gpuName, now).keys).containsExactly(GPU)
        assertThat(referenceEnvironments(listOf(emulator(healthy = false)), properties, gpuName, now)).isEmpty()

        val result = JudgeResult(Verdict.ACCEPTED, processor = ProcessorRun(RTL, "Yosys 0.70+286, Verilator 5.053", "Verilator + Yosys", "emu"))
        assertThat(one.accepts(result)).isTrue()
        assertThat(one.accepts(result.copy(processor = result.processor!!.copy(executorId = "unknown")))).isFalse()
        assertThat(one.accepts(result.copy(processor = result.processor!!.copy(id = "riscv-rvv")))).isFalse()
        assertThat(one.accepts(result.copy(processor = result.processor!!.copy(version = "mock (no compiler, no GPU, no emulator)")))).isFalse()
    }

    @Test
    fun `a mock executor next to real ones of an emulated processor gives no environment`() {
        val mixed = listOf(emulator(), emulator(id = "mock", toolchain = "mock (no compiler, no GPU, no emulator)"))
        assertThat(referenceEnvironments(mixed, properties, gpuName, now)).doesNotContainKey(RTL)
    }

    private companion object {
        const val GPU = "gpu-cuda"
        const val RTL = "rtl"
    }
}
