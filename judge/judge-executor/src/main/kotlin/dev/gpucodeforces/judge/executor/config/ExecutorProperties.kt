package dev.gpucodeforces.judge.executor.config

import dev.gpucodeforces.judge.api.Processors
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("judge.executor")
data class ExecutorProperties(
    /** Unique id of this executor in logs, events and heartbeats. Defaults to the hostname. */
    val id: String = defaultHostname(),
    /**
     * Processors this executor judges, ids from [Processors]: it consumes `judge.jobs.<id>` of each. GPU settings
     * ([gpuProfile], [profile], [gpus]) matter only with `gpu-cuda` here, [emulators] only for the others.
     */
    val processors: List<String> = listOf(Processors.GPU_CUDA),
    /**
     * Name of this machine's GPU type, e.g. `rtx3060`, reported with every `gpu-cuda` result. All executors of
     * `gpu-cuda` share one queue and their times are compared with each other, so they must all have this GPU;
     * [profile] says what hardware the name stands for.
     */
    val gpuProfile: String = "rtx3060",
    /** The GPU of this machine. Every executor of `gpu-cuda` has to describe it the same way. */
    val profile: GpuProfileConfig = GpuProfileConfig(arch = "sm_86"),
    /** GPUs of this machine used for judging `gpu-cuda`; one job runs on a GPU at a time. */
    val gpus: List<GpuSlotConfig> = listOf(GpuSlotConfig(0)),
    /**
     * Settings of emulated processors by id; a processor without an entry gets the defaults. Entries of processors
     * not in [processors] are ignored.
     */
    val emulators: Map<String, EmulatorConfig> = emptyMap(),
    /**
     * Jobs of each processor taken from its queue on top of its slots (GPUs, emulator workers), so compilation
     * overlaps with runs.
     */
    val prefetchExtra: Int = 2,
    /** Parallel nvcc invocations. */
    val compileWorkers: Int = 4,
    val paths: PathsConfig = PathsConfig(),
    val toolchain: ToolchainConfig = ToolchainConfig(),
    /** Toolchain and limits of `rtl`. */
    val rtl: RtlConfig = RtlConfig(),
    /** Toolchain and limits of `riscv-rvv`. */
    val riscv: RiscvConfig = RiscvConfig(),
    /** Simulator and limits of `cuda-sim`. */
    val cudaSim: CudaSimConfig = CudaSimConfig(),
    val sandbox: SandboxConfig = SandboxConfig(),
    val compile: CompileConfig = CompileConfig(),
    val prepare: PrepareConfig = PrepareConfig(),
    val run: RunConfig = RunConfig(),
    val mock: MockConfig = MockConfig(),
    /** How often an idle healthy GPU is probed; unhealthy GPUs are re-probed every [unhealthyProbeInterval]. */
    val idleProbeInterval: Duration = Duration.ofMinutes(5),
    val unhealthyProbeInterval: Duration = Duration.ofSeconds(30),
    /**
     * How long a stopping executor lets the jobs it has taken finish. A job cut short goes back to the queue and
     * that counts towards its delivery limit, so a few restarts in a row would dead-letter an innocent job.
     */
    val shutdownTimeout: Duration = Duration.ofMinutes(5),
) {
    init {
        require(id.isNotBlank()) { "judge.executor.id is blank: set JUDGE_EXECUTOR_ID to a name unique among all executors" }
        require(processors.isNotEmpty()) { "judge.executor.processors is empty: set JUDGE_PROCESSORS" }
        require(processors.toSet().size == processors.size) { "judge.executor.processors lists a processor twice: $processors" }
        processors.filter { Processors.find(it) == null }.forEach {
            throw IllegalArgumentException("judge.executor.processors: unknown processor '$it', known: ${Processors.ids}")
        }
        // settings of a processor this executor does not judge are allowed (one configuration for every executor)
        emulators.keys.filter { Processors.find(it)?.emulated != true }.forEach {
            throw IllegalArgumentException("judge.executor.emulators.$it: not an emulated processor, known: ${Processors.all.filter { p -> p.emulated }.map { p -> p.id }}")
        }
        require(emulators.values.all { it.workers >= 1 }) { "judge.executor.emulators.*.workers must be at least 1" }
    }

    /** Slots a processor runs jobs in: GPUs for `gpu-cuda`, workers for the others. */
    fun slotsOf(processor: String): Int =
        if (processor == Processors.GPU_CUDA) gpus.size else emulator(processor).workers

    /** Concurrent consumers of a processor's queue = its slots + prefetch. */
    fun capacityOf(processor: String): Int = slotsOf(processor) + prefetchExtra

    /** Jobs this executor takes at once, over all its processors. */
    val capacity: Int get() = processors.sumOf { capacityOf(it) }

    fun emulator(processor: String): EmulatorConfig = emulators[processor] ?: EmulatorConfig()

    companion object {
        fun defaultHostname(): String =
            System.getenv("HOSTNAME") ?: InetAddress.getLocalHost().hostName
    }
}

data class EmulatorConfig(
    /** Simulators of this processor running at once; each is single-threaded, so one worker is one core. */
    val workers: Int = 1,
)

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
    /** Absolute paths: these run inside the sandbox, which does not search PATH. */
    val nm: String = "/usr/bin/nm",
    val objcopy: String = "/usr/bin/objcopy",
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

