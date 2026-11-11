package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.executor.processor.gpucuda.HarnessGenerator
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvHarness
import dev.gpucodeforces.judge.executor.processor.rtl.RtlTestbench
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The example problems must stay valid; their generated harnesses and testbenches are written to build/ for manual
 * checks with nvcc and Verilator.
 */
class ExampleProblemsTest {
    private val examples = Path.of("../problems/examples")
    private val mapper = jacksonObjectMapper()

    @Test
    fun `example problems are valid and produce harnesses`() {
        val dirs = examples.listDirectoryEntries().filter { it.isDirectory() }
        assertTrue(dirs.isNotEmpty())
        val out = Files.createDirectories(Path.of("build/generated-harness"))
        for (dir in dirs) {
            // problem.json holds everything except the generator source (kept in problem.py), like tools/judge_cli.py
            val tree = mapper.readTree(dir.resolve("problem.json").readText()) as ObjectNode
            tree.putObject("generator").put("source", dir.resolve("problem.py").readText())
            val spec = mapper.treeToValue(tree, ProblemSpec::class.java)
            assertEquals(emptyList(), SpecValidator.validateProblem(spec), dir.name)
            val processors = spec.declaredProcessors()
            if (processors.any { it.id == Processors.GPU_CUDA }) out.resolve("${dir.name}.cu").writeText(HarnessGenerator.generate(spec.signature))
            if (processors.any { it.id == Processors.RISCV_RVV }) out.resolve("${dir.name}.harness.c").writeText(RiscvHarness.generate(spec.signature))
            processors.find { it.id == Processors.RTL }?.let {
                out.resolve("${dir.name}.testbench.cpp").writeText(RtlTestbench.generate(spec.signature, it.rtl ?: RtlSettings()))
            }
        }
    }
}
