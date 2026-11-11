package dev.gpucodeforces.judge.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.intellij.lang.annotations.Language

class SizeExprTest {
    @Test
    fun `evaluates arithmetic with precedence`() {
        val e = SizeExpr.parse("(N + 255) / 256 * 256 + M*K % 7 - -1")
        assertEquals(setOf("N", "M", "K"), e.identifiers)
        assertEquals((1000L + 255) / 256 * 256 + 6L * 5 % 7 + 1, e.eval(mapOf("N" to 1000L, "M" to 6L, "K" to 5L)))
    }

    @Test
    fun `rejects garbage and overflow`() {
        assertFailsWith<IllegalArgumentException> { SizeExpr.parse("N +") }
        assertFailsWith<IllegalArgumentException> { SizeExpr.parse("N; system()") }
        assertFailsWith<ArithmeticException> { SizeExpr.parse("N*N").eval(mapOf("N" to Long.MAX_VALUE / 2)) }
    }
}

class SpecValidatorTest {
    @Test
    fun `valid spec has no errors`() {
        assertEquals(emptyList(), SpecValidator.validateProblem(Fixtures.vectorAdd()))
    }

    @Test
    fun `reports structural problems`() {
        val spec = Fixtures.vectorAdd().let {
            it.copy(
                signature = it.signature.copy(
                    params = it.signature.params.map { p -> if (p.name == "A") p.copy(size = "N*Q") else p } +
                        ParamSpec("main", DType.FLOAT16, ParamRole.SCALAR),
                ),
            )
        }
        val errors = SpecValidator.validateProblem(spec)
        assertTrue(errors.any { "'Q'" in it }, errors.toString())
        assertTrue(errors.any { "param 'main'" in it }, errors.toString())
        assertTrue(errors.any { "not allowed for scalars" in it }, errors.toString())
    }

    @Test
    fun `array sizes follow the size expressions`() {
        assertEquals(mapOf("A" to 6L, "B" to 6L, "C" to 6L), SpecValidator.elementCounts(Fixtures.vectorAdd().signature, mapOf("N" to 6)))
    }

    @Test
    fun `compile settings accept optimisation, standard, defines and math flags`() {
        val spec = Fixtures.vectorAdd().copy(
            compile = CompileSpec("sm_86", listOf("-O2", "-std=c++20", "-DNDEBUG", "-DBLOCK=256", "--use_fast_math", "--fmad=false")),
        )
        assertEquals(emptyList(), SpecValidator.validateProblem(spec))
    }

    @Test
    fun `compile flags that touch files, the host toolchain or the architecture are rejected`() {
        val forbidden = listOf(
            "-include/etc/passwd", "-I/etc", "-o/tmp/x", "-Xcompiler", "-Xlinker", "-arch=sm_90", "-gencode",
            "-DX=\$(id)", "-O2 -I/etc", "--ptx",
        )
        val errors = SpecValidator.validateProblem(Fixtures.vectorAdd().copy(compile = CompileSpec("rtx3060", forbidden)))
        assertTrue(errors.any { "compile.arch 'rtx3060'" in it }, errors.toString())
        for (flag in forbidden) assertTrue(errors.any { "flag '$flag' is not allowed" in it }, "$flag: $errors")
    }

    @Test
    fun `device code runs only on the same or a later revision of its architecture family`() {
        assertTrue(CompileSpec.runsOn("sm_86", "sm_86"))
        assertTrue(CompileSpec.runsOn("sm_80", "sm_86"))
        assertTrue(!CompileSpec.runsOn("sm_89", "sm_86"), "later revision than the GPU")
        assertTrue(!CompileSpec.runsOn("sm_75", "sm_86"), "another family")
        assertTrue(!CompileSpec.runsOn("sm_100", "sm_120"), "another family")
        assertTrue(!CompileSpec.runsOn("ampere", "sm_86"))
    }

