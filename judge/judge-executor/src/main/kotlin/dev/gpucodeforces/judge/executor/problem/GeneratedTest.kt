package dev.gpucodeforces.judge.executor.problem

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SpecValidator
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A test as the problem's generator declares it in `tests()`. `judge_problem.prepare` writes the list to
 * `tests.json` next to the generated data; the executor checks it here before it trusts any file of that data.
 */
data class GeneratedTest(
    val name: String,
    /** Sample tests are visible to contestants: details of failures may be shown to them. */
    val sample: Boolean = false,
    /** Values of all SCALAR params. Integers for integer types, decimals for float types. */
    val scalars: Map<String, Number> = emptyMap(),
) {
    companion object {
        /** Values the harness can pass for an integer scalar; UINT64 is capped at what a Long holds. */
        private val INTEGER_RANGES: Map<DType, ClosedRange<BigInteger>> = mapOf(
            DType.INT32 to BigInteger.valueOf(Int.MIN_VALUE.toLong())..BigInteger.valueOf(Int.MAX_VALUE.toLong()),
            DType.UINT32 to BigInteger.ZERO..BigInteger.valueOf(UInt.MAX_VALUE.toLong()),
            DType.INT64 to BigInteger.valueOf(Long.MIN_VALUE)..BigInteger.valueOf(Long.MAX_VALUE),
            DType.UINT64 to BigInteger.ZERO..BigInteger.valueOf(Long.MAX_VALUE),
        )

        /** Checks the tests a generator declared; they are its output, so as untrusted as the rest of it. */
        fun validate(sig: Signature, tests: List<GeneratedTest>, limits: SpecValidator.Limits): List<String> = buildList {
            if (tests.isEmpty()) add("the generator declares no tests")
            if (tests.size > limits.maxTests) add("too many tests: ${tests.size} > ${limits.maxTests}")
            val names = mutableSetOf<String>()
            tests.take(limits.maxTests).forEachIndexed { i, t ->
                val where = "test ${i + 1} '${t.name.take(SpecValidator.MAX_NAME_LENGTH)}'"
                if (t.name.isBlank()) add("test ${i + 1}: name is empty")
                if (t.name.length > SpecValidator.MAX_NAME_LENGTH) add("test ${i + 1}: name is longer than ${SpecValidator.MAX_NAME_LENGTH} characters")
                if (!names.add(t.name)) add("$where: duplicate test name")
                addAll(validateSizes(sig, t, limits).map { "$where: $it" })
            }
        }

        private fun validateSizes(sig: Signature, t: GeneratedTest, limits: SpecValidator.Limits): List<String> = buildList {
            val scalarNames = sig.scalars.map { it.name }.toSet()
            val missing = scalarNames - t.scalars.keys
            val extra = t.scalars.keys - scalarNames
            if (missing.isNotEmpty()) add("missing scalars $missing")
            if (extra.isNotEmpty()) add("unknown scalars $extra")
            if (missing.isNotEmpty() || extra.isNotEmpty()) return@buildList

            for (p in sig.scalars) {
                val v = t.scalars.getValue(p.name)
                when {
                    p.type.floating -> if (!v.toDouble().isFinite()) add("scalar ${p.name} must be finite")
                    !SpecValidator.isIntegral(v) -> add("scalar ${p.name} must be an integer")
                    BigDecimal(v.toString()).toBigInteger() !in INTEGER_RANGES.getValue(p.type) -> add("scalar ${p.name} = $v is out of range of ${p.type}")
                }
            }
            if (isNotEmpty()) return@buildList
            val sizes = try {
                SpecValidator.elementCounts(sig, t.scalars)
            } catch (e: IllegalArgumentException) {
                add(e.message ?: "invalid size")
                return@buildList
            } catch (e: ArithmeticException) {
                add(e.message ?: "size overflows")
                return@buildList
            }
            var bytes = 0L
            for (p in sig.arrays) {
                val n = sizes.getValue(p.name)
                if (n < 0) add("size of ${p.name} is negative ($n)")
                // per array, so that a sum which overflows cannot wrap back under the limit
                if (n > limits.maxTestBytes / p.type.bytes) {
                    add("${p.name} alone exceeds the limit of ${limits.maxTestBytes} bytes")
                    return@buildList
                }
                bytes += n * p.type.bytes
            }
            if (bytes > limits.maxTestBytes) add("test data is $bytes bytes, limit ${limits.maxTestBytes}")
        }
    }
}
