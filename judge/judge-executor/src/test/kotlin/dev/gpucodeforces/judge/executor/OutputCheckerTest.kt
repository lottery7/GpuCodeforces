package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.CheckerMode
import dev.gpucodeforces.judge.api.CheckerSpec
import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.executor.run.OutputChecker
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputCheckerTest {
    @TempDir
    lateinit var dir: Path

    private fun floats(name: String, vararg v: Float): Path {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return Files.write(dir.resolve(name), b.array())
    }

    private fun ints(name: String, vararg v: Int): Path {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putInt(it) }
        return Files.write(dir.resolve(name), b.array())
    }

    private val spec = CheckerSpec(rtol = 1e-3, atol = 1e-5)

    @Test
    fun `float tolerance, NaN and infinities`() {
        val exp = floats("e", 1f, 1000f, 0f, Float.NaN, Float.POSITIVE_INFINITY)
        assertTrue(OutputChecker.check("C", DType.FLOAT32, floats("a", 1.0005f, 1000.9f, 0.000009f, Float.NaN, Float.POSITIVE_INFINITY), exp, spec).ok)

        val bad = OutputChecker.check("C", DType.FLOAT32, floats("b", 1.01f, 1000f, 0f, 0f, Float.NEGATIVE_INFINITY), exp, spec)
        assertFalse(bad.ok)
        assertEquals("C: 3 of 5 elements differ", bad.summary)
        assertTrue(bad.detail.startsWith("C[0]: expected 1.0, got 1.0099999"), bad.detail)

        // poisoned (never written) output = NaN everywhere
        assertFalse(OutputChecker.check("C", DType.FLOAT32, floats("p", Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN), exp, spec).ok)
    }

    @Test
    fun `integers and EXACT mode compare bits`() {
        val exp = ints("e", 1, -2, 3)
        assertTrue(OutputChecker.check("R", DType.INT32, ints("a", 1, -2, 3), exp, spec).ok)
        val r = OutputChecker.check("R", DType.INT32, ints("b", 1, -2, 4), exp, spec)
        assertEquals("R[2]: expected 3, got 4 (exact comparison)", r.detail)
        val f = floats("fe", 1f)
        assertFalse(OutputChecker.check("C", DType.FLOAT32, floats("fa", 1.0000001f), f, CheckerSpec(mode = CheckerMode.EXACT)).ok)
    }

    @Test
    fun `fp16 conversion covers special values`() {
        assertEquals(1.0f, OutputChecker.halfToFloat(0x3C00))
        assertEquals(-2.0f, OutputChecker.halfToFloat(0xC000))
        assertEquals(65504.0f, OutputChecker.halfToFloat(0x7BFF))
        assertEquals(5.9604645E-8f, OutputChecker.halfToFloat(0x0001)) // smallest subnormal
        assertEquals(6.097555E-5f, OutputChecker.halfToFloat(0x03FF)) // largest subnormal
        assertEquals(-0.0f, OutputChecker.halfToFloat(0x8000))
        assertEquals(Float.NEGATIVE_INFINITY, OutputChecker.halfToFloat(0xFC00))
        assertTrue(OutputChecker.halfToFloat(0x7E00).isNaN())
    }

    @Test
    fun `half precision and size mismatch`() {
        val h = dir.resolve("h")
        Files.write(h, byteArrayOf(0x00, 0x3C, 0x00, 0x40)) // 1.0, 2.0 in fp16
        val h2 = dir.resolve("h2")
        Files.write(h2, byteArrayOf(0x01, 0x3C, 0x00, 0x40)) // 1.000977, 2.0
        assertTrue(OutputChecker.check("H", DType.FLOAT16, h2, h, CheckerSpec(rtol = 1e-2, atol = 0.0)).ok)
        assertFalse(OutputChecker.check("H", DType.FLOAT16, h2, h, CheckerSpec(rtol = 1e-4, atol = 0.0)).ok)
        assertEquals("C: output has wrong size", OutputChecker.check("C", DType.FLOAT32, floats("s", 1f), floats("t", 1f, 2f), spec).summary)
    }
}