    @Test
    fun `time is the whole call unless the problem asks for kernel time`() {
        val host = Fixtures.vectorAdd().let { p ->
            p.copy(signature = p.signature.copy(params = p.signature.params.map { if (it.name == "A") it.copy(memory = MemorySpace.HOST) else it }))
        }
        assertEquals(TimingMode.WALL, Fixtures.vectorAdd().timing.effectiveMode)
        assertEquals(TimingMode.WALL, host.timing.effectiveMode)
        assertEquals(TimingMode.GPU_KERNELS, TimingSpec(mode = TimingMode.GPU_KERNELS).effectiveMode)
    }
}

class ProcessorsTest {
    @Test
    fun `catalog ids are unique queue names and every processor has a language`() {
        assertEquals(Processors.ids.toSet().size, Processors.ids.size)
        assertTrue(Processors.ids.all(Topology::isProfileName), Processors.ids.toString())
        assertTrue(Processors.all.all { it.languages.isNotEmpty() })
        assertEquals(listOf(Processors.GPU_CUDA), Processors.all.filter { !it.emulated }.map { it.id })
        assertEquals(ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS, Processors.get(Processors.RTL).scoreType)
    }
}

class ProcessorSpecTest {
    private val rtl = ProcessorSpec(Processors.RTL, listOf("systemverilog"), limit = 1e6)

    @Test
    fun `a package without processors is a gpu-cuda problem with its time limit`() {
        val spec = Fixtures.vectorAdd()
        assertEquals(listOf(ProcessorSpec(Processors.GPU_CUDA, listOf("cuda"))), spec.declaredProcessors())
        assertEquals(1000.0, spec.scoreLimit(spec.processorSpec(Processors.GPU_CUDA)!!))
        assertEquals(emptyList(), SpecValidator.validateTarget(spec, Processors.GPU_CUDA, "cuda"))
        assertTrue(SpecValidator.validateTarget(spec, Processors.RTL, "systemverilog").single().contains("declares: gpu-cuda"))
    }

    @Test
    fun `gpu-cuda takes its own limit when it has one`() {
        val spec = Fixtures.vectorAdd().copy(processors = listOf(ProcessorSpec(Processors.GPU_CUDA, listOf("cuda"), limit = 0.5), rtl))
        assertEquals(emptyList(), SpecValidator.validateProblem(spec))
        assertEquals(0.5, spec.scoreLimit(spec.processorSpec(Processors.GPU_CUDA)!!))
        assertEquals(2001, spec.limits.wallTimeLimitMsFor(0.5))
    }

    @Test
    fun `processors must be known, unique, with their own languages and limits`() {
        val spec = Fixtures.vectorAdd().copy(
            processors = listOf(
                ProcessorSpec("gpu", listOf("cuda")),
                rtl.copy(limit = null, languages = listOf("verilog")),
                rtl,
                ProcessorSpec(Processors.RISCV_RVV, listOf("c"), limit = -1.0, rtl = RtlSettings()),
                ProcessorSpec(Processors.GPU_CUDA, emptyList(), limit = 1e9),
            ),
        )
        val errors = SpecValidator.validateProblem(spec)
        listOf(
            "unknown processor 'gpu'", "processor rtl: limit is required", "language 'verilog' is not one of systemverilog",
            "processor rtl: declared twice", "processor riscv-rvv: limit must be a positive number",
            "rtl settings belong to processor rtl only", "processor gpu-cuda: languages is empty",
            "processor gpu-cuda: limit must be at most 30000 ms",
        ).forEach { expected -> assertTrue(errors.any { expected in it }, "$expected: $errors") }
    }

    @Test
    fun `rtl checks its ports`() {
        val sig = Fixtures.vectorAdd().signature
        val bad = Fixtures.vectorAdd().copy(
            signature = sig.copy(params = sig.params + ParamSpec("wire", DType.INT32, ParamRole.SCALAR) + ParamSpec("A_addr", DType.INT32, ParamRole.SCALAR)),
            processors = listOf(rtl.copy(rtl = RtlSettings(readPorts = 9))),
        )
        val errors = SpecValidator.validateProblem(bad)
        listOf("readPorts must be in 1..8", "'wire' is a SystemVerilog keyword", "two ports of the module would be named 'A_addr'")
            .forEach { expected -> assertTrue(errors.any { expected in it }, "$expected: $errors") }
        val inout = sig.copy(params = sig.params.map { if (it.name == "C") it.copy(role = ParamRole.INOUT) else it })
        val stream = Fixtures.vectorAdd().copy(signature = inout, processors = listOf(rtl.copy(rtl = RtlSettings(dataMode = RtlDataMode.STREAM))))
        assertTrue(SpecValidator.validateProblem(stream).any { "'C' is INOUT" in it })
    }

