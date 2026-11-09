package gpucodeforces.judge.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `auto timing mode depends on memory space`() {
        val dev = Fixtures.vectorAdd()
        assertEquals(TimingMode.WALL, dev.timing.effectiveMode(dev.signature))
        val host = dev.copy(signature = dev.signature.copy(params = dev.signature.params.map {
            if (it.name == "A") it.copy(memory = MemorySpace.HOST) else it
        }))
        assertEquals(TimingMode.GPU_KERNELS, host.timing.effectiveMode(host.signature))
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
        generator = GeneratorSpec(source = "def generate(test, gen): ...\ndef reference(test, inputs): ..."),
    )
}
