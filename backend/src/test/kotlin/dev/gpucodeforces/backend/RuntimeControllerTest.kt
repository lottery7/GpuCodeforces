package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.RuntimeController
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.BDDMockito.given

class RuntimeControllerTest {
    private val references = mock(ReferenceMeasurements::class.java)

    @Test
    fun `mock runtime is explicit and does not claim an available GPU`() {
        val runtime = RuntimeController(JudgeProperties(executionMode = "mock"), GpuProperties(), references).getRuntime().body!!

        assertThat(runtime.executionMode.value).isEqualTo("mock")
        assertThat(runtime.gpuName).isNull()
    }

    @Test
    fun `GPU runtime publishes its configured device`() {
        val runtime = RuntimeController(JudgeProperties(), GpuProperties(), references).getRuntime().body!!

        assertThat(runtime.executionMode.value).isEqualTo("gpu")
        assertThat(runtime.gpuName).isEqualTo("NVIDIA GeForce RTX 3060")
    }

    @Test
    fun `GPU runtime reports the actual device after hardware changes`() {
        given(references.environmentName()).willReturn("NVIDIA GeForce RTX 4090")

        val runtime = RuntimeController(JudgeProperties(), GpuProperties(), references).getRuntime().body!!

        assertThat(runtime.gpuName).isEqualTo("NVIDIA GeForce RTX 4090")
    }
}
