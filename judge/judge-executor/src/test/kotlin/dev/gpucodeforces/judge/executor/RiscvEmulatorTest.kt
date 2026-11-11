package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Languages
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.config.PathsConfig
import dev.gpucodeforces.judge.executor.config.PrepareConfig
import dev.gpucodeforces.judge.executor.config.RiscvConfig
import dev.gpucodeforces.judge.executor.config.ToolchainConfig
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvBackend
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvBuilder
import dev.gpucodeforces.judge.executor.processor.riscv.RiscvToolchain
import dev.gpucodeforces.judge.executor.problem.PrepareRuntime
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Judges the solutions of `examples/vector_add_riscv` with the real QEMU, plugin and cross compilers (Linux only:
 * QEMU's user mode). `RISCV_QEMU` is `qemu-riscv64`, `RISCV_PLUGIN` the built judge plugin, `JUDGE_PYTHON` a Python
 * with torch; the compilers default to riscv64-linux-gnu-gcc/g++ in /usr/bin. No sandbox: the tools run directly.
 */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiscvEmulatorTest {
    @TempDir
    lateinit var tmp: Path

    private val example = Path.of("../problems/examples/vector_add_riscv")
    private val problem: ProblemSpec by lazy {
        val mapper = jacksonObjectMapper()
        val tree = mapper.readTree(example.resolve("problem.json").readText()) as ObjectNode
        tree.putObject("generator").put("source", example.resolve("problem.py").readText())
        mapper.treeToValue(tree, ProblemSpec::class.java)
    }

    private val backend: RiscvBackend by lazy {
        val props = ExecutorProperties(
            processors = listOf(Processors.RISCV_RVV),
            paths = PathsConfig(work = tmp.resolve("work"), cache = tmp.resolve("cache"), pytools = Path.of("../pytools").toAbsolutePath()),
            toolchain = ToolchainConfig(python = System.getenv("JUDGE_PYTHON") ?: "python3"),
            prepare = PrepareConfig(device = "cpu"),
            riscv = RiscvConfig(
                qemu = Path.of(System.getenv("RISCV_QEMU") ?: error("set RISCV_QEMU to qemu-riscv64 built with plugins")),
                plugin = Path.of(System.getenv("RISCV_PLUGIN") ?: error("set RISCV_PLUGIN to the built judge plugin")),
                // the example's limit is far below this; a solution that never returns fails fast
                maxInstructions = 100_000_000,
            ),
        )
        val sandbox = DirectSandbox()
        val toolchain = RiscvToolchain.probe(props.riscv)
        RiscvBackend(props, toolchain, RiscvBuilder(props, toolchain, sandbox), ProblemStore(props, null, PrepareRuntime(props), sandbox), sandbox)
    }

    private var runs = 0

    private val hooks = object : JudgeHooks {
        override fun status(status: SubmissionStatus) {}
        override fun progress(current: Int, total: Int) {}
        override fun cancelled() = false
    }

    private fun judge(solution: String): JudgeResult {
        val file = example.resolve("solutions/$solution")
        val language = when (file.extension) {
            "cpp" -> Languages.CPP
            "S" -> Languages.ASM
            else -> Languages.C
        }
        val job = JudgeJob(solution, problem, file.readText(), Processors.RISCV_RVV, language, Instant.now(), solution)
        // The guest reads the paths of its files, and their lengths move its stack: under nsjail they are always the
        // same, here every run gets a directory name of the same length.
        val runDir = Files.createDirectories(tmp.resolve("runs").resolve("run-%04d".format(++runs)))
        return backend.judge(job, runDir, hooks)
    }

    @Test
    fun `accepted solutions in every language, the vector ones with far fewer instructions, the same score on a resubmit`() {
        val results = listOf("accepted.c", "accepted_rvv.c", "accepted.cpp", "accepted.S").associateWith { solution ->
            judge(solution).also { result ->
                assertEquals(Verdict.ACCEPTED, result.verdict, "$solution: $result")
                assertEquals(ScoreType.WEIGHTED_INSTRUCTIONS, assertNotNull(result.score).type)
            }
        }
        val instructions = results.mapValues { it.value.metrics.getValue("instructions") }
        // the largest test streams 1.2 MB through a 1 MB L2: the misses cost the same, the instructions do not
        assertTrue(instructions.getValue("accepted_rvv.c") < instructions.getValue("accepted.c") / 5, instructions.toString())
        assertTrue(instructions.getValue("accepted.S") < instructions.getValue("accepted.c") / 5, instructions.toString())
        assertTrue(results.getValue("accepted_rvv.c").score!!.value < results.getValue("accepted.c").score!!.value)
        val again = judge("accepted.c")
        assertEquals(results.getValue("accepted.c").tests.map { it.metrics }, again.tests.map { it.metrics })
        assertEquals(results.getValue("accepted.c").score, again.score)
    }

    @Test
    fun `every other example solution gets its verdict`() {
        val expected = mapOf(
            "wrong_answer.c" to Verdict.WRONG_ANSWER,
            "crash.c" to Verdict.RUNTIME_ERROR,
            "hang.c" to Verdict.TIME_LIMIT_EXCEEDED,
            "compile_error.c" to Verdict.COMPILATION_ERROR,
        )
        val actual = expected.keys.associateWith { judge(it) }
        assertEquals(expected, actual.mapValues { it.value.verdict }, actual.toString())
    }

    @Test
    fun `a forged report is refused and counted, the real score stands`() {
        val forged = judge("forge.c")
        assertEquals(Verdict.ACCEPTED, forged.verdict, forged.toString())
        assertTrue((forged.metrics["denied_syscalls"] ?: 0.0) >= 3, forged.metrics.toString())
        assertTrue(forged.score!!.value >= judge("accepted.c").score!!.value * 0.9, "${forged.score}")
    }
}
