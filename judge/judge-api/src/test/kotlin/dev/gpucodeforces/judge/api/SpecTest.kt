package dev.gpucodeforces.judge.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.intellij.lang.annotations.Language

class SizeExprTest {
    @Test
    fun `evaluates arithmetic with precedence`() {
        val e = SizeExpr.parse("(N + 255) / 256 * 256 + M*K % 7 - -1")
        assertEquals(setOf("N", "M", "K"), e.identifiers)
        assertEquals((1000L + 255) / 256 * 256 + 6L * 5 % 7 + 1, e.eval(mapOf("N" to 1000L, "M" to 6L, "K" to 5L)))
    }

    @Test
    fun `rejects garbage and overflow`() {
        assertFailsWith<IllegalArgumentException> { SizeExpr.parse("N +") }
        assertFailsWith<IllegalArgumentException> { SizeExpr.parse("N; system()") }
        assertFailsWith<ArithmeticException> { SizeExpr.parse("N*N").eval(mapOf("N" to Long.MAX_VALUE / 2)) }
    }
}

class SpecValidatorTest {
    @Test
    fun `valid spec has no errors`() {
        assertEquals(emptyList(), SpecValidator.validateProblem(Fixtures.vectorAdd()))
    }

    @Test
    fun `reports structural problems`() {
        val spec = Fixtures.vectorAdd().let {
            it.copy(
                signature = it.signature.copy(
                    params = it.signature.params.map { p -> if (p.name == "A") p.copy(size = "N*Q") else p } +
                        ParamSpec("main", DType.FLOAT16, ParamRole.SCALAR),
                ),
            )
        }
        val errors = SpecValidator.validateProblem(spec)
        assertTrue(errors.any { "'Q'" in it }, errors.toString())
        assertTrue(errors.any { "param 'main'" in it }, errors.toString())
        assertTrue(errors.any { "not allowed for scalars" in it }, errors.toString())
    }

    @Test
    fun `checks test scalars and inline data`() {
        val spec = Fixtures.vectorAdd().copy(
            tests = listOf(
                TestSpec("a", scalars = mapOf("N" to 2.5)),
                TestSpec("b", scalars = mapOf("N" to 3), data = mapOf("A" to listOf(1, 2, 3))),
                TestSpec("c", scalars = mapOf()),
            ),
        )
        val errors = SpecValidator.validateProblem(spec)
        assertTrue(errors.any { "'a'" in it && "integer" in it }, errors.toString())
        assertTrue(errors.any { "'b'" in it && "exactly the input arrays" in it }, errors.toString())
        assertTrue(errors.any { "'c'" in it && "missing scalars" in it }, errors.toString())
    }

    @Test
    fun `compile settings accept optimisation, standard, defines and math flags`() {
        val spec = Fixtures.vectorAdd().copy(
            compile = CompileSpec("sm_86", listOf("-O2", "-std=c++20", "-DNDEBUG", "-DBLOCK=256", "--use_fast_math", "--fmad=false")),
        )
        assertEquals(emptyList(), SpecValidator.validateProblem(spec))
    }

    @Test
    fun `compile flags that touch files, the host toolchain or the architecture are rejected`() {
        val forbidden = listOf(
            "-include/etc/passwd", "-I/etc", "-o/tmp/x", "-Xcompiler", "-Xlinker", "-arch=sm_90", "-gencode",
            "-DX=\$(id)", "-O2 -I/etc", "--ptx",
        )
        val errors = SpecValidator.validateProblem(Fixtures.vectorAdd().copy(compile = CompileSpec("rtx3060", forbidden)))
        assertTrue(errors.any { "compile.arch 'rtx3060'" in it }, errors.toString())
        for (flag in forbidden) assertTrue(errors.any { "flag '$flag' is not allowed" in it }, "$flag: $errors")
    }

    @Test
    fun `device code runs only on the same or a later revision of its architecture family`() {
        assertTrue(CompileSpec.runsOn("sm_86", "sm_86"))
        assertTrue(CompileSpec.runsOn("sm_80", "sm_86"))
        assertTrue(!CompileSpec.runsOn("sm_89", "sm_86"), "later revision than the GPU")
        assertTrue(!CompileSpec.runsOn("sm_75", "sm_86"), "another family")
        assertTrue(!CompileSpec.runsOn("sm_100", "sm_120"), "another family")
        assertTrue(!CompileSpec.runsOn("ampere", "sm_86"))
    }

    @Test
    fun `time is the whole call unless the problem asks for kernel time`() {
        val host = Fixtures.vectorAdd().let { p ->
            p.copy(signature = p.signature.copy(params = p.signature.params.map { if (it.name == "A") it.copy(memory = MemorySpace.HOST) else it }))
        }
        assertEquals(TimingMode.WALL, Fixtures.vectorAdd().timing.effectiveMode)
        assertEquals(TimingMode.WALL, host.timing.effectiveMode)
        assertEquals(TimingMode.GPU_KERNELS, TimingSpec(mode = TimingMode.GPU_KERNELS).effectiveMode)
    }

    @Test
    fun `hand-written answers need hand-written inputs and the right sizes`() {
        val sample = TestSpec("s", scalars = mapOf("N" to 2), data = mapOf("A" to listOf(1, 2), "B" to listOf(3, 4)), expected = mapOf("C" to listOf(4, 6)))
        assertEquals(emptyList(), SpecValidator.validateProblem(Fixtures.vectorAdd().copy(tests = listOf(sample))))

        val bad = listOf(
            sample.copy(name = "short", expected = mapOf("C" to listOf(4))),
            sample.copy(name = "wrong-array", expected = mapOf("A" to listOf(1, 2))),
            TestSpec("generated", scalars = mapOf("N" to 2), expected = mapOf("C" to listOf(4, 6))),
        )
        val errors = SpecValidator.validateProblem(Fixtures.vectorAdd().copy(tests = bad))
        assertTrue(errors.any { "'short'" in it && "expected.C has 1 values" in it }, errors.toString())
        assertTrue(errors.any { "'wrong-array'" in it && "exactly the output arrays" in it }, errors.toString())
        assertTrue(errors.any { "'generated'" in it && "needs hand-written data" in it }, errors.toString())
    }

    @Test
    fun `inline data of all tests together is bounded`() {
        val values = List(60_000) { 0 }
        val tests = List(10) { TestSpec("t$it", scalars = mapOf("N" to 60_000), data = mapOf("A" to values, "B" to values)) }
        val errors = SpecValidator.validateProblem(Fixtures.vectorAdd().copy(tests = tests))
        assertTrue(errors.any { "1200000 inline values in total" in it }, errors.toString())
    }
}

class CudaSignatureTest {
    @Test
    fun `renders declaration`() {
        val sig = Fixtures.vectorAdd().signature
        assertEquals("void solve(const float* A, const float* B, float* C, int N)", CudaSignature.declaration(sig))
    }
}

object Fixtures {
    fun vectorAdd() = ProblemSpec(
        id = "vector-add",
        signature = Signature(
            params = listOf(
                ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("B", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("C", DType.FLOAT32, ParamRole.OUTPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        ),
        tests = listOf(
            TestSpec("sample", scalars = mapOf("N" to 4), sample = true, data = mapOf("A" to listOf(1, 2, 3, 4), "B" to listOf(1.5, 0, 0, -4))),
            TestSpec("big", scalars = mapOf("N" to (1 shl 24)), seed = 7),
        ),
        generator = GeneratorSpec(source = GENERATOR),
    )

    @Language("Python")
    private const val GENERATOR = "def generate(test, gen, device): ...\ndef reference(test, inputs): ..."
}
