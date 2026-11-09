package dev.gpucodeforces.judge.executor.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.slf4j.MDC

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

    /** Drains [input] on a daemon thread. */
    fun drain(input: InputStream, name: String): Thread = daemonThread(name) {
        val chunk = ByteArray(8192)
        try {
            input.use {
                while (true) {
                    val n = it.read(chunk)
                    if (n < 0) break
                    append(chunk, n)
                }
            }
        } catch (_: IOException) {
            // the stream is closed when the process is killed; what was read so far is kept
        }
    }
}

/** Starts a daemon thread that logs with the caller's MDC (submission id, attempt, GPU). */
fun daemonThread(name: String, body: () -> Unit): Thread {
    val mdc = MDC.getCopyOfContextMap()
    return Thread.ofPlatform().daemon().name(name).start {
        mdc?.let { MDC.setContextMap(it) }
        body()
    }
}

/**
 * Splits [input] into lines without buffering more than [maxLength] bytes of any of them: longer lines are dropped
 * whole. For output of untrusted processes, where a line without an end must not exhaust the heap.
 */
fun readBoundedLines(input: InputStream, maxLength: Int, onLine: (String) -> Unit) {
    val chunk = ByteArray(8192)
    val line = ByteArrayOutputStream(256)
    var tooLong = false
    fun endOfLine() {
        if (!tooLong) onLine(line.toString(Charsets.UTF_8))
        line.reset()
        tooLong = false
    }
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        for (i in 0 until n) {
            when {
                chunk[i] == '\n'.code.toByte() -> endOfLine()
                line.size() < maxLength -> line.write(chunk[i].toInt())
                else -> tooLong = true
            }
        }
    }
    if (line.size() > 0) endOfLine()
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
        log.debug("exec {}", command)
        val pb = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        cwd?.let { pb.directory(it.toFile()) }
        pb.environment().putAll(env)
        val process = try {
            pb.start()
        } catch (e: IOException) {
            return ProcResult(command, exitCode = 127, stdout = "", stderr = e.message ?: "cannot start", timedOut = false, durationMs = 0)
        }
        val result = await(process, command, timeout, maxOutputBytes)
        if (!result.ok) log.debug("command failed: {}", result.describe())
        return result
    }

    /** Waits for a started [process], capturing bounded stdout/stderr; kills its whole tree on [timeout]. */
    fun await(process: Process, command: List<String>, timeout: Duration, maxOutputBytes: Int): ProcResult {
        val started = System.nanoTime()
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
        return ProcResult(command, if (finished) process.exitValue() else -9, stdout = out.text(), stderr = err.text(), timedOut = !finished, durationMs = ms)
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
        return HexFormat.of().formatHex(md.digest())
    }

    fun sha256(vararg parts: String): String = sha256(*parts.map { it.toByteArray() }.toTypedArray())
}