data class RtlConfig(
    /**
     * Root of the OSS CAD Suite release (`bin/yosys`, `bin/verilator`). Mounted read-only at the same path in the
     * sandbox; Verilator also needs perl, make and a C++ compiler of the system.
     */
    val home: Path = Path.of("/opt/oss-cad-suite"),
    /** Prefix `yosys -V` must report after "Yosys ", e.g. "0.70+286"; null = not checked. */
    val expectedYosysVersion: String? = null,
    /** Prefix `verilator --version` must report after "Verilator ", e.g. "5.053"; null = not checked. */
    val expectedVerilatorVersion: String? = null,
    /** Synthesis and the simulator build, each. */
    val buildTimeLimit: Duration = Duration.ofMinutes(2),
    val buildMemoryLimitMb: Long = 4096,
    /** `make -j` of the simulator build. */
    val buildJobs: Int = 2,
    /** Larger circuits are a compilation error: their simulator would take too long to build and run. */
    val maxCells: Long = 1_000_000,
    /** Cycles a test may run when the score limit allows more, and on tests that are not measured. */
    val maxCycles: Long = 100_000_000,
    /** Host time one simulation may take. */
    val simulationTimeLimit: Duration = Duration.ofSeconds(60),
    /** Memory of one simulation on top of the test data. */
    val simulationMemoryLimitMb: Long = 2048,
) {
    val yosys: Path get() = home.resolve("bin/yosys")
    val verilator: Path get() = home.resolve("bin/verilator")
}

data class RiscvConfig(
    /** QEMU user mode built with plugins; its directory is mounted read-only in the sandbox. */
    val qemu: Path = Path.of("/opt/qemu/bin/qemu-riscv64"),
    /** The judge's TCG plugin (judge/riscv/judge_plugin.c). */
    val plugin: Path = Path.of("/opt/judge/riscv/judge_plugin.so"),
    /** Cross compilers: they run in the sandbox, so absolute paths. */
    val cc: String = "/usr/bin/riscv64-linux-gnu-gcc",
    val cxx: String = "/usr/bin/riscv64-linux-gnu-g++",
    /** Prefix `qemu-riscv64 --version` must report after "version ", e.g. "11.1.3"; null = not checked. */
    val expectedQemuVersion: String? = null,
    /** Prefix of `gcc -dumpfullversion`, e.g. "13.3"; null = not checked. */
    val expectedGccVersion: String? = null,
    /** The vector unit QEMU emulates: VLEN in bits. Part of the processor, the same for every problem. */
    val vlen: Int = 256,
    val compileTimeLimit: Duration = Duration.ofSeconds(60),
    val compileMemoryLimitMb: Long = 2048,
    /** Instructions a test may run when the score limit allows more, and on tests that are not measured. */
    val maxInstructions: Long = 5_000_000_000,
    /** Host time one simulation may take. */
    val simulationTimeLimit: Duration = Duration.ofSeconds(120),
    /** Memory of one simulation on top of the test data. */
    val simulationMemoryLimitMb: Long = 2048,
)

data class CudaSimConfig(
    /**
     * GPGPU-Sim's built tree: `runtime/` is its `libcudart` (a link the image makes to the build directory),
     * `configs/tested-cfgs/<gpu>/` the GPU models, `VERSION` the commit.
     */
    val home: Path = Path.of("/opt/gpgpu-sim"),
    /** The modelled GPU, a directory of `configs/tested-cfgs`: the processor, the same for every problem. */
    val gpu: String = "SM7_QV100",
    /** PTX that goes into the binary; GPGPU-Sim runs PTX, and QV100 is compute capability 7.0. */
    val ptxArch: String = "compute_70",
    val nvcc: String = "/usr/local/cuda/bin/nvcc",
    val cuobjdump: String = "/usr/local/cuda/bin/cuobjdump",
    val nm: String = "/usr/bin/nm",
    /** Prefix of the commit in [home]/VERSION; null = not checked. */
    val expectedVersion: String? = null,
    val compileTimeLimit: Duration = Duration.ofSeconds(120),
    val compileMemoryLimitMb: Long = 4096,
    /** Cycles a test may run when the score limit allows more (`-gpgpu_max_cycle`). */
    val maxCycles: Long = 10_000_000,
    /** Host time one simulation may take. */
    val simulationTimeLimit: Duration = Duration.ofMinutes(5),
    /** Memory of one simulation on top of the test data. */
    val simulationMemoryLimitMb: Long = 4096,
) {
    val runtime: Path get() = home.resolve("runtime")
    val gpuConfigs: Path get() = home.resolve("configs/tested-cfgs").resolve(gpu)
}

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
    /**
     * First uid/gid sandboxed processes run as (outside the user namespace). Each concurrent sandbox gets its own
     * out of [uidPoolSize] consecutive ones: RLIMIT_NPROC is counted per uid, and with a shared uid one solution
     * spawning threads up to the limit would make compilations and runs of other submissions fail.
     */
    val uid: Int = 99999,
    val gid: Int = 99999,
    val uidPoolSize: Int = 32,
    /** Use nsjail cgroups v2 for memory/pids limits. Requires a delegated cgroup subtree; otherwise the executor polls RSS. */
    val useCgroupsV2: Boolean = false,
    val pidsLimit: Int = 256,
    val maxOpenFiles: Int = 512,
    /** RLIMIT_STACK; also the default stack size of every thread the sandboxed process starts. */
    val stackLimitMb: Long = 64,
    /**
     * RLIMIT_NPROC: processes and threads of one sandbox (each has a uid of its own, see [uid]).
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
