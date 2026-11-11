package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.JudgeJob
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Languages
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.CoralNpuConfig
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.config.PathsConfig
import dev.gpucodeforces.judge.executor.config.PrepareConfig
import dev.gpucodeforces.judge.executor.config.ToolchainConfig
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuBackend
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuBuilder
import dev.gpucodeforces.judge.executor.processor.coralnpu.CoralNpuToolchain
import dev.gpucodeforces.judge.executor.problem.PrepareRuntime
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Judges the solutions of `examples/vector_add_coral` with the real Renode and bare-metal cross compilers.
 * `CORAL_RENODE` is Renode's launcher, `JUDGE_PYTHON` a Python with torch; the compilers default to
 * riscv64-unknown-elf-gcc/g++ in /usr/bin (with picolibc). No sandbox: the tools run directly.
 */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoralNpuEmulatorTest {
    @TempDir
    lateinit var tmp: Path

    private val example = Path.of("../problems/examples/vector_add_coral")
    private val problem: ProblemSpec by lazy {
        val mapper = jacksonObjectMapper()
        val tree = mapper.readTree(example.resolve("problem.json").readText()) as ObjectNode
        tree.putObject("generator").put("source", example.resolve("problem.py").readText())
        mapper.treeToValue(tree, ProblemSpec::class.java)
    }

    private val backend: CoralNpuBackend by lazy {
        val props = ExecutorProperties(
            processors = listOf(Processors.CORAL_NPU),
            paths = PathsConfig(work = tmp.resolve("work"), cache = tmp.resolve("cache"), pytools = Path.of("../pytools").toAbsolutePath()),
            toolchain = ToolchainConfig(python = System.getenv("JUDGE_PYTHON") ?: "python3"),
            prepare = PrepareConfig(device = "cpu"),
            coralNpu = CoralNpuConfig(
                renode = Path.of(System.getenv("CORAL_RENODE") ?: error("set CORAL_RENODE to Renode's launcher")),
                platform = Path.of("../coralnpu/platform.resc").toAbsolutePath().normalize(),
                // the example's limit is far below this; a solution that never returns fails fast
                maxInstructions = 100_000_000,
            ),
        )
        val sandbox = DirectSandbox()
        val toolchain = CoralNpuToolchain.probe(props.coralNpu)
        CoralNpuBackend(props, toolchain, CoralNpuBuilder(props, toolchain, sandbox), ProblemStore(props, null, PrepareRuntime(props), sandbox), sandbox)
    }

    private var runs = 0

    private val hooks = object : JudgeHooks {
        override fun status(status: SubmissionStatus) {}
        override fun progress(current: Int, total: Int) {}
        override fun cancelled() = false
    }

    private fun judge(solution: String): JudgeResult {
        val file = example.resolve("solutions/$solution")
        val language = if (file.extension == "cpp") Languages.CPP else Languages.C
        val job = JudgeJob(solution, problem, file.readText(), Processors.CORAL_NPU, language, Instant.now(), solution)
        return backend.judge(job, Files.createDirectories(tmp.resolve("runs").resolve("run-${++runs}")), hooks)
    }

    @Test
    fun `accepted solutions in both languages, the vector one with far fewer instructions, the same score on a resubmit`() {
        val results = listOf("accepted.c", "accepted_rvv.c", "accepted.cpp").associateWith { solution ->
            judge(solution).also { result ->
                assertEquals(Verdict.ACCEPTED, result.verdict, "$solution: $result")
                assertEquals(ScoreType.INSTRUCTIONS, assertNotNull(result.score).type)
            }
        }
        val scores = results.mapValues { it.value.score!!.value }
        // 2000 elements: one per iteration against 32 per vector instruction
        assertTrue(scores.getValue("accepted_rvv.c") < scores.getValue("accepted.c") / 10, scores.toString())
        assertEquals(scores.getValue("accepted.c"), judge("accepted.c").score!!.value)
        assertEquals(results.getValue("accepted_rvv.c").tests.map { it.score }, judge("accepted_rvv.c").tests.map { it.score })
    }

    @Test
    fun `every other example solution gets its verdict`() {
        val expected = mapOf(
            "wrong_answer.c" to Verdict.WRONG_ANSWER,
            "crash.c" to Verdict.RUNTIME_ERROR,
            "trap.c" to Verdict.RUNTIME_ERROR,
            "hang.c" to Verdict.TIME_LIMIT_EXCEEDED,
            "compile_error.c" to Verdict.COMPILATION_ERROR,
            "code_too_big.c" to Verdict.COMPILATION_ERROR,
            "data_too_big.c" to Verdict.MEMORY_LIMIT_EXCEEDED,
        )
        val actual = expected.keys.associateWith { judge(it) }
        assertEquals(expected, actual.mapValues { it.value.verdict }, actual.toString())
        assertContains(actual.getValue("crash.c").tests.first().debug!!, "FAULT")
        assertContains(actual.getValue("trap.c").tests.first().debug!!, "illegal instruction")
        assertContains(actual.getValue("code_too_big.c").compileLog!!, "ITCM")
        // the arrays of the three smaller tests still fit next to the 16 KB of the solution's own data
        assertEquals(4, actual.getValue("data_too_big.c").failedTest)
    }
}
