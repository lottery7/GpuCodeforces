package gpucodeforces.judge.executor

import gpucodeforces.judge.executor.config.SandboxConfig
import gpucodeforces.judge.executor.sandbox.GpuAccess
import gpucodeforces.judge.executor.sandbox.NsjailSandbox
import gpucodeforces.judge.executor.sandbox.SandboxSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NsjailCommandTest {
    @Test
    fun `builds isolated command with read-only data and gpu env`() {
        val sandbox = NsjailSandbox(SandboxConfig(seccompPolicy = Path.of("../sandbox/seccomp.kafel").toAbsolutePath()))
        val work = Path.of("/dev/shm/judge/work/s-1-a1")
        val spec = SandboxSpec(
            command = listOf("/work/solution.bin", "/work/manifest.txt"),
            workDir = work,
            readOnly = mapOf(Path.of("/var/lib/judge/cache/problems/abc/inputs") to "/data"),
            gpu = GpuAccess(0, "/dev/nvidia0"),
            wallTimeLimit = Duration.ofMillis(2500),
            memoryLimitBytes = 1L shl 30,
        )
        val cmd = sandbox.buildCommand(spec)
        val joined = cmd.joinToString(" ")
        assertTrue("--time_limit 3" in joined, joined)
        assertTrue("-R /var/lib/judge/cache/problems/abc/inputs:/data" in joined, joined)
        assertTrue("-B /dev/shm/judge/work/s-1-a1:/work" in joined, joined)
        assertTrue("--env CUDA_VISIBLE_DEVICES=0" in joined, joined)
        assertTrue("--rlimit_as inf" in joined, joined)
        assertTrue("--seccomp_policy" in joined, joined)
        assertEquals(listOf("--", "/work/solution.bin", "/work/manifest.txt"), cmd.takeLast(3))
        assertEquals("/data/t3/A.bin", sandbox.inside(spec, Path.of("/var/lib/judge/cache/problems/abc/inputs/t3/A.bin")))
        assertEquals("/work/out/t1_C.bin", sandbox.inside(spec, work.resolve("out/t1_C.bin")))

        // Exported for a manual check with a real nsjail binary.
        Files.createDirectories(Path.of("build"))
        Path.of("build/nsjail-cmd.txt").writeText(cmd.joinToString("\n"))
    }

    @Test
    fun `merged usr symlinks are recreated, real dirs are bound`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        val usrBin = Files.createDirectories(tmp.resolve("usr/bin"))
        val bin = Files.createSymbolicLink(tmp.resolve("bin"), Path.of("usr/bin"))
        val args = NsjailSandbox(SandboxConfig()).systemMountArgs(listOf(bin.toString(), usrBin.toString(), tmp.resolve("missing").toString()))
        assertEquals(listOf("-s", "usr/bin:$bin", "-R", usrBin.toString()), args)
    }
}
