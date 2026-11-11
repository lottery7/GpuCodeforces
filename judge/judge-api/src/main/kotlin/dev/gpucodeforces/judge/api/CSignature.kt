package dev.gpucodeforces.judge.api

/**
 * The contestant's function in C and C++ for processors without a GPU (`riscv-rvv`, `coral-npu`): every array is a
 * plain pointer to host memory, whatever [ParamSpec.memory] says. Half-precision types are the compiler's own.
 */
object CSignature {
    private val TYPES = mapOf(
        DType.FLOAT16 to "_Float16",
        DType.BFLOAT16 to "__bf16",
        DType.FLOAT32 to "float",
        DType.FLOAT64 to "double",
        DType.INT8 to "int8_t",
        DType.UINT8 to "uint8_t",
        DType.INT16 to "int16_t",
        DType.INT32 to "int32_t",
        DType.UINT32 to "uint32_t",
        DType.INT64 to "int64_t",
        DType.UINT64 to "uint64_t",
    )

    fun type(type: DType): String = TYPES.getValue(type)

    fun paramDecl(p: ParamSpec): String = when (p.role) {
        ParamRole.SCALAR -> "${type(p.type)} ${p.name}"
        ParamRole.INPUT -> "const ${type(p.type)}* ${p.name}"
        ParamRole.OUTPUT, ParamRole.INOUT -> "${type(p.type)}* ${p.name}"
    }

    fun declaration(sig: Signature): String =
        "void ${sig.function}(${sig.params.joinToString(", ") { paramDecl(it) }})"

    /** `stdint.h` for C, `cstdint` for C++. */
    fun includes(cpp: Boolean): List<String> = listOf(if (cpp) "cstdint" else "stdint.h")
}
