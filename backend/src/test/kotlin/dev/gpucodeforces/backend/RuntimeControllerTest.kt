package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.RuntimeController
import dev.gpucodeforces.backend.submission.JudgeProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuntimeControllerTest {
    @Test
    fun `mock runtime is explicit and does not claim an available GPU`() {
        val runtime = RuntimeController(JudgeProperties(executionMode = "mock"), GpuProperties()).getRuntime().body!!

        assertThat(runtime.executionMode.value).isEqualTo("mock")
        assertThat(runtime.gpuName).isNull()
    }

    @Test
    fun `GPU runtime publishes its configured device`() {
        val runtime = RuntimeController(JudgeProperties(), GpuProperties()).getRuntime().body!!

        assertThat(runtime.executionMode.value).isEqualTo("gpu")
        assertThat(runtime.gpuName).isEqualTo("NVIDIA GeForce RTX 3060")
    }
}
