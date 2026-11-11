package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.processor.SimulatorExit
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.processor.cudasim.CudaSimBackend
import dev.gpucodeforces.judge.executor.processor.cudasim.CudaSimHarness
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The parts of `cuda-sim` that need no GPGPU-Sim: the harness and how a run's output is read. */
class CudaSimTest {
    @Test
    fun `the harness passes device pointers and scalars to solve in order`() {
        val sig = Signature(
            params = listOf(
                ParamSpec("A", DType.FLOAT16, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("C", DType.INT64, ParamRole.OUTPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        )
        val harness = CudaSimHarness.generate(sig)
        assertContains(harness, "#include <cuda_fp16.h>")
        assertContains(harness, "void solve(const __half* A, int64_t* C, int N);")
        assertContains(harness, "solve((const __half*)A_a.device, (int64_t*)C_a.device, N_v);")
    }

    @Test
    fun `the last statistics before done are the score, the cycle cap is a time limit`() {
        val stats = listOf("gpu_tot_sim_cycle = 100", "gpu_tot_sim_insn = 5", "gpu_tot_sim_cycle = 6405", "gpu_tot_sim_insn = 2101056")
        val finished = assertIs<SimulatorOutcome.Finished>(CudaSimBackend.outcome(SimulatorExit(0, listOf("done"), "", stats), 50_000, ScoreType.CYCLES))
        assertEquals(6405.0, finished.score)
        assertEquals(mapOf("cycles" to 6405.0, "instructions" to 2101056.0), finished.metrics)

        val capped = SimulatorExit(1, emptyList(), "", listOf("GPGPU-Sim: ** break due to reaching the maximum cycles (or instructions) **"))
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, assertIs<SimulatorOutcome.Failed>(CudaSimBackend.outcome(capped, 50_000, ScoreType.CYCLES)).verdict)

        val error = assertIs<SimulatorOutcome.Failed>(CudaSimBackend.outcome(SimulatorExit(3, listOf("error solve: invalid argument"), ""), 1, ScoreType.CYCLES))
        assertEquals(Verdict.RUNTIME_ERROR, error.verdict)
        assertEquals("solve: invalid argument", error.details)
        assertEquals(Verdict.RUNTIME_ERROR, assertIs<SimulatorOutcome.Failed>(CudaSimBackend.outcome(SimulatorExit(139, emptyList(), ""), 1, ScoreType.CYCLES)).verdict)
        assertTrue(CudaSimBackend.isStatistic("gpu_tot_sim_cycle = 1"))
        assertEquals(50_000, CudaSimBackend.cycleLimit(5e4, 10_000_000))
    }
}
