package dev.gpucodeforces.judge.executor.run

import dev.gpucodeforces.judge.api.CheckerMode
import dev.gpucodeforces.judge.api.CheckerSpec
import dev.gpucodeforces.judge.api.DType
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize

data class CheckResult(
    val ok: Boolean,
    /** Short public message, e.g. "C: 57 of 1048576 elements differ". */
    val summary: String = "",
    /** First mismatch with values; reveals test data, so only shown to contestants on sample tests. */
    val detail: String = "",
)

/**
 * Compares an output array with the reference answer. Floats: |out - exp| <= atol + rtol * |exp|, NaN matches only
 * NaN, infinities must be equal. Integers and EXACT mode: bitwise equality. The whole array
 * is scanned (in chunks) to report the number of mismatching elements.
 */
object OutputChecker {

    fun check(name: String, type: DType, actual: Path, expected: Path, spec: CheckerSpec): CheckResult {
        if (!actual.exists()) return CheckResult(false, "$name: output was not produced")
        val size = expected.fileSize()
        if (actual.fileSize() != size) return CheckResult(false, "$name: output has wrong size")
        val n = size / type.bytes
        if (n == 0L) return CheckResult(true)

        val exact = spec.mode == CheckerMode.EXACT || !type.floating
        var mismatches = 0L
        var first = -1L
        var firstExp = ""
        var firstAct = ""
        var maxAbs = 0.0

        val elemsPerChunk = (CHUNK / type.bytes).toInt()
        val a = ByteBuffer.allocateDirect(elemsPerChunk * type.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val e = ByteBuffer.allocateDirect(elemsPerChunk * type.bytes).order(ByteOrder.LITTLE_ENDIAN)
        FileChannel.open(actual, StandardOpenOption.READ).use { ca ->
            FileChannel.open(expected, StandardOpenOption.READ).use { ce ->
                var base = 0L
                while (base < n) {
                    val count = minOf(elemsPerChunk.toLong(), n - base).toInt()
                    readFully(ca, a, count * type.bytes)
                    readFully(ce, e, count * type.bytes)
                    if (exact) {
                        if (a != e) {
                            for (i in 0 until count) {
                                if (!sameBits(a, e, i, type)) {
                                    mismatches++
                                    if (first < 0) {
                                        first = base + i
                                        firstAct = format(a, i, type)
                                        firstExp = format(e, i, type)
                                    }
                                }
                            }
                        }
                    } else {
                        for (i in 0 until count) {
                            val x = read(a, i, type)
                            val y = read(e, i, type)
                            val good = when {
                                y.isNaN() -> x.isNaN()
                                y.isInfinite() -> x == y
                                x.isNaN() || x.isInfinite() -> false
                                else -> {
                                    val diff = Math.abs(x - y)
                                    if (diff > maxAbs) maxAbs = diff
                                    diff <= spec.atol + spec.rtol * Math.abs(y)
                                }
                            }
                            if (!good) {
                                mismatches++
                                if (first < 0) {
                                    first = base + i
                                    firstAct = x.toString()
                                    firstExp = y.toString()
                                }
                            }
                        }
                    }
                    base += count
                }
            }
        }
        if (mismatches == 0L) return CheckResult(true)
        val tol = if (exact) "exact comparison" else "rtol=${spec.rtol}, atol=${spec.atol}, max abs error $maxAbs"
        return CheckResult(
            ok = false,
            summary = "$name: $mismatches of $n elements differ",
            detail = "$name[$first]: expected $firstExp, got $firstAct ($tol)",
        )
    }

    private const val CHUNK = 16L shl 20

    private fun read(b: ByteBuffer, i: Int, t: DType): Double = when (t) {
        DType.FLOAT32 -> b.getFloat(i * 4).toDouble()
        DType.FLOAT64 -> b.getDouble(i * 8)
        DType.FLOAT16 -> halfToFloat(b.getShort(i * 2).toInt()).toDouble()
        DType.BFLOAT16 -> Float.fromBits((b.getShort(i * 2).toInt() and 0xFFFF) shl 16).toDouble()
        else -> error("not a float type: $t")
    }

    /** IEEE 754 binary16 -> binary32 (exact; handles subnormals, infinities and NaN payloads). */
    internal fun halfToFloat(half: Int): Float {
        val h = half and 0xFFFF
        val sign = (h ushr 15) shl 31
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> {
                // subnormal: normalise the mantissa, each shift lowers the exponent by one
                var e = -14
                var m = mant
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e--
                }
                sign or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 31 -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return Float.fromBits(bits)
    }

    private fun sameBits(a: ByteBuffer, e: ByteBuffer, i: Int, t: DType): Boolean = when (t.bytes) {
        1 -> a.get(i) == e.get(i)
        2 -> a.getShort(i * 2) == e.getShort(i * 2)
        4 -> a.getInt(i * 4) == e.getInt(i * 4)
        else -> a.getLong(i * 8) == e.getLong(i * 8)
    }

    private fun format(b: ByteBuffer, i: Int, t: DType): String = when (t) {
        DType.INT8 -> b.get(i).toString()
        DType.UINT8 -> (b.get(i).toInt() and 0xFF).toString()
        DType.INT16 -> b.getShort(i * 2).toString()
        DType.INT32 -> b.getInt(i * 4).toString()
        DType.UINT32 -> b.getInt(i * 4).toUInt().toString()
        DType.INT64 -> b.getLong(i * 8).toString()
        DType.UINT64 -> b.getLong(i * 8).toULong().toString()
        else -> read(b, i, t).toString()
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer, bytes: Int) {
        buf.clear().limit(bytes)
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw EOFException("unexpected end of file")
        }
        buf.flip()
    }
}
