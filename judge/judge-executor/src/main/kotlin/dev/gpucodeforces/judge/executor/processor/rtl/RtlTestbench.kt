package dev.gpucodeforces.judge.executor.processor.rtl

import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.RtlDataMode
import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.RtlSignature
import dev.gpucodeforces.judge.api.Signature
import org.intellij.lang.annotations.Language

/**
 * The judge's testbench of `rtl`: C++ compiled by Verilator together with the netlist of the contestant's circuit
 * (top module renamed to [TOP]). It is trusted code, like the harness of `gpu-cuda`.
 *
 * Protocol of one run: the only argument is a manifest with lines `nonce <hex>`, `max_cycles <n>`,
 * `scalar <name> <bits>` (raw bits of the value) and `array <name> <elements> <input file or -> <output file or ->`.
 * Before the first clock edge all inputs but `start` are set and `done` must be low; `start` is high for the first
 * cycle only. Cycles are counted until `done` is high after a rising edge. On stdout the testbench prints, prefixed
 * with the nonce, `cycles <n>` (exit 0), `error <text>` for a fault of the circuit (exit 3) or `limit` when `done`
 * did not rise within `max_cycles` (exit 4); a broken file is exit 2.
 *
 * - MEMORY: a read port answers on the cycle after its address (addresses out of range read zero); a write port
 *   stores on the rising edge it is high at, and a later read sees it. A write out of range is an error.
 * - STREAM: from the cycle after `start` every input array is fed one element per cycle, `valid` low once it is
 *   exhausted; every output array takes an element on each rising edge its `valid` is high, in index order, and one
 *   element too many is an error.
 *
 * Output arrays start as 0xFF bytes, like the outputs of `gpu-cuda`: a circuit that writes nothing fails the check.
 */
object RtlTestbench {
    const val TOP = "judge_top"

    /** Part of the build cache key: a change here must not reuse simulators built with the previous testbench. */
    const val VERSION = "1"

    const val EXIT_BROKEN = 2
    const val EXIT_ERROR = 3
    const val EXIT_LIMIT = 4

    fun generate(sig: Signature, settings: RtlSettings): String = buildString {
        append(PRELUDE)
        appendLine()
        val arrays = sig.arrays
        appendLine("static Mem ${arrays.joinToString(", ") { it.name }};")
        appendLine()
        appendLine("static void manifest_line(const std::string& kind, std::istringstream& in, Vjudge_top& top) {")
        appendLine("    std::string name;")
        appendLine("    in >> name;")
        appendLine("    if (kind == \"array\") {")
        for (p in arrays) {
            appendLine("        if (name == \"${p.name}\") return load(${p.name}, in, ${p.type.bytes});")
        }
        appendLine("    } else if (kind == \"scalar\") {")
        appendLine("        unsigned long long bits = 0;")
        appendLine("        in >> bits;")
        for (p in sig.scalars) {
            appendLine("        if (name == \"${p.name}\") { set(top.${p.name}, 0, ${p.type.bytes * 8}, bits); return; }")
        }
        appendLine("    }")
        appendLine("    broken(\"unknown manifest entry \" + kind + \" \" + name);")
        appendLine("}")
        appendLine()
        appendLine("static void before_edge(Vjudge_top& top) {")
        for (p in arrays) appendBeforeEdge(p, settings)
        appendLine("}")
        appendLine()
        appendLine("static void after_edge(Vjudge_top& top) {")
        for (p in arrays) appendAfterEdge(p, settings)
        appendLine("}")
        appendLine()
        appendLine("static void save_outputs() {")
        for (p in sig.outputs) appendLine("    save(${p.name});")
        appendLine("}")
        appendLine()
        append(MAIN)
    }

