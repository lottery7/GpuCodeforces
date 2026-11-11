package dev.gpucodeforces.judge.executor.processor.riscv

import dev.gpucodeforces.judge.api.CSignature
import dev.gpucodeforces.judge.api.Languages
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.BuildCache
import dev.gpucodeforces.judge.executor.processor.BuildStatus
import dev.gpucodeforces.judge.executor.processor.EmulatorBuild
import dev.gpucodeforces.judge.executor.processor.FreshBuild
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.StoredBuild
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.Hashing
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.writeText

/** A static RISC-V executable at [path]: the harness with the contestant's `solve`. [key] names the build. */
data class RiscvBinary(val path: Path, val key: String)

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
    private val config = props.riscv
    private val cache = BuildCache(props.paths.cache.resolve("riscv"), props.compileWorkers)

    fun build(source: String, language: String, sig: Signature): EmulatorBuild<RiscvBinary> {
        val harness = RiscvHarness.generate(sig)
        val key = Hashing.sha256(source, language, harness, toolchain.fingerprint, FLAGS.joinToString(" "), CACHE_FORMAT)
        return cache.build(key, read = { read(it, key) }) { tmp -> compile(source, language, harness, tmp) }
    }

    /**
     * The harness with a `solve` that does nothing, compiled like a submission in [language]: what the harness costs
     * by itself, subtracted from every run. A failure is the judge's.
     */
    fun baseline(language: String, sig: Signature): RiscvBinary {
        val compiler = if (language == Languages.CPP) Languages.CPP else Languages.C
        return when (val built = build("${CSignature.includes(Processors.RISCV_RVV, compiler == Languages.CPP).joinToString("") { "#include <$it>\n" }}${CSignature.declaration(sig)} {}\n", compiler, sig)) {
            is EmulatorBuild.Ready -> built.artifact
            is EmulatorBuild.Rejected -> throw JudgeInfrastructureException("the empty solve does not compile: ${built.compileLog.take(2000)}")
        }
    }

    // builds cached before the message was stored have none: it is always the same
    private fun read(build: StoredBuild, key: String): EmulatorBuild<RiscvBinary> =
        if (build.status == BuildStatus.OK) EmulatorBuild.Ready(RiscvBinary(build.dir.resolve("solution"), key), build.compileLog)
        else build.rejected(FAILED)

    private fun compile(source: String, language: String, harness: String, tmp: Path): FreshBuild {
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
        val res = cache.withSlot { sandbox.runToCompletion(spec.copy(command = command), config.compileTimeLimit, props.compile.maxLogBytes) }
        val compileLog = (res.stderr + res.stdout).replace(tmp.toAbsolutePath().toString() + "/", "").replace("${Sandbox.WORK}/", "").trim()
        if (res.timedOut) {
            return FreshBuild(BuildStatus.COMPILATION_ERROR, "$compileLog\nCompilation exceeded ${config.compileTimeLimit.seconds}s".trim(), FAILED, keep = false)
        }
        val ok = res.ok && Files.isRegularFile(tmp.resolve("solution"), LinkOption.NOFOLLOW_LINKS)
        // a real compile error always has diagnostics; silence means the compiler did not run properly
        if (!ok && compileLog.isBlank()) throw JudgeInfrastructureException("the RISC-V compiler failed without output: ${res.describe()}")
        listOf(sourceName, harnessName).forEach { Files.deleteIfExists(tmp.resolve(it)) }
        return if (ok) FreshBuild(BuildStatus.OK, compileLog) else FreshBuild(BuildStatus.COMPILATION_ERROR, compileLog, FAILED)
    }

    companion object {
        /** Bump when the cached layout or the flags change. */
        private const val CACHE_FORMAT = "v1"

        /** The processor: RV64GC with the vector extension, a static executable without position independence. */
        val FLAGS = listOf("-O2", "-march=rv64gcv", "-mabi=lp64d", "-static")

        private const val MAX_FILE_BYTES = 256L shl 20
        private const val FAILED = "Compilation failed"
    }
}
