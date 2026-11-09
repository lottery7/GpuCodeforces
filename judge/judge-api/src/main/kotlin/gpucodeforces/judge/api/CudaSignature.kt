package gpucodeforces.judge.api

/**
 * Renders the C++ declaration of the contestant's function for the generated harness. The function has
 * C++ linkage: a solution whose signature does not match the problem fails at link time with a readable
 * error instead of undefined behaviour. (Starter code is stored with the problem, not generated here.)
 */
object CudaSignature {

    fun paramDecl(p: ParamSpec): String = when (p.role) {
        ParamRole.SCALAR -> "${p.type.cppType} ${p.name}"
        ParamRole.INPUT -> "const ${p.type.cppType}* ${p.name}"
        ParamRole.OUTPUT, ParamRole.INOUT -> "${p.type.cppType}* ${p.name}"
    }

    fun declaration(sig: Signature): String =
        "void ${sig.function}(${sig.params.joinToString(", ") { paramDecl(it) }})"

    /** Headers needed for the parameter types. */
    fun typeIncludes(sig: Signature): List<String> = buildList {
        val types = sig.params.map { it.type }.toSet()
        if (types.any { it in setOf(DType.INT8, DType.UINT8, DType.INT16, DType.INT64, DType.UINT64) }) add("cstdint")
        if (DType.FLOAT16 in types) add("cuda_fp16.h")
        if (DType.BFLOAT16 in types) add("cuda_bf16.h")
    }
}
