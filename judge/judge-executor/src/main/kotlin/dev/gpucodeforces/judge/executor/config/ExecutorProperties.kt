package dev.gpucodeforces.judge.executor.config

import dev.gpucodeforces.judge.api.Topology
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("judge.executor")
data class ExecutorProperties(
    /** Unique id of this executor in logs, events and heartbeats. Defaults to the hostname. */
    val id: String = defaultHostname(),
    /**
     * Name of the GPU type this executor serves, e.g. `rtx3060`: it consumes `judge.jobs.<gpuProfile>` and nothing
     * else. [profile] says what hardware that name stands for.
     */
    val gpuProfile: String = "rtx3060",
    /** The GPU of this machine. Every executor of one [gpuProfile] has to describe it the same way. */
    val profile: GpuProfileConfig = GpuProfileConfig(arch = "sm_86"),
    /** GPUs of this machine used for judging; one job runs on a GPU at a time. */
    val gpus: List<GpuSlotConfig> = listOf(GpuSlotConfig(0)),
    /** Jobs taken from the queue on top of the GPU count, so compilation overlaps with GPU work. */
    val prefetchExtra: Int = 2,
    /** Parallel nvcc invocations. */
    val compileWorkers: Int = 4,
    val paths: PathsConfig = PathsConfig(),
    val toolchain: ToolchainConfig = ToolchainConfig(),
    val sandbox: SandboxConfig = SandboxConfig(),
    val compile: CompileConfig = CompileConfig(),
    val prepare: PrepareConfig = PrepareConfig(),
    val run: RunConfig = RunConfig(),
    val mock: MockConfig = MockConfig(),
    /** How often an idle healthy GPU is probed; unhealthy GPUs are re-probed every [unhealthyProbeInterval]. */
    val idleProbeInterval: Duration = Duration.ofMinutes(5),
    val unhealthyProbeInterval: Duration = Duration.ofSeconds(30),
) {
    init {
        // the name becomes a queue name and a routing key shared with the gateway
        require(Topology.isProfileName(gpuProfile)) { "judge.executor.gpu-profile '$gpuProfile' must match ${Topology.PROFILE_NAME}" }
        require(id.isNotBlank()) { "judge.executor.id is blank: set JUDGE_EXECUTOR_ID to a name unique among all executors" }
    }

    /** Concurrent consumers = GPUs + prefetch. */
    val capacity: Int get() = gpus.size + prefetchExtra

    companion object {
        fun defaultHostname(): String =
            System.getenv("HOSTNAME") ?: InetAddress.getLocalHost().hostName
    }
}

data class GpuSlotConfig(
    /** CUDA device index (PCI bus order; CUDA_DEVICE_ORDER=PCI_BUS_ID is always set). */
    val index: Int,
    /**
     * Device node bound into the sandbox for this GPU (e.g. /dev/nvidia0). Null = all /dev/nvidia[0-9]* nodes
     * (fine for single-GPU machines). Ignored on WSL2 where the GPU is exposed through /dev/dxg.
     */
    val deviceNode: String? = null,
    /** Lock graphics clocks to this range (`nvidia-smi -lgc min,max`) for stable timing. Needs root; not available on WSL2. */
    val lockGraphicsClocksMhz: String? = null,
    /** Lock memory clocks (`nvidia-smi -lmc min,max`). Not supported on all GeForce cards. */
    val lockMemoryClocksMhz: String? = null,
)

data class PathsConfig(
    /**
     * Per-run scratch directories. Prefer tmpfs (e.g. /dev/shm/judge) - outputs are written here. The solution
     * binary is linked and executed here too, so the mount must allow exec (Docker's /dev/shm does not).
     */
    val work: Path = Path.of("/var/lib/judge/work"),
    /** Compiled objects, harnesses and generated tests. */
    val cache: Path = Path.of("/var/lib/judge/cache"),
    /** Kept run directories of INTERNAL_ERROR runs for post-mortem. */
    val failed: Path = Path.of("/var/lib/judge/failed"),
    /** Directory containing `include/judge/runtime.h` and `probe/probe.cu`. */
    val harness: Path = Path.of("/opt/judge/harness"),
    /** Directory containing the `judge_problem` Python package. */
    val pytools: Path = Path.of("/opt/judge/pytools"),
)

data class ToolchainConfig(
    val nvcc: String = "/usr/local/cuda/bin/nvcc",
    val cuobjdump: String = "/usr/local/cuda/bin/cuobjdump",
    val nm: String = "nm",
    val python: String = "python3",
    val nvidiaSmi: String = "nvidia-smi",
    /** Host compiler passed to nvcc via -ccbin; pinned so all submissions are built identically. */
    val hostCompiler: String = "g++",
    /**
     * Expected CUDA release, e.g. "12.8". The executor refuses to start if `nvcc --version` reports a different
     * release: all submissions must be compiled by the same, pinned toolchain.
     */
    val expectedCudaRelease: String? = null,
    /** Expected host compiler major version, e.g. "13". Null = not checked. */
    val expectedHostCompilerMajor: String? = null,
    /** Expected torch version prefix for problem preparation, e.g. "2.8.0". Null = not checked. */
    val expectedTorchVersion: String? = null,
)

