package dev.gpucodeforces.judge.executor.processor.coralnpu

import dev.gpucodeforces.judge.executor.config.CoralNpuConfig
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.Proc
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The pinned Renode, cross compiler and Monitor scripts of `coral-npu`, as they report themselves. */
class CoralNpuToolchain(
    val renodeVersion: String,
    val gccVersion: String,
    /** Version outputs and the hashes of the judge's scripts: part of the build cache key. */
    val fingerprint: String,
) {
    val description: String get() = "Renode $renodeVersion, GCC $gccVersion"

    companion object {
        /** The Monitor command of [CoralNpuConfig.platform], next to it. */
        const val RUN_SCRIPT = "judge_run.py"

        /** Runs the tools; throws [IllegalStateException] if one is missing or not the expected version. */
        fun probe(config: CoralNpuConfig): CoralNpuToolchain {
            val scripts = listOf(config.platform, config.platform.resolveSibling(RUN_SCRIPT))
            scripts.forEach { check(Files.isRegularFile(it)) { "$it is missing: set judge.executor.coral-npu.platform" } }
            val renodeOut = output(listOf(config.renode.toString(), "--version"))
            val gccOut = output(listOf(config.cc, "-dumpfullversion"))
            output(listOf(config.cxx, "-dumpfullversion"))
            val renode = parseRenodeVersion(renodeOut) ?: error("cannot parse the Renode version from `$renodeOut`")
            val gcc = gccOut.lineSequence().first().trim()
            config.expectedRenodeVersion?.let {
                check(renode.startsWith(it)) { "Renode $renode is not the expected $it (judge.executor.coral-npu.expected-renode-version)" }
            }
            config.expectedGccVersion?.let {
                check(gcc.startsWith(it)) { "GCC $gcc is not the expected $it (judge.executor.coral-npu.expected-gcc-version)" }
            }
            val hashes = scripts.joinToString(" ") { Hashing.sha256(Files.readAllBytes(it)) }
            return CoralNpuToolchain(renode, gcc, fingerprint = "$renodeOut\n$gccOut\nscripts $hashes")
        }

        private fun output(command: List<String>): String {
            check(Files.isExecutable(Path.of(command.first()))) { "${command.first()} is missing: see judge.executor.coral-npu" }
            val res = Proc.run(command, Duration.ofSeconds(60))
            check(res.ok) { "coral-npu toolchain check failed: ${res.describe()}" }
            return res.stdout.trim()
        }

        /** `Renode v1.17.0` -> `1.17.0`. */
        fun parseRenodeVersion(output: String): String? =
            Regex("""Renode v(\S+)""").find(output)?.groupValues?.get(1)
    }
}
