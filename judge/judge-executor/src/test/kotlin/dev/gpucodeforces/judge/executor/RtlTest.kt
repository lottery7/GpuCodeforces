package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.RtlDataMode
import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.RtlSignature
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.processor.JudgeInfrastructureException
import dev.gpucodeforces.judge.executor.processor.SimulatorExit
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.processor.rtl.CircuitSize
import dev.gpucodeforces.judge.executor.processor.rtl.RtlBackend
import dev.gpucodeforces.judge.executor.processor.scalarBits
import dev.gpucodeforces.judge.executor.processor.rtl.RtlChecks
import dev.gpucodeforces.judge.executor.processor.rtl.RtlTestbench
import dev.gpucodeforces.judge.executor.processor.rtl.RtlToolchain
import dev.gpucodeforces.judge.executor.processor.unitLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.intellij.lang.annotations.Language

/** The parts of `rtl` that need no Yosys or Verilator: checks, reports of the tools, the testbench, the limits. */
class RtlTest {
    private val sig = Signature(
        params = listOf(
            ParamSpec("A", DType.INT32, ParamRole.INPUT, MemorySpace.HOST, "N"),
            ParamSpec("X", DType.FLOAT32, ParamRole.INOUT, MemorySpace.HOST, "N"),
            ParamSpec("C", DType.INT64, ParamRole.OUTPUT, MemorySpace.HOST, "N"),
            ParamSpec("N", DType.INT32, ParamRole.SCALAR),
        ),
    )

    @Test
    fun `include and readmem are violations, also behind a string with slashes, but not in comments`() {
        assertEquals(1, RtlChecks.checkSource("`include \"defs.svh\"\nmodule solve; endmodule").size)
        assertEquals(1, RtlChecks.checkSource("initial \$readmemb(\"x\", rom);").size)
        assertEquals(1, RtlChecks.checkSource("initial \$display(\"//\"); `include \"x\"").size)
        assertEquals(emptyList(), RtlChecks.checkSource("// `include \"x\"\n/* \$readmemh(\"y\", m); */ module solve; endmodule"))
    }

    @Test
    fun `stripping comments keeps line numbers`() {
        val source = "a /* one\ntwo */ b // three\nc"
        val stripped = RtlChecks.stripComments(source)
        assertEquals(source.lines().size, stripped.lines().size)
        assertEquals(listOf("a", "b", "c"), stripped.split(Regex("\\s+")).filter { it.isNotEmpty() })
    }

    @Test
    fun `cells and transistors are read from the report of Yosys`() {
        val stat = RtlChecks.parseStat(STAT)
        assertEquals(544, stat.cells)
        assertEquals(4978, stat.transistors)
        assertEquals(emptyList(), RtlChecks.unmappedCells(stat))
        assertEquals(emptyMap(), RtlChecks.unweightedCells(stat))
        assertEquals(listOf("\$mul", "adder"), RtlChecks.unmappedCells(stat.copy(cellsByType = stat.cellsByType + mapOf("adder" to 1L, "\$mul" to 1L))))
        assertFailsWith<IllegalArgumentException> { RtlChecks.parseStat("{}") }
        // stat without -tech cmos has no estimate
        assertFailsWith<IllegalArgumentException> { RtlChecks.parseStat("""{"design": {"num_cells": 1, "num_cells_by_type": {}}}""") }
    }

    @Test
    fun `latches and asynchronous flip-flops have no transistor count`() {
        val stat = RtlChecks.parseStat(
            """{"design": {"num_cells": 13, "estimated_num_transistors": "64+",
               "num_cells_by_type": {"${'$'}_DFF_N_": 1, "${'$'}_DFF_PP0_": 4, "${'$'}_DLATCH_P_": 4, "${'$'}_MUX_": 4}}}""",
        )
        assertEquals(null, stat.transistors)
        assertEquals(mapOf("\$_DFF_PP0_" to 4L, "\$_DLATCH_P_" to 4L), RtlChecks.unweightedCells(stat))
    }

