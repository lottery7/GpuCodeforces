package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.RtlDataMode
import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.config.PathsConfig
import dev.gpucodeforces.judge.executor.config.PrepareConfig
import dev.gpucodeforces.judge.executor.config.RtlConfig
import dev.gpucodeforces.judge.executor.config.ToolchainConfig
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.rtl.RtlBackend
import dev.gpucodeforces.judge.executor.processor.rtl.RtlBuilder
import dev.gpucodeforces.judge.executor.processor.rtl.RtlToolchain
import dev.gpucodeforces.judge.executor.problem.PrepareRuntime
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Judges the solutions of `examples/vector_add_rtl` with the real Yosys and Verilator: `RTL_HOME` is the root of the
 * OSS CAD Suite, `JUDGE_PYTHON` a Python with torch for the generator. No sandbox: the tools run directly.
 */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RtlEmulatorTest {
    @TempDir
    lateinit var tmp: Path

    private val example = Path.of("../problems/examples/vector_add_rtl")
    private val problem: ProblemSpec by lazy {
        val mapper = jacksonObjectMapper()
        val tree = mapper.readTree(example.resolve("problem.json").readText()) as ObjectNode
        tree.putObject("generator").put("source", example.resolve("problem.py").readText())
        mapper.treeToValue(tree, ProblemSpec::class.java)
    }

    private val backend: RtlBackend by lazy {
        val home = System.getenv("RTL_HOME") ?: error("set RTL_HOME to the root of the OSS CAD Suite")
        val props = ExecutorProperties(
            processors = listOf(Processors.RTL),
            paths = PathsConfig(work = tmp.resolve("work"), cache = tmp.resolve("cache"), pytools = Path.of("../pytools").toAbsolutePath()),
            toolchain = ToolchainConfig(python = System.getenv("JUDGE_PYTHON") ?: "python3"),
            prepare = PrepareConfig(device = "cpu"),
            // enough for every test of the example, and a circuit that never finishes fails fast
            rtl = RtlConfig(home = Path.of(home), maxCycles = 1_000_000),
        )
        val sandbox = DirectSandbox()
        val toolchain = RtlToolchain.probe(props.rtl)
        RtlBackend(props, toolchain, RtlBuilder(props, toolchain, sandbox), ProblemStore(props, null, PrepareRuntime(props), sandbox), sandbox)
    }

    private val hooks = object : JudgeHooks {
        override fun status(status: SubmissionStatus) {}
        override fun progress(current: Int, total: Int) {}
        override fun cancelled() = false
    }

    private fun judge(solution: String): JudgeResult = judge(problem, solution, example.resolve("solutions/$solution").readText())

    private fun judge(problem: ProblemSpec, name: String, source: String): JudgeResult {
        val job = JudgeJob(
            submissionId = name, problem = problem, source = source,
            processor = Processors.RTL, language = "systemverilog", enqueuedAt = Instant.now(), jobId = name,
        )
        val runDir = Files.createTempDirectory(Files.createDirectories(tmp.resolve("runs")), "run-")
        return backend.judge(job, runDir, hooks)
    }

    @Test
    fun `accepted solution gets cycles times depth times transistors, the same on a resubmit`() {
        val first = judge("accepted.sv")
        assertEquals(Verdict.ACCEPTED, first.verdict, first.toString())
        val score = assertNotNull(first.score)
        assertEquals(ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS, score.type)
        val cycles = first.metrics.getValue("cycles")
        val depth = first.metrics.getValue("depth")
        val transistors = first.metrics.getValue("transistors")
        assertEquals(cycles * depth * transistors, score.value)
        // a 32-bit adder is tens of gates deep, and every type of gate has its weight
        assertTrue(depth in 20.0..200.0, "depth $depth")
        assertTrue(transistors > first.metrics.getValue("cells"), first.metrics.toString())
        // the largest measured test has 4096 elements, one per cycle
        assertEquals(4099.0, cycles)
        // the long test is not measured: no score of its own counts
        assertEquals(5, first.tests.size)
        assertEquals(false, first.tests.last().measured)

        val again = judge("accepted.sv")
        assertEquals(first.score, again.score)
        assertEquals(first.metrics, again.metrics)
    }

    @Test
    fun `every example solution gets its verdict`() {
        val expected = mapOf(
            "wrong_answer.sv" to Verdict.WRONG_ANSWER,
            "out_of_bounds.sv" to Verdict.RUNTIME_ERROR,
            "no_done.sv" to Verdict.TIME_LIMIT_EXCEEDED,
            "synthesis_error.sv" to Verdict.COMPILATION_ERROR,
            "wrong_ports.sv" to Verdict.COMPILATION_ERROR,
            "forbidden.sv" to Verdict.POLICY_VIOLATION,
            "latch.sv" to Verdict.COMPILATION_ERROR,
            "loop.sv" to Verdict.COMPILATION_ERROR,
        )
        val actual = expected.keys.associateWith { judge(it) }
        assertEquals(expected, actual.mapValues { it.value.verdict }, actual.toString())

        // failures on the sample show what went wrong
        assertContains(actual.getValue("out_of_bounds.sv").tests.single().debug.orEmpty(), "write out of bounds: C[4]")
        assertContains(actual.getValue("wrong_answer.sv").tests.single().message.orEmpty(), "C")
        assertContains(actual.getValue("no_done.sv").message.orEmpty(), "Cycles x depth x transistors limit exceeded on test 1")
        assertContains(actual.getValue("synthesis_error.sv").compileLog.orEmpty(), "solution.sv")
        assertContains(actual.getValue("wrong_ports.sv").message.orEmpty(), "module solve has no port C_write")
        assertContains(actual.getValue("forbidden.sv").message.orEmpty(), "\$readmemh")
        assertContains(actual.getValue("latch.sv").message.orEmpty(), "latches or flip-flops with an asynchronous set or reset")
        assertContains(actual.getValue("loop.sv").message.orEmpty(), "combinational loop")
    }

    private fun withRtl(problem: ProblemSpec, settings: RtlSettings) =
        problem.copy(processors = problem.processors.map { it.copy(rtl = settings) })

    @Test
    fun `stream mode hands elements over one per cycle`() {
        val result = judge(withRtl(problem, RtlSettings(dataMode = RtlDataMode.STREAM)), "stream", STREAM_ADD)
        assertEquals(Verdict.ACCEPTED, result.verdict, result.toString())
        assertEquals(4097.0, result.metrics["cycles"])
    }

    @Test
    fun `every read port of a packed port array reads its own address, also beyond 64 bits`() {
        val result = judge(withRtl(problem, RtlSettings(readPorts = 3)), "three-ports", THREE_PORT_ADD)
        assertEquals(Verdict.ACCEPTED, result.verdict, result.toString())
    }

    @Test
    fun `an inout array is read and written in place`() {
        val increment = problem.copy(
            id = "increment",
            signature = Signature(
                params = listOf(
                    ParamSpec("X", DType.INT32, ParamRole.INOUT, MemorySpace.HOST, "N"),
                    ParamSpec("N", DType.INT32, ParamRole.SCALAR),
                ),
            ),
            generator = GeneratorSpec(source = INCREMENT_GENERATOR),
        )
        val result = judge(increment, "increment", INOUT_INCREMENT)
        assertEquals(Verdict.ACCEPTED, result.verdict, result.toString())
    }

    private companion object {
        /** C = A + B as the elements stream by: an element of C leaves on the cycle the elements of A and B arrive. */
        @Language("SystemVerilog")
        const val STREAM_ADD = """
module solve (
    input  logic clk,
    input  logic start,
    output logic done,
    input  logic [31:0] A_data,
    input  logic A_valid,
    input  logic [31:0] B_data,
    input  logic B_valid,
    output logic [31:0] C_data,
    output logic C_valid,
    input  logic signed [31:0] N
);
    logic [31:0] count;
    assign C_valid = A_valid;
    assign C_data = A_data + B_data;
    always_ff @(posedge clk) begin
        if (start) begin
            count <= 0;
            done <= 0;
        end else if (A_valid) begin
            count <= count + 1;
            if (count + 1 == N) done <= 1;
        end
    end
endmodule
"""

        /**
         * The addition of the example, with A on read port 2 and B on read port 0; the other ports read out of range or
         * the first element. Three 32-bit addresses take 96 bits, which Verilator keeps as an array of words.
         */
        @Language("SystemVerilog")
        const val THREE_PORT_ADD = """
module solve (
    input  logic clk,
    input  logic start,
    output logic done,
    output logic [2:0] [31:0] A_addr,
    input  logic [2:0] [31:0] A_data,
    output logic [2:0] [31:0] B_addr,
    input  logic [2:0] [31:0] B_data,
    output logic [31:0] C_addr,
    output logic [31:0] C_data,
    output logic C_write,
    input  logic signed [31:0] N
);
    logic [31:0] i, pending_index;
    logic running, pending, started;

    assign A_addr[0] = 32'hFFFF_FFFF;
    assign A_addr[1] = 0;
    assign A_addr[2] = i;
    assign B_addr[0] = i;
    assign B_addr[1] = 32'hFFFF_FFFF;
    assign B_addr[2] = 0;
    assign C_addr = pending_index;
    assign C_data = A_data[2] + B_data[0];
    assign C_write = pending;

    always_ff @(posedge clk) begin
        if (start) begin
            i <= 0;
            running <= 1;
            started <= 1;
            pending <= 0;
            done <= 0;
        end else begin
            pending <= running && ${'$'}signed(i) < N;
            pending_index <= i;
            if (running) begin
                if (${'$'}signed(i) < N) i <= i + 1;
                else running <= 0;
            end
            done <= started && !running && !pending;
        end
    end
endmodule
"""

        /** X[i] += 1: X[i] is read on one cycle and written back on the next. */
        @Language("SystemVerilog")
        const val INOUT_INCREMENT = """
module solve (
    input  logic clk,
    input  logic start,
    output logic done,
    output logic [31:0] X_raddr,
    input  logic [31:0] X_rdata,
    output logic [31:0] X_waddr,
    output logic [31:0] X_wdata,
    output logic X_write,
    input  logic signed [31:0] N
);
    logic [31:0] i, pending_index;
    logic running, pending, started;

    assign X_raddr = i;
    assign X_waddr = pending_index;
    assign X_wdata = X_rdata + 1;
    assign X_write = pending;

    always_ff @(posedge clk) begin
        if (start) begin
            i <= 0;
            running <= 1;
            started <= 1;
            pending <= 0;
            done <= 0;
        end else begin
            pending <= running && ${'$'}signed(i) < N;
            pending_index <= i;
            if (running) begin
                if (${'$'}signed(i) < N) i <= i + 1;
                else running <= 0;
            end
            done <= started && !running && !pending;
        end
    end
endmodule
"""

        @Language("Python")
        const val INCREMENT_GENERATOR = """
import torch


def tests():
    yield {"name": "sample", "sample": True, "scalars": {"N": 3}, "inputs": {"X": [1, -1, 2147483647]}}
    yield {"name": "random", "scalars": {"N": 777}, "seed": 1}


def generate(test, gen, device):
    return {"X": torch.randint(-2**31, 2**31, (test["N"],), dtype=torch.int32, device=device, generator=gen)}


def reference(test, inputs):
    return {"X": inputs["X"] + 1}
"""
    }
}
