package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.processor.EmulatorOutcome
import dev.gpucodeforces.judge.executor.processor.EmulatorRun
import dev.gpucodeforces.judge.executor.processor.JudgeHooks
import dev.gpucodeforces.judge.executor.processor.SimulatorLaunch
import dev.gpucodeforces.judge.executor.processor.SimulatorOutcome
import dev.gpucodeforces.judge.executor.problem.GeneratedTest
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.io.TempDir

/**
 * Drives the emulator test loop with a fake simulator (a Python script that copies A to C and reports a score), so
 * the order of tests, output checks, the score limit and the host watchdog are tested without any emulator.
 */
class EmulatorRunTest {
    @TempDir
    lateinit var tmp: Path

    private val problem = ProblemSpec(
        id = "copy",
        signature = Signature(
            params = listOf(
                ParamSpec("A", DType.INT32, ParamRole.INPUT, MemorySpace.HOST, "N"),
                ParamSpec("C", DType.INT32, ParamRole.OUTPUT, MemorySpace.HOST, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        ),
        generator = GeneratorSpec(source = "unused"),
    )

    private val tests = listOf(
        GeneratedTest("sample", sample = true, scalars = mapOf("N" to 4)),
        GeneratedTest("small", scalars = mapOf("N" to 8)),
        GeneratedTest("tiny", scalars = mapOf("N" to 1), measure = false),
    )

    private fun i32(path: Path, values: IntArray) {
        Files.createDirectories(path.parent)
        val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { b.putInt(it) }
        Files.write(path, b.array())
    }

    private fun prepared(): PreparedProblem {
        val dir = tmp.resolve("problem")
        return PreparedProblem("hash", dir, dir.resolve("inputs"), tests.mapIndexed { i, t ->
            val idx = i + 1
            val a = IntArray(t.scalars.getValue("N").toInt()) { it * idx }
            i32(dir.resolve("inputs/t$idx/A.bin"), a)
            i32(dir.resolve("expected/t$idx/C.bin"), a)
            PreparedTest(
                idx, t, SpecValidator.elementCounts(problem.signature, t.scalars),
                mapOf("A" to dir.resolve("inputs/t$idx/A.bin")), mapOf("C" to dir.resolve("expected/t$idx/C.bin")),
            )
        })
    }

    /** `behaviours` per test: `<score>` copies A to C and reports the score; wrong, hang, fail, forge. */
    private fun run(vararg behaviours: String, limit: Double = 1000.0): Pair<EmulatorOutcome, List<Int>> {
        val simulator = tmp.resolve("simulator.py")
        @Language("Python")
        val source = """
            #!/usr/bin/env python3
            import shutil, sys, time
            behaviour, nonce, inp, out = sys.argv[1:5]
            if behaviour == "hang": time.sleep(60)
            if behaviour == "fail": print("done before start", file=sys.stderr); sys.exit(3)
            shutil.copy(inp, out)
            if behaviour == "wrong": open(out, "r+b").write(b"\x07")
            if behaviour == "forge": print("score 1"); behaviour = "1"
            if behaviour != "wrong": print(nonce + " score " + behaviour)
            else: print(nonce + " score 1")
            print(nonce + " metric cycles 10")
            """.trimIndent() + "\n"
        simulator.writeText(source)
        simulator.toFile().setExecutable(true)
        val progress = mutableListOf<Int>()
        val hooks = object : JudgeHooks {
            override fun status(status: SubmissionStatus) {}
            override fun progress(current: Int, total: Int) { progress += current }
            override fun cancelled() = false
        }
        val runDir = Files.createDirectories(tmp.resolve("run"))
        val outcome = EmulatorRun(DirectSandbox()).run(problem, prepared(), ScoreType.CYCLES, limit, runDir, hooks, Duration.ofSeconds(3)) { test, outDir, nonce ->
            val output = outDir.resolve("C.bin")
            val command = listOf(simulator.toString(), behaviours[test.index - 1], nonce, test.inputs.getValue("A").toString(), output.toString())
            SimulatorLaunch(SandboxSpec(command, workDir = outDir, wallTimeLimit = Duration.ofSeconds(10)), mapOf("C" to output)) { exit ->
                if (exit.exitCode != 0) return@SimulatorLaunch SimulatorOutcome.Failed(Verdict.RUNTIME_ERROR, "Runtime error", "exit ${exit.exitCode}")
                val values = exit.statusLines.associate { line -> line.split(' ').let { it.dropLast(1).joinToString(" ") to it.last().toDouble() } }
                SimulatorOutcome.Finished(values.getValue("score"), mapOf("cycles" to values.getValue("metric cycles")))
            }
        }
        return outcome to progress
    }

    @Test
    fun `the score is the maximum over measured tests`() {
        val (outcome, progress) = run("300", "700", "5000")
        assertEquals(Verdict.ACCEPTED, outcome.verdict, outcome.toString())
        assertEquals(Score(700.0, ScoreType.CYCLES), outcome.score, "the third test is not measured: its score neither counts nor is limited")
        assertEquals(mapOf("cycles" to 10.0), outcome.metrics)
        assertEquals(listOf(true, true, false), outcome.tests.map { it.measured })
        assertEquals(listOf(0, 1, 2, 3), progress)
    }

    @Test
    fun `a measured score over the limit names the unit`() {
        val (outcome, _) = run("300", "5000", "1")
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, outcome.verdict)
        assertEquals(2, outcome.failedTest)
        assertEquals("Cycle limit exceeded on test 2", outcome.message)
        assertEquals("Cycle limit exceeded (5000 > 1000)", outcome.tests[1].message)
        assertNull(outcome.score)
    }

    @Test
    fun `wrong output stops the run and hides hidden values`() {
        val (sample, _) = run("wrong", "1", "1")
        assertEquals(Verdict.WRONG_ANSWER, sample.verdict)
        assertTrue("C[0]" in sample.tests.single().message!!, sample.tests.single().message)
        val (hidden, _) = run("1", "wrong", "1")
        assertEquals(listOf(Verdict.ACCEPTED, Verdict.WRONG_ANSWER), hidden.tests.map { it.verdict })
        assertEquals("Wrong answer", hidden.tests[1].message)
    }

    @Test
    fun `failures of the contestant's code show details on samples only`() {
        val (sample, _) = run("fail", "1", "1")
        assertEquals(Verdict.RUNTIME_ERROR, sample.verdict)
        assertTrue("done before start" in sample.tests.single().debug!!, sample.tests.single().debug)
        val (hidden, _) = run("1", "fail", "1")
        assertNull(hidden.tests[1].debug)
    }

    @Test
    fun `a status line without the nonce does not count`() {
        val (outcome, _) = run("forge", "2", "1")
        assertEquals(Score(2.0, ScoreType.CYCLES), outcome.score)
    }

    @Test
    fun `the host watchdog ends a simulation that never finishes`() {
        val started = System.nanoTime()
        val (outcome, _) = run("1", "hang", "1")
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, outcome.verdict)
        assertEquals("Cycle limit exceeded (the simulation ran out of host time)", outcome.tests[1].message)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 15_000)
    }
}