    @Test
    fun `logic depth is the longest path of the report of ltp`() {
        assertEquals(62, RtlChecks.parseLongestPath(LTP))
        val loop = "5. Executing LTP pass (find longest path).\nWarning: Detected loop at ${'$'}abc${'$'}9${'$'}new_n5 in solve\n\n" +
            "Longest topological path in solve (length=2):\n"
        assertEquals(null, RtlChecks.parseLongestPath(loop))
        assertFailsWith<IllegalArgumentException> { RtlChecks.parseLongestPath("") }
    }

    @Test
    fun `a test costs cycles times depth times transistors`() {
        val size = CircuitSize(cells = 544, transistors = 4978, depth = 62)
        assertEquals(4978L * 62, size.perCycle)
        // a circuit of wires still costs its area per cycle
        assertEquals(10, CircuitSize(cells = 3, transistors = 10, depth = 0).perCycle)
        assertEquals(mapOf("cycles" to 7.0, "depth" to 62.0, "transistors" to 4978.0, "cells" to 544.0), size.metrics(7))
    }

    @Test
    fun `ports of the netlist are checked against the interface`() {
        val settings = RtlSettings(readPorts = 2)
        val ports = RtlSignature.ports(sig, settings)
        val netlist = netlistOf(ports)
        assertEquals(emptyList(), RtlChecks.checkPorts(netlist, ports, "solve"))

        val broken = netlist.replace("  input [63:0] X_rdata;\n", "")
            .replace("output [63:0] C_data;", "output [31:0] C_data;")
            .replace("endmodule", "  output debug;\nendmodule")
        assertEquals(
            listOf(
                "module solve has no port X_rdata: expected `input  logic [1:0] [31:0] X_rdata`",
                "port C_data of module solve does not match: expected `output logic [63:0] C_data`",
                "module solve has a port the problem does not define: debug",
            ),
            RtlChecks.checkPorts(broken, ports, "solve"),
        )
    }

    @Test
    fun `versions are read from what the tools print`() {
        assertEquals("0.70+286", RtlToolchain.parseVersion("Yosys 0.70+286 (git sha1 63eecf3dd, Release, Clang 21.1.8)", "Yosys"))
        assertEquals("5.053", RtlToolchain.parseVersion("Verilator 5.053 devel rev v5.052-654-gdd8a7d5e4 (mod)", "Verilator"))
        assertEquals(null, RtlToolchain.parseVersion("command not found", "Yosys"))
    }

    @Test
    fun `the testbench drives exactly the ports of the interface`() {
        for (settings in listOf(RtlSettings(), RtlSettings(readPorts = 3), RtlSettings(dataMode = RtlDataMode.STREAM))) {
            val streamable = if (settings.dataMode == RtlDataMode.STREAM) sig.copy(params = sig.params.filter { it.role != ParamRole.INOUT }) else sig
            val ports = RtlSignature.ports(streamable, settings).map { it.name }.toSet()
            val used = Regex("""(?<!\w)top\.(\w+)""").findAll(RtlTestbench.generate(streamable, settings)).map { it.groupValues[1] }.toSet() - setOf("eval", "final")
            assertEquals(ports, used, settings.toString())
        }
    }

    @Test
    fun `cycle limit keeps the score within the limit`() {
        assertEquals(2252, unitLimit(1e6, cap = 100_000_000, perUnit = 444))
        // a circuit too large for a single cycle still runs one, and fails on its score
        assertEquals(1, unitLimit(100.0, cap = 100_000_000, perUnit = 444))
        assertEquals(1000, unitLimit(1e12, cap = 1000, perUnit = 444))
        assertEquals(1000, unitLimit(1e6, cap = 1000, perUnit = 0))
    }

