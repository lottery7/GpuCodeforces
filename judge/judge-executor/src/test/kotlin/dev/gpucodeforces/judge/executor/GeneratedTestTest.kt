package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.executor.problem.GeneratedTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedTestTest {
    private val sig = Signature(
        params = listOf(
            ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
            ParamSpec("C", DType.FLOAT32, ParamRole.OUTPUT, MemorySpace.DEVICE, "N"),
            ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ParamSpec("alpha", DType.FLOAT32, ParamRole.SCALAR),
        ),
    )
    private val limits = SpecValidator.Limits()

    private val declared = listOf("gpu-cuda", "rtl")

    private fun errors(vararg tests: GeneratedTest) = GeneratedTest.validate(sig, tests.toList(), limits, declared, "gpu-cuda")

    @Test
    fun `declared tests with all scalars are valid`() {
        val tests = arrayOf(
            GeneratedTest("sample", sample = true, scalars = mapOf("N" to 4, "alpha" to 0.5)),
            GeneratedTest("big", scalars = mapOf("N" to (1 shl 24), "alpha" to 2)),
        )
        assertEquals(emptyList(), errors(*tests))
    }

    @Test
    fun `scalars must match the signature and fit their types`() {
        val errors = errors(
            GeneratedTest("fraction", scalars = mapOf("N" to 2.5, "alpha" to 1)),
            GeneratedTest("missing", scalars = mapOf("N" to 4)),
            GeneratedTest("extra", scalars = mapOf("N" to 4, "alpha" to 1, "M" to 3)),
            GeneratedTest("overflow", scalars = mapOf("N" to 3_000_000_000L, "alpha" to 1)),
            GeneratedTest("negative", scalars = mapOf("N" to -1, "alpha" to 1)),
            GeneratedTest("infinite", scalars = mapOf("N" to 1, "alpha" to Double.POSITIVE_INFINITY)),
        )
        assertTrue(errors.any { "'fraction'" in it && "must be an integer" in it }, errors.toString())
        assertTrue(errors.any { "'missing'" in it && "missing scalars [alpha]" in it }, errors.toString())
        assertTrue(errors.any { "'extra'" in it && "unknown scalars [M]" in it }, errors.toString())
        assertTrue(errors.any { "'overflow'" in it && "out of range of INT32" in it }, errors.toString())
        assertTrue(errors.any { "'negative'" in it && "negative" in it }, errors.toString())
        assertTrue(errors.any { "'infinite'" in it && "finite" in it }, errors.toString())
    }

    @Test
    fun `the list is bounded and names are unique`() {
        assertEquals(listOf("the generator declares no tests for processor gpu-cuda"), errors())
        val ok = mapOf("N" to 1, "alpha" to 1)
        assertTrue(errors(GeneratedTest("a", scalars = ok), GeneratedTest("a", scalars = ok)).any { "duplicate test name" in it })
        assertTrue(errors(GeneratedTest(" ", scalars = ok)).any { "name is empty" in it })
        val many = GeneratedTest.validate(sig, List(limits.maxTests + 1) { GeneratedTest("t$it", scalars = ok) }, limits, declared, "gpu-cuda")
        assertEquals(listOf("too many tests: ${limits.maxTests + 1} > ${limits.maxTests}"), many)
    }

    @Test
    fun `test data size is limited`() {
        val big = GeneratedTest("huge", scalars = mapOf("N" to Int.MAX_VALUE, "alpha" to 1))
        assertTrue(errors(big).any { "'huge'" in it && "limit" in it })
    }

    @Test
    fun `tests name processors of the problem and one of them is measured`() {
        val ok = mapOf("N" to 1, "alpha" to 1)
        assertEquals(emptyList(), errors(GeneratedTest("big", scalars = ok, processors = listOf("gpu-cuda")), GeneratedTest("one", scalars = ok, measure = false)))
        val unmeasured = errors(GeneratedTest("one", scalars = ok, measure = false))
        assertTrue(unmeasured.single().startsWith("processor gpu-cuda has no measured test"), unmeasured.toString())
        val foreign = errors(GeneratedTest("small", scalars = ok, processors = listOf("gpu", "rtl")))
        assertTrue(foreign.any { "'small': unknown processor \"gpu\"; the problem declares gpu-cuda, rtl" in it }, foreign.toString())
        assertTrue(foreign.any { "'small': does not run on processor gpu-cuda" in it }, foreign.toString())
    }
}
