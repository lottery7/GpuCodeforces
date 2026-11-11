package dev.gpucodeforces.judge.executor.processor.gpucuda

import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.TimingMode
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.problem.PreparedProblem
import dev.gpucodeforces.judge.executor.problem.PreparedTest
import dev.gpucodeforces.judge.executor.run.CheckResult
import dev.gpucodeforces.judge.executor.run.OutputChecker
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.BoundedBuffer
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Proc
import dev.gpucodeforces.judge.executor.util.daemonThread
import dev.gpucodeforces.judge.executor.util.readBoundedLines
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

data class RunOutcome(
    val verdict: Verdict,
    val tests: List<TestResult>,
    val failedTest: Int? = null,
    val message: String? = null,
    val stats: GpuStats,
    /** The process had to be killed or crashed hard: probe the GPU before reusing it. */
    val needsHealthCheck: Boolean = false,
    /** Set for INTERNAL_ERROR: judge-side reason for logs. */
    val internalReason: String? = null,
    /** INTERNAL_ERROR caused by infrastructure that may work on another attempt/GPU. */
    val retryable: Boolean = false,
    /**
     * The process died or hung before the harness was ready. The solution's own code already runs then (static
     * initialisers), so it is the solution's fault unless the GPU turns out to be broken.
     */
    val solutionSuspected: Boolean = false,
)

/**
 * Runs a linked solution binary in the sandbox on one GPU, following the harness status protocol (see runtime.h),
 * enforcing the watchdog / memory limits and checking outputs as soon as each test finishes.
 *
 * The harness shares its process with the solution, so the solution can read the nonce and write status lines of
 * its own. Whatever it writes, a test only counts once this class has compared that test's output file with the
 * reference, and tests only count in order: lines that the harness could not have produced end the run.
 *
 * That protects the verdict, not the time: `time_ns` and `wall_ns` come from a status line the solution may have
 * written itself, and the clock runs inside its process. Reported times are bounded by the watchdog only and must
 * not be treated as verified, e.g. for a ranking by time.
 *
 * Every test is timed by the harness; the time limit applies to measured tests only (`measure` of the generator's
 * test), the wall limit to all of them.
 */
