package dev.gpucodeforces.judge.executor.sandbox

import dev.gpucodeforces.judge.executor.util.Proc
import dev.gpucodeforces.judge.executor.util.ProcResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/** GPU made visible inside the sandbox. */
data class GpuAccess(val cudaIndex: Int, val deviceNode: String?) {
    /** Makes CUDA see exactly this GPU. */
    fun cudaEnv(): Map<String, String> = mapOf("CUDA_DEVICE_ORDER" to "PCI_BUS_ID", "CUDA_VISIBLE_DEVICES" to cudaIndex.toString())
}

data class SandboxSpec(
    /** Command with paths as seen inside the sandbox (use [Sandbox.inside]). */
    val command: List<String>,
    /** Read-write directory, mounted at [Sandbox.WORK] in nsjail. */
    val workDir: Path,
    /** Read-only directories: host path -> path inside the sandbox. */
    val readOnly: Map<Path, String> = emptyMap(),
    val gpu: GpuAccess? = null,
    val env: Map<String, String> = emptyMap(),
    /** Hard backstop; the caller normally enforces tighter limits itself. */
    val wallTimeLimit: Duration,
    /** Enforced by cgroups when enabled; the caller also polls RSS. */
    val memoryLimitBytes: Long? = null,
    /**
     * RLIMIT_AS. Works without cgroups, but only for processes that do not reserve address space they never
     * touch: fine for compilers, impossible for anything that initialises CUDA.
     */
    val addressSpaceBytes: Long? = null,
    /** RLIMIT_FSIZE. */
    val maxFileBytes: Long = 1L shl 30,
    /** Size of the private /tmp. */
    val tmpBytes: Long = 256L shl 20,
    /** `PATH` inside the sandbox; a processor whose tools live elsewhere (CUDA) adds their directories. */
    val path: List<String> = DEFAULT_PATH,
) {
    companion object {
        val DEFAULT_PATH = listOf("/usr/local/bin", "/usr/bin", "/bin")
    }
}

interface Sandbox {
    val name: String

    /** Starts the command. stdout/stderr are pipes, stdin is /dev/null. */
    fun start(spec: SandboxSpec): Process

    /** Path inside the sandbox for a host path located in [SandboxSpec.workDir] or a [SandboxSpec.readOnly] directory. */
    fun inside(spec: SandboxSpec, host: Path): String

    /** Runs to completion, capturing bounded stdout/stderr (used for compilation). */
    fun runToCompletion(spec: SandboxSpec, timeout: Duration, maxOutputBytes: Int): ProcResult =
        Proc.await(start(spec), spec.command, timeout, maxOutputBytes)

    companion object {
        const val WORK = "/work"

        /** Where the sandbox writes its own diagnostics for [workDir]: next to it, out of the sandboxed code's reach. */
        fun logFile(workDir: Path): Path = workDir.resolveSibling(workDir.name + ".nsjail.log")

        /** Tail of [logFile], or null if the sandbox wrote nothing. */
        fun logTail(workDir: Path): String? =
            logFile(workDir).takeIf { it.exists() }?.readText()?.takeLast(2000)?.ifBlank { null }

        fun deleteLog(workDir: Path) {
            Files.deleteIfExists(logFile(workDir))
        }
    }
}

/** Maps a host path through a set of mounts (host dir -> inside dir). */
internal fun mapThroughMounts(host: Path, mounts: Map<Path, String>): String {
    val abs = host.toAbsolutePath().normalize()
    for ((dir, inside) in mounts.entries.sortedByDescending { it.key.nameCount }) {
        val d = dir.toAbsolutePath().normalize()
        if (abs.startsWith(d)) {
            val rel = d.relativize(abs).toString()
            return if (rel.isEmpty()) inside else "$inside/$rel"
        }
    }
    throw IllegalArgumentException("$host is not inside any sandbox mount")
}
