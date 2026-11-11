package dev.gpucodeforces.judge.api

/**
 * The interface of the contestant's top module `solve` on `rtl`: what the judge's testbench connects to and what the
 * default template declares. Control: `clk`, a one-cycle `start` pulse, `done`. There is no reset: `start` plays
 * its role and registers are zero before it.
 *
 * Scalars are inputs as wide as their type. Arrays depend on [RtlSettings.dataMode]:
 * - [RtlDataMode.MEMORY]: every array is a memory of the judge with ports of its own, and read data arrives on
 *   the cycle after its address. An INPUT array has k read ports `A_addr`/`A_data` ([RtlSettings.readPorts]): with
 *   k > 1 they are packed arrays `logic [k-1:0][31:0] A_addr`, port i is `A_addr[i]` (Yosys reads no unpacked
 *   arrays in ports),
 *   an OUTPUT array a write port `C_addr`/`C_data`/`C_write`, an INOUT array both, named `X_raddr`/`X_rdata` and
 *   `X_waddr`/`X_wdata`/`X_write`.
 * - [RtlDataMode.STREAM]: after `start` the judge feeds each INPUT array one element per cycle (`A_data`, `A_valid`)
 *   in index order, and the circuit hands each OUTPUT array over the same way (`C_data`, `C_valid`).
 *
 * Elements are raw bits of their type: floats too. Addresses are element indices, 32 bits wide.
 */
object RtlSignature {
    const val ADDRESS_BITS = 32

    /** Ports every module has; parameter names must not take them. */
    val CONTROL_PORTS = listOf("clk", "start", "done")

    enum class Direction(val keyword: String) { INPUT("input"), OUTPUT("output") }

    data class Port(
        val name: String,
        val direction: Direction,
        val bits: Int,
        /** Elements of a packed port array (`[k-1:0][bits-1:0]`), null for a single port. */
        val count: Int? = null,
        val signed: Boolean = false,
    ) {
        fun declaration(): String {
            val type = buildString {
                append("logic")
                if (signed) append(" signed")
                count?.let { append(" [${it - 1}:0]") }
                if (bits > 1) append(" [${bits - 1}:0]")
            }
            val direction = direction.keyword.padEnd(6)
            return "$direction $type $name"
        }

        /** Bits of the whole port, as the netlist has it. */
        val width: Int get() = bits * (count ?: 1)
    }

    fun ports(sig: Signature, settings: RtlSettings = RtlSettings()): List<Port> = buildList {
        add(Port("clk", Direction.INPUT, 1))
        add(Port("start", Direction.INPUT, 1))
        add(Port("done", Direction.OUTPUT, 1))
        for (p in sig.params) {
            val bits = p.type.bytes * 8
            when (settings.dataMode) {
                RtlDataMode.MEMORY -> addAll(memoryPorts(p, bits, settings.readPorts))
                RtlDataMode.STREAM -> addAll(streamPorts(p, bits))
            }
        }
    }

    private fun memoryPorts(p: ParamSpec, bits: Int, readPorts: Int): List<Port> {
        val count = readPorts.takeIf { it > 1 }
        val n = p.name
        return when (p.role) {
            ParamRole.SCALAR -> listOf(scalarPort(p, bits))
            ParamRole.INPUT -> listOf(
                Port("${n}_addr", Direction.OUTPUT, ADDRESS_BITS, count),
                Port("${n}_data", Direction.INPUT, bits, count),
            )
            ParamRole.OUTPUT -> listOf(
                Port("${n}_addr", Direction.OUTPUT, ADDRESS_BITS),
                Port("${n}_data", Direction.OUTPUT, bits),
                Port("${n}_write", Direction.OUTPUT, 1),
            )
            ParamRole.INOUT -> listOf(
                Port("${n}_raddr", Direction.OUTPUT, ADDRESS_BITS, count),
                Port("${n}_rdata", Direction.INPUT, bits, count),
                Port("${n}_waddr", Direction.OUTPUT, ADDRESS_BITS),
                Port("${n}_wdata", Direction.OUTPUT, bits),
                Port("${n}_write", Direction.OUTPUT, 1),
            )
        }
    }

    /** INOUT arrays have no stream form; [SpecValidator] rejects them in this mode. */
    private fun streamPorts(p: ParamSpec, bits: Int): List<Port> = when (p.role) {
        ParamRole.SCALAR -> listOf(scalarPort(p, bits))
        ParamRole.INPUT -> listOf(Port("${p.name}_data", Direction.INPUT, bits), Port("${p.name}_valid", Direction.INPUT, 1))
        ParamRole.OUTPUT -> listOf(Port("${p.name}_data", Direction.OUTPUT, bits), Port("${p.name}_valid", Direction.OUTPUT, 1))
        ParamRole.INOUT -> emptyList()
    }

    private fun scalarPort(p: ParamSpec, bits: Int) =
        Port(p.name, Direction.INPUT, bits, signed = !p.type.floating && p.type.name.startsWith("INT"))

    /** `module solve (...);` with every port, one per line. */
    fun declaration(sig: Signature, settings: RtlSettings = RtlSettings()): String =
        "module ${sig.function} (\n" + ports(sig, settings).joinToString(",\n") { "    ${it.declaration()}" } + "\n);"

    /** A module that compiles and does nothing yet: `done` stays low, so every test runs out of cycles. */
    fun template(sig: Signature, settings: RtlSettings = RtlSettings()): String = buildString {
        appendLine(declaration(sig, settings))
        appendLine()
        appendLine("    // Your circuit. Raise done once the outputs are written; registers are zero before start.")
        appendLine("    assign done = 1'b0;")
        appendLine()
        appendLine("endmodule")
    }

    /** IEEE 1800-2017 reserved words: a parameter named like one would break the generated module. */
    val KEYWORDS: Set<String> = """
        accept_on alias always always_comb always_ff always_latch and assert assign assume automatic before begin
        bind bins binsof bit break buf bufif0 bufif1 byte case casex casez cell chandle checker class clocking cmos
        config const constraint context continue cover covergroup coverpoint cross deassign default defparam design
        disable dist do edge else end endcase endchecker endclass endclocking endconfig endfunction endgenerate
        endgroup endinterface endmodule endpackage endprimitive endprogram endproperty endspecify endsequence
        endtable endtask enum event eventually expect export extends extern final first_match for force foreach
        forever fork forkjoin function generate genvar global highz0 highz1 if iff ifnone ignore_bins illegal_bins
        implements implies import incdir include initial inout input inside instance int integer interconnect
        interface intersect join join_any join_none large let liblist library local localparam logic longint
        macromodule matches medium modport module nand negedge nettype new nexttime nmos nor noshowcancelled not
        notif0 notif1 null or output package packed parameter pmos posedge primitive priority program property
        protected pull0 pull1 pulldown pullup pulsestyle_ondetect pulsestyle_onevent pure rand randc randcase
        randsequence rcmos real realtime ref reg reject_on release repeat restrict return rnmos rpmos rtran rtranif0
        rtranif1 s_always s_eventually s_nexttime s_until s_until_with scalared sequence shortint shortreal
        showcancelled signed small soft solve specify specparam static string strong strong0 strong1 struct super
        supply0 supply1 sync_accept_on sync_reject_on table tagged task this throughout time timeprecision timeunit
        tran tranif0 tranif1 tri tri0 tri1 triand trior trireg type typedef union unique unique0 unsigned until
        until_with untyped use uwire var vectored virtual void wait wait_order wand weak weak0 weak1 while wildcard
        wire with within wor xnor xor
    """.trim().split(Regex("\\s+")).toSet()
}
