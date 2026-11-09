package dev.gpucodeforces.judge.executor.sandbox

import dev.gpucodeforces.judge.executor.config.SandboxConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory

/** WSL2 exposes GPUs through the DirectX kernel interface instead of /dev/nvidia*. */
fun isWsl2(): Boolean = Path.of("/dev/dxg").exists()

/**
 * nsjail-based sandbox:
 *  - new user/pid/mount/net/ipc/uts namespaces (no network at all), unprivileged uid,
 *  - only system dirs, the run directory and the test inputs are mounted (inputs read-only),
 *  - only the GPU device nodes of the assigned GPU are present in /dev,
 *  - seccomp policy blocks ptrace, mount, bpf, perf_event_open, kernel module/keyring syscalls, etc.,
 *  - rlimits for file size, open files and the number of processes; cgroups v2 memory+pids when enabled.
 * RLIMIT_AS is unlimited unless the caller asks for it: CUDA reserves huge virtual address ranges.
 */
class NsjailSandbox(private val cfg: SandboxConfig, val wsl: Boolean = isWsl2()) : Sandbox {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "nsjail"

    private fun mounts(spec: SandboxSpec): Map<Path, String> = spec.readOnly + (spec.workDir to Sandbox.WORK)

    override fun inside(spec: SandboxSpec, host: Path): String = mapThroughMounts(host, mounts(spec))

    fun buildCommand(spec: SandboxSpec): List<String> = buildList {
        add(cfg.nsjail)
        addAll(listOf("--mode", "o", "--quiet"))
        addAll(listOf("--log", Sandbox.logFile(spec.workDir).toString()))
        addAll(listOf("--hostname", "judge", "--cwd", Sandbox.WORK))
        addAll(listOf("--user", "${cfg.uid}:${cfg.uid}:1", "--group", "${cfg.gid}:${cfg.gid}:1"))
        addAll(listOf("--time_limit", ((spec.wallTimeLimit.toMillis() + 999) / 1000).toString()))
        addAll(listOf("--rlimit_as", spec.addressSpaceBytes?.let { megabytes(it) } ?: "inf", "--rlimit_core", "0", "--rlimit_cpu", "inf"))
        addAll(listOf("--rlimit_fsize", megabytes(spec.maxFileBytes)))
        // The stack limit must be finite: nsjail turns "inf" into a huge finite value, glibc then uses it as the
        // default thread stack size and every pthread_create fails with EAGAIN.
        addAll(listOf("--rlimit_nofile", cfg.maxOpenFiles.toString(), "--rlimit_stack", cfg.stackLimitMb.toString()))
        // Without cgroups this is the only thing between a fork bomb and the executor's own threads.
        addAll(listOf("--rlimit_nproc", cfg.maxProcesses.toString()))
        if (cfg.useCgroupsV2) {
            add("--use_cgroupv2")
            spec.memoryLimitBytes?.let { addAll(listOf("--cgroup_mem_max", it.toString())) }
            addAll(listOf("--cgroup_pids_max", cfg.pidsLimit.toString()))
        }
        cfg.seccompPolicy?.let { addAll(listOf("--seccomp_policy", it.toString())) }

        addAll(systemMountArgs(cfg.readOnlyMounts + cfg.extraReadOnlyMounts))
        addAll(listOf("-R", "/sys", "-R", "/dev/urandom", "-R", "/dev/zero", "-B", "/dev/null"))
        addAll(listOf("-m", "none:/tmp:tmpfs:size=${spec.tmpBytes}", "-m", "none:/dev/shm:tmpfs:size=${spec.tmpBytes}"))
        for ((host, inside) in spec.readOnly) addAll(listOf("-R", "$host:$inside"))
        addAll(listOf("-B", "${spec.workDir}:${Sandbox.WORK}"))
        // Docker Desktop mounts /dev/dxg read-only, and a user namespace cannot remount it read-write (EPERM).
        // A read-only mount does not restrict ioctls on the device node, so the GPU works either way.
        val deviceBind = if (wsl) "-R" else "-B"
        spec.gpu?.let { gpu -> gpuDevices(gpu).forEach { addAll(listOf(deviceBind, it)) } }
        if (spec.gpu != null && wsl && Path.of("/usr/lib/wsl").exists()) addAll(listOf("-R", "/usr/lib/wsl"))

        val env = baseEnv(spec) + spec.env
        for ((k, v) in env) addAll(listOf("--env", "$k=$v"))
        add("--")
        addAll(spec.command)
    }