    private fun StringBuilder.appendBeforeEdge(p: ParamSpec, settings: RtlSettings) {
        val n = p.name
        val bits = p.type.bytes * 8
        val ports = settings.readPorts
        when (settings.dataMode) {
            RtlDataMode.MEMORY -> {
                val (addr, writeAddr, writeData, write) = when (p.role) {
                    ParamRole.INPUT -> listOf("${n}_addr", null, null, null)
                    ParamRole.OUTPUT -> listOf(null, "${n}_addr", "${n}_data", "${n}_write")
                    else -> listOf("${n}_raddr", "${n}_waddr", "${n}_wdata", "${n}_write")
                }
                if (addr != null) {
                    appendLine("    for (int port = 0; port < $ports; ++port) $n.pending[port] = get(top.$addr, port * ${RtlSignature.ADDRESS_BITS}, ${RtlSignature.ADDRESS_BITS});")
                }
                if (write != null) {
                    appendLine("    if (get(top.$write, 0, 1)) {")
                    appendLine("        uint64_t at = get(top.$writeAddr, 0, ${RtlSignature.ADDRESS_BITS});")
                    appendLine("        if (at >= $n.v.size()) fault(\"write out of bounds: $n[\" + std::to_string(at) + \"], $n has \" + std::to_string($n.v.size()) + \" elements\");")
                    appendLine("        $n.v[at] = get(top.$writeData, 0, $bits);")
                    appendLine("    }")
                }
            }
            RtlDataMode.STREAM -> if (p.role == ParamRole.OUTPUT) {
                appendLine("    if (get(top.${n}_valid, 0, 1)) {")
                appendLine("        if ($n.next >= $n.v.size()) fault(\"more outputs than $n has elements (\" + std::to_string($n.v.size()) + \")\");")
                appendLine("        $n.v[$n.next++] = get(top.${n}_data, 0, $bits);")
                appendLine("    }")
            }
        }
    }

    private fun StringBuilder.appendAfterEdge(p: ParamSpec, settings: RtlSettings) {
        val n = p.name
        val bits = p.type.bytes * 8
        when (settings.dataMode) {
            RtlDataMode.MEMORY -> {
                val data = when (p.role) {
                    ParamRole.INPUT -> "${n}_data"
                    ParamRole.INOUT -> "${n}_rdata"
                    else -> return
                }
                appendLine("    for (int port = 0; port < ${settings.readPorts}; ++port) {")
                appendLine("        uint64_t at = $n.pending[port];")
                appendLine("        set(top.$data, port * $bits, $bits, at < $n.v.size() ? $n.v[at] : 0);")
                appendLine("    }")
            }
            RtlDataMode.STREAM -> if (p.role == ParamRole.INPUT) {
                appendLine("    bool ${n}_has = $n.next < $n.v.size();")
                appendLine("    set(top.${n}_data, 0, $bits, ${n}_has ? $n.v[$n.next] : 0);")
                appendLine("    set(top.${n}_valid, 0, 1, ${n}_has ? 1 : 0);")
                appendLine("    if (${n}_has) ++$n.next;")
            }
        }
    }

