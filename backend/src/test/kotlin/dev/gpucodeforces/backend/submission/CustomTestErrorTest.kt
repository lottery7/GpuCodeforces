package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.judge.api.CustomTest
import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SpecValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomTestErrorTest {
    private val signature = Signature(
        "solve",
        listOf(
            ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, size = "N"),
            ParamSpec("C", DType.FLOAT32, ParamRole.OUTPUT, size = "N"),
            ParamSpec("N", DType.INT32, ParamRole.SCALAR),
        ),
    )

    private fun findings(test: CustomTest): List<String> = SpecValidator.validateCustomTest(signature, test).map(::customTestError)

    @Test
    fun `every finding of the judge about a hack test reaches the participant in Russian`() {
        val latin = Regex("[A-Za-z]{3,}")
        val tests = listOf(
            CustomTest(mapOf("M" to 1), mapOf("A" to listOf(0.5))),
            CustomTest(mapOf("N" to 1), mapOf("B" to listOf(0.5))),
            CustomTest(mapOf("N" to 9000), mapOf("A" to List(9000) { 0.5 })),
            CustomTest(mapOf("N" to 1.5), mapOf("A" to listOf(0.5))),
            CustomTest(mapOf("N" to 3), mapOf("A" to listOf(0.5))),
            CustomTest(mapOf("N" to 1), mapOf("A" to listOf(Double.MAX_VALUE))),
        )
        val all = tests.flatMap(::findings)
        assertThat(all).hasSizeGreaterThanOrEqualTo(tests.size)
        // names of the problem's own parameters stay as they are; words of the judge do not
        assertThat(all.filter { latin.containsMatchIn(it) }).isEmpty()
        assertThat(findings(tests[4])).containsExactly("Размер массива A не соответствует скалярам теста")
    }
}
