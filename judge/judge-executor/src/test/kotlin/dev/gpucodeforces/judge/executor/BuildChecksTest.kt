package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.PolicyOverrides
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.TimingMode
import dev.gpucodeforces.judge.executor.compile.EffectivePolicy
import dev.gpucodeforces.judge.executor.compile.HarnessGenerator
import dev.gpucodeforces.judge.executor.compile.SourceChecks
import dev.gpucodeforces.judge.executor.config.ProfilePolicyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildChecksTest {
    private val policy = EffectivePolicy.of(ProfilePolicyConfig(forbiddenSass = listOf("HMMA")), PolicyOverrides(), TimingMode.WALL)

    @Test
    fun `allows standard and CUDA runtime headers`() {
        val src = """
            #include <cuda_runtime.h>
            #include <cuda_fp16.h>
            #include <cooperative_groups.h>
            #include <cooperative_groups/reduce.h>
            #include <cuda/std/atomic>
            #include <vector>
            #include <cstdio>
            // #include <thrust/sort.h> in a comment is fine
            const char* s = "#include <cublas_v2.h> in a string is fine";
            __global__ void k() {}
            void solve(const float* a, float* b, int n) {}
        """.trimIndent()
        assertEquals(emptyList(), SourceChecks.checkSource(src, policy))
    }

    @Test
    fun `rejects forbidden, local and computed includes`() {
        val src = """
            #include <thrust/sort.h>
            #  include "helper.h"
            #define H <cub/cub.cuh>
            #include H
            %:include <cublas_v2.h>
            #inc\
            lude <cuda.h>
            #include_next <vector>
        """.trimIndent()
        val v = SourceChecks.checkSource(src, policy)
        assertTrue(v.any { "thrust/sort.h" in it }, v.toString())
        assertTrue(v.any { "local includes" in it }, v.toString())
        assertTrue(v.any { "literal name" in it }, v.toString())
        assertTrue(v.any { "cublas_v2.h" in it }, v.toString())
        assertTrue(v.any { "<cuda.h>" in it }, v.toString())
        assertTrue(v.any { "#include_next" in it }, v.toString())
    }

    @Test
    fun `rejects inline asm unless allowed`() {
        val src = "__device__ int lane() { int r; asm volatile(\"mov.u32 %0, %%laneid;\" : \"=r\"(r)); return r; }\nint asmCount = 0; // asm"
        assertEquals(1, SourceChecks.checkSource(src, policy).size)
        val allowed = EffectivePolicy.of(ProfilePolicyConfig(), PolicyOverrides(allowInlineAsm = true), TimingMode.WALL)
        assertEquals(emptyList(), SourceChecks.checkSource(src, allowed))
    }

    @Test
    fun `problem can allow a library header`() {
        val p = EffectivePolicy.of(ProfilePolicyConfig(), PolicyOverrides(allowHeaders = listOf("thrust/")), TimingMode.WALL)
        assertEquals(emptyList(), SourceChecks.checkSource("#include <thrust/sort.h>", p))
        assertEquals(emptyList(), SourceChecks.checkDependencies("solution.o: solution.cu /usr/local/cuda/include/thrust/sort.h", p))
    }

    @Test
    fun `dependency file catches forbidden headers reached indirectly`() {
        val deps = """
            solution.o: /work/solution.cu \
             /usr/local/cuda/include/cuda_runtime.h \
             /usr/local/cuda/include/cub/cub.cuh /usr/include/c++/13/vector
        """.trimIndent()
        val v = SourceChecks.checkDependencies(deps, policy)
        assertEquals(1, v.size, v.toString())
        assertTrue("cub/cub.cuh" in v.single())
    }

    @Test
    fun `parses SASS opcodes and applies profile bans`() {
        val sass = """
                Function : _Z6kernelPf
                /*0000*/                   MOV R1, c[0x0][0x28] ;
                /*0010*/                   HMMA.16816.F32 R4, R8, R12, R4 ;
                /*0020*/               @!P0 EXIT ;
                /*0030*/              @P1 LDG.E R2, [R2.64] ;
        """.trimIndent()
        val ops = SourceChecks.sassOpcodes(sass)
        assertEquals(setOf("MOV", "HMMA", "EXIT", "LDG"), ops)
        assertEquals(listOf("device code uses forbidden instruction HMMA"), SourceChecks.checkSass(ops, policy))
    }

    @Test
    fun `symbol checks block driver API, dlopen and harness tampering`() {
        val nm = """
                             U cudaMalloc
                             U cudaLaunchKernel
                             U __cudaRegisterFatBinary
                             U cuLaunchKernel
                             U dlsym
                             U cudaGraphLaunch
            0000000000000000 T _Z5solvePKfPfi
            0000000000000010 T __wrap_cudaLaunchKernel
            0000000000000020 t _ZL10helperv
        """.trimIndent()
        val (undefined, defined) = SourceChecks.parseNm(nm)
        assertTrue("_ZL10helperv" !in defined)
        val wall = SourceChecks.checkSymbols(undefined, defined, policy)
        assertEquals(
            listOf("calls forbidden function cuLaunchKernel", "calls forbidden function dlsym", "defines reserved symbol __wrap_cudaLaunchKernel"),
            wall,
        )
        val kernels = EffectivePolicy.of(ProfilePolicyConfig(), PolicyOverrides(), TimingMode.GPU_KERNELS)
        assertTrue(SourceChecks.checkSymbols(undefined, defined, kernels).any { "cudaGraphLaunch" in it })
    }

    @Test
    fun `generates harness for mixed signature`() {
        val sig = Signature(
            params = listOf(
                ParamSpec("x", DType.FLOAT16, ParamRole.INPUT, MemorySpace.HOST, "n"),
                ParamSpec("y", DType.FLOAT32, ParamRole.INOUT, MemorySpace.DEVICE, "n"),
                ParamSpec("alpha", DType.FLOAT32, ParamRole.SCALAR),
                ParamSpec("n", DType.INT64, ParamRole.SCALAR),
            ),
        )
        val src = HarnessGenerator.generate(sig)
        assertTrue("void solve(const __half* x, float* y, float alpha, int64_t n);" in src, src)
        assertTrue("#include <cuda_fp16.h>" in src, src)
        assertTrue("{\"x\", 2, judge::Role::Input, judge::Mem::Host, 0}" in src, src)
        assertTrue("{\"alpha\", 4, judge::Role::Scalar, judge::Mem::None, 'f'}" in src, src)
        assertTrue(
            "solve(static_cast<const __half*>(p[0]), static_cast<float*>(p[1]), static_cast<float>(s[2].d), static_cast<int64_t>(s[3].i));" in src,
            src,
        )
    }
}
