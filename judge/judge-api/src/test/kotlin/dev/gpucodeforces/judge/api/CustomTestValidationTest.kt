package dev.gpucodeforces.judge.api

import kotlin.test.Test
import kotlin.test.assertTrue

class CustomTestValidationTest {
    private val signature = Signature(params = listOf(
        ParamSpec("N", DType.INT32, ParamRole.SCALAR),
        ParamSpec("A", DType.INT8, ParamRole.INOUT, MemorySpace.DEVICE, "N"),
    ))

    @Test
    fun `accepts typed inputs and rejects invalid dimensions values and names`() {
        val good = CustomTest(mapOf("N" to 2), mapOf("A" to listOf(-128, 127)))
        assertTrue(SpecValidator.validateCustomTest(signature, good).isEmpty())
        val invalid = listOf(
            good.copy(scalars = mapOf("N" to 2.5)),
            good.copy(scalars = mapOf("N" to 0)),
            good.copy(scalars = mapOf("N" to Long.MAX_VALUE)),
            good.copy(inputs = mapOf("A" to listOf(128, 0))),
            good.copy(inputs = mapOf("A" to listOf(0.1, 0))),
            good.copy(inputs = mapOf("A" to listOf(Double.NaN, 0))),
            good.copy(inputs = mapOf("A" to listOf(1))),
            good.copy(inputs = mapOf("B" to listOf(1, 2))),
            good.copy(scalars = mapOf("N" to 8193), inputs = mapOf("A" to List(8193) { 0 })),
        )
        invalid.forEach { assertTrue(SpecValidator.validateCustomTest(signature, it).isNotEmpty(), it.toString()) }
    }
}