    @Language("C++")
    private val PRELUDE = """
        // Generated by the judge (RtlTestbench), version $VERSION.
        #include "Vjudge_top.h"
        #include "verilated.h"
        #include <cstdint>
        #include <cstdio>
        #include <cstdlib>
        #include <fstream>
        #include <sstream>
        #include <string>
        #include <type_traits>
        #include <vector>

        struct Mem {
            std::vector<uint64_t> v;
            int bytes = 0;
            std::string out;
            size_t next = 0;
            uint64_t pending[8] = {0};
        };

        static std::string nonce;

        [[noreturn]] static void broken(const std::string& what) {
            std::fprintf(stderr, "testbench: %s\n", what.c_str());
            std::exit($EXIT_BROKEN);
        }

        [[noreturn]] static void fault(const std::string& what) {
            std::printf("%s error %s\n", nonce.c_str(), what.c_str());
            std::fflush(stdout);
            std::exit($EXIT_ERROR);
        }

        static uint64_t mask(int width) { return width >= 64 ? ~0ull : (1ull << width) - 1; }

        // Ports up to 64 bits are integers in Verilator, wider ones arrays of 32-bit words.
        template <class T> static uint64_t get(const T& port, int lo, int width) {
            if constexpr (std::is_integral_v<T>) {
                return (static_cast<uint64_t>(port) >> lo) & mask(width);
            } else {
                uint64_t value = 0;
                for (int bit = 0; bit < width; ++bit) {
                    int at = lo + bit;
                    if ((port[at / 32] >> (at % 32)) & 1u) value |= 1ull << bit;
                }
                return value;
            }
        }

        template <class T> static void set(T& port, int lo, int width, uint64_t value) {
            value &= mask(width);
            if constexpr (std::is_integral_v<T>) {
                using U = std::make_unsigned_t<T>;
                U current = static_cast<U>(port);
                U field = static_cast<U>(mask(width) << lo);
                port = static_cast<T>((current & ~field) | (static_cast<U>(value << lo) & field));
            } else {
                for (int bit = 0; bit < width; ++bit) {
                    int at = lo + bit;
                    uint32_t one = 1u << (at % 32);
                    if ((value >> bit) & 1) port[at / 32] |= one; else port[at / 32] &= ~one;
                }
            }
        }

        static void load(Mem& mem, std::istringstream& in, int bytes) {
            size_t elements = 0;
            std::string input, output;
            in >> elements >> input >> output;
            mem.bytes = bytes;
            mem.v.assign(elements, mask(bytes * 8));
            mem.out = output == "-" ? "" : output;
            if (input == "-") return;
            std::ifstream file(input, std::ios::binary);
            std::vector<unsigned char> raw(elements * bytes);
            if (!file.read(reinterpret_cast<char*>(raw.data()), raw.size())) broken("cannot read " + input);
            for (size_t i = 0; i < elements; ++i) {
                uint64_t value = 0;
                for (int b = 0; b < bytes; ++b) value |= static_cast<uint64_t>(raw[i * bytes + b]) << (8 * b);
                mem.v[i] = value;
            }
        }

        static void save(const Mem& mem) {
            std::vector<unsigned char> raw(mem.v.size() * mem.bytes);
            for (size_t i = 0; i < mem.v.size(); ++i)
                for (int b = 0; b < mem.bytes; ++b) raw[i * mem.bytes + b] = static_cast<unsigned char>(mem.v[i] >> (8 * b));
            std::ofstream file(mem.out, std::ios::binary);
            if (!file.write(reinterpret_cast<const char*>(raw.data()), raw.size())) broken("cannot write " + mem.out);
        }
    """.trimIndent() + "\n"

    @Language("C++")
    private val MAIN = """
        int main(int argc, char** argv) {
            if (argc != 2) broken("usage: sim <manifest>");
            VerilatedContext context;
            Vjudge_top top{&context};
            unsigned long long max_cycles = 0;
            std::ifstream manifest(argv[1]);
            std::string line;
            while (std::getline(manifest, line)) {
                std::istringstream in(line);
                std::string kind;
                in >> kind;
                if (kind.empty()) continue;
                if (kind == "nonce") in >> nonce;
                else if (kind == "max_cycles") in >> max_cycles;
                else manifest_line(kind, in, top);
            }
            if (nonce.empty() || max_cycles == 0) broken("incomplete manifest");

            top.clk = 0;
            top.start = 0;
            top.eval();
            if (top.done) fault("done is high before start");
            top.start = 1;
            top.eval();
            unsigned long long cycles = 0;
            while (true) {
                before_edge(top);
                top.clk = 1;
                top.eval();
                ++cycles;
                after_edge(top);
                top.clk = 0;
                top.start = 0;
                top.eval();
                if (top.done) break;
                if (cycles >= max_cycles) {
                    std::printf("%s limit\n", nonce.c_str());
                    return $EXIT_LIMIT;
                }
            }
            save_outputs();
            top.final();
            std::printf("%s cycles %llu\n", nonce.c_str(), cycles);
            return 0;
        }
    """.trimIndent() + "\n"
}
