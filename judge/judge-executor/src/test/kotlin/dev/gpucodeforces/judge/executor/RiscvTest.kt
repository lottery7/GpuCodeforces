package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.SimulatorExit
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvBackend
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvCounts
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvHarness
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvToolchain
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The parts of `riscv-rvv` that need no QEMU: the harness, the plugin's report, the limits. */
class RiscvTest {
    private val sig = Signature(
        params = listOf(
            ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
            ParamSpec("X", DType.INT64, ParamRole.INOUT, MemorySpace.HOST, "N"),
            ParamSpec("N", DType.INT32, ParamRole.SCALAR),
        ),
    )

    @Test
    fun `the harness declares solve and passes every parameter in order`() {
        val harness = RiscvHarness.generate(sig)
        assertContains(harness, "void solve(const float* A, int64_t* X, int32_t N);")
        assertContains(harness, "solve((const float*)A_a.data, (int64_t*)X_a.data, N_v);")
        assertContains(harness, "save(&X_a);")
    }

    @Test
    fun `the plugin's report is read, and anything else is not`() {
        assertEquals(
            RiscvCounts(1000, 12, 5, denied = 2),
            RiscvCounts.parse("insns 1000 l1i_misses 4 l1d_misses 8 l2_misses 5 denied 2"),
        )
        assertNull(RiscvCounts.parse("insns 1000"))
        assertNull(RiscvCounts.parse("insns x l1i_misses 4 l1d_misses 8 l2_misses 5 denied 2"))
    }

    @Test
    fun `the score is what the solution adds to the harness, weighted by misses`() {
        val base = RiscvCounts(100, 10, 2)
        fun outcome(code: Int, vararg lines: String) =
            RiscvBackend.outcome(SimulatorExit(code, lines.toList(), stderr = ""), base, maxInstructions = 500, ScoreType.WEIGHTED_INSTRUCTIONS)

        val finished = assertIs<SimulatorOutcome.Finished>(outcome(0, "insns 1100 l1i_misses 10 l1d_misses 20 l2_misses 7 denied 0"))
        // 1000 instructions + 10 x 20 L1 misses + 100 x 5 L2 misses
        assertEquals(1700.0, finished.score)
        assertEquals(mapOf("instructions" to 1000.0, "l1_misses" to 20.0, "ll_misses" to 5.0), finished.metrics)

        val limit = assertIs<SimulatorOutcome.Failed>(outcome(4, "limit"))
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, limit.verdict)
        assertEquals("Instruction limit exceeded (more than 500 instructions)", limit.message)
        assertEquals(Verdict.RUNTIME_ERROR, assertIs<SimulatorOutcome.Failed>(outcome(139, "insns 1 l1i_misses 0 l1d_misses 0 l2_misses 0 denied 0")).verdict)
        assertFailsWith<JudgeInfrastructureException> { outcome(0) }
    }

    @Test
    fun `refused system calls are reported, and a run cheaper than the harness scores zero`() {
        val outcome = RiscvBackend.outcome(
            SimulatorExit(0, listOf("insns 90 l1i_misses 1 l1d_misses 1 l2_misses 1 denied 3"), ""),
            RiscvCounts(100, 10, 2), 500, ScoreType.WEIGHTED_INSTRUCTIONS,
        )
        val finished = assertIs<SimulatorOutcome.Finished>(outcome)
        assertEquals(0.0, finished.score)
        assertEquals(3.0, finished.metrics["denied_syscalls"])
    }

    @Test
    fun `instruction limit and versions`() {
        assertEquals(20_000_000, RiscvBackend.instructionLimit(2e7, 5_000_000_000))
        assertEquals(1, RiscvBackend.instructionLimit(0.5, 5_000_000_000))
        assertEquals(1000, RiscvBackend.instructionLimit(1e12, 1000))
        assertEquals("11.1.3", RiscvToolchain.parseQemuVersion("qemu-riscv64 version 11.1.3\nCopyright (c) 2003-2026"))
    }
}
