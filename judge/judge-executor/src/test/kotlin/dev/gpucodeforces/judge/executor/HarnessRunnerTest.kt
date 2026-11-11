package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.LimitsSpec
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.TimingSpec
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.config.GpuSlotConfig
import dev.gpucodeforces.judge.executor.config.RunConfig
import dev.gpucodeforces.judge.executor.processor.gpucuda.GpuSlot
import dev.gpucodeforces.judge.executor.problem.GeneratedTest
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.processor.gpucuda.HarnessRunner
import dev.gpucodeforces.judge.executor.processor.gpucuda.RunOutcome
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import dev.gpucodeforces.judge.executor.util.readBoundedLines
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.io.TempDir

/**
 * Drives HarnessRunner with a fake "solution binary" (a Python script speaking the harness protocol), so the
 * watchdog, verdict logic and checker are tested without a GPU.
 */
class HarnessRunnerTest {
    @TempDir
    lateinit var tmp: Path

    private val problem = ProblemSpec(
        id = "vector-add",
        signature = Signature(
            params = listOf(
                ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("B", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("C", DType.FLOAT32, ParamRole.OUTPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        ),
        generator = GeneratorSpec(source = "unused"),
        limits = LimitsSpec(timeLimitMs = 200, wallTimeLimitMs = 500),
        timing = TimingSpec(warmupRuns = 0, maxRuns = 1),
    )

    private val declared = listOf(GeneratedTest("sample", sample = true, scalars = mapOf("N" to 4)), GeneratedTest("random", scalars = mapOf("N" to 8)))

    private val props = ExecutorProperties(run = RunConfig(startupTimeout = Duration.ofSeconds(10), samplingInterval = Duration.ofMillis(20)))

    private fun f32(path: Path, values: FloatArray) {
        Files.createDirectories(path.parent)
        val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { b.putFloat(it) }
        Files.write(path, b.array())
    }

    private fun prepared(declared: List<GeneratedTest>): PreparedProblem {
        val dir = tmp.resolve("problem")
        val tests = declared.mapIndexed { i, t ->
            val idx = i + 1
            val n = t.scalars.getValue("N").toInt()
            val a = FloatArray(n) { it.toFloat() + idx }
            val b = FloatArray(n) { 0.5f * it }
            f32(dir.resolve("inputs/t$idx/A.bin"), a)
            f32(dir.resolve("inputs/t$idx/B.bin"), b)
            f32(dir.resolve("expected/t$idx/C.bin"), FloatArray(n) { a[it] + b[it] })
            PreparedTest(
                idx, t, SpecValidator.elementCounts(problem.signature, t.scalars),
                mapOf("A" to dir.resolve("inputs/t$idx/A.bin"), "B" to dir.resolve("inputs/t$idx/B.bin")),
                mapOf("C" to dir.resolve("expected/t$idx/C.bin")),
            )
        }
        return PreparedProblem("hash", dir, dir.resolve("inputs"), tests)
    }

    /** Fake solution. `behaviour(test)` returns: ok | wrong | slow | hang | crash | cuda_error | exit. */
    private fun fakeBinary(behaviour: String): Path {
        val script = tmp.resolve("solution.bin")
        @Language("Python")
        val source = """
            #!/usr/bin/env python3
            import os, struct, sys, time
            print("ready garbage that must be ignored", flush=True)
            mode = "$behaviour"
            nonce = None; tests = []; cur = None
            for line in open(sys.argv[1]):
                t = line.split()
                if not t: continue
                if t[0] == "nonce": nonce = t[1]
                elif t[0] == "test": cur = {"idx": int(t[1]), "arrays": []}; tests.append(cur)
                elif t[0] == "array": cur["arrays"].append((int(t[1]), int(t[2]), t[3], t[4]))
            def st(s): print(nonce + " " + s, flush=True)
            st("ready")
            for tc in tests:
                i = tc["idx"]
                st("load %d 0" % i); st("run %d 0" % i)
                behaviour = mode.split(",")[i - 1] if "," in mode else mode
                if behaviour == "hang": time.sleep(60)
                # The runner consumes the exit status; a real signal also triggers desktop crash reports.
                if behaviour == "crash": os._exit(128 + 11)
                if behaviour == "exit": sys.exit(0)
                if behaviour == "cuda_error": st("test %d runtime_error cudaErrorIllegalAddress (an illegal memory access was encountered)" % i); sys.exit(0)
                if behaviour == "replay": st("test 1 ok runs 1 time_ns 1 wall_ns 1"); time.sleep(60)
                a = b = None
                for (p, nbytes, inp, out) in tc["arrays"]:
                    if p == 0: a = struct.unpack("<%df" % (nbytes // 4), open(inp, "rb").read()); reference = inp.replace("/inputs/", "/expected/").replace("A.bin", "C.bin")
                    if p == 1: b = struct.unpack("<%df" % (nbytes // 4), open(inp, "rb").read())
                    if p == 2: outp = out
                c = [x + y for x, y in zip(a, b)]
                if behaviour == "wrong": c[1] += 1.0
                if behaviour == "link": os.symlink(reference, outp)
                else: open(outp, "wb").write(struct.pack("<%df" % len(c), *c))
                t_ns = 300000000 if behaviour == "slow" else 1234567
                st("test %d ok runs 1 time_ns %d wall_ns %d" % (i, t_ns, t_ns))
            st("done")
            """.trimIndent() + "\n"
        script.writeText(source)
        script.toFile().setExecutable(true)
        return script
    }

    private fun run(behaviour: String, tests: List<GeneratedTest> = declared): RunOutcome {
        val runDir = Files.createDirectories(tmp.resolve("run"))
        val binary = fakeBinary(behaviour)
        Files.move(binary, runDir.resolve("solution.bin"))
        val progress = mutableListOf<Int>()
        val outcome = HarnessRunner(props, DirectSandbox()).run(
            problem, prepared(tests), runDir.resolve("solution.bin"), runDir, GpuSlot(GpuSlotConfig(0)), problem.limits.timeLimitMs.toDouble(),
            { progress += it }, { false },
        )
        if (outcome.verdict == Verdict.ACCEPTED) assertEquals(listOf(1, 2), progress)
        return outcome
    }

    @Test
    fun accepted() {
        val o = run("ok")
        assertEquals(Verdict.ACCEPTED, o.verdict, o.toString())
        assertEquals(2, o.tests.size)
        assertEquals(Score(1.234567, ScoreType.TIME_MS), o.tests[0].score)
        assertEquals(false, o.needsHealthCheck)
    }

    @Test
    fun `wrong answer on hidden test hides values`() {
        val o = run("ok,wrong")
        assertEquals(Verdict.WRONG_ANSWER, o.verdict)
        assertEquals(2, o.failedTest)
        assertEquals("Wrong answer", o.tests[1].message)
        assertEquals(null, o.tests[1].debug, "what a hidden test reveals stays in the executor's log")
        assertEquals(false, o.needsHealthCheck, "a wrong answer is no reason to probe the GPU")
    }

    @Test
    fun `wrong answer on sample shows details`() {
        val o = run("wrong")
        assertEquals(1, o.failedTest)
        assertTrue(o.tests[0].message!!.contains("C[1]: expected 2.5, got 3.5"), o.tests[0].message)
    }

    @Test
    fun `reported time over the limit`() {
        val o = run("ok,slow")
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, o.verdict)
        assertEquals(2, o.failedTest)
        assertEquals(300.0, o.tests[1].score!!.value, 1e-9)
    }

    @Test
    fun `the time limit does not apply to a test that is not measured`() {
        val o = run("ok,slow", declared.mapIndexed { i, t -> if (i == 1) t.copy(measure = false) else t })
        assertEquals(Verdict.ACCEPTED, o.verdict, o.toString())
        assertEquals(listOf(true, false), o.tests.map { it.measured })
        assertEquals(300.0, o.tests[1].score!!.value, 1e-9)
    }

    @Test
    fun `hung solution is killed by the watchdog`() {
        val started = System.nanoTime()
        val o = run("ok,hang")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, o.verdict)
        assertEquals(2, o.failedTest)
        assertTrue(o.needsHealthCheck)
        assertTrue(ms < 10_000, "watchdog took ${ms}ms")
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, o.tests[1].verdict)
        assertTrue(o.tests[1].message!!.contains("killed after 500 ms"), o.tests[1].message)
    }

