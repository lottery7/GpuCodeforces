package dev.gpucodeforces.judge.executor.processor.cudasim

import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.gpucuda.CUDA_PATH
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.ProcResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

sealed interface CudaSimBuildOutcome {
    val compileLog: String

    data class Ok(val binary: Path, override val compileLog: String) : CudaSimBuildOutcome
    data class CompileError(override val compileLog: String, val message: String) : CudaSimBuildOutcome
    data class Violation(val message: String, override val compileLog: String) : CudaSimBuildOutcome
}

/**
 * Builds a submission for GPGPU-Sim, in the sandbox: the solution with PTX for the modelled GPU in the binary (the
 * simulator runs PTX), the harness of [CudaSimHarness], and GPGPU-Sim's own `libcudart` instead of NVIDIA's, so a
 * runtime function the simulator lacks (CUDA Graphs, texture objects) is a link error. Two checks of the solution:
 * no `cudaMemcpy*` (work on the host costs no simulated cycles; advisory, the harness copies the data) and no warp
 * shuffles (`shfl.sync`), which GPGPU-Sim computes wrongly without an error. Cached like the other processors.
 */
class CudaSimBuilder(private val props: ExecutorProperties, private val fingerprint: String, private val sandbox: Sandbox) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.cudaSim
    private val slots = Semaphore(props.compileWorkers)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val cacheDir = props.paths.cache.resolve("cuda-sim")

    fun build(source: String, sig: Signature): CudaSimBuildOutcome {
        val harness = CudaSimHarness.generate(sig)
        val key = Hashing.sha256(source, harness, fingerprint, flags().joinToString(" "), CACHE_FORMAT)
        val dir = cacheDir.resolve(key.take(2)).resolve(key)
        return locks.computeIfAbsent(key) { ReentrantLock() }.withLock { load(dir) ?: buildFresh(source, harness, dir) }
    }

    // compute_70 is the simulated QV100's PTX; nvcc's warning that such old targets are deprecated is noise for a participant
    private fun flags() = listOf("-O3", "-std=c++17", "-Wno-deprecated-gpu-targets", "-gencode", "arch=${config.ptxArch},code=${config.ptxArch}")

    private fun load(dir: Path): CudaSimBuildOutcome? {
        val status = dir.resolve("status")
        if (!status.exists()) return null
        val compileLog = dir.resolve("compile.log").readText()
        return when (status.readText().trim()) {
            "ok" -> CudaSimBuildOutcome.Ok(dir.resolve("solution"), compileLog)
            "violation" -> CudaSimBuildOutcome.Violation(dir.resolve("message").readText(), compileLog)
            else -> CudaSimBuildOutcome.CompileError(compileLog, dir.resolve("message").readText())
        }
    }

    private fun buildFresh(source: String, harness: String, dir: Path): CudaSimBuildOutcome {
        val tmp = Files.createDirectories(cacheDir.resolve("tmp")).let { Files.createTempDirectory(it, "b-") }
        try {
            FileTrees.makeWritableByAll(tmp)
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

            slots.acquire()
            val outcome = try {
                val solution = run(listOf(config.nvcc) + flags() + listOf("-c", inside("solution.cu"), "-o", inside("solution.o")))
                when {
                    solution.timedOut -> Outcome("timeout", "Compilation exceeded ${config.compileTimeLimit.seconds}s", text(solution))
                    !solution.ok -> Outcome("ce", "Compilation failed", text(solution).ifBlank { throw JudgeInfrastructureException("nvcc failed without output: ${solution.describe()}") })
                    else -> checks(run(listOf(config.nm, "-u", inside("solution.o"))), run(listOf(config.cuobjdump, "-ptx", inside("solution.o"))), text(solution))
                        ?: link(::run, inside, text, text(solution))
                }
            } finally {
                slots.release()
            }
            log.info("cuda-sim build: {}", outcome.status)
            if (outcome.status == "ok" && !Files.isRegularFile(tmp.resolve("solution"), LinkOption.NOFOLLOW_LINKS)) {
                throw JudgeInfrastructureException("nvcc left no binary")
            }
            if (outcome.status == "timeout") return CudaSimBuildOutcome.CompileError(outcome.log, outcome.message)
            tmp.resolve("compile.log").writeText(outcome.log)
            tmp.resolve("message").writeText(outcome.message)
            tmp.resolve("status").writeText(outcome.status)
            listOf("solution.cu", "harness.cu", "solution.o", "harness.o").forEach { Files.deleteIfExists(tmp.resolve(it)) }
            Files.createDirectories(dir.parent)
            if (!dir.exists()) Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
            return load(dir)!!
        } finally {
            FileTrees.delete(tmp)
            Sandbox.deleteLog(tmp)
        }
    }

    private class Outcome(val status: String, val message: String, val log: String)

    private fun checks(nm: ProcResult, ptx: ProcResult, compileLog: String): Outcome? {
        if (!nm.ok || !ptx.ok) throw JudgeInfrastructureException("nm/cuobjdump failed: ${nm.describe()} ${ptx.describe()}")
        val copies = nm.stdout.lines().mapNotNull { Regex("""\bcudaMemcpy\w*""").find(it)?.value }.distinct()
        if (copies.isNotEmpty()) {
            return Outcome("violation", "${copies.joinToString()}: the solution must not copy memory, the arrays are already on the GPU (work on the host costs no simulated cycles)", compileLog)
        }
        if ("shfl.sync" in ptx.stdout) {
            return Outcome("violation", "warp shuffles (__shfl_*_sync) are not allowed: GPGPU-Sim computes them wrongly", compileLog)
        }
        return null
    }

    private fun link(run: (List<String>) -> ProcResult, inside: (String) -> String, text: (ProcResult) -> String, compileLog: String): Outcome {
        val harness = run(listOf(config.nvcc) + flags() + listOf("-c", inside("harness.cu"), "-o", inside("harness.o")))
        if (!harness.ok) throw JudgeInfrastructureException("the harness does not compile: ${harness.describe()}")
        val link = run(
            listOf(config.nvcc) + flags() + listOf("-cudart", "none", "-o", inside("solution"), inside("harness.o"), inside("solution.o"), "-L${config.runtime}", "-lcudart"),
        )
        val log = listOf(compileLog, text(link)).filter { it.isNotBlank() }.joinToString("\n")
        return when {
            link.ok -> Outcome("ok", "", log)
            "undefined reference to `cuda" in text(link) ->
                Outcome("ce", "The solution calls a CUDA runtime function GPGPU-Sim does not provide (such as CUDA Graphs or texture objects)", log)
            else -> Outcome("ce", "Linking failed: is solve defined with the required signature?", log)
        }
    }

    private companion object {
        /** Bump when the cached layout, the flags or the checks change. */
        const val CACHE_FORMAT = "v1"
    }
}
