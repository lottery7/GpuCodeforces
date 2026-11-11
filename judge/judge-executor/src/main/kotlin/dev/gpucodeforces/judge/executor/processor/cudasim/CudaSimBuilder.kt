package dev.gpucodeforces.judge.executor.processor.cudasim

import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.BuildCache
import dev.gpucodeforces.judge.executor.processor.BuildStatus
import dev.gpucodeforces.judge.executor.processor.EmulatorBuild
import dev.gpucodeforces.judge.executor.processor.FreshBuild
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.StoredBuild
import dev.gpucodeforces.judge.executor.processor.gpucuda.CUDA_PATH
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.ProcResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Builds a submission for GPGPU-Sim, in the sandbox: the solution with PTX for the modelled GPU in the binary (the
 * simulator runs PTX), the harness of [CudaSimHarness], and GPGPU-Sim's own `libcudart` instead of NVIDIA's, so a
 * runtime function the simulator lacks (CUDA Graphs, texture objects) is a link error. Two checks of the solution:
 * no `cudaMemcpy*` (work on the host costs no simulated cycles; advisory, the harness copies the data) and no warp
 * shuffles (`shfl.sync`), which GPGPU-Sim computes wrongly without an error. Cached like the other processors.
 */
class CudaSimBuilder(private val props: ExecutorProperties, private val fingerprint: String, private val sandbox: Sandbox) {
    private val config = props.cudaSim
    private val cache = BuildCache(props.paths.cache.resolve("cuda-sim"), props.compileWorkers)

    /** The artifact is the binary: the harness linked with the solution and GPGPU-Sim's runtime. */
    fun build(source: String, sig: Signature): EmulatorBuild<Path> {
        val harness = CudaSimHarness.generate(sig)
        val key = Hashing.sha256(source, harness, fingerprint, flags().joinToString(" "), CACHE_FORMAT)
        return cache.build(key, ::read) { tmp -> buildFresh(source, harness, tmp) }
    }

    // compute_70 is the simulated QV100's PTX; nvcc's warning that such old targets are deprecated is noise for a participant
    private fun flags() = listOf("-O3", "-std=c++17", "-Wno-deprecated-gpu-targets", "-gencode", "arch=${config.ptxArch},code=${config.ptxArch}")

    private fun read(build: StoredBuild): EmulatorBuild<Path> =
        if (build.status == BuildStatus.OK) EmulatorBuild.Ready(build.dir.resolve("solution"), build.compileLog) else build.rejected()

    private fun buildFresh(source: String, harness: String, tmp: Path): FreshBuild {
        tmp.resolve("solution.cu").writeText(source)
        tmp.resolve("harness.cu").writeText(harness)
        val memory = config.compileMemoryLimitMb shl 20
        val spec = SandboxSpec(
            command = emptyList(), path = CUDA_PATH, workDir = tmp,
            readOnly = mapOf(config.home to config.home.toString()),
            wallTimeLimit = config.compileTimeLimit.plusSeconds(5), memoryLimitBytes = memory, addressSpaceBytes = memory,
            maxFileBytes = 256L shl 20, tmpBytes = 1L shl 30,
        )
        val inside = { name: String -> sandbox.inside(spec, tmp.resolve(name)) }
        fun run(command: List<String>): ProcResult = sandbox.runToCompletion(spec.copy(command = command), config.compileTimeLimit, props.compile.maxLogBytes)
        val text = { res: ProcResult -> (res.stderr + res.stdout).replace(tmp.toAbsolutePath().toString() + "/", "").replace("${Sandbox.WORK}/", "").trim() }

        val built = cache.withSlot {
            val solution = run(listOf(config.nvcc) + flags() + listOf("-c", inside("solution.cu"), "-o", inside("solution.o")))
            when {
                solution.timedOut ->
                    FreshBuild(BuildStatus.COMPILATION_ERROR, text(solution), "Compilation exceeded ${config.compileTimeLimit.seconds}s", keep = false)
                !solution.ok -> FreshBuild(
                    BuildStatus.COMPILATION_ERROR,
                    text(solution).ifBlank { throw JudgeInfrastructureException("nvcc failed without output: ${solution.describe()}") },
                    "Compilation failed",
                )
                else -> checks(run(listOf(config.nm, "-u", inside("solution.o"))), run(listOf(config.cuobjdump, "-ptx", inside("solution.o"))), text(solution))
                    ?: link(::run, inside, text, text(solution))
            }
        }
        if (built.status == BuildStatus.OK && !Files.isRegularFile(tmp.resolve("solution"), LinkOption.NOFOLLOW_LINKS)) {
            throw JudgeInfrastructureException("nvcc left no binary")
        }
        listOf("solution.cu", "harness.cu", "solution.o", "harness.o").forEach { Files.deleteIfExists(tmp.resolve(it)) }
        return built
    }

    /** Why the compiled solution is not allowed, if it is not. */
    private fun checks(nm: ProcResult, ptx: ProcResult, compileLog: String): FreshBuild? {
        if (!nm.ok || !ptx.ok) throw JudgeInfrastructureException("nm/cuobjdump failed: ${nm.describe()} ${ptx.describe()}")
        val copies = nm.stdout.lines().mapNotNull { Regex("""\bcudaMemcpy\w*""").find(it)?.value }.distinct()
        if (copies.isNotEmpty()) {
            return FreshBuild(
                BuildStatus.POLICY_VIOLATION, compileLog,
                "${copies.joinToString()}: the solution must not copy memory, the arrays are already on the GPU (work on the host costs no simulated cycles)",
            )
        }
        if ("shfl.sync" in ptx.stdout) {
            return FreshBuild(BuildStatus.POLICY_VIOLATION, compileLog, "warp shuffles (__shfl_*_sync) are not allowed: GPGPU-Sim computes them wrongly")
        }
        return null
    }

    private fun link(run: (List<String>) -> ProcResult, inside: (String) -> String, text: (ProcResult) -> String, compileLog: String): FreshBuild {
        val harness = run(listOf(config.nvcc) + flags() + listOf("-c", inside("harness.cu"), "-o", inside("harness.o")))
        if (!harness.ok) throw JudgeInfrastructureException("the harness does not compile: ${harness.describe()}")
        val link = run(
            listOf(config.nvcc) + flags() + listOf("-cudart", "none", "-o", inside("solution"), inside("harness.o"), inside("solution.o"), "-L${config.runtime}", "-lcudart"),
        )
        val log = listOf(compileLog, text(link)).filter { it.isNotBlank() }.joinToString("\n")
        return when {
            link.ok -> FreshBuild(BuildStatus.OK, log)
            "undefined reference to `cuda" in text(link) -> FreshBuild(
                BuildStatus.COMPILATION_ERROR, log,
                "The solution calls a CUDA runtime function GPGPU-Sim does not provide (such as CUDA Graphs or texture objects)",
            )
            else -> FreshBuild(BuildStatus.COMPILATION_ERROR, log, "Linking failed: is solve defined with the required signature?")
        }
    }

    private companion object {
        /** Bump when the cached layout, the flags or the checks change. */
        const val CACHE_FORMAT = "v1"
    }
}
