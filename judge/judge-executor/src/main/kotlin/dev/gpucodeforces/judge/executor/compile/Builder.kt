package dev.gpucodeforces.judge.executor.compile

import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.Proc
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

sealed interface BuildOutcome {
    val compileLog: String

    data class Ok(val binary: Path, override val compileLog: String, val cached: Boolean) : BuildOutcome
    data class CompileError(override val compileLog: String, val message: String) : BuildOutcome
    data class Violation(val violations: List<String>, override val compileLog: String) : BuildOutcome
}

/** Infrastructure failure while building (not the contestant's fault). */
class BuildInfrastructureException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)

/**
 * Compiles a submission into a runnable binary:
 *   source checks -> nvcc (sandboxed, cached by source hash) -> dependency/SASS/symbol checks -> link with the
 *   per-signature harness object (cached).
 * Identical sources are compiled once (resubmits, rejudges, plagiarism), only the link is redone.
 */
class Builder(
    private val props: ExecutorProperties,
    private val toolchain: Toolchain,
    private val sandbox: Sandbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val compileSlots = Semaphore(props.compileWorkers)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val tc = props.toolchain
    private val arch = props.profile.arch
    private val gencode = listOf("-gencode", "arch=${arch.replace("sm_", "compute_")},code=$arch")
    private val objectsDir = props.paths.cache.resolve("objects")
    private val harnessDir = props.paths.cache.resolve("harness")
    private val runtimeHeader = props.paths.harness.resolve("include/judge/runtime.h")

    fun build(source: String, signature: Signature, policy: EffectivePolicy, runDir: Path): BuildOutcome {
        SourceChecks.checkSource(source, policy).takeIf { it.isNotEmpty() }?.let { return BuildOutcome.Violation(it, "") }

        val harness = harnessObject(signature)
        val obj = solutionObject(source)
        if (!obj.ok) return BuildOutcome.CompileError(obj.log, "Compilation failed")

        val violations = buildList {
            addAll(SourceChecks.checkDependencies(obj.deps, policy))
            addAll(SourceChecks.checkSass(obj.sassOpcodes, policy))
            val (undefined, defined) = SourceChecks.parseNm(obj.nm)
            addAll(SourceChecks.checkSymbols(undefined, defined, policy))
        }
        if (violations.isNotEmpty()) return BuildOutcome.Violation(violations, obj.log)

        val binary = runDir.resolve("solution.bin")
        val link = Proc.run(
            listOf(tc.nvcc, "-ccbin", tc.hostCompiler) + gencode +
                listOf("-o", binary.toString(), obj.dir.resolve("solution.o").toString(), harness.toString(), "-cudart", "static") +
                HarnessGenerator.WRAPPED_SYMBOLS.flatMap { listOf("-Xlinker", "--wrap=$it") },
            props.compile.timeLimit,
            maxOutputBytes = props.compile.maxLogBytes,
        )
        if (link.timedOut) throw BuildInfrastructureException("link timed out: ${link.describe()}")
        if (!link.ok) {
            val text = sanitize(link.stderr + link.stdout, listOf(obj.dir, harness.parent, runDir))
            val hint = if ("undefined reference" in text && signature.function in text) {
                "Function ${signature.function} with the required signature is not defined"
            } else {
                "Linking failed"
            }
            return BuildOutcome.CompileError(listOf(obj.log, text).filter { it.isNotBlank() }.joinToString("\n"), hint)
        }
        return BuildOutcome.Ok(binary, obj.log, obj.cached)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Contestant object (sandboxed nvcc, cached)
    // ---------------------------------------------------------------------------------------------------------

    private class CompiledObject(
        val dir: Path, val ok: Boolean, val log: String, val deps: String,
        val sassOpcodes: Set<String>, val nm: String, val cached: Boolean,
    )

    private fun solutionObject(source: String): CompiledObject {
        val key = Hashing.sha256(source, toolchain.fingerprint, props.compile.flags.joinToString(" "), OBJECT_FORMAT)
        val dir = objectsDir.resolve(key.take(2)).resolve(key)
        return lockFor(key).withLock {
            load(dir, cached = true) ?: compileFresh(source, dir)
        }
    }

    private fun load(dir: Path, cached: Boolean): CompiledObject? {
        val status = dir.resolve("status")
        if (!status.exists()) return null
        val ok = status.readText().trim() == "ok"
        Files.setLastModifiedTime(status, FileTime.fromMillis(System.currentTimeMillis()))
        return CompiledObject(
            dir, ok, dir.resolve("compile.log").readText(),
            if (ok) dir.resolve("solution.d").readText() else "",
            if (ok) dir.resolve("sass.txt").readText().lines().filter { it.isNotBlank() }.toSet() else emptySet(),
            if (ok) dir.resolve("nm.txt").readText() else "",
            cached,
        )
    }

    private fun compileFresh(source: String, dir: Path): CompiledObject {
        val tmp = Files.createDirectories(objectsDir.resolve("tmp")).let { Files.createTempDirectory(it, "c-") }
        try {
            makeWorldWritable(tmp)
            tmp.resolve("solution.cu").writeText(source)
            val spec = SandboxSpec(
                command = emptyList(),
                workDir = tmp,
                wallTimeLimit = props.compile.timeLimit.plusSeconds(5),
                memoryLimitBytes = props.compile.memoryLimitMb shl 20,
                maxFileBytes = 512L shl 20,
                tmpBytes = 1L shl 30,
            )
            val inside = { name: String -> sandbox.inside(spec, tmp.resolve(name)) }
            val cmd = listOf(tc.nvcc, "-ccbin", tc.hostCompiler) + props.compile.flags + gencode +
                listOf("-MD", "-MF", inside("solution.d"), "-c", inside("solution.cu"), "-o", inside("solution.o"))
            val started = System.nanoTime()
            compileSlots.acquire()
            val res = try {
                sandbox.runToCompletion(spec.copy(command = cmd), props.compile.timeLimit, props.compile.maxLogBytes)
            } finally {
                compileSlots.release()
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            val logText = sanitize(res.stderr + res.stdout, listOf(tmp)).let {
                if (res.timedOut) "$it\nCompilation exceeded ${props.compile.timeLimit.seconds}s" else it
            }
            val ok = res.ok && tmp.resolve("solution.o").exists()
            if (!ok && !res.timedOut && logText.isBlank()) {
                // a real compile error always has diagnostics; silence means nvcc/nsjail did not run properly
                throw BuildInfrastructureException("compiler failed without output: ${res.describe()}")
            }
            log.info("nvcc finished in {}ms: {}", ms, if (ok) "ok" else if (res.timedOut) "timeout" else "compile error")
            tmp.resolve("compile.log").writeText(logText)
            if (ok) {
                val obj = tmp.resolve("solution.o").toString()
                val sass = Proc.run(listOf(tc.cuobjdump, "-sass", obj), Duration.ofSeconds(60), maxOutputBytes = 256 shl 20)
                if (!sass.ok) throw BuildInfrastructureException("cuobjdump failed: ${sass.describe()}")
                tmp.resolve("sass.txt").writeText(SourceChecks.sassOpcodes(sass.stdout).sorted().joinToString("\n"))
                val nm = Proc.run(listOf(tc.nm, obj), Duration.ofSeconds(30), maxOutputBytes = 16 shl 20)
                if (!nm.ok) throw BuildInfrastructureException("nm failed: ${nm.describe()}")
                tmp.resolve("nm.txt").writeText(nm.stdout)
                if (!tmp.resolve("solution.d").exists()) tmp.resolve("solution.d").writeText("solution.o: solution.cu\n")
            }
            // Timeouts are not cached: they may be caused by machine load.
            if (!res.timedOut) {
                tmp.resolve("status").writeText(if (ok) "ok" else "ce")
                Files.createDirectories(dir.parent)
                if (!dir.exists()) Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
                return load(dir, cached = false)!!
            }
            return CompiledObject(tmp, false, logText, "", emptySet(), "", false)
        } finally {
            if (tmp.exists()) tmp.toFile().deleteRecursively()
            Files.deleteIfExists(tmp.resolveSibling(tmp.fileName.toString() + ".nsjail.log"))
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Harness object per signature (trusted, cached)
    // ---------------------------------------------------------------------------------------------------------

    private fun harnessObject(sig: Signature): Path {
        val source = HarnessGenerator.generate(sig)
        val key = Hashing.sha256(source, Files.readString(runtimeHeader), toolchain.fingerprint, OBJECT_FORMAT)
        val dir = harnessDir.resolve(key)
        val obj = dir.resolve("harness.o")
        return lockFor("h$key").withLock {
            if (obj.exists()) return@withLock obj
            val tmp = Files.createDirectories(harnessDir).let { Files.createTempDirectory(it, "h-") }
            try {
                tmp.resolve("harness_main.cu").writeText(source)
                val res = Proc.run(
                    listOf(tc.nvcc, "-ccbin", tc.hostCompiler, "-std=c++17", "-O2") + gencode +
                        listOf("-I", props.paths.harness.resolve("include").toString(), "-c", "harness_main.cu", "-o", "harness.o"),
                    Duration.ofMinutes(2),
                    cwd = tmp,
                )
                if (!res.ok) throw BuildInfrastructureException("harness compilation failed: ${res.describe()}")
                Files.createDirectories(dir.parent)
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
                log.info("Built harness {} for {}", key.take(12), sig.function)
            } finally {
                if (tmp.exists()) tmp.toFile().deleteRecursively()
            }
            obj
        }
    }

    private fun lockFor(key: String) = locks.computeIfAbsent(key) { ReentrantLock() }

    companion object {
        /** Bump when the cached artifact layout or checks change. */
        private const val OBJECT_FORMAT = "v1"

        fun makeWorldWritable(dir: Path) {
            // the sandboxed process runs as a different uid and writes outputs here
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))
        }

        /** Removes host paths from compiler messages: `/work/solution.cu(3): error` -> `solution.cu(3): error`. */
        fun sanitize(text: String, dirs: List<Path>): String {
            var t = text
            for (d in dirs) t = t.replace(d.toAbsolutePath().toString() + "/", "")
            return t.replace("${Sandbox.WORK}/", "").trim()
        }
    }
}
