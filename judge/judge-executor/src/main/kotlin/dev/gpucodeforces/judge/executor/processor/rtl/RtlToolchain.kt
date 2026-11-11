package dev.gpucodeforces.judge.executor.processor.rtl

import dev.gpucodeforces.judge.executor.config.RtlConfig
import dev.gpucodeforces.judge.executor.util.Proc
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The pinned Yosys and Verilator of `rtl`, as they report themselves. */
class RtlToolchain(
    val yosysVersion: String,
    val verilatorVersion: String,
    /** Full version output of both tools: part of the build cache key. */
    val fingerprint: String,
) {
    val description: String get() = "Yosys $yosysVersion, Verilator $verilatorVersion"

    companion object {
        /** Runs both tools; throws [IllegalStateException] if one is missing or not the expected version. */
        fun probe(config: RtlConfig): RtlToolchain {
            val yosysOut = versionOutput(listOf(config.yosys.toString(), "-V"))
            val verilatorOut = versionOutput(listOf(config.verilator.toString(), "--version"))
            val yosys = parseVersion(yosysOut, "Yosys") ?: error("cannot parse the Yosys version from `$yosysOut`")
            val verilator = parseVersion(verilatorOut, "Verilator") ?: error("cannot parse the Verilator version from `$verilatorOut`")
            config.expectedYosysVersion?.let {
                check(yosys.startsWith(it)) { "Yosys $yosys is not the expected $it (judge.executor.rtl.expected-yosys-version)" }
            }
            config.expectedVerilatorVersion?.let {
                check(verilator.startsWith(it)) { "Verilator $verilator is not the expected $it (judge.executor.rtl.expected-verilator-version)" }
            }
            return RtlToolchain(yosys, verilator, fingerprint = "$yosysOut\n$verilatorOut")
        }

        private fun versionOutput(command: List<String>): String {
            check(Files.isExecutable(Path.of(command.first()))) { "${command.first()} is missing: set judge.executor.rtl.home to the OSS CAD Suite" }
            val res = Proc.run(command, Duration.ofSeconds(30))
            check(res.ok) { "rtl toolchain check failed: ${res.describe()}" }
            return res.stdout.trim()
        }

        /** `Yosys 0.70+286 (git sha1 …)` -> `0.70+286`; `Verilator 5.053 devel rev …` -> `5.053`. */
        fun parseVersion(output: String, tool: String): String? =
            Regex("""^$tool (\S+)""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
    }
}
