package gpucodeforces.judge.executor.build

import gpucodeforces.judge.api.PolicyOverrides
import gpucodeforces.judge.api.TimingMode
import gpucodeforces.judge.executor.config.ProfilePolicyConfig

/**
 * What a solution may use. Default: everything is forbidden except the C/C++ standard library and the CUDA
 * runtime (incl. fp16/bf16, cooperative groups, WMMA, libcu++). The GPU profile and the problem can adjust it.
 *
 * Enforcement layers:
 *  1. source scan: `#include` allow-list, no computed includes, no inline asm;
 *  2. compiler dependency list (-MD): no header from a forbidden library, even if reached via macros;
 *  3. object file: forbidden SASS opcodes (cuobjdump) and forbidden host symbols (nm);
 *  4. link: only cudart_static and libstdc++ are linked, so other CUDA libraries are simply unavailable.
 */
data class EffectivePolicy(
    val allowedHeaders: Set<String>,
    val allowedHeaderPrefixes: List<String>,
    val forbiddenHeaders: Set<String>,
    /** Substrings of resolved header paths that are forbidden (library directories). */
    val forbiddenPathFragments: List<String>,
    val forbiddenSass: Set<String>,
    val allowInlineAsm: Boolean,
    /** Host symbols the solution object must not reference. */
    val forbiddenUndefinedSymbols: List<Regex>,
    /** Symbols the solution object must not define (harness internals, CUDA runtime entry points). */
    val forbiddenDefinedSymbols: List<Regex>,
) {
    fun headerAllowed(name: String): Boolean =
        name !in forbiddenHeaders && (name in allowedHeaders || allowedHeaderPrefixes.any { name.startsWith(it) })

    companion object {
        fun of(profile: ProfilePolicyConfig, problem: PolicyOverrides, timing: TimingMode): EffectivePolicy {
            val extra = profile.extraAllowedHeaders + problem.allowHeaders
            val forbiddenHeaders = (profile.forbiddenHeaders + problem.forbidHeaders).toSet()
            val sass = (profile.forbiddenSass + problem.forbidSass).map { it.uppercase() }.toSet() - problem.allowSass.map { it.uppercase() }.toSet()
            val undefined = buildList {
                addAll(ALWAYS_FORBIDDEN_UNDEFINED)
                // Kernel-time measurement only sees launches through the wrapped runtime entry points.
                if (timing == TimingMode.GPU_KERNELS) addAll(KERNEL_TIMING_FORBIDDEN_UNDEFINED)
            }
            // Explicitly allowed headers lift the path ban for that library (e.g. allowing <thrust/sort.h>).
            val liftedFragments = extra.mapNotNull { h -> FORBIDDEN_PATH_FRAGMENTS.firstOrNull { frag -> "/$h".contains(frag) } }.toSet()
            return EffectivePolicy(
                allowedHeaders = DEFAULT_HEADERS + extra.filter { !it.endsWith("/") },
                allowedHeaderPrefixes = DEFAULT_PREFIXES + extra.filter { it.endsWith("/") },
                forbiddenHeaders = forbiddenHeaders,
                forbiddenPathFragments = FORBIDDEN_PATH_FRAGMENTS - liftedFragments,
                forbiddenSass = sass,
                allowInlineAsm = problem.allowInlineAsm ?: profile.allowInlineAsm,
                forbiddenUndefinedSymbols = undefined,
                forbiddenDefinedSymbols = FORBIDDEN_DEFINED,
            )
        }

        private val CXX_HEADERS = setOf(
            "algorithm", "any", "array", "atomic", "barrier", "bit", "bitset", "cassert", "cctype", "cerrno", "cfenv",
            "cfloat", "charconv", "chrono", "cinttypes", "climits", "clocale", "cmath", "compare", "complex", "concepts",
            "condition_variable", "cstdarg", "cstddef", "cstdint", "cstdio", "cstdlib", "cstring", "ctime", "cuchar",
            "cwchar", "cwctype", "deque", "exception", "execution", "expected", "forward_list", "functional", "future",
            "initializer_list", "iomanip", "ios", "iosfwd", "iostream", "istream", "iterator", "latch", "limits", "list",
            "locale", "map", "memory", "memory_resource", "mutex", "new", "numbers", "numeric", "optional", "ostream",
            "queue", "random", "ranges", "ratio", "regex", "scoped_allocator", "semaphore", "set", "shared_mutex", "span",
            "sstream", "stack", "stdexcept", "stop_token", "streambuf", "string", "string_view", "system_error", "thread",
            "tuple", "type_traits", "typeindex", "typeinfo", "unordered_map", "unordered_set", "utility", "valarray",
            "variant", "vector", "version", "fstream", "filesystem", "format",
            // C headers
            "assert.h", "ctype.h", "errno.h", "float.h", "inttypes.h", "limits.h", "math.h", "stdarg.h", "stdbool.h",
            "stddef.h", "stdint.h", "stdio.h", "stdlib.h", "string.h", "time.h", "wchar.h",
        )

        private val CUDA_HEADERS = setOf(
            "cuda_runtime.h", "cuda_runtime_api.h", "device_launch_parameters.h", "device_functions.h", "driver_types.h",
            "vector_types.h", "vector_functions.h", "math_constants.h", "cuda_fp16.h", "cuda_bf16.h", "cuda_fp8.h",
            "cuda_fp4.h", "cuda_fp6.h", "mma.h", "cooperative_groups.h", "cuda_pipeline.h", "cuda_pipeline_primitives.h",
            "cuda_awbarrier.h", "cuda_awbarrier_primitives.h", "sm_20_atomic_functions.h", "sm_32_intrinsics.h",
            "sm_60_atomic_functions.h", "sm_61_intrinsics.h", "surface_functions.h", "texture_fetch_functions.h",
            "cuda_texture_types.h", "cuda_surface_types.h", "builtin_types.h", "host_defines.h", "channel_descriptor.h",
        )

        val DEFAULT_HEADERS: Set<String> = CXX_HEADERS + CUDA_HEADERS

        /** libcu++ (the CUDA C++ standard library) and cooperative groups sub-headers. */
        val DEFAULT_PREFIXES: List<String> = listOf("cuda/", "cooperative_groups/")

        val FORBIDDEN_PATH_FRAGMENTS: List<String> = listOf(
            "/thrust/", "/cub/", "/cutlass/", "/cute/", "/cublas", "/cublasLt", "/cudnn", "/cufft", "/curand",
            "/cusparse", "/cusolver", "/nvrtc", "/npp", "/nccl", "/cupti", "/nvToolsExt", "/nvtx3/", "/cudaProfiler",
            "/cuda.h", "/cudaGL", "/cudaEGL", "/cudaVDPAU",
        )

        private val ALWAYS_FORBIDDEN_UNDEFINED = listOf(
            // dynamic loading would bypass every other check
            Regex("dl(open|mopen|sym|vsym|info|addr)"),
            // CUDA driver API (only the runtime API is allowed)
            Regex("cu[A-Z]\\w*"),
            // destroying the context invalidates harness buffers and measurements
            Regex("cudaDeviceReset"),
            Regex("cudaGetDriverEntryPoint\\w*"),
            Regex("cupti\\w*"),
            // harness internals
            Regex("__wrap_\\w+"), Regex("__real_\\w+"), Regex("_ZN5judge\\w*"),
            Regex("fork|vfork|execv\\w*|execl\\w*|execve|system|popen|posix_spawn\\w*"),
        )

        private val KERNEL_TIMING_FORBIDDEN_UNDEFINED = listOf(
            Regex("cudaGraphLaunch\\w*"),
            Regex("cudaStreamBeginCapture\\w*"),
        )

        private val FORBIDDEN_DEFINED = listOf(
            Regex("main"), Regex("__wrap_\\w+"), Regex("__real_\\w+"), Regex("cuda[A-Z]\\w*"), Regex("_ZN5judge\\w*"),
        )
    }
}
