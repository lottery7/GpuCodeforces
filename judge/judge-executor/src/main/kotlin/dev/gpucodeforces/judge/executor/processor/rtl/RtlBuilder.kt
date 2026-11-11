package dev.gpucodeforces.judge.executor.processor.rtl

import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.RtlSignature
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.BuildCache
import dev.gpucodeforces.judge.executor.processor.BuildStatus
import dev.gpucodeforces.judge.executor.processor.EmulatorBuild
import dev.gpucodeforces.judge.executor.processor.FreshBuild
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.StoredBuild
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.ProcResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The synthesized circuit: [transistors] is its area (Yosys' CMOS estimate, a weight per type of cell), [depth] the most
 * cells on a path between registers, which bounds its clock. A test costs cycles x [perCycle].
 */
data class CircuitSize(val cells: Long, val transistors: Long, val depth: Long) {
    /** A circuit of wires only has depth 0, yet still takes its cycles: it counts as depth 1. */
    val perCycle: Long get() = transistors * maxOf(depth, 1)

    fun metrics(cycles: Long): Map<String, Double> = mapOf(
        "cycles" to cycles.toDouble(), "depth" to depth.toDouble(), "transistors" to transistors.toDouble(), "cells" to cells.toDouble(),
    )
}

/** [simulator] runs one test per process (see [RtlTestbench]); [size] is what the circuit costs per cycle. */
data class RtlSimulator(val simulator: Path, val size: CircuitSize)

/**
 * Turns a submission into a simulator: source checks -> Yosys (`synth -flatten`, area, netlist; the top module is
 * renamed to [RtlTestbench.TOP]) -> port check -> Verilator builds the netlist with the generated testbench. The
 * netlist is what is simulated, so exactly the circuit whose cells were counted runs, and nothing but the circuit:
 * synthesis drops `$display`, rejects `$fopen` and `$system`, and no DPI reaches it. Verilator never reads the
 * contestant's source: it rejects a module named `solve`, a keyword of SystemVerilog.
 *
 * Both tools parse untrusted input and run in the sandbox. Results, compile errors included, are cached by the
 * source, the interface, the testbench and the toolchain, like the objects of `gpu-cuda`.
 */
class RtlBuilder(
    private val props: ExecutorProperties,
    private val toolchain: RtlToolchain,
    private val sandbox: Sandbox,
) {
    private val config = props.rtl
    private val cache = BuildCache(props.paths.cache.resolve("rtl"), props.compileWorkers)

    fun build(source: String, sig: Signature, settings: RtlSettings): EmulatorBuild<RtlSimulator> {
        val violations = RtlChecks.checkSource(source)
        if (violations.isNotEmpty()) return EmulatorBuild.Rejected(Verdict.POLICY_VIOLATION, violations.joinToString("\n"))

        val testbench = RtlTestbench.generate(sig, settings)
        val ports = RtlSignature.ports(sig, settings)
        val key = Hashing.sha256(
            source, sig.function, ports.joinToString("\n") { it.declaration() }, testbench, toolchain.fingerprint,
            config.buildJobs.toString(), CACHE_FORMAT,
        )
        return cache.build(key, ::read) { tmp -> buildFresh(source, sig.function, ports, testbench, tmp) }
    }

    private fun read(build: StoredBuild): EmulatorBuild<RtlSimulator> {
        if (build.status != BuildStatus.OK) return build.rejected()
        val (cells, transistors, depth) = build.dir.resolve("size").readText().trim().split(" ").map(String::toLong)
        return EmulatorBuild.Ready(RtlSimulator(build.dir.resolve("sim"), CircuitSize(cells, transistors, depth)), build.compileLog)
    }

    private fun buildFresh(source: String, module: String, ports: List<RtlSignature.Port>, testbench: String, tmp: Path): FreshBuild {
        tmp.resolve("solution.sv").writeText(source)
        tmp.resolve("testbench.cpp").writeText(testbench)
        val memory = config.buildMemoryLimitMb shl 20
        val spec = SandboxSpec(
            command = emptyList(),
            workDir = tmp,
            readOnly = mapOf(config.home to config.home.toString()),
            path = listOf(config.home.resolve("bin").toString()) + SandboxSpec.DEFAULT_PATH,
            wallTimeLimit = config.buildTimeLimit.plusSeconds(5),
            memoryLimitBytes = memory,
            addressSpaceBytes = memory,
            maxFileBytes = MAX_FILE_BYTES,
            tmpBytes = 1L shl 30,
        )
        val inside = { name: String -> sandbox.inside(spec, tmp.resolve(name)) }
        tmp.resolve("synth.ys").writeText(yosysScript(module, inside("solution.sv"), inside("stat.json"), inside("path.txt"), inside("netlist.v")))

        val outcome = cache.withSlot {
            val yosys = sandbox.runToCompletion(spec.copy(command = listOf(config.yosys.toString(), "-q", "-s", inside("synth.ys"))), config.buildTimeLimit, props.compile.maxLogBytes)
            val synthesis = synthesized(yosys, tmp, module, ports)
            if (synthesis !is Built.Ok) return@withSlot synthesis
            val verilator = sandbox.runToCompletion(
                spec.copy(command = verilatorCommand(inside("netlist.v"), inside("testbench.cpp"))), config.buildTimeLimit, props.compile.maxLogBytes,
            )
            simulator(verilator, synthesis, tmp)
        }
        return when (outcome) {
            is Built.Ok -> {
                val sim = tmp.resolve("sim")
                Files.copy(tmp.resolve("obj/sim"), sim, LinkOption.NOFOLLOW_LINKS)
                Files.setPosixFilePermissions(sim, PosixFilePermissions.fromString("rwxr-xr-x"))
                tmp.resolve("size").writeText(outcome.size.run { "$cells $transistors $depth" })
                FileTrees.delete(tmp.resolve("obj"))
                FreshBuild(BuildStatus.OK, outcome.log)
            }
            is Built.Failed -> FreshBuild(BuildStatus.COMPILATION_ERROR, outcome.log, outcome.message)
            // a timeout may come from the load of the machine: built again next time
            is Built.TimedOut -> FreshBuild(BuildStatus.COMPILATION_ERROR, outcome.log, outcome.message, keep = false)
        }
    }

    private sealed interface Built {
        val log: String

        data class Ok(val size: CircuitSize, override val log: String) : Built
        data class Failed(val message: String, override val log: String) : Built
        data class TimedOut(val message: String, override val log: String) : Built
    }

    /** [Built.Ok] when synthesis gave a netlist with the right ports; otherwise why the build stops there. */
    private fun synthesized(yosys: ProcResult, tmp: Path, module: String, ports: List<RtlSignature.Port>): Built {
        val log = sanitize(yosys.stderr + yosys.stdout, tmp)
        if (yosys.timedOut) return Built.TimedOut("Synthesis exceeded ${config.buildTimeLimit.seconds}s", log)
        val netlist = tmp.resolve("netlist.v")
        if (!yosys.ok || !netlist.exists()) {
            // a real synthesis error always has a message; silence means the tool did not run properly
            if (log.isBlank()) throw JudgeInfrastructureException("yosys failed without output: ${yosys.describe()}")
            return Built.Failed("Synthesis failed", log)
        }
        val stat = RtlChecks.parseStat(tmp.resolve("stat.json").readText())
        val unmapped = RtlChecks.unmappedCells(stat)
        if (unmapped.isNotEmpty()) {
            return Built.Failed("Module $module uses modules that are not defined in the submission: ${unmapped.joinToString()}", log)
        }
        if (stat.cells > config.maxCells) {
            return Built.Failed("The circuit has ${stat.cells} cells after synthesis, more than the judge simulates (${config.maxCells})", log)
        }
        val unweighted = RtlChecks.unweightedCells(stat)
        if (unweighted.isNotEmpty()) {
            return Built.Failed(
                "Module $module has latches or flip-flops with an asynchronous set or reset " +
                    "(${unweighted.entries.joinToString { (type, n) -> "$type x$n" }}): the judge measures the area of synchronous " +
                    "circuits only. Clear the registers on start instead.",
                log,
            )
        }
        val transistors = stat.transistors
            ?: throw JudgeInfrastructureException("Yosys gave no transistor count for ${stat.cellsByType.keys}")
        val depth = RtlChecks.parseLongestPath(tmp.resolve("path.txt").readText())
            ?: return Built.Failed("Module $module has a combinational loop, so its logic depth is undefined: break the loop with a register", log)
        val portErrors = RtlChecks.checkPorts(netlist.readText(), ports, module)
        if (portErrors.isNotEmpty()) return Built.Failed(portErrors.joinToString("\n"), log)
        return Built.Ok(CircuitSize(stat.cells, transistors, depth), log)
    }

    /** [synthesis] again once the simulator is built. */
    private fun simulator(verilator: ProcResult, synthesis: Built.Ok, tmp: Path): Built {
        val synthLog = synthesis.log
        if (verilator.timedOut) return Built.TimedOut("Building the simulator exceeded ${config.buildTimeLimit.seconds}s", synthLog)
        if (!verilator.ok || !Files.isRegularFile(tmp.resolve("obj/sim"), LinkOption.NOFOLLOW_LINKS)) {
            // The netlist has only the judge's names (ports, and the private ones `rename -hide` gave the rest) and
            // passed the size check: a failure here is the judge's, never the contestant's.
            throw JudgeInfrastructureException("Verilator failed on a synthesized netlist: ${verilator.describe()}")
        }
        return synthesis
    }

    private fun verilatorCommand(netlist: String, testbench: String) = listOf(
        config.verilator.toString(), "--cc", "--exe", "--build", "-j", config.buildJobs.toString(),
        // registers are zero before start; the netlist has no X sources of its own
        "--x-assign", "0", "--x-initial", "0",
        "-Wno-fatal", "-Wno-lint", "-Wno-style", "-CFLAGS", "-std=c++17",
        "--top-module", RtlTestbench.TOP, "--Mdir", "obj", "-o", "sim", netlist, testbench,
    )

    companion object {
        /** Bump when the cached layout or the build steps change. */
        private const val CACHE_FORMAT = "v2"

        /** Far above any netlist or simulator; bounds what one submission can add to the cache. */
        private const val MAX_FILE_BYTES = 256L shl 20

        /**
         * `dffunmap` turns flip-flops with an enable or a synchronous reset into plain ones with a multiplexer in front:
         * the same behaviour, and cells the CMOS estimate of `stat` has a transistor count for. `flatten` leaves a
         * `$scopeinfo` cell per instance for debuggers: no hardware, so deleted before the count. `ltp -noff` finds the
         * longest path of cells between flip-flops and ports. `rename -hide` replaces the contestant's names of wires and
         * cells with generated ones: Yosys accepts some keywords of SystemVerilog as names, Verilator does not. Every name
         * in the script comes from the judge: the module is a validated identifier, the paths are the sandbox's. The
         * netlist written at the end is the one measured, and the one simulated.
         */
        fun yosysScript(module: String, source: String, stat: String, path: String, netlist: String): String = """
            read_verilog -sv $source
            synth -top $module -flatten
            dffunmap
            opt_clean
            delete t:${'$'}scopeinfo
            tee -q -o $stat stat -json -tech cmos
            tee -q -o $path ltp -noff
            rename -hide
            rename $module ${RtlTestbench.TOP}
            write_verilog -noattr $netlist
        """.trimIndent() + "\n"

        /** Removes host and sandbox paths from tool messages: `/work/solution.sv:3: ERROR` -> `solution.sv:3: ERROR`. */
        fun sanitize(text: String, dir: Path): String =
            text.replace(dir.toAbsolutePath().toString() + "/", "").replace("${Sandbox.WORK}/", "").trim()
    }
}
