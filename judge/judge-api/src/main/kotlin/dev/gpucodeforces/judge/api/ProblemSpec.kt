package dev.gpucodeforces.judge.api

/**
 * Everything the judge needs to know about a problem. Sent inline with every submission (a few KB). The tests
 * are not part of it: the generator declares them, and executors generate them once per content hash of the
 * signature and the generator.
 */
data class ProblemSpec(
    /** Stable problem identifier from the backend (used in logs and cache paths). */
    val id: String,
    /** Backend-side version label. Informational; caching is keyed by content hash. */
    val version: String = "1",
    /** Signature of the function the contestant implements. */
    val signature: Signature,
    /** Python + PyTorch module with `tests()`, `generate(...)` and `reference(...)`, see docs/problem-format.md. */
    val generator: GeneratorSpec,
    val checker: CheckerSpec = CheckerSpec(),
    val limits: LimitsSpec = LimitsSpec(),
    val timing: TimingSpec = TimingSpec(),
    val compile: CompileSpec = CompileSpec(),
    val policy: PolicyOverrides = PolicyOverrides(),
    /** Requires an author-supplied validate_input(test, inputs) function. */
    val allowCustomTests: Boolean = false,
    val customTests: List<CustomTest> = emptyList(),
    val customOnly: Boolean = false,
)

data class CustomTest(val scalars: Map<String, Number>, val inputs: Map<String, List<Number>>)

/** How the solution is compiled. Null fields fall back to the executor's defaults for its GPU profile. */
data class CompileSpec(
    /**
     * Architecture the solution's device code is generated for, e.g. `sm_86` (no PTX, no JIT). The GPU of the
     * profile the submission is judged on must be able to run it, see [runsOn].
     */
    val arch: String? = null,
    /** nvcc flags for the solution, replacing the executor's defaults. Only flags [SpecValidator] allows. */
    val flags: List<String>? = null,
) {
    companion object {
        private val ARCH = Regex("sm_(\\d{2,3})")

        fun isArch(arch: String): Boolean = ARCH.matches(arch)

        /**
         * CUDA device code runs on GPUs of the same major architecture with the same or a later minor revision:
         * `sm_80` code runs on an `sm_86` GPU, `sm_75` or `sm_89` code does not.
         */
        fun runsOn(codeArch: String, gpuArch: String): Boolean {
            val code = ARCH.matchEntire(codeArch)?.groupValues?.get(1)?.toInt() ?: return false
            val gpu = ARCH.matchEntire(gpuArch)?.groupValues?.get(1)?.toInt() ?: return false
            return code / 10 == gpu / 10 && code <= gpu
        }
    }
}

data class Signature(
    val function: String = "solve",
    val params: List<ParamSpec>,
) {
    val arrays: List<ParamSpec> get() = params.filter { it.role != ParamRole.SCALAR }
    val scalars: List<ParamSpec> get() = params.filter { it.role == ParamRole.SCALAR }
    val outputs: List<ParamSpec> get() = params.filter { it.role == ParamRole.OUTPUT || it.role == ParamRole.INOUT }
}

data class ParamSpec(
    /** C identifier used in the generated signature and as a key in generator/reference dicts. */
    val name: String,
    val type: DType,
    val role: ParamRole,
    /** Where the harness places the array before calling `solve`. Required for arrays. */
    val memory: MemorySpace? = null,
    /** Element count of an array as an integer expression over scalar params, e.g. `"M*K"`. */
    val size: String? = null,
)

enum class ParamRole {
    /** `const T*` — read-only array. */
    INPUT,

    /** `T*` — array the solution must fill; compared against the reference. */
    OUTPUT,

    /** `T*` — initialised with generated data, modified in place, compared against the reference. */
    INOUT,

    /** Passed by value (`int N`, `float alpha`). Values come from the `scalars` of each test the generator declares. */
    SCALAR,
}