    /** nsjail takes rlimits in MiB; rounded up so the limit is never below what was asked for. */
    private fun megabytes(bytes: Long): String = ((bytes shr 20) + 1).toString()

    /**
     * Read-only binds of system paths. On merged-/usr distros (Ubuntu 24.04) /bin, /lib, /lib64, /sbin are symlinks:
     * a bind mount would not recreate them, so they are recreated as symlinks inside the sandbox.
     */
    internal fun systemMountArgs(paths: List<String>): List<String> = buildList {
        for (m in paths) {
            val p = Path.of(m)
            when {
                Files.isSymbolicLink(p) -> addAll(listOf("-s", "${Files.readSymbolicLink(p)}:$m"))
                p.exists() -> addAll(listOf("-R", m))
            }
        }
    }

    private fun baseEnv(spec: SandboxSpec): Map<String, String> = buildMap {
        put("PATH", "/usr/local/cuda/bin:/usr/local/bin:/usr/bin:/bin")
        put("HOME", "/tmp")
        put("TMPDIR", "/tmp")
        put("LANG", "C.UTF-8")
        if (wsl) put("LD_LIBRARY_PATH", "/usr/lib/wsl/lib")
        spec.gpu?.let { putAll(it.cudaEnv()) }
    }

    private fun gpuDevices(gpu: GpuAccess): List<String> {
        if (wsl) return listOf("/dev/dxg")
        val common = listOf("/dev/nvidiactl", "/dev/nvidia-uvm", "/dev/nvidia-uvm-tools", "/dev/nvidia-modeset")
            .filter { Path.of(it).exists() }
        val cards = gpu.deviceNode?.let { listOf(it) }
            ?: Path.of("/dev").listDirectoryEntries("nvidia*").map { it.toString() }.filter { it.matches(Regex("/dev/nvidia[0-9]+")) }
        return common + cards
    }

    override fun start(spec: SandboxSpec): Process {
        Files.createDirectories(spec.workDir)
        val cmd = buildCommand(spec)
        log.debug("nsjail command: {}", cmd)
        return ProcessBuilder(cmd)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
    }

    /**
     * Fails fast at startup if nsjail is missing, cannot create namespaces on this host, cannot execute files
     * from the work directory (solutions are linked there; Docker mounts /dev/shm noexec, for example), or the
     * configured seccomp policy is absent (running without the filter must be a decision, not a typo in a path).
     */
    fun selfTest(workDir: Path) {
        cfg.seccompPolicy?.let { check(it.exists()) { "seccomp policy $it does not exist (judge.executor.sandbox.seccomp-policy)" } }
        Files.createDirectories(workDir)
        val script = workDir.resolve("selftest.sh")
        script.writeText(SELF_TEST_SCRIPT)
        check(script.toFile().setExecutable(true, false)) { "cannot make $script executable" }
        val spec = SandboxSpec(emptyList(), workDir, wallTimeLimit = Duration.ofSeconds(10))
        val res = runToCompletion(spec.copy(command = listOf(inside(spec, script))), Duration.ofSeconds(15), 8192)
        check(res.ok) {
            "nsjail self-test failed (it runs a script from $workDir, which must not be on a noexec mount): " +
                "${res.describe()}; log: ${Sandbox.logTail(workDir) ?: "-"}"
        }
        log.info("nsjail self-test passed (wsl={}, cgroupsV2={})", wsl, cfg.useCgroupsV2)
    }

    private companion object {
        @Language("Shell Script")
        const val SELF_TEST_SCRIPT = "#!/bin/sh\nexit 0\n"
    }
}

/** No isolation: runs the command directly. Limits are only those enforced by the executor's watchdog. */
class DirectSandbox : Sandbox {
    override val name = "none"

    override fun inside(spec: SandboxSpec, host: Path): String = host.toAbsolutePath().normalize().toString()

    override fun start(spec: SandboxSpec): Process {
        Files.createDirectories(spec.workDir)
        val pb = ProcessBuilder(spec.command)
            .directory(spec.workDir.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val env = pb.environment()
        spec.gpu?.let { env.putAll(it.cudaEnv()) }
        env.putAll(spec.env)
        return pb.start()
    }
}
