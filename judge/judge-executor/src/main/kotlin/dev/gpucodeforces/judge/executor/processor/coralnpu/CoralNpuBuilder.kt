package dev.gpucodeforces.judge.executor.processor.coralnpu

import dev.gpucodeforces.judge.api.Languages
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

/** A bare-metal executable for the Coral NPU core at [path]; its own data ends at [dataEnd] in the data memory. */
data class CoralNpuProgram(val path: Path, val dataEnd: Long)

/**
 * Compiles a submission together with [CoralNpuHarness] into one bare-metal executable, in the sandbox: the compiler
 * parses untrusted code. Flags are the judge's, the same for every problem. Results, compile errors included, are
 * cached by source, language, harness and toolchain.
 */
class CoralNpuBuilder(
    private val props: ExecutorProperties,
    private val toolchain: CoralNpuToolchain,
    private val sandbox: Sandbox,
) {
    private val config = props.coralNpu
    private val cache = BuildCache(props.paths.cache.resolve("coral-npu"), props.compileWorkers)

    fun build(source: String, language: String, sig: Signature): EmulatorBuild<CoralNpuProgram> {
        val harness = CoralNpuHarness.generate(sig)
        val key = Hashing.sha256(
            source, language, harness, CoralNpuHarness.START, CoralNpuHarness.LINKER_SCRIPT, toolchain.fingerprint,
            (FLAGS + CXX_FLAGS).joinToString(" "), CACHE_FORMAT,
        )
        return cache.build(key, read = ::read) { tmp -> compile(source, language, harness, tmp) }
    }

    private fun read(build: StoredBuild): EmulatorBuild<CoralNpuProgram> {
        if (build.status != BuildStatus.OK) return build.rejected()
        val program = build.dir.resolve(PROGRAM)
        return EmulatorBuild.Ready(CoralNpuProgram(program, dataEnd(Elf32.loadSegments(program))), build.compileLog)
    }

    private fun compile(source: String, language: String, harness: String, tmp: Path): FreshBuild {
        val (compiler, sourceName, harnessName, own) = when (language) {
            Languages.C -> Compilation(config.cc, "solution.c", "harness.c", listOf("-std=gnu17"))
            Languages.CPP -> Compilation(config.cxx, "solution.cpp", "harness.cpp", CXX_FLAGS)
            else -> throw IllegalArgumentException("coral-npu has no language $language")
        }
        val files = mapOf(sourceName to source, harnessName to harness, START to CoralNpuHarness.START, LINKER_SCRIPT to CoralNpuHarness.LINKER_SCRIPT)
        files.forEach { (name, text) -> tmp.resolve(name).writeText(text) }
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
        val command = listOf(compiler) + FLAGS + own +
            listOf("-T", inside(LINKER_SCRIPT), "-o", inside(PROGRAM), inside(START), inside(harnessName), inside(sourceName))
        val res = cache.withSlot { sandbox.runToCompletion(spec.copy(command = command), config.compileTimeLimit, props.compile.maxLogBytes) }
        val compileLog = (res.stderr + res.stdout).replace(tmp.toAbsolutePath().toString() + "/", "").replace("${Sandbox.WORK}/", "").trim()
        if (res.timedOut) {
            return FreshBuild(BuildStatus.COMPILATION_ERROR, "$compileLog\nCompilation exceeded ${config.compileTimeLimit.seconds}s".trim(), FAILED, keep = false)
        }
        val program = tmp.resolve(PROGRAM)
        val ok = res.ok && Files.isRegularFile(program, LinkOption.NOFOLLOW_LINKS)
        // a real compile error always has diagnostics; silence means the compiler did not run properly
        if (!ok && compileLog.isBlank()) throw JudgeInfrastructureException("the Coral NPU compiler failed without output: ${res.describe()}")
        files.keys.forEach { Files.deleteIfExists(tmp.resolve(it)) }
        if (!ok) return FreshBuild(BuildStatus.COMPILATION_ERROR, compileLog, FAILED)
        return misplaced(program)?.let { FreshBuild(BuildStatus.COMPILATION_ERROR, "$compileLog\n$it".trim(), FAILED) }
            ?: FreshBuild(BuildStatus.OK, compileLog)
    }

    private data class Compilation(val compiler: String, val source: String, val harness: String, val flags: List<String>)

    companion object {
        /** Bump when the cached layout changes. */
        private const val CACHE_FORMAT = "v1"

        /**
         * The processor is RvvCoreMini as Renode models it: RV32IMF with Zbb and the 32-bit vector extension, no
         * compressed instructions. picolibc is the C library; nothing of it runs before `solve`. Math functions
         * do not set `errno` in picolibc, and without `-fno-math-errno` the compiler would call `sqrtf` instead of
         * using the instruction.
         */
        val FLAGS = listOf(
            "-O2", "-march=rv32imf_zve32f_zicsr_zifencei_zbb", "-mabi=ilp32f", "--specs=picolibc.specs", "-nostartfiles", "-static",
            "-fno-math-errno", "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
        )

        /** There is no C++ standard library for this target, hence no exceptions and no RTTI either. */
        val CXX_FLAGS = listOf("-std=gnu++17", "-nostdlib++", "-fno-exceptions", "-fno-rtti", "-fno-use-cxa-atexit", "-fno-threadsafe-statics")

        private const val PROGRAM = "solution.elf"
        private const val START = "start.S"
        private const val LINKER_SCRIPT = "memory.ld"
        private const val MAX_FILE_BYTES = 64L shl 20
        private const val FAILED = "Compilation failed"

        /** Where the program's own data ends: the judge's argument words are always there, at the start. */
        fun dataEnd(segments: List<ElfSegment>): Long =
            segments.filter { CoralNpuMemory.inDataMemory(it.address, it.bytes) }.maxOfOrNull { it.address + it.bytes } ?: CoralNpuMemory.DATA_START

        /**
         * What is wrong with where [program] wants to be loaded, or null. The linker script keeps ordinary sections
         * inside the two memories (and fails the link when they overflow); this is for anything a submission places
         * by itself.
         */
        fun misplaced(program: Path): String? {
            val segments = try {
                Elf32.loadSegments(program)
            } catch (e: IllegalArgumentException) {
                return "The linker's output is not an executable for the core: ${e.message}"
            }
            return segments.firstOrNull { !CoralNpuMemory.inInstructionMemory(it.address, it.bytes) && !CoralNpuMemory.inDataMemory(it.address, it.bytes) }
                ?.let { "${it.bytes} bytes at 0x${it.address.toString(16)} are outside the instruction memory (8 KB at 0x0) and the data memory (32 KB at 0x10000)" }
        }
    }
}
