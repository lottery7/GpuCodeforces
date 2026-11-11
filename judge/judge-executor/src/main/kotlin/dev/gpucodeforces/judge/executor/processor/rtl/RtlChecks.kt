package dev.gpucodeforces.judge.executor.processor.rtl

import dev.gpucodeforces.judge.api.RtlSignature
import tools.jackson.databind.json.JsonMapper

/** Checks of an `rtl` submission and of what Yosys made of it. */
object RtlChecks {
    private val json = JsonMapper.builder().build()

    /**
     * Constructs that read files of the machine while Yosys synthesizes: the build sandbox holds neither tests nor
     * answers, so this is about clear messages, not the security boundary.
     */
    private val FORBIDDEN = listOf(
        Regex("""`\s*include\b""") to "`include is not allowed: the submission is a single file",
        Regex("""\${'$'}readmem[bh]\b""") to "\$readmemh/\$readmemb are not allowed: write the values into the code",
    )

    fun checkSource(source: String): List<String> {
        val code = stripComments(source)
        return FORBIDDEN.filter { (pattern, _) -> pattern.containsMatchIn(code) }.map { it.second }
    }

    /**
     * [source] with comments blanked out (newlines kept, so line numbers stay). String literals stay as they are:
     * a `//` inside one is no comment, and `` `include `` takes its file name from one.
     */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                c == '"' -> {
                    val end = stringEnd(source, i)
                    out.append(source, i, end)
                    i = end
                }
                c == '/' && next == '/' -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    out.append(" ".repeat(end - i))
                    i = end
                }
                c == '/' && next == '*' -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    source.substring(i, end).forEach { out.append(if (it == '\n') '\n' else ' ') }
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Index after the closing quote of the string literal at [start], or the end of the line if it is unclosed. */
    private fun stringEnd(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return source.length
    }

    /** A port of the netlist; [direction] is null for an `inout`, which no interface has. */
    data class NetlistPort(val name: String, val direction: RtlSignature.Direction?, val msb: Int, val lsb: Int) {
        val width: Int get() = msb - lsb + 1
    }

    private val PORT_LINE = Regex("""^\s*(input|output|inout)\s+(?:signed\s+)?(?:\[(\d+):(\d+)]\s+)?(\S+)\s*;\s*$""")

    /** Port declarations of a netlist `write_verilog` wrote: one line each, `input [31:0] A_data;`. */
    fun parsePorts(netlist: String): List<NetlistPort> = netlist.lineSequence().mapNotNull { line ->
        PORT_LINE.matchEntire(line)?.let { m ->
            val (dir, msb, lsb, name) = m.destructured
            NetlistPort(name, RtlSignature.Direction.entries.find { it.keyword == dir }, msb.toIntOrNull() ?: 0, lsb.toIntOrNull() ?: 0)
        }
    }.toList()

    /** What is wrong with the ports of the synthesized module, compared with the interface the problem requires. */
    fun checkPorts(netlist: String, expected: List<RtlSignature.Port>, module: String): List<String> = buildList {
        val actual = parsePorts(netlist)
        val byName = actual.associateBy { it.name }
        for (port in expected) {
            val found = byName[port.name]
            when {
                found == null -> add("module $module has no port ${port.name}: expected `${port.declaration()}`")
                found.direction != port.direction || found.width != port.width || found.lsb != 0 ->
                    add("port ${port.name} of module $module does not match: expected `${port.declaration()}`")
            }
        }
        val expectedNames = expected.map { it.name }.toSet()
        actual.filter { it.name !in expectedNames }.forEach { add("module $module has a port the problem does not define: ${it.name}") }
    }

    /** [transistors] is null when Yosys knows no transistor count for some of the cells (see [unweightedCells]). */
    data class Stat(val cells: Long, val cellsByType: Map<String, Long>, val transistors: Long?)

    /**
     * The `design` part of `stat -json -tech cmos`. Yosys writes the transistor estimate as a string, with a trailing
     * `+` when some cell types have no count: `"12884"`, `"64+"`.
     */
    fun parseStat(text: String): Stat {
        val design = json.readTree(text).path("design")
        val cells = design.path("num_cells")
        require(cells.isIntegralNumber) { "no design.num_cells in the Yosys report" }
        val byType = design.path("num_cells_by_type").properties().associate { (type, n) -> type to n.asLong() }
        val estimate = design.path("estimated_num_transistors").asString("")
        require(estimate.isNotEmpty()) { "no design.estimated_num_transistors in the Yosys report (stat without -tech cmos?)" }
        val transistors = if (estimate.endsWith("+")) null else requireNotNull(estimate.toLongOrNull()) { "transistors '$estimate' is not a number" }
        return Stat(cells.asLong(), byType, transistors)
    }

    /**
     * Cells Yosys has no transistor count for. After `dffunmap` a synchronous circuit has only gates and the plain
     * flip-flops `$_DFF_P_`/`$_DFF_N_`, which it counts; what remains is a latch or a flip-flop with an asynchronous
     * set or reset (`$_DLATCH_P_`, `$_DFF_PP0_`, `$_DFFSR_PPP_`, ...).
     */
    fun unweightedCells(stat: Stat): Map<String, Long> =
        stat.cellsByType.filterKeys { STATEFUL.containsMatchIn(it) && it !in PLAIN_FLIP_FLOPS }.toSortedMap()

    private val STATEFUL = Regex("DFF|LATCH|_SR_")
    private val PLAIN_FLIP_FLOPS = setOf("\$_DFF_P_", "\$_DFF_N_")

    /**
     * Logic depth from the report of `ltp -noff`: the most cells on a path between flip-flops and ports. Null when Yosys
     * found a combinational loop, where no path is the longest.
     */
    fun parseLongestPath(text: String): Long? {
        if ("Detected loop" in text) return null
        val length = Regex("""Longest topological path in \S+ \(length=(\d+)\)""").find(text)
        return requireNotNull(length) { "no path length in the report of ltp" }.groupValues[1].toLong()
    }

    /**
     * After `synth` every cell is a gate or a flip-flop of Yosys' internal library (`$_AND_`, `$_DFF_P_`). Anything
     * else is a module Yosys could not map (a black box), and the area would not count it.
     */
    fun unmappedCells(stat: Stat): List<String> = stat.cellsByType.keys.filterNot { it.startsWith("\$_") }.sorted()
}