    @Test
    fun `crash and CUDA errors are runtime errors`() {
        val crash = run("crash")
        assertEquals(Verdict.RUNTIME_ERROR, crash.verdict)
        assertNotNull(crash.tests.single().message).let { assertTrue("SIGSEGV" in it, it) }
        assertTrue(crash.needsHealthCheck)

        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        Files.createDirectories(tmp)
        val cuda = run("ok,cuda_error")
        assertEquals(Verdict.RUNTIME_ERROR, cuda.verdict)
        assertEquals(2, cuda.failedTest)
        // test 2 is hidden: the text after runtime_error could be anything the solution chose to write
        assertEquals("CUDA error", cuda.tests[1].message)
    }

    @Test
    fun `solution exiting early is a runtime error`() {
        val o = run("ok,exit")
        assertEquals(Verdict.RUNTIME_ERROR, o.verdict)
        assertEquals(2, o.failedTest)
    }

    @Test
    fun `reporting an earlier test again does not count as passing the next one`() {
        val o = run("ok,replay")
        assertEquals(Verdict.RUNTIME_ERROR, o.verdict)
        assertEquals(2, o.failedTest)
        assertTrue("interfered with the judge" in o.message!!, o.message)
        assertEquals(listOf(Verdict.ACCEPTED, Verdict.RUNTIME_ERROR), o.tests.map { it.verdict })
    }

    @Test
    fun `output that is a link to the reference answer is a wrong answer`() {
        val o = run("link")
        assertEquals(Verdict.WRONG_ANSWER, o.verdict)
        assertEquals("C: output was not produced; ", o.tests.single().message)
    }

    @Test
    fun `status lines are bounded however long the solution makes them`() {
        val input = ("first\n" + "x".repeat(100_000) + "\nsecond\n" + "y".repeat(50_000)).byteInputStream()
        val lines = mutableListOf<String>()
        readBoundedLines(input, 1000) { lines += it }
        assertEquals(listOf("first", "second"), lines)
    }
}
