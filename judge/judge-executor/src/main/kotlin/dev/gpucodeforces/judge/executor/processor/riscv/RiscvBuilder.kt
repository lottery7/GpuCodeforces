package dev.gpucodeforces.judge.executor.processor.riscv

import dev.gpucodeforces.judge.api.CSignature
import dev.gpucodeforces.judge.api.Languages
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Hashing
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

sealed interface RiscvBuildOutcome {
    val compileLog: String

    /** [binary] is a static RISC-V executable: the harness with the contestant's `solve`. */
    data class Ok(val binary: Path, val key: String, override val compileLog: String, val cached: Boolean) : RiscvBuildOutcome
    data class CompileError(override val compileLog: String, val message: String) : RiscvBuildOutcome
}

/**
 * Compiles a submission together with [RiscvHarness] into one static executable, in the sandbox: the compiler parses
 * untrusted code. Flags are the judge's (`-O2`, RV64GCV), the same for every problem. Results, compile errors
 * included, are cached by source, language, harness and toolchain.
 */
class RiscvBuilder(
    private val props: ExecutorProperties,
    private val toolchain: RiscvToolchain,
    private val sandbox: Sandbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.riscv
    private val slots = Semaphore(props.compileWorkers)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val cacheDir = props.paths.cache.resolve("riscv")

    fun build(source: String, language: String, sig: Signature): RiscvBuildOutcome {
        val harness = RiscvHarness.generate(sig)
        val key = Hashing.sha256(source, language, harness, toolchain.fingerprint, FLAGS.joinToString(" "), CACHE_FORMAT)
        val dir = cacheDir.resolve(key.take(2)).resolve(key)
        return locks.computeIfAbsent(key) { ReentrantLock() }.withLock {
            load(dir, key, cached = true) ?: compile(source, language, harness, dir, key)
        }
    }

    /**
     * The harness with a `solve` that does nothing, compiled like a submission in [language]: what the harness costs
     * by itself, subtracted from every run. A failure is the judge's.
     */
    fun baseline(language: String, sig: Signature): RiscvBuildOutcome.Ok {
        val compiler = if (language == Languages.CPP) Languages.CPP else Languages.C
        return when (val built = build("${CSignature.includes(compiler == Languages.CPP).joinToString("") { "#include <$it>\n" }}${CSignature.declaration(sig)} {}\n", compiler, sig)) {
            is RiscvBuildOutcome.Ok -> built
            is RiscvBuildOutcome.CompileError -> throw JudgeInfrastructureException("the empty solve does not compile: ${built.compileLog.take(2000)}")
        }
    }

    private fun load(dir: Path, key: String, cached: Boolean): RiscvBuildOutcome? {
        val status = dir.resolve("status")
        if (!status.exists()) return null
        Files.setLastModifiedTime(status, FileTime.fromMillis(System.currentTimeMillis()))
        val compileLog = dir.resolve("compile.log").readText()
        return when (status.readText().trim()) {
            "ok" -> RiscvBuildOutcome.Ok(dir.resolve("solution"), key, compileLog, cached)
            else -> RiscvBuildOutcome.CompileError(compileLog, "Compilation failed")
        }
    }

    private fun compile(source: String, language: String, harness: String, dir: Path, key: String): RiscvBuildOutcome {
        val tmp = Files.createDirectories(cacheDir.resolve("tmp")).let { Files.createTempDirectory(it, "b-") }
        try {
            FileTrees.makeWritableByAll(tmp)
            val (compiler, sourceName, harnessName, std) = when (language) {
                Languages.C -> listOf(config.cc, "solution.c", "harness.c", "-std=gnu17")
                Languages.CPP -> listOf(config.cxx, "solution.cpp", "harness.cpp", "-std=gnu++17")
                Languages.ASM -> listOf(config.cc, "solution.S", "harness.c", "-std=gnu17")
                else -> throw IllegalArgumentException("riscv-rvv has no language $language")
            }
            tmp.resolve(sourceName).writeText(source)
            tmp.resolve(harnessName).writeText(harness)
            val memory = config.compileMemoryLimitMb shl 20
            val spec = SandboxSpec(
                command = emptyList(),
                workDir = tmp,
                wallTimeLimit = config.compileTimeLimit.plusSeconds(5),
                memoryLimitBytes = memory,
                addressSpaceBytes = memory,
                maxFileBytes = MAX_FILE_BYTES,
                tmpBytes = 1L shl 30,
            )
            val inside = { name: String -> sandbox.inside(spec, tmp.resolve(name)) }
            val command = listOf(compiler) + FLAGS + std +
                listOf("-o", inside("solution"), inside(harnessName), inside(sourceName), "-lm")
            val started = System.nanoTime()
            slots.acquire()
            val res = try {
                sandbox.runToCompletion(spec.copy(command = command), config.compileTimeLimit, props.compile.maxLogBytes)
            } finally {
                slots.release()
            }
            val compileLog = (res.stderr + res.stdout).replace(tmp.toAbsolutePath().toString() + "/", "").replace("${Sandbox.WORK}/", "").trim()
            log.info("riscv-rvv {} build finished in {}ms: {}", language, (System.nanoTime() - started) / 1_000_000, if (res.ok) "ok" else "failed")
            if (res.timedOut) return RiscvBuildOutcome.CompileError("$compileLog\nCompilation exceeded ${config.compileTimeLimit.seconds}s".trim(), "Compilation failed")
            val ok = res.ok && Files.isRegularFile(tmp.resolve("solution"), LinkOption.NOFOLLOW_LINKS)
            // a real compile error always has diagnostics; silence means the compiler did not run properly
            if (!ok && compileLog.isBlank()) throw JudgeInfrastructureException("the RISC-V compiler failed without output: ${res.describe()}")
            tmp.resolve("compile.log").writeText(compileLog)
            tmp.resolve("status").writeText(if (ok) "ok" else "ce")
            listOf(sourceName, harnessName).forEach { Files.deleteIfExists(tmp.resolve(it)) }
            Files.createDirectories(dir.parent)
            if (!dir.exists()) Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
            return load(dir, key, cached = false)!!
        } finally {
            FileTrees.delete(tmp)
            Sandbox.deleteLog(tmp)
        }
    }

    companion object {
        /** Bump when the cached layout or the flags change. */
        private const val CACHE_FORMAT = "v1"

        /** The processor: RV64GC with the vector extension, a static executable without position independence. */
        val FLAGS = listOf("-O2", "-march=rv64gcv", "-mabi=lp64d", "-static")

        private const val MAX_FILE_BYTES = 256L shl 20
    }
}
