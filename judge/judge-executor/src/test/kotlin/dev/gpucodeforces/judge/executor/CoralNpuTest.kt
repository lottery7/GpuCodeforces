package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.SimulatorExit
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.processor.TestRun
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuBackend
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuBuilder
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuHarness
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuMemory
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuPlacement
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuToolchain
import dev.gpucodeforces.judge.executor.processor.coralnpu.Elf32
import dev.gpucodeforces.judge.executor.processor.coralnpu.ElfSegment
import dev.gpucodeforces.judge.executor.problem.GeneratedTest
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The parts of `coral-npu` that need no Renode: the harness, the layout of a test in the data memory, the result. */
class CoralNpuTest {
    @TempDir
    lateinit var tmp: Path

    private val sig = Signature(
        params = listOf(
            ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.HOST, "N"),
            ParamSpec("K", DType.INT64, ParamRole.SCALAR),
            ParamSpec("X", DType.INT16, ParamRole.INOUT, MemorySpace.HOST, "N"),
            ParamSpec("C", DType.INT32, ParamRole.OUTPUT, MemorySpace.HOST, "N"),
            ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ParamSpec("S", DType.FLOAT32, ParamRole.SCALAR),
        ),
    )

    @Test
    fun `the harness reads every parameter from its words and calls solve in order`() {
        val harness = CoralNpuHarness.generate(sig)
        assertContains(harness, "void solve(const float* A, int64_t K, int16_t* X, int32_t* C, int32_t N, float S);")
        // A, K (two words), X, C, N, S
        assertContains(harness, "uint32_t judge_args[7];")
        assertContains(harness, "int64_t K_v; memcpy(&K_v, &judge_args[1], sizeof K_v);")
        assertContains(harness, "solve((const float*)judge_args[0], K_v, (int16_t*)judge_args[3], (int32_t*)judge_args[4], N_v, S_v);")
    }

    @Test
    fun `arrays follow the program's data, aligned, and must leave the stack its reserve`() {
        val counts = mapOf("A" to 5L, "X" to 5L, "C" to 5L)
        val placement = CoralNpuPlacement.of(sig, counts, dataEnd = 0x10024)
        // 20 bytes of A from 0x10030, 10 of X from the next multiple of 16, 20 of C after that
        assertEquals(mapOf("A" to 0x10030L, "X" to 0x10050L, "C" to 0x10060L), placement.addresses)
        assertEquals(0x10074L, placement.end)
        assertTrue(placement.fits)

        val room = CoralNpuMemory.DATA_END - CoralNpuMemory.STACK_RESERVE - 0x10030
        val one = Signature(params = listOf(ParamSpec("A", DType.INT8, ParamRole.INPUT, MemorySpace.HOST, "N"), ParamSpec("N", DType.INT32, ParamRole.SCALAR)))
        assertTrue(CoralNpuPlacement.of(one, mapOf("A" to room), dataEnd = 0x10024).fits)
        assertTrue(!CoralNpuPlacement.of(one, mapOf("A" to room + 1), dataEnd = 0x10024).fits)
    }

    @Test
    fun `the manifest carries addresses, raw scalar bits and the files of every array`() {
        val test = GeneratedTest("t", scalars = mapOf("K" to -2L, "N" to 5, "S" to 1.5))
        val dir = tmp.resolve("problem")
        val prepared = PreparedProblem(
            "hash", dir, dir.resolve("inputs"),
            listOf(
                PreparedTest(
                    1, test, SpecValidator.elementCounts(sig, test.scalars),
                    inputs = mapOf("A" to dir.resolve("inputs/t1/A.bin"), "X" to dir.resolve("inputs/t1/X.bin")),
                    expected = mapOf("X" to dir.resolve("expected/t1/X.bin"), "C" to dir.resolve("expected/t1/C.bin")),
                ),
            ),
        )
        val problem = ProblemSpec(id = "p", signature = sig, generator = GeneratorSpec(source = "unused"))
        val run = TestRun(problem, prepared, prepared.tests.single(), limit = 100.0, tmp.resolve("run"), tmp.resolve("run/out/t1"), "nonce")
        val placement = CoralNpuPlacement.of(sig, run.test.counts, dataEnd = 0x10024)
        val manifest = CoralNpuBackend.manifest(run, placement, "/sim/solution.elf", limit = 100) { "/host/${tmp.relativize(it)}" }
        assertEquals(
            """
            nonce nonce
            limit 100
            elf /sim/solution.elf
            word 65536 ${0x10030}
            word 65540 4294967294
            word 65544 4294967295
            word 65548 ${0x10050}
            word 65552 ${0x10060}
            word 65556 5
            word 65560 ${1.5f.toRawBits()}
            load ${0x10030} 20 /host/problem/inputs/t1/A.bin
            load ${0x10050} 10 /host/problem/inputs/t1/X.bin
            dump ${0x10050} 10 /host/run/out/t1/X.bin
            fill ${0x10060} 20 255
            dump ${0x10060} 20 /host/run/out/t1/C.bin
            """.trimIndent() + "\n",
            manifest,
        )
    }

    @Test
    fun `the score is the instructions of a core that halted within the limit`() {
        fun outcome(vararg lines: String, code: Int = 0) =
            CoralNpuBackend.outcome(SimulatorExit(code, lines.toList(), stderr = "", kept = listOf("Renode v1.17.0", "judge_run failed: boom")), maxInstructions = 500, ScoreType.INSTRUCTIONS)

        assertEquals(SimulatorOutcome.Finished(385.0), outcome("halted 385"))
        // the run is stopped only some time after the limit: a program may halt beyond it, and still exceeded it
        val late = assertIs<SimulatorOutcome.Failed>(outcome("halted 501"))
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, late.verdict)
        assertEquals("Instruction limit exceeded (more than 500 instructions)", late.message)
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, assertIs<SimulatorOutcome.Failed>(outcome("limit 30166111")).verdict)

        val fault = assertIs<SimulatorOutcome.Failed>(outcome("fault 16"))
        assertEquals(Verdict.RUNTIME_ERROR, fault.verdict)
        assertContains(fault.details!!, "outside its instruction and data memories after 16 instructions")
        val trap = assertIs<SimulatorOutcome.Failed>(outcome("trap 33 2"))
        assertEquals(Verdict.RUNTIME_ERROR, trap.verdict)
        assertContains(trap.details!!, "illegal instruction after 33 instructions")

        // without a result line the failure is Renode's or the judge's, and what Renode printed says why
        assertContains(assertFailsWith<JudgeInfrastructureException> { outcome(code = 3) }.message!!, "judge_run failed: boom")
        assertFailsWith<JudgeInfrastructureException> { outcome("exploded 5") }
        assertFailsWith<JudgeInfrastructureException> { outcome("halted many") }
    }

    /** A 32-bit little-endian executable with these program headers: type, address, bytes of memory. */
    private fun elf(vararg headers: Triple<Int, Long, Long>): Path {
        val data = ByteBuffer.allocate(52 + 32 * headers.size).order(ByteOrder.LITTLE_ENDIAN)
        data.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1))
        data.putInt(28, 52).putShort(42, 32).putShort(44, headers.size.toShort())
        headers.forEachIndexed { i, (type, address, bytes) ->
            val at = 52 + 32 * i
            data.putInt(at, type).putInt(at + 8, address.toInt()).putInt(at + 12, address.toInt()).putInt(at + 20, bytes.toInt())
        }
        return Files.write(tmp.resolve("program-${headers.contentHashCode()}.elf"), data.array())
    }

    @Test
    fun `the program's data ends where its last segment in the data memory does`() {
        // code, data with bss, a TLS header over the same bytes (not loaded by itself), an empty segment
        val program = elf(Triple(1, 0x0, 0x4AC), Triple(1, 0x10000, 0x1018), Triple(7, 0x10070, 0x18), Triple(1, 0x17000, 0))
        val segments = Elf32.loadSegments(program)
        assertEquals(listOf(ElfSegment(0x0, 0x4AC), ElfSegment(0x10000, 0x1018)), segments)
        assertEquals(0x11018L, CoralNpuBuilder.dataEnd(segments))
        assertNull(CoralNpuBuilder.misplaced(program))
        assertEquals(CoralNpuMemory.DATA_START, CoralNpuBuilder.dataEnd(listOf(ElfSegment(0x0, 0x100))))
    }

    @Test
    fun `a program that wants to be loaded outside the two memories is refused`() {
        assertContains(assertNotNull(CoralNpuBuilder.misplaced(elf(Triple(1, 0x0, 0x100), Triple(1, 0x17FF0, 0x20)))), "32 bytes at 0x17ff0 are outside")
        assertContains(assertNotNull(CoralNpuBuilder.misplaced(elf(Triple(1, 0x30000, 4)))), "4 bytes at 0x30000")
        val text = Files.writeString(tmp.resolve("not-elf"), "#!/bin/sh\n")
        assertContains(assertNotNull(CoralNpuBuilder.misplaced(text)), "not an executable for the core")
        val truncated = Files.write(tmp.resolve("truncated.elf"), Files.readAllBytes(elf(Triple(1, 0x0, 0x100))).copyOf(60))
        assertFailsWith<IllegalArgumentException> { Elf32.loadSegments(truncated) }
    }

    @Test
    fun `the Renode version is read from its banner`() {
        assertEquals("1.17.0", CoralNpuToolchain.parseRenodeVersion("Renode v1.17.0\n  build: 1.17.0+20260906gitf1dd1b4af\n  build type: Release"))
        assertNull(CoralNpuToolchain.parseRenodeVersion("command not found"))
    }
}
