package dev.gpucodeforces.judge.executor.processor.rtl

import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.RtlSignature
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.ProcResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

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

sealed interface RtlBuildOutcome {
    val compileLog: String

    /** [simulator] runs one test per process (see [RtlTestbench]); [size] is what the circuit costs per cycle. */
    data class Ok(val simulator: Path, val size: CircuitSize, override val compileLog: String, val cached: Boolean) : RtlBuildOutcome
    data class CompileError(override val compileLog: String, val message: String) : RtlBuildOutcome
    data class Violation(val violations: List<String>) : RtlBuildOutcome {
        override val compileLog: String get() = ""
    }
}

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
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.rtl
    private val buildSlots = Semaphore(props.compileWorkers)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val cacheDir = props.paths.cache.resolve("rtl")

    fun build(source: String, sig: Signature, settings: RtlSettings): RtlBuildOutcome {
        val violations = RtlChecks.checkSource(source)
        if (violations.isNotEmpty()) return RtlBuildOutcome.Violation(violations)

        val testbench = RtlTestbench.generate(sig, settings)
        val ports = RtlSignature.ports(sig, settings)
        val key = Hashing.sha256(
            source, sig.function, ports.joinToString("\n") { it.declaration() }, testbench, toolchain.fingerprint,
            config.buildJobs.toString(), CACHE_FORMAT,
        )
        val dir = cacheDir.resolve(key.take(2)).resolve(key)
        return locks.computeIfAbsent(key) { ReentrantLock() }.withLock {
            load(dir, cached = true) ?: buildFresh(source, sig.function, ports, testbench, dir)
        }
    }

    private fun load(dir: Path, cached: Boolean): RtlBuildOutcome? {
        val status = dir.resolve("status")
        if (!status.exists()) return null
        Files.setLastModifiedTime(status, FileTime.fromMillis(System.currentTimeMillis()))
        val compileLog = dir.resolve("compile.log").readText()
        return when (status.readText().trim()) {
            "ok" -> {
                val (cells, transistors, depth) = dir.resolve("size").readText().trim().split(" ").map(String::toLong)
                RtlBuildOutcome.Ok(dir.resolve("sim"), CircuitSize(cells, transistors, depth), compileLog, cached)
            }
            else -> RtlBuildOutcome.CompileError(compileLog, dir.resolve("message").readText())
        }
    }

    private fun buildFresh(source: String, module: String, ports: List<RtlSignature.Port>, testbench: String, dir: Path): RtlBuildOutcome {
        val tmp = Files.createDirectories(cacheDir.resolve("tmp")).let { Files.createTempDirectory(it, "b-") }
        try {
            FileTrees.makeWritableByAll(tmp)
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

            val started = System.nanoTime()
            val outcome = buildSlots.withPermit {
                val yosys = sandbox.runToCompletion(spec.copy(command = listOf(config.yosys.toString(), "-q", "-s", inside("synth.ys"))), config.buildTimeLimit, props.compile.maxLogBytes)
                val synthesis = synthesized(yosys, tmp, module, ports)
                if (synthesis !is Built.Ok) return@withPermit synthesis
                val verilator = sandbox.runToCompletion(
                    spec.copy(command = verilatorCommand(inside("netlist.v"), inside("testbench.cpp"))), config.buildTimeLimit, props.compile.maxLogBytes,
                )
                simulator(verilator, synthesis, tmp)
            }
            log.info("rtl build of {} finished in {}ms: {}", module, (System.nanoTime() - started) / 1_000_000, outcome::class.simpleName)
            // a timeout may come from the load of the machine: built again next time
            if (outcome is Built.TimedOut) return RtlBuildOutcome.CompileError(outcome.log, outcome.message)
            when (outcome) {
                is Built.Ok -> {
                    val sim = tmp.resolve("sim")
                    Files.copy(tmp.resolve("obj/sim"), sim, LinkOption.NOFOLLOW_LINKS)
                    Files.setPosixFilePermissions(sim, PosixFilePermissions.fromString("rwxr-xr-x"))
                    tmp.resolve("size").writeText(outcome.size.run { "$cells $transistors $depth" })
                    FileTrees.delete(tmp.resolve("obj"))
                }
                is Built.Failed -> tmp.resolve("message").writeText(outcome.message)
                is Built.TimedOut -> error("handled above")
            }
            tmp.resolve("compile.log").writeText(outcome.log)
            tmp.resolve("status").writeText(if (outcome is Built.Ok) "ok" else "ce")
            Files.createDirectories(dir.parent)
            if (!dir.exists()) Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
            return load(dir, cached = false)!!
        } finally {
            FileTrees.delete(tmp)
            Sandbox.deleteLog(tmp)
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

    private inline fun <T> Semaphore.withPermit(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

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