enum class MemorySpace { DEVICE, HOST }

enum class DType(val bytes: Int, val cppType: String, val floating: Boolean, val scalarAllowed: Boolean) {
    FLOAT16(2, "__half", true, false),
    BFLOAT16(2, "__nv_bfloat16", true, false),
    FLOAT32(4, "float", true, true),
    FLOAT64(8, "double", true, true),
    INT8(1, "int8_t", false, false),
    UINT8(1, "uint8_t", false, false),
    INT16(2, "int16_t", false, false),
    INT32(4, "int", false, true),
    UINT32(4, "unsigned int", false, true),
    INT64(8, "int64_t", false, true),
    UINT64(8, "uint64_t", false, true),
}

enum class GeneratorLanguage { PYTHON }

data class GeneratorSpec(
    val language: GeneratorLanguage = GeneratorLanguage.PYTHON,
    /** Source of the Python module. See docs/problem-format.md for the contract. */
    val source: String,
)

enum class CheckerMode {
    /** Floats: `|out - exp| <= atol + rtol * |exp|`, NaN only matches NaN, Inf must match exactly. Integers: exact. */
    TOLERANCE,

    /** Bitwise equality for every element. */
    EXACT,
}

data class CheckerSpec(
    val mode: CheckerMode = CheckerMode.TOLERANCE,
    val rtol: Double = 1e-5,
    val atol: Double = 1e-6,
)

data class LimitsSpec(
    /** Limit on the measured time of one `solve` call (see [TimingSpec.mode]). */
    val timeLimitMs: Long = 1000,
    /**
     * Safety limit on the wall time of one `solve` call, applied in every timing mode.
     * Defaults to `max(3 * timeLimitMs, timeLimitMs + 2000)`.
     */
    val wallTimeLimitMs: Long? = null,
    /** Host RAM available to the solution on top of the memory holding test data. */
    val memoryLimitMb: Long = 1024,
    /** GPU memory available to the solution on top of device buffers allocated by the harness. Null = whole GPU. */
    val gpuMemoryLimitMb: Long? = null,
) {
    val effectiveWallTimeLimitMs: Long get() = wallTimeLimitMs ?: maxOf(3 * timeLimitMs, timeLimitMs + 2000)
}

enum class TimingMode {
    /** Wall clock from entering `solve` until all GPU work it started has finished. */
    WALL,

    /** Total GPU time of kernels launched by `solve` (union of their execution intervals). */
    GPU_KERNELS,
}

data class TimingSpec(
    /**
     * Null = [TimingMode.WALL], wherever the data starts: with kernel time alone a solution that computes on the
     * CPU would measure as zero. [TimingMode.GPU_KERNELS] has to be asked for.
     */
    val mode: TimingMode? = null,
    /** Untimed calls on the first test before measuring (CUDA lazy module loading, caches, clocks). */
    val warmupRuns: Int = 1,
    /**
     * Max timed calls per test. The first call decides unless it lands in the grey zone
     * `(TL, 1.25 * TL]`; then up to [maxRuns] calls are made and the median is compared to TL.
     */
    val maxRuns: Int = 3,
) {
    val effectiveMode: TimingMode get() = mode ?: TimingMode.WALL
}

/** Per-problem adjustments of the GPU profile's default policy. */
data class PolicyOverrides(
    /** Extra allowed headers (exact names, or prefixes ending with `/`). */
    val allowHeaders: List<String> = emptyList(),
    /** Headers forbidden on top of the profile policy. */
    val forbidHeaders: List<String> = emptyList(),
    /** Extra SASS opcodes to forbid, e.g. `HMMA` to ban tensor cores. */
    val forbidSass: List<String> = emptyList(),
    /** SASS opcodes the profile forbids but this problem allows. */
    val allowSass: List<String> = emptyList(),
    /** Null = profile default (forbidden unless the profile allows it). */
    val allowInlineAsm: Boolean? = null,
)
