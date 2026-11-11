package dev.gpucodeforces.judge.executor.processor.riscv

import dev.gpucodeforces.judge.executor.config.RiscvConfig
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.Proc
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The pinned QEMU, cross compiler and plugin of `riscv-rvv`, as they report themselves. */
class RiscvToolchain(
    val qemuVersion: String,
    val gccVersion: String,
    /** Version outputs and the plugin's hash: part of the build and baseline cache keys. */
    val fingerprint: String,
) {
    val description: String get() = "QEMU $qemuVersion, GCC $gccVersion"

    companion object {
        /** Runs the tools; throws [IllegalStateException] if one is missing or not the expected version. */
        fun probe(config: RiscvConfig): RiscvToolchain {
            check(Files.isRegularFile(config.plugin)) { "${config.plugin} is missing: set judge.executor.riscv.plugin" }
            val qemuOut = output(listOf(config.qemu.toString(), "--version"))
            val gccOut = output(listOf(config.cc, "-dumpfullversion"))
            output(listOf(config.cxx, "-dumpfullversion"))
            val qemu = parseQemuVersion(qemuOut) ?: error("cannot parse the QEMU version from `$qemuOut`")
            val gcc = gccOut.lineSequence().first().trim()
            config.expectedQemuVersion?.let {
                check(qemu.startsWith(it)) { "QEMU $qemu is not the expected $it (judge.executor.riscv.expected-qemu-version)" }
            }
            config.expectedGccVersion?.let {
                check(gcc.startsWith(it)) { "GCC $gcc is not the expected $it (judge.executor.riscv.expected-gcc-version)" }
            }
            val plugin = Hashing.sha256(Files.readAllBytes(config.plugin))
            return RiscvToolchain(qemu, gcc, fingerprint = "$qemuOut\n$gccOut\nplugin $plugin\nvlen ${config.vlen}")
        }

        private fun output(command: List<String>): String {
            check(Files.isExecutable(Path.of(command.first()))) { "${command.first()} is missing: see judge.executor.riscv" }
            val res = Proc.run(command, Duration.ofSeconds(30))
            check(res.ok) { "riscv-rvv toolchain check failed: ${res.describe()}" }
            return res.stdout.trim()
        }

        /** `qemu-riscv64 version 11.1.3` -> `11.1.3`. */
        fun parseQemuVersion(output: String): String? =
            Regex("""version (\S+)""").find(output)?.groupValues?.get(1)
    }
}
