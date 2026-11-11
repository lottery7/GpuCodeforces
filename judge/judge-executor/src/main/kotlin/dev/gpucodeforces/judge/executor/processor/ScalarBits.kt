package dev.gpucodeforces.judge.executor.processor

import dev.gpucodeforces.judge.api.DType

/**
 * The raw bits of a scalar as an unsigned decimal: what the manifests of the emulated processors carry, so a float
 * reaches the harness or the testbench exactly.
 */
fun scalarBits(type: DType, value: Number): String {
    val bits = when (type) {
        DType.FLOAT32 -> value.toFloat().toRawBits().toLong() and 0xFFFF_FFFFL
        DType.FLOAT64 -> value.toDouble().toRawBits()
        else -> value.toLong().let { if (type.bytes < Long.SIZE_BYTES) it and ((1L shl (type.bytes * 8)) - 1) else it }
    }
    return bits.toULong().toString()
}