    @Test
    fun `scalars become the raw bits of their type`() {
        assertEquals("4294967295", scalarBits(DType.INT32, -1))
        assertEquals("18446744073709551615", scalarBits(DType.INT64, -1L))
        assertEquals("1065353216", scalarBits(DType.FLOAT32, 1.0))
        assertEquals("13830554455654793216", scalarBits(DType.FLOAT64, -1.0))
        assertEquals("7", scalarBits(DType.UINT32, 7))
    }

    @Test
    fun `the end of a simulation becomes a score or a verdict`() {
        fun outcome(code: Int, vararg lines: String) =
            RtlBackend.outcome(
                SimulatorExit(code, lines.toList(), stderr = "boom"), CircuitSize(cells = 30, transistors = 100, depth = 5), maxCycles = 50,
                ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS,
            )

        val finished = assertIs<SimulatorOutcome.Finished>(outcome(0, "cycles 42"))
        assertEquals(42.0 * 5 * 100, finished.score)
        assertEquals(mapOf("cycles" to 42.0, "depth" to 5.0, "transistors" to 100.0, "cells" to 30.0), finished.metrics)

        val error = assertIs<SimulatorOutcome.Failed>(outcome(RtlTestbench.EXIT_ERROR, "error write out of bounds: C[9]"))
        assertEquals(Verdict.RUNTIME_ERROR, error.verdict)
        assertEquals("write out of bounds: C[9]", error.details)

        val limit = assertIs<SimulatorOutcome.Failed>(outcome(RtlTestbench.EXIT_LIMIT, "limit"))
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, limit.verdict)
        assertEquals("Cycles x depth x transistors limit exceeded (done did not rise within 50 cycles)", limit.message)

        assertEquals(Verdict.RUNTIME_ERROR, assertIs<SimulatorOutcome.Failed>(outcome(137)).verdict)
        assertFailsWith<JudgeInfrastructureException> { outcome(RtlTestbench.EXIT_BROKEN) }
        assertFailsWith<JudgeInfrastructureException> { outcome(0) }
    }

    /** The port declarations `write_verilog` writes for [ports]: packed arrays become one wide vector. */
    private fun netlistOf(ports: List<RtlSignature.Port>): String = buildString {
        appendLine("module judge_top(${ports.joinToString { it.name }});")
        for (p in ports) {
            val range = if (p.width > 1) "[${p.width - 1}:0] " else ""
            appendLine("  ${p.direction.keyword} ${if (p.signed) "signed " else ""}$range${p.name};")
            appendLine("  wire $range${p.name};")
        }
        appendLine("endmodule")
    }

    private companion object {
        /** `stat -json -tech cmos` of `vector_add_rtl/solutions/accepted.sv` after `dffunmap`, by Yosys 0.70+286. */
        @Language("JSON")
        const val STAT = """
        {
           "creator": "Yosys 0.70+286",
           "invocation": "stat -json -tech cmos ",
           "modules": {
              "\\solve": {"num_cells": 544, "num_cells_by_type": {"${'$'}_AND_": 78, "${'$'}_DFF_P_": 67}, "estimated_num_transistors": "4978"}
           },
           "design": {
              "num_wires": 369,
              "num_memories": 0,
              "num_cells": 544,
              "num_submodules": 0,
              "num_cells_by_type": {
                 "${'$'}_ANDNOT_": 33, "${'$'}_AND_": 78, "${'$'}_DFF_P_": 67, "${'$'}_MUX_": 100, "${'$'}_NAND_": 119, "${'$'}_NOR_": 9,
                 "${'$'}_NOT_": 2, "${'$'}_OR_": 18, "${'$'}_XNOR_": 45, "${'$'}_XOR_": 73
              },
              "estimated_num_transistors": "4978"
           }
        }
        """

        /** The start of `ltp -noff` on the same circuit. */
        const val LTP = """
5. Executing LTP pass (find longest path).

Longest topological path in solve (length=62):
    0: \A_data [1]
    1: ${'$'}abc${'$'}1535${'$'}new_n140 (via ${'$'}abc${'$'}1535${'$'}blif${'$'}1535${'$'}43)
"""
    }
}
