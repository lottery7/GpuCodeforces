package gpucodeforces.judge.executor.sandbox

import gpucodeforces.judge.executor.util.BoundedBuffer
import gpucodeforces.judge.executor.util.ProcResult
import gpucodeforces.judge.executor.util.Proc
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** GPU made visible inside the sandbox. */
data class GpuAccess(val cudaIndex: Int, val deviceNode: String?)

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
    /** RLIMIT_FSIZE. */
    val maxFileBytes: Long = 1L shl 30,
    /** Size of the private /tmp. */
    val tmpBytes: Long = 256L shl 20,
)

interface Sandbox {
    val name: String

    /** Starts the command. stdout/stderr are pipes, stdin is /dev/null. */
    fun start(spec: SandboxSpec): Process

    /** Path inside the sandbox for a host path located in [SandboxSpec.workDir] or a [SandboxSpec.readOnly] directory. */
    fun inside(spec: SandboxSpec, host: Path): String

    /** Runs to completion, capturing bounded stdout/stderr (used for compilation). */
    fun runToCompletion(spec: SandboxSpec, timeout: Duration, maxOutputBytes: Int): ProcResult {
        val started = System.nanoTime()
        val process = start(spec)
        val out = BoundedBuffer(maxOutputBytes)
        val err = BoundedBuffer(maxOutputBytes)
        val t1 = out.drain(process.inputStream, "sbx-out")
        val t2 = err.drain(process.errorStream, "sbx-err")
        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) Proc.killTree(process)
        process.waitFor(5, TimeUnit.SECONDS)
        t1.join(2000)
        t2.join(2000)
        val ms = (System.nanoTime() - started) / 1_000_000
        return ProcResult(spec.command, if (finished) process.exitValue() else -9, out.text(), err.text(), !finished, ms)
    }

    companion object {
        const val WORK = "/work"
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
