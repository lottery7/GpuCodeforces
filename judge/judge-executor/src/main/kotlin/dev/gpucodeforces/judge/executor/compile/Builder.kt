package dev.gpucodeforces.judge.executor.compile

import dev.gpucodeforces.judge.api.CompileSpec
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.Proc
import dev.gpucodeforces.judge.executor.util.ProcResult
import java.nio.file.Files
import java.nio.file.LinkOption
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
import org.intellij.lang.annotations.Language
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
    /** The harness and the link step always target the GPU itself; only the solution follows the problem's arch. */
    private val gpuGencode = gencode(props.profile.arch)
    private val objectsDir = props.paths.cache.resolve("objects")
    private val harnessDir = props.paths.cache.resolve("harness")
    private val runtimeHeader = props.paths.harness.resolve("include/judge/runtime.h")

    fun build(source: String, signature: Signature, compile: CompileSpec, policy: EffectivePolicy, runDir: Path): BuildOutcome {
        val sourceViolations = SourceChecks.checkSource(source, policy)
        if (sourceViolations.isNotEmpty()) return BuildOutcome.Violation(sourceViolations, compileLog = "")

        val harness = harnessObject(signature)
        val obj = solutionObject(source, compile.arch ?: props.profile.arch, compile.flags ?: props.compile.flags, signature.function)
        if (!obj.ok) return BuildOutcome.CompileError(obj.log, "Compilation failed")

        val violations = buildList {
            addAll(SourceChecks.checkDependencies(obj.deps, policy))
            addAll(SourceChecks.checkSass(obj.sassOpcodes, policy))
            val (undefined, defined) = SourceChecks.parseNm(obj.nm)
            addAll(SourceChecks.checkSymbols(undefined, defined, policy))
        }
        if (violations.isNotEmpty()) return BuildOutcome.Violation(violations, obj.log)

        val binary = runDir.resolve("solution.bin")
        val link = link(obj.dir.resolve("solution.o"), harness, binary)
        if (link.timedOut) throw BuildInfrastructureException("link timed out: ${link.describe()}")
        if (!link.ok) {
            val text = sanitize(link.stderr + link.stdout, emptyList()).replace("$OBJECT_MOUNT/", "").replace("$HARNESS_MOUNT/", "")
            val hint = if ("undefined reference" in text && signature.function in text) {
                "Function ${signature.function} with the required signature is not defined"
            } else {
                "Linking failed"
            }
            return BuildOutcome.CompileError(listOf(obj.log, text).filter { it.isNotBlank() }.joinToString("\n"), hint)
        }
        return BuildOutcome.Ok(binary, obj.log, obj.cached)
    }

    /**
     * Links in the sandbox: the linker parses the contestant's object file, like the compiler parsed the source.
     * The result is copied to [binary] by the judge, into a directory the solution cannot write to.
     */
    private fun link(solution: Path, harness: Path, binary: Path): ProcResult {
        val tmp = Files.createTempDirectory(Files.createDirectories(props.paths.work), "link-")
        try {
            FileTrees.makeWritableByAll(tmp)
            // harness directories are created private to the judge; the sandboxed linker has another uid
            FileTrees.makeReadableByAll(harness.parent)
            val spec = SandboxSpec(
                command = emptyList(),
                workDir = tmp,
                readOnly = mapOf(solution.parent to OBJECT_MOUNT, harness.parent to HARNESS_MOUNT),
                wallTimeLimit = props.compile.timeLimit.plusSeconds(5),
                addressSpaceBytes = props.compile.memoryLimitMb shl 20,
                maxFileBytes = MAX_OBJECT_BYTES,
            )
            val linked = tmp.resolve("solution.bin")
            val command = listOf(tc.nvcc, "-ccbin", tc.hostCompiler) + gpuGencode +
                listOf("-o", sandbox.inside(spec, linked), sandbox.inside(spec, solution), sandbox.inside(spec, harness), "-cudart", "static") +
                HarnessGenerator.WRAPPED_SYMBOLS.flatMap { listOf("-Xlinker", "--wrap=$it") }
            val res = sandbox.runToCompletion(spec.copy(command = command), props.compile.timeLimit, props.compile.maxLogBytes)
            if (res.ok) {
                if (!Files.isRegularFile(linked, LinkOption.NOFOLLOW_LINKS)) throw BuildInfrastructureException("the linker left no binary: ${res.describe()}")
                Files.copy(linked, binary, LinkOption.NOFOLLOW_LINKS)
                Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
            return res
        } finally {
            FileTrees.delete(tmp)
            Sandbox.deleteLog(tmp)
        }
    }

    /** The contestant's object file with what the checks need, as cached on disk. */
    private class CompiledObject(
        val dir: Path, val ok: Boolean, val log: String, val deps: String,
        val sassOpcodes: Set<String>, val nm: String, val cached: Boolean,
    )

    private fun solutionObject(source: String, arch: String, flags: List<String>, function: String): CompiledObject {
        val key = Hashing.sha256(source, toolchain.fingerprint, arch, flags.joinToString(" "), function, OBJECT_FORMAT)
        val dir = objectsDir.resolve(key.take(2)).resolve(key)
        return lockFor(key).withLock {
            load(dir, cached = true) ?: compileFresh(source, arch, flags, function, dir)
        }
    }

    private fun load(dir: Path, cached: Boolean): CompiledObject? {
        val status = dir.resolve("status")
        if (!status.exists()) return null
        val ok = status.readText().trim() == "ok"
        Files.setLastModifiedTime(status, FileTime.fromMillis(System.currentTimeMillis()))
        return CompiledObject(
            dir, ok,
            log = dir.resolve("compile.log").readText(),
            deps = if (ok) dir.resolve("solution.d").readText() else "",
            sassOpcodes = if (ok) dir.resolve("sass.txt").readText().lines().filter { it.isNotBlank() }.toSet() else emptySet(),
            nm = if (ok) dir.resolve("nm.txt").readText() else "",
            cached = cached,
        )
    }

    private fun compileFresh(source: String, arch: String, flags: List<String>, function: String, dir: Path): CompiledObject {
        // Itanium mangling of a function at namespace scope: _Z, the length of its name, the name, then the parameters
        val entryPointPrefix = "_Z${function.length}$function"
        val tmp = Files.createDirectories(objectsDir.resolve("tmp")).let { Files.createTempDirectory(it, "c-") }
        try {
            FileTrees.makeWritableByAll(tmp)
            tmp.resolve("solution.cu").writeText(source)
            val memoryLimit = props.compile.memoryLimitMb shl 20
            val spec = SandboxSpec(
                command = emptyList(),
                workDir = tmp,
                wallTimeLimit = props.compile.timeLimit.plusSeconds(5),
                memoryLimitBytes = memoryLimit,
                // a template or constexpr bomb must not take the machine's memory when cgroups are off
                addressSpaceBytes = memoryLimit,
                maxFileBytes = MAX_OBJECT_BYTES,
                tmpBytes = 1L shl 30,
            )
            val inside = { name: String -> sandbox.inside(spec, tmp.resolve(name)) }
            val cmd = listOf(tc.nvcc, "-ccbin", tc.hostCompiler) + flags + gencode(arch) +
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
            val how = when {
                ok -> "ok"
                res.timedOut -> "timeout"
                else -> "compile error"
            }
            log.info("nvcc {} {} finished in {}ms: {}", arch, flags, ms, how)
            tmp.resolve("compile.log").writeText(logText)
            if (ok) {
                // cuobjdump, nm and objcopy parse an object built from untrusted code: same sandbox as the compiler.
                val obj = inside("solution.o")
                // The disassembly of a large object runs to hundreds of MB and several compile at once: only the
                // set of opcodes is kept, line by line.
                val opcodes = sortedSetOf<String>()
                val sassCommand = listOf(tc.cuobjdump, "-sass", obj)
                val sass = Proc.awaitLines(sandbox.start(spec.copy(command = sassCommand)), sassCommand, Duration.ofSeconds(60)) { line ->
                    SourceChecks.sassOpcode(line)?.let(opcodes::add)
                }
                if (!sass.ok) throw BuildInfrastructureException("cuobjdump failed: ${sass.describe()}")
                tmp.resolve("sass.txt").writeText(opcodes.joinToString("\n"))
                val nm = sandbox.runToCompletion(spec.copy(command = listOf(tc.nm, obj)), Duration.ofSeconds(30), 16 shl 20)
                if (!nm.ok) throw BuildInfrastructureException("nm failed: ${nm.describe()}")
                tmp.resolve("nm.txt").writeText(nm.stdout)
                hideSymbols(spec, obj, nm.stdout, entryPointPrefix)
                if (!tmp.resolve("solution.d").exists()) tmp.resolve("solution.d").writeText(NO_DEPENDENCIES)
            }
            // Timeouts are not cached: they may be caused by machine load.
            if (!res.timedOut) {
                tmp.resolve("status").writeText(if (ok) "ok" else "ce")
                Files.createDirectories(dir.parent)
                if (!dir.exists()) Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
                return load(dir, cached = false)!!
            }
            return CompiledObject(tmp, ok = false, log = logText, deps = "", sassOpcodes = emptySet(), nm = "", cached = false)
        } finally {
            FileTrees.delete(tmp)
            Sandbox.deleteLog(tmp)
        }
    }

    /**
     * Makes every symbol of the object local except the entry point. libc and libstdc++ are linked dynamically, so
     * a solution that defined `clock_gettime`, `write` or `std::chrono::steady_clock::now` would replace them for
     * the harness too, and with them the clock and the status channel. (nm.txt was taken before: the symbol checks
     * see the object as the contestant wrote it.)
     */
    private fun hideSymbols(spec: SandboxSpec, obj: String, nm: String, entryPointPrefix: String) {
        val entryPoints = SourceChecks.parseNm(nm).second.filter { it.startsWith(entryPointPrefix) }
        // without an entry point the link fails with "undefined reference", which is reported to the contestant
        if (entryPoints.isEmpty()) return
        val command = listOf(tc.objcopy) + entryPoints.map { "--keep-global-symbol=$it" } + obj
        val res = sandbox.runToCompletion(spec.copy(command = command), Duration.ofSeconds(30), props.compile.maxLogBytes)
        if (!res.ok) throw BuildInfrastructureException("objcopy failed: ${res.describe()}")
    }

    /** The harness object for a signature: trusted code, compiled outside the sandbox and cached. */
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
                    listOf(tc.nvcc, "-ccbin", tc.hostCompiler, "-std=c++17", "-O2") + gpuGencode +
                        listOf("-I", props.paths.harness.resolve("include").toString(), "-c", "harness_main.cu", "-o", "harness.o"),
                    Duration.ofMinutes(2),
                    cwd = tmp,
                )
                if (!res.ok) throw BuildInfrastructureException("harness compilation failed: ${res.describe()}")
                Files.createDirectories(dir.parent)
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
                log.info("Built harness {} for {}", key.take(12), sig.function)
            } finally {
                FileTrees.delete(tmp)
            }
            obj
        }
    }

    private fun lockFor(key: String) = locks.computeIfAbsent(key) { ReentrantLock() }

    companion object {
        /** Bump when the cached artifact layout or checks change. */
        private const val OBJECT_FORMAT = "v2"
        private const val OBJECT_MOUNT = "/obj"
        private const val HARNESS_MOUNT = "/harness"

        /** Code for exactly this architecture: no PTX is embedded, so nothing is JIT-compiled at run time. */
        private fun gencode(arch: String) = listOf("-gencode", "arch=${arch.replace("sm_", "compute_")},code=$arch")

        /** Far above any real object file; bounds what one submission can add to the object cache. */
        private const val MAX_OBJECT_BYTES = 64L shl 20

        /** Stands in for the dependency file when the compiler wrote none. */
        @Language("Makefile")
        private const val NO_DEPENDENCIES = "solution.o: solution.cu\n"

        /** Removes host paths from compiler messages: `/work/solution.cu(3): error` -> `solution.cu(3): error`. */
        fun sanitize(text: String, dirs: List<Path>): String =
            dirs.fold(text) { t, d -> t.replace(d.toAbsolutePath().toString() + "/", "") }.replace("${Sandbox.WORK}/", "").trim()
    }
}
