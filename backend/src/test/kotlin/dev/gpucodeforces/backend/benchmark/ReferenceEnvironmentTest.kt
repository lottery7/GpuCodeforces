package dev.gpucodeforces.backend.benchmark

import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.submission.JudgeExecutorView
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ReferenceEnvironmentTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val gpu = GpuProperties("profile", "Configured GPU")
    private val properties = JudgeProperties()

    private fun executor(name: String = "GPU A", id: String = "executor", version: String = "v1", healthy: Boolean = true, alive: Boolean = true, lastSeen: Instant = now, toolchain: String? = "nvcc test") =
        JudgeExecutorView(alive, lastSeen, ExecutorHeartbeat(
            id, "host", "profile", version, now.minusSeconds(3600), now,
            listOf(GpuSlotStatus(0, name, healthy, false)), 0, 1, toolchain,
        ))

    @Test
    fun `hardware changes under the same profile change the fingerprint while identical extra executors do not`() {
        val original = referenceEnvironment(listOf(executor()), properties, gpu, now)!!
        val replaced = referenceEnvironment(listOf(executor("GPU B")), properties, gpu, now)!!
        val expanded = referenceEnvironment(listOf(executor(), executor(id = "second")), properties, gpu, now)!!
        val upgraded = referenceEnvironment(listOf(executor(version = "v2")), properties, gpu, now)!!

        assertThat(replaced.key).isNotEqualTo(original.key)
        assertThat(expanded.key).isEqualTo(original.key)
        assertThat(upgraded.key).isNotEqualTo(original.key)
    }

    @Test
    fun `unavailable stale unhealthy or mixed runtimes do not expose an environment`() {
        assertThat(referenceEnvironment(emptyList(), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor(alive = false)), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor(healthy = false)), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor(lastSeen = now.minusSeconds(30))), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor(), executor("GPU B", "second")), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor(), executor(id = "second", version = "v2")), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor("Mock GPU")), properties, gpu, now)).isNull()
    }

    @Test
    fun `result provenance must match an actual healthy GPU and the runtime mode`() {
        val environment = referenceEnvironment(listOf(executor()), properties, gpu, now)!!
        val result = JudgeResult(Verdict.ACCEPTED, gpu = GpuInfo("profile", "GPU A", 0, "executor"), toolchain = "nvcc test")
        assertThat(environment.accepts(result)).isTrue()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(name = "GPU B")))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(profile = "different")))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(executorId = "unknown")))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(index = 1)))).isFalse()
        assertThat(environment.accepts(result.copy(gpu = result.gpu!!.copy(throttled = true)))).isFalse()
        assertThat(environment.accepts(result.copy(toolchain = "mock (no compiler, no GPU)"))).isFalse()
        assertThat(environment.accepts(result.copy(toolchain = null))).isFalse()
        assertThat(environment.accepts(result.copy(toolchain = "nvcc other"))).isFalse()
    }

    @Test
    fun `mock environments only accept explicit mock results`() {
        val mockProperties = JudgeProperties(executionMode = "mock")
        val environment = referenceEnvironment(listOf(executor("Mock GPU", toolchain = "mock (no compiler, no GPU)")), mockProperties, gpu, now)!!
        val result = JudgeResult(Verdict.ACCEPTED, gpu = GpuInfo("profile", "Mock GPU", 0, "executor"), toolchain = "mock (no compiler, no GPU)")
        assertThat(environment.accepts(result)).isTrue()
        assertThat(environment.accepts(result.copy(toolchain = "nvcc 12.8"))).isFalse()
        assertThat(referenceEnvironment(listOf(executor()), mockProperties, gpu, now)).isNull()
    }

    @Test
    fun `toolchain changes invalidate cached references and mixed or absent toolchains remain pending`() {
        val original = referenceEnvironment(listOf(executor()), properties, gpu, now)!!
        val updated = referenceEnvironment(listOf(executor(toolchain = "nvcc newer")), properties, gpu, now)!!
        assertThat(updated.key).isNotEqualTo(original.key)
        assertThat(referenceEnvironment(listOf(executor(toolchain = null)), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor(toolchain = "")), properties, gpu, now)).isNull()
        assertThat(referenceEnvironment(listOf(executor(), executor(id = "second", toolchain = "nvcc newer")), properties, gpu, now)).isNull()
    }
}