    @Test
    fun `a submission names a declared processor, an allowed language`() {
        val spec = Fixtures.vectorAdd().copy(processors = listOf(rtl))
        assertEquals(emptyList(), SpecValidator.validateTarget(spec, Processors.RTL, "systemverilog"))
        assertTrue(SpecValidator.validateTarget(spec, Processors.GPU_CUDA, "cuda").single().contains("declares: rtl"))
        assertTrue(SpecValidator.validateTarget(spec, Processors.RTL, "cuda").single().contains("allowed: systemverilog"))
    }
}

class RtlSignatureTest {
    private val sig = Fixtures.vectorAdd().signature

    @Test
    fun `memory mode gives every array its ports`() {
        assertEquals(
            """
            module solve (
                input  logic clk,
                input  logic start,
                output logic done,
                output logic [31:0] A_addr,
                input  logic [31:0] A_data,
                output logic [31:0] B_addr,
                input  logic [31:0] B_data,
                output logic [31:0] C_addr,
                output logic [31:0] C_data,
                output logic C_write,
                input  logic signed [31:0] N
            );
            """.trimIndent(),
            RtlSignature.declaration(sig),
        )
    }

    @Test
    fun `more read ports become port arrays, INOUT gets read and write ports`() {
        val inout = sig.copy(params = sig.params.map { if (it.name == "C") it.copy(role = ParamRole.INOUT, type = DType.UINT8) else it })
        val ports = RtlSignature.ports(inout, RtlSettings(readPorts = 2)).associateBy { it.name }
        assertEquals("output logic [1:0] [31:0] A_addr", ports.getValue("A_addr").declaration())
        assertEquals("input  logic [1:0] [7:0] C_rdata", ports.getValue("C_rdata").declaration())
        assertEquals(64, ports.getValue("A_addr").width)
        assertEquals("output logic [7:0] C_wdata", ports.getValue("C_wdata").declaration())
        assertEquals(setOf("C_raddr", "C_rdata", "C_waddr", "C_wdata", "C_write"), ports.keys.filter { it.startsWith("C_") }.toSet())
    }

    @Test
    fun `stream mode has data and valid per array`() {
        val names = RtlSignature.ports(sig, RtlSettings(dataMode = RtlDataMode.STREAM)).map { it.name }
        assertEquals(listOf("clk", "start", "done", "A_data", "A_valid", "B_data", "B_valid", "C_data", "C_valid", "N"), names)
    }

    @Test
    fun `template is a module that keeps done low`() {
        val template = RtlSignature.template(sig)
        assertTrue(template.startsWith("module solve ("))
        assertTrue("assign done = 1'b0;" in template && template.trimEnd().endsWith("endmodule"))
    }
}

class CSignatureTest {
    @Test
    fun `renders a host declaration with fixed-width types`() {
        val sig = Fixtures.vectorAdd().signature
        assertEquals("void solve(const float* A, const float* B, float* C, int32_t N)", CSignature.declaration(sig))
    }
}

class CudaSignatureTest {
    @Test
    fun `renders declaration`() {
        val sig = Fixtures.vectorAdd().signature
        assertEquals("void solve(const float* A, const float* B, float* C, int N)", CudaSignature.declaration(sig))
    }
}

object Fixtures {
    fun vectorAdd() = ProblemSpec(
        id = "vector-add",
        signature = Signature(
            params = listOf(
                ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("B", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("C", DType.FLOAT32, ParamRole.OUTPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        ),
        generator = GeneratorSpec(source = GENERATOR),
    )

    @Language("Python")
    private const val GENERATOR = "def tests(): ...\ndef generate(test, gen, device): ...\ndef reference(test, inputs): ..."
}
