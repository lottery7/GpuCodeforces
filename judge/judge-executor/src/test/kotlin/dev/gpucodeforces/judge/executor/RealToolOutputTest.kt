package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.PolicyOverrides
import dev.gpucodeforces.judge.api.TimingMode
import dev.gpucodeforces.judge.executor.processor.gpucuda.EffectivePolicy
import dev.gpucodeforces.judge.executor.processor.gpucuda.SourceChecks
import dev.gpucodeforces.judge.executor.config.ProfilePolicyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsers against real outputs of the pinned toolchain (nvcc 12.8.93, g++ 13.3) for the example solutions:
 * `-MD` dependency files, `cuobjdump -sass` and `nm` (captured in nvidia/cuda:12.8.1-devel-ubuntu24.04).
 */
class RealToolOutputTest {
    private fun fixture(name: String) = javaClass.getResource("/nvcc-12.8/$name")!!.readText()

    private val policy = EffectivePolicy.of(ProfilePolicyConfig(), PolicyOverrides(), TimingMode.GPU_KERNELS)

    @Test
    fun `dependency file of a clean solution has no violations`() {
        val deps = SourceChecks.parseDepFile(fixture("deps_accepted.txt"))
        assertTrue(deps.size > 50, "parsed ${deps.size} entries")
        assertTrue(deps.any { it.endsWith("/cuda_runtime.h") })
        assertEquals(emptyList(), SourceChecks.checkDependencies(fixture("deps_accepted.txt"), policy))
    }

    @Test
    fun `dependency file of a thrust solution is a violation`() {
        val v = SourceChecks.checkDependencies(fixture("deps_forbidden.txt"), policy)
        assertTrue(v.any { "thrust/" in it }, v.toString())
        assertTrue(v.any { "cub/" in it }, v.toString())
    }

    @Test
    fun `SASS opcodes of the matmul example`() {
        val ops = SourceChecks.sassOpcodes(fixture("sass_matmul.txt"))
        assertTrue(listOf("FFMA", "LDS", "STS", "BAR", "LDG", "STG", "EXIT").all { it in ops }, ops.toString())
        assertTrue("HMMA" !in ops)
        assertTrue(ops.none { it.contains('.') || it.startsWith("R") }, ops.toString())
    }

    @Test
    fun `nm of a clean solution passes symbol checks in both timing modes`() {
        val (undefined, defined) = SourceChecks.parseNm(fixture("nm_accepted.txt"))
        assertTrue("cudaLaunchKernel" in undefined, undefined.toString()) // <<<>>> goes through the wrapped entry point
        assertTrue("_Z5solvePKfS0_Pfi" in defined, defined.toString())
        assertEquals(emptyList(), SourceChecks.checkSymbols(undefined, defined, policy))
        val wall = EffectivePolicy.of(ProfilePolicyConfig(), PolicyOverrides(), TimingMode.WALL)
        assertEquals(emptyList(), SourceChecks.checkSymbols(undefined, defined, wall))
    }
}
