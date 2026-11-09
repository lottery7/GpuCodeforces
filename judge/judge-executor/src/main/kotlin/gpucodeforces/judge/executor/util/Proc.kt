package gpucodeforces.judge.executor.util

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Keeps at most [limit] bytes of a stream and counts the rest. */
class BoundedBuffer(private val limit: Int) {
    private val buf = ByteArrayOutputStream()
    @Volatile var total: Long = 0
        private set

    @Synchronized
    fun append(bytes: ByteArray, len: Int) {
        total += len
        val room = limit - buf.size()
        if (room > 0) buf.write(bytes, 0, minOf(room, len))
    }

    @Synchronized
    fun text(): String {
        val s = buf.toString(Charsets.UTF_8)
        return if (total > limit) "$s\n... [${total - limit} more bytes truncated]" else s
    }

    /** Drains [input] on a daemon thread (keeps the MDC for logging). */
    fun drain(input: InputStream, name: String): Thread {
        val mdc = MDC.getCopyOfContextMap()
        return Thread.ofPlatform().daemon().name(name).start {
            mdc?.let { MDC.setContextMap(it) }
            val chunk = ByteArray(8192)
            try {
                input.use {
                    while (true) {
                        val n = it.read(chunk)
                        if (n < 0) break
                        append(chunk, n)
                    }
                }
            } catch (_: java.io.IOException) {
                // process killed
            }
        }
    }
}

data class ProcResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
) {
    val ok: Boolean get() = !timedOut && exitCode == 0

    /** One-line description for logs and exceptions. */
    fun describe(): String = buildString {
        append("`").append(command.joinToString(" ").take(300)).append("` ")
        append(if (timedOut) "timed out" else "exited with $exitCode").append(" after ${durationMs}ms")
        val err = stderr.trim().ifEmpty { stdout.trim() }
        if (err.isNotEmpty()) append(": ").append(err.takeLast(2000))
    }
}

object Proc {
    private val log = LoggerFactory.getLogger(Proc::class.java)

    /** Runs a trusted helper command (nvcc for the harness, nm, cuobjdump, nvidia-smi, python). */
    fun run(
        command: List<String>,
        timeout: Duration,
        env: Map<String, String> = emptyMap(),
        cwd: Path? = null,
        maxOutputBytes: Int = 1 shl 20,
    ): ProcResult {
        val started = System.nanoTime()
        log.debug("exec {}", command)
        val pb = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        cwd?.let { pb.directory(it.toFile()) }
        pb.environment().putAll(env)
        val process = try {
            pb.start()
        } catch (e: java.io.IOException) {
            return ProcResult(command, 127, "", e.message ?: "cannot start", false, 0)
        }
        val out = BoundedBuffer(maxOutputBytes)
        val err = BoundedBuffer(maxOutputBytes)
        val t1 = out.drain(process.inputStream, "proc-out")
        val t2 = err.drain(process.errorStream, "proc-err")
        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) killTree(process)
        process.waitFor(5, TimeUnit.SECONDS)
        t1.join(2000)
        t2.join(2000)
        val ms = (System.nanoTime() - started) / 1_000_000
        val result = ProcResult(command, if (finished) process.exitValue() else -9, out.text(), err.text(), !finished, ms)
        if (!result.ok) log.debug("command failed: {}", result.describe())
        return result
    }

    /** SIGKILL a process and all its descendants (children first so nothing gets re-parented and survives). */
    fun killTree(process: Process) {
        val handle = process.toHandle()
        handle.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}

object Hashing {
    fun sha256(vararg parts: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) {
            md.update(p.size.toString().toByteArray())
            md.update(0)
            md.update(p)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(vararg parts: String): String = sha256(*parts.map { it.toByteArray() }.toTypedArray())
}