enum class SandboxType {
    /** nsjail: namespaces, no network, read-only FS, seccomp, optional cgroups v2. Production. */
    NSJAIL,

    /** Plain child process with limits enforced by the executor only. Development / trusted setups. */
    NONE,
}

data class SandboxConfig(
    val type: SandboxType = SandboxType.NSJAIL,
    val nsjail: String = "nsjail",
    /** Kafel seccomp policy file passed to nsjail. Null = no seccomp filter. */
    val seccompPolicy: Path? = Path.of("/opt/judge/sandbox/seccomp.kafel"),
    /** Host directories bind-mounted read-only at the same path. Missing ones are skipped. */
    val readOnlyMounts: List<String> = listOf("/usr", "/lib", "/lib64", "/lib32", "/bin", "/sbin", "/etc/alternatives", "/etc/ld.so.cache", "/etc/ld.so.conf", "/etc/ld.so.conf.d"),
    /** Extra read-only mounts, e.g. a CUDA install outside /usr. */
    val extraReadOnlyMounts: List<String> = emptyList(),
    /** uid/gid the sandboxed process runs as (outside the user namespace). */
    val uid: Int = 99999,
    val gid: Int = 99999,
    /** Use nsjail cgroups v2 for memory/pids limits. Requires a delegated cgroup subtree; otherwise the executor polls RSS. */
    val useCgroupsV2: Boolean = false,
    val pidsLimit: Int = 256,
    val maxOpenFiles: Int = 512,
    /** RLIMIT_STACK; also the default stack size of every thread the sandboxed process starts. */
    val stackLimitMb: Long = 64,
    /**
     * RLIMIT_NPROC: processes and threads of the sandbox uid. All sandboxes of an executor share the uid, so this
     * covers the solution, the compilers and the test generator running at the same time.
     */
    val maxProcesses: Int = 2048,
)

data class CompileConfig(
    /** Fixed nvcc flags for every submission (in addition to -arch from the profile). */
    val flags: List<String> = listOf("-std=c++17", "-O3", "--expt-relaxed-constexpr"),
    val timeLimit: Duration = Duration.ofSeconds(30),
    val memoryLimitMb: Long = 4096,
    /** Max compiler output kept in results. */
    val maxLogBytes: Int = 16 * 1024,
)

data class PrepareConfig(
    /** Upper bound on generating all tests of a problem and running the reference. */
    val timeLimit: Duration = Duration.ofMinutes(10),
    /** Disk budget for generated tests; least recently used problems are evicted beyond it. */
    val cacheBudgetGb: Long = 40,
    /** cuda | cpu | auto */
    val device: String = "auto",
)

data class RunConfig(
    /** Allowance for CUDA context creation before `ready`. */
    val startupTimeout: Duration = Duration.ofSeconds(30),
    /** Host RAM allowed on top of the solution's limit and the test data (CUDA runtime, harness). */
    val hostOverheadMb: Long = 1024,
    /** GPU memory allowed on top of the solution's limit and the harness buffers (context, L2 scratch). */
    val gpuOverheadMb: Long = 512,
    /** Assumed minimal I/O throughput for loading/saving test data when computing watchdog deadlines. */
    val ioMbPerSecond: Long = 200,
    /** Memory/GPU sampling period while a solution runs. */
    val samplingInterval: Duration = Duration.ofMillis(50),
    /** Max stderr kept from the solution. */
    val maxStderrBytes: Int = 16 * 1024,
    /** Run directories of INTERNAL_ERROR runs kept in paths.failed. */
    val keepFailedRuns: Int = 50,
)

/** Settings of the `mock` Spring profile (an executor that judges nothing, for integration tests without a GPU). */
data class MockConfig(
    /** Pause after each test's progress event, so a client can observe TESTING before FINISHED. */
    val testDelay: Duration = Duration.ofMillis(20),
)

data class GpuProfileConfig(
    /**
     * Architecture of the GPU, e.g. sm_86: what solutions are compiled for by default (no PTX JIT), and what the
     * executor's GPU must report or it refuses to start.
     */
    val arch: String,
    /**
     * Exact device name the GPU must report, e.g. "NVIDIA GeForce RTX 3060". The architecture alone does not
     * identify a card (every RTX 30 is sm_86). Null = any card of [arch], with a warning at startup.
     */
    val gpuName: String? = null,
    val policy: ProfilePolicyConfig = ProfilePolicyConfig(),
)

/** What is allowed on a GPU profile, on top of the built-in defaults (C++ std + CUDA runtime headers). */
data class ProfilePolicyConfig(
    val extraAllowedHeaders: List<String> = emptyList(),
    val forbiddenHeaders: List<String> = emptyList(),
    /** SASS opcodes (e.g. HMMA, IMMA) that must not appear in the solution's device code. */
    val forbiddenSass: List<String> = emptyList(),
    val allowInlineAsm: Boolean = false,
)