class HarnessRunner(private val props: ExecutorProperties, private val sandbox: Sandbox) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    fun run(
        problem: ProblemSpec,
        prepared: PreparedProblem,
        binary: Path,
        runDir: Path,
        slot: GpuSlot,
        /** The problem's limit for `gpu-cuda`, see [ProblemSpec.scoreLimit]. */
        timeLimitMs: Double,
        onTestStart: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): RunOutcome {
        val sig = problem.signature
        val timing = problem.timing.effectiveMode
        val limits = TimeLimits((timeLimitMs * 1e6).toLong(), problem.limits.wallTimeLimitMsFor(timeLimitMs))
        val wallNs = limits.wallMs * 1_000_000
        // Only the output directory is writable for the solution: the binary and the manifest stay out of its reach.
        val outDir = Files.createDirectories(runDir.resolve("out"))
        FileTrees.makeWritableByAll(outDir)
        val nonce = HexFormat.of().formatHex(ByteArray(12).also { random.nextBytes(it) })

        val hostArrays = sig.arrays.filter { it.memory == MemorySpace.HOST }
        val deviceArrays = sig.arrays.filter { it.memory == MemorySpace.DEVICE }
        val hostBytes = prepared.tests.maxOf { bytesOf(hostArrays, it) }
        val deviceBytes = prepared.tests.maxOf { bytesOf(deviceArrays, it) }
        val maxDeviceArray = prepared.tests.maxOf { t -> deviceArrays.maxOfOrNull { t.bytes(it) } ?: 0 }
        val hostLimit = ((problem.limits.memoryLimitMb + props.run.hostOverheadMb) shl 20) + hostBytes + maxDeviceArray
        val maxOutput = prepared.tests.maxOf { bytesOf(sig.outputs, it) }

        val spec = SandboxSpec(
            command = emptyList(),
            path = CUDA_PATH,
            workDir = runDir,
            readOnly = mapOf(prepared.inputsDir to "/data"),
            gpu = slot.access(),
            env = mapOf("CUDA_MODULE_LOADING" to "LAZY"),
            wallTimeLimit = totalBudget(problem, prepared, wallNs),
            memoryLimitBytes = hostLimit,
            maxFileBytes = maxOutput + (64L shl 20),
        )
        val manifest = runDir.resolve("manifest.txt")
        manifest.writeText(buildManifest(problem, prepared, spec, outDir, nonce, timing, limits))
        val command = listOf(sandbox.inside(spec, binary), sandbox.inside(spec, manifest))

        val gpuBaselineMb = slot.monitor?.latest()?.memoryUsedMb
        val gpuLimitMb = problem.limits.gpuMemoryLimitMb?.let { lim ->
            gpuBaselineMb?.let { it + lim + props.run.gpuOverheadMb + (deviceBytes shr 20) }
        }
        val stats = GpuStats()

        val process = sandbox.start(spec.copy(command = command))
        val session = Session(problem, prepared, process, runDir = runDir, outDir = outDir, limits, onTestStart)
        log.info(
            "Started solution on {} ({} tests, timing={}, TL={}ms, wall={}ms, host limit={}MB, gpu limit={}MB)",
            slot, prepared.tests.size, timing, timeLimitMs, limits.wallMs,
            hostLimit shr 20, gpuLimitMb ?: "-",
        )
        val stderr = BoundedBuffer(props.run.maxStderrBytes)
        val stderrThread = stderr.drain(process.errorStream, "solution-stderr")
        val lines = ArrayBlockingQueue<String>(STATUS_QUEUE)
        val reader = startStatusReader(process, "$nonce ", lines, session)
        val gpuListener: (GpuSample) -> Unit = { s ->
            stats.add(s)
            if (gpuLimitMb != null && s.memoryUsedMb > gpuLimitMb && session.verdict == null) {
                // a limit exists only when the baseline was sampled
                session.fail(Verdict.MEMORY_LIMIT_EXCEEDED, "GPU memory limit exceeded (${s.memoryUsedMb - gpuBaselineMb!!} MB used)", kill = true)
            }
        }
        slot.monitor?.addListener(gpuListener)
        val watch = startResourceWatch(process, hostLimit, outputLimit = 2 * maxOutput + OUTPUT_SLACK, session)
        try {
            session.loop(lines, cancelled)
        } finally {
            watch.interrupt()
            slot.monitor?.removeListener(gpuListener)
            // A harness that reported the end of the run exits by itself within milliseconds. Killing it instead
            // looks like a crash (exit 137) and costs a GPU health probe before the next submission.
            if (!session.killedByJudge) process.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
            if (process.isAlive) Proc.killTree(process)
            process.waitFor(10, TimeUnit.SECONDS)
            session.closed = true
            reader.join(2000)
            stderrThread.join(2000)
        }
        val exit = if (process.isAlive) -1 else process.exitValue()
        return session.finish(exit, stderr.text(), stats)
    }

    /** Time limit of a measured test, and the wall limit of every test. */
    private data class TimeLimits(val timeNs: Long, val wallMs: Long)

    private fun PreparedTest.bytes(p: ParamSpec): Long = counts.getValue(p.name) * p.type.bytes

    private fun bytesOf(params: List<ParamSpec>, t: PreparedTest) = params.sumOf { t.bytes(it) }

    private fun ioAllowance(bytes: Long): Duration =
        Duration.ofMillis(2000 + bytes * 1000 / (props.run.ioMbPerSecond shl 20))

    private fun totalBudget(problem: ProblemSpec, prepared: PreparedProblem, wallNs: Long): Duration {
        val sig = problem.signature
        var ms = props.run.startupTimeout.toMillis()
        prepared.tests.forEach { t ->
            val perRun = wallNs / 1_000_000 + ioAllowance(bytesOf(sig.arrays, t)).toMillis()
            ms += perRun * allowedRuns(problem, t.index)
        }
        return Duration.ofMillis(ms)
    }

    /** Calls of `solve` the harness makes for a test: warm-ups happen on the first test only. */
    private fun allowedRuns(problem: ProblemSpec, test: Int): Int =
        problem.timing.maxRuns + if (test == 1) problem.timing.warmupRuns else 0

    private fun buildManifest(
        problem: ProblemSpec, prepared: PreparedProblem, spec: SandboxSpec, outDir: Path, nonce: String, timing: TimingMode,
        limits: TimeLimits,
    ): String = buildString {
        val sig = problem.signature
        appendLine("nonce $nonce")
        appendLine("timing ${if (timing == TimingMode.GPU_KERNELS) "kernels" else "wall"}")
        appendLine("warmup ${problem.timing.warmupRuns}")
        appendLine("max_runs ${problem.timing.maxRuns}")
        appendLine("time_limit_ns ${limits.timeNs}")
        appendLine("wall_limit_ns ${limits.wallMs * 1_000_000}")
        appendLine("grey_factor $GREY_FACTOR")
        appendLine("params ${sig.params.size}")
        for (t in prepared.tests) {
            appendLine("test ${t.index}")
            appendLine("measure ${if (t.spec.measure) 1 else 0}")
            sig.params.forEachIndexed { pi, p ->
                if (p.role == ParamRole.SCALAR) {
                    val v = t.spec.scalars.getValue(p.name)
                    appendLine("scalar $pi ${if (p.type.floating) v.toDouble().toString() else v.toLong().toString()}")
                } else {
                    val input = t.inputs[p.name]?.let { sandbox.inside(spec, it) } ?: "-"
                    val output = if (p.role == ParamRole.INPUT) "-" else sandbox.inside(spec, outFile(outDir, t.index, p.name))
                    appendLine("array $pi ${t.bytes(p)} $input $output")
                }
            }
            appendLine("end")
        }
    }

    private fun outFile(outDir: Path, test: Int, param: String) = outDir.resolve("t${test}_$param.bin")

    /**
     * Forwards the status lines (those starting with the nonce) to the session. Everything else the solution
     * prints is dropped here, and a full queue stalls the solution's stdout instead of growing.
     */
    private fun startStatusReader(process: Process, prefix: String, lines: BlockingQueue<String>, session: Session): Thread =
        daemonThread("solution-status") {
            fun put(line: String) {
                while (!session.closed && !lines.offer(line, 100, TimeUnit.MILLISECONDS)) {
                    // the session is busy checking outputs, or gone: keep trying until it says so
                }
            }
            try {
                readBoundedLines(process.inputStream, MAX_STATUS_LINE) { if (it.startsWith(prefix)) put(it.removePrefix(prefix)) }
            } catch (_: IOException) {
                // stdout is closed when the process is killed; EOF below ends the session either way
            } finally {
                put(EOF)
            }
        }

    /**
     * Polls what the sandboxed process tree uses and kills it on overflow (works without cgroups, also on WSL2):
     * RSS against the memory limit, and the output directory, which lives in RAM as well and is the only place
     * the solution can write to.
     */
    private fun startResourceWatch(process: Process, memoryLimit: Long, outputLimit: Long, session: Session): Thread =
        daemonThread("solution-watch") {
            try {
                while (process.isAlive && session.verdict == null) {
                    val pids = listOf(process.pid()) + process.toHandle().descendants().map { it.pid() }.toList()
                    val rss = pids.sumOf { rssBytes(it) }
                    session.peakRss = maxOf(session.peakRss, rss)
                    val written = FileTrees.regularBytes(session.outDir, MAX_OUTPUT_ENTRIES)
                    when {
                        rss > memoryLimit ->
                            session.fail(Verdict.MEMORY_LIMIT_EXCEEDED, "Memory limit exceeded (${rss shr 20} MB RSS)", kill = true)
                        written == null || written > outputLimit ->
                            session.fail(Verdict.RUNTIME_ERROR, "Runtime error on test ${session.currentTest} (output limit exceeded)", kill = true)
                    }
                    Thread.sleep(props.run.samplingInterval.toMillis())
                }
            } catch (_: InterruptedException) {
                // interrupted by run() when the solution finished: stop watching
            }
        }

    /** RSS of one process; 0 if it exited between listing the tree and reading /proc (or /proc is absent, e.g. macOS dev). */
    private fun rssBytes(pid: Long): Long = try {
        Path.of("/proc/$pid/statm").readText().split(' ')[1].toLong() * PAGE_SIZE
    } catch (_: IOException) {
        0L
    }

    private enum class Phase { STARTING, LOADING, RUNNING, SAVING }

    /** State of one solution process; [fail] may be called from watcher threads. */
    private inner class Session(
        val problem: ProblemSpec,
        val prepared: PreparedProblem,
        val process: Process,
        val runDir: Path,
        val outDir: Path,
        val limits: TimeLimits,
        val onTestStart: (Int) -> Unit,
    ) {
        @Volatile var verdict: Verdict? = null
        @Volatile var message: String? = null
        @Volatile var debug: String? = null
        @Volatile var killedByJudge = false

        /** The process was killed in a state that may have left the GPU unusable (hung, out of memory). */
        @Volatile var gpuSuspect = false
        @Volatile var peakRss = 0L
        @Volatile var currentTest = 1

        /** Set once nobody reads the status queue any more. */
        @Volatile var closed = false
        var internalReason: String? = null
        var retryable = false
        var solutionSuspected = false
        var ready = false
        var done = false
        var phase = Phase.STARTING
        var deadline = System.nanoTime() + props.run.startupTimeout.toNanos()

        /** `load` and `run` lines seen for [currentTest]. */
        var stepsOfCurrentTest = 0
        val results = mutableListOf<TestResult>()
        val sig = problem.signature
        val tlNs = limits.timeNs
        val wallNs = limits.wallMs * 1_000_000

        @Synchronized
        fun fail(v: Verdict, msg: String, kill: Boolean, gpuSuspect: Boolean = kill) {
            if (verdict != null) return
            verdict = v
            message = msg
            if (kill) {
                killedByJudge = true
                this.gpuSuspect = gpuSuspect
                Proc.killTree(process)
            }
        }

        private fun internalError(reason: String, kill: Boolean, solutionSuspected: Boolean = false) {
            internalReason = reason
            this.solutionSuspected = solutionSuspected
            retryable = true
            fail(Verdict.INTERNAL_ERROR, "Judging failed", kill)
        }

        /** The line carries the nonce but the harness cannot have written it: the solution did. */
        private fun forged(line: String) {
            log.info("Solution wrote to the status channel: {}", line.take(200))
            fail(Verdict.RUNTIME_ERROR, "Runtime error on test $currentTest (the solution interfered with the judge)", kill = true)
        }

        fun loop(lines: BlockingQueue<String>, cancelled: () -> Boolean) {
            while (true) {
                if (verdict != null) return
                if (cancelled()) {
                    fail(Verdict.CANCELLED, "Cancelled", kill = true)
                    return
                }
                val waitMs = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(0, 100)
                val line = lines.poll(waitMs, TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (System.nanoTime() > deadline) onDeadline()
                    continue
                }
                if (line == EOF) return
                handle(line)
                if (done) return
            }
        }

        private fun onDeadline() {
            when (phase) {
                Phase.RUNNING -> fail(
                    Verdict.TIME_LIMIT_EXCEEDED,
                    "Time limit exceeded on test $currentTest (killed after ${limits.wallMs} ms)",
                    kill = true,
                )
                Phase.STARTING -> internalError(
                    "harness did not become ready within ${props.run.startupTimeout.seconds}s (CUDA context creation stuck?)",
                    kill = true, solutionSuspected = true,
                )
                else -> fail(Verdict.TIME_LIMIT_EXCEEDED, "Time limit exceeded on test $currentTest (solution stalled the judge)", kill = true)
            }
            log.info("Watchdog fired in phase {} on test {}", phase, currentTest)
        }

        private fun testBytes(index: Int) = prepared.tests.getOrNull(index - 1)?.let { bytesOf(sig.arrays, it) } ?: 0L

        /** Index from a status line, if it names the test the harness has to be working on now. */
        private fun nextTestIndex(parts: List<String>): Int? =
            parts.getOrNull(1)?.toIntOrNull()?.takeIf { it == results.size + 1 && it <= prepared.tests.size }

        private fun handle(line: String) {
            val parts = line.split(' ')
            val now = System.nanoTime()
            when (parts[0]) {
                "ready" -> {
                    if (ready) return forged(line)
                    ready = true
                    phase = Phase.LOADING
                    deadline = now + ioAllowance(testBytes(1)).toNanos()
                }
                "load", "run" -> {
                    val idx = nextTestIndex(parts) ?: return forged(line)
                    // every call of solve is announced by one load and one run; more would keep resetting the watchdog
                    if (!ready || ++stepsOfCurrentTest > 2 * allowedRuns(problem, idx)) return forged(line)
                    currentTest = idx
                    if (parts[0] == "load") {
                        phase = Phase.LOADING
                        deadline = now + ioAllowance(testBytes(idx)).toNanos()
                    } else {
                        if (parts.getOrNull(2) == "0") onTestStart(idx)
                        phase = Phase.RUNNING
                        deadline = now + wallNs + GRACE_NS
                    }
                }
                "test" -> onTestLine(line, parts)
                // Once the harness is running the tests, a "fatal" is the solution's doing (or its forgery): the
                // judge must not answer it with retries on the GPU.
                "fatal" -> {
                    val reason = "harness fatal error: ${parts.drop(1).joinToString(" ")}"
                    if (ready) {
                        log.info("{}", reason)
                        fail(Verdict.RUNTIME_ERROR, "Runtime error on test $currentTest", kill = false)
                    } else {
                        internalError(reason, kill = false, solutionSuspected = true)
                    }
                }
                "done" -> if (results.size == prepared.tests.size) done = true else forged(line)
                else -> forged(line)
            }
        }

        private fun PreparedTest.result(verdict: Verdict, message: String? = null, debug: String? = null) =
            TestResult(index, spec.name, spec.sample, verdict, message = message, debug = debug)

        private fun onTestLine(line: String, parts: List<String>) {
            val idx = nextTestIndex(parts) ?: return forged(line)
            val test = prepared.tests[idx - 1]
            val rest = parts.drop(3).joinToString(" ")
            // After each of these lines the harness exits by itself, so the process is not killed.
            when (parts.getOrNull(2)) {
                "ok" -> {
                    val kv = parts.drop(3).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
                    // a result is only reported for a call of solve that was announced, i.e. timed by the watchdog
                    if (phase != Phase.RUNNING) return forged(line)
                    val t = kv["time_ns"]?.toLongOrNull() ?: return forged(line)
                    val w = kv["wall_ns"]?.toLongOrNull() ?: return forged(line)
                    phase = Phase.SAVING
                    deadline = System.nanoTime() + ioAllowance(testBytes(idx) + testBytes(idx + 1)).toNanos()
                    stepsOfCurrentTest = 0
                    val timeMs = t / 1e6
                    val wallMs = w / 1e6
                    val timed = TestResult(
                        idx, test.spec.name, test.spec.sample, Verdict.ACCEPTED, score = Score(timeMs, ScoreType.TIME_MS),
                        measured = test.spec.measure, wallTimeMs = wallMs, runs = kv["runs"]?.toIntOrNull(),
                    )
                    if ((test.spec.measure && t > tlNs) || w > wallNs) {
                        val what = if (test.spec.measure && t > tlNs) "%.1f ms".format(timeMs) else "wall %.1f ms".format(wallMs)
                        results += timed.copy(verdict = Verdict.TIME_LIMIT_EXCEEDED, message = "Time limit exceeded ($what)")
                        fail(Verdict.TIME_LIMIT_EXCEEDED, "Time limit exceeded on test $idx", kill = false)
                        return
                    }
                    val check = checkOutputs(test)
                    if (check == null) {
                        results += timed
                    } else {
                        val details = "${check.summary}; ${check.detail}"
                        // On a hidden test even the number of differing elements is an oracle: a solution can
                        // spoil k elements on purpose and read hidden input back through k, one submission at a time.
                        results += timed.copy(verdict = Verdict.WRONG_ANSWER, message = publicText(test, details, "Wrong answer"))
                        // The harness is already loading the next test, so it has to be killed. It had just reported
                        // normally and SIGKILL lets the driver tear the context down: no reason to suspect the GPU
                        // and pay for a health probe after the most common verdict.
                        fail(Verdict.WRONG_ANSWER, "Wrong answer on test $idx", kill = true, gpuSuspect = false)
                    }
                }
                "runtime_error" -> {
                    results += test.result(Verdict.RUNTIME_ERROR, publicText(test, "CUDA error: $rest", "CUDA error"))
                    fail(Verdict.RUNTIME_ERROR, "Runtime error on test $idx", kill = false)
                }
                "alloc_failed" -> {
                    results += test.result(Verdict.MEMORY_LIMIT_EXCEEDED, publicText(test, rest, "Allocation failed"))
                    fail(Verdict.MEMORY_LIMIT_EXCEEDED, "Memory limit exceeded on test $idx", kill = false)
                }
                else -> forged(line)
            }
        }

        /**
         * [details] may carry hidden data: reference values, or anything at all, since the solution can write
         * status lines itself and every test's input is readable to it. On a hidden test the contestant gets the
         * [fixed] text and the details go to this executor's log only - not even into the event stream.
         */
        private fun publicText(test: PreparedTest, details: String, fixed: String): String {
            if (test.spec.sample) return details
            log.info("Hidden test {} '{}': {}", test.index, test.spec.name, details.take(4000))
            return fixed
        }

        private fun checkOutputs(test: PreparedTest): CheckResult? {
            try {
                for (p in sig.outputs) {
                    val r = OutputChecker.check(p.name, p.type, actual = outFile(outDir, test.index, p.name), expected = test.expected.getValue(p.name), problem.checker)
                    if (!r.ok) return r
                }
                return null
            } finally {
                for (p in sig.outputs) outFile(outDir, test.index, p.name).deleteIfExists()
            }
        }

        fun finish(exitCode: Int, stderr: String, stats: GpuStats): RunOutcome {
            if (verdict == null) {
                val sandboxLog = Sandbox.logTail(runDir)
                when {
                    done -> verdict = Verdict.ACCEPTED
                    // nsjail reports its own failures in its log, which the solution cannot write to
                    !ready && exitCode == 255 && sandboxLog != null -> {
                        internalError("sandbox failed to start (exit 255): $sandboxLog", kill = false)
                    }
                    !ready && exitCode in 119..121 -> {
                        internalError("harness failed before start (exit $exitCode): ${stderr.takeLast(1000)}", kill = false, solutionSuspected = true)
                    }
                    else -> {
                        val how = describeExit(exitCode)
                        val oom = exitCode == 137 && peakRss > 0
                        verdict = if (oom && !killedByJudge && props.sandbox.useCgroupsV2) Verdict.MEMORY_LIMIT_EXCEEDED else Verdict.RUNTIME_ERROR
                        message = "${if (verdict == Verdict.MEMORY_LIMIT_EXCEEDED) "Memory limit exceeded" else "Runtime error"} on test $currentTest ($how)"
                        debug = how
                    }
                }
            }
            val v = verdict!!
            val failedOnTest = v !in NOT_ABOUT_A_TEST
            // Failures detected outside a test result line (watchdog, memory watch, crash) still get a test entry.
            val t = prepared.tests.getOrNull(currentTest - 1)
            if (t != null && failedOnTest && results.none { it.verdict != Verdict.ACCEPTED } && results.none { it.index == currentTest }) {
                results += t.result(v, debug ?: message)
            }
            val failed = results.lastOrNull()?.takeIf { it.verdict != Verdict.ACCEPTED }?.index
                ?: if (failedOnTest) currentTest else null
            // Every test's input is mounted for the whole run, so stderr of a sample test can carry hidden data: it
            // is attached to the results only when the problem has nothing to hide, and logged here otherwise.
            val stderrTail = stderr.trim().takeLast(2000)
            val stderrIsPublic = prepared.tests.all { it.spec.sample }
            if (!stderrIsPublic && stderrTail.isNotEmpty()) log.info("stderr of the solution:\n{}", stderrTail)
            val finalResults = results.map { r ->
                if (r.verdict == Verdict.ACCEPTED || stderrTail.isEmpty() || !stderrIsPublic) r
                else r.copy(debug = "stderr:\n$stderrTail")
            }
            val crashed = !killedByJudge && (exitCode > 128 || exitCode < 0)
            val hardStop = gpuSuspect || crashed || v == Verdict.RUNTIME_ERROR || v == Verdict.INTERNAL_ERROR
            return RunOutcome(v, finalResults, failed, message, stats, needsHealthCheck = hardStop, internalReason = internalReason, retryable = retryable, solutionSuspected = solutionSuspected)
        }
    }

    companion object {
        private const val EOF = "\u0000EOF"
        private const val GREY_FACTOR = 1.25
        private const val GRACE_NS = 1_000_000_000L
        private const val EXIT_GRACE_MS = 2000L
        private const val PAGE_SIZE = 4096L
        private const val MAX_STATUS_LINE = 8192
        private const val STATUS_QUEUE = 256

        /** Besides the outputs of the test being checked and of the next one. */
        private const val OUTPUT_SLACK = 64L shl 20
        private const val MAX_OUTPUT_ENTRIES = 4096

        private val NOT_ABOUT_A_TEST = setOf(Verdict.ACCEPTED, Verdict.INTERNAL_ERROR, Verdict.CANCELLED)

        fun describeExit(code: Int): String = when {
            code < 0 -> "killed"
            code > 128 -> "terminated by signal ${signalName(code - 128)}"
            code == 0 -> "the process exited before finishing all tests"
            else -> "exit code $code"
        }

        private fun signalName(sig: Int) = when (sig) {
            4 -> "SIGILL"
            6 -> "SIGABRT"
            7 -> "SIGBUS"
            8 -> "SIGFPE"
            9 -> "SIGKILL"
            11 -> "SIGSEGV"
            15 -> "SIGTERM"
            else -> "$sig"
        }
    }
}
