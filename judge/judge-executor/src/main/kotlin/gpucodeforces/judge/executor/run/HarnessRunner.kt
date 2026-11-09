package gpucodeforces.judge.executor.run

import gpucodeforces.judge.api.MemorySpace
import gpucodeforces.judge.api.ParamRole
import gpucodeforces.judge.api.ProblemSpec
import gpucodeforces.judge.api.TestResult
import gpucodeforces.judge.api.TimingMode
import gpucodeforces.judge.api.Verdict
import gpucodeforces.judge.executor.build.Builder
import gpucodeforces.judge.executor.config.ExecutorProperties
import gpucodeforces.judge.executor.gpu.GpuSample
import gpucodeforces.judge.executor.gpu.GpuSlot
import gpucodeforces.judge.executor.gpu.GpuStats
import gpucodeforces.judge.executor.problem.PreparedProblem
import gpucodeforces.judge.executor.problem.PreparedTest
import gpucodeforces.judge.executor.sandbox.GpuAccess
import gpucodeforces.judge.executor.sandbox.Sandbox
import gpucodeforces.judge.executor.sandbox.SandboxSpec
import gpucodeforces.judge.executor.util.BoundedBuffer
import gpucodeforces.judge.executor.util.Proc
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
)

/**
 * Runs a linked solution binary in the sandbox on one GPU, following the harness status protocol (see runtime.h),
 * enforcing the watchdog / memory limits and checking outputs as soon as each test finishes.
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
        onTestStart: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): RunOutcome {
        val sig = problem.signature
        val timing = problem.timing.effectiveMode(sig)
        val tlNs = problem.limits.timeLimitMs * 1_000_000
        val wallNs = problem.limits.effectiveWallTimeLimitMs * 1_000_000
        val outDir = Files.createDirectories(runDir.resolve("out"))
        Builder.makeWorldWritable(outDir)
        val nonce = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        val hostBytes = prepared.tests.maxOf { t -> bytesOf(sig.arrays.filter { it.memory == MemorySpace.HOST }, t) }
        val maxDeviceArray = prepared.tests.maxOf { t -> sig.arrays.filter { it.memory == MemorySpace.DEVICE }.maxOfOrNull { t.counts.getValue(it.name) * it.type.bytes } ?: 0 }
        val deviceBytes = prepared.tests.maxOf { t -> bytesOf(sig.arrays.filter { it.memory == MemorySpace.DEVICE }, t) }
        val hostLimit = ((problem.limits.memoryLimitMb + props.run.hostOverheadMb) shl 20) + hostBytes + maxDeviceArray
        val maxOutput = prepared.tests.maxOf { t -> bytesOf(sig.outputs, t) }

        val spec = SandboxSpec(
            command = emptyList(),
            workDir = runDir,
            readOnly = mapOf(prepared.inputsDir to "/data"),
            gpu = GpuAccess(slot.index, slot.config.deviceNode),
            env = mapOf("CUDA_MODULE_LOADING" to "LAZY"),
            wallTimeLimit = totalBudget(problem, prepared, wallNs),
            memoryLimitBytes = hostLimit,
            maxFileBytes = maxOutput + (64L shl 20),
        )
        val manifest = runDir.resolve("manifest.txt")
        manifest.writeText(buildManifest(problem, prepared, spec, outDir, nonce, timing, tlNs, wallNs))
        val command = listOf(sandbox.inside(spec, binary), sandbox.inside(spec, manifest))

        val gpuBaselineMb = slot.monitor?.latest()?.memoryUsedMb
        val gpuLimitMb = problem.limits.gpuMemoryLimitMb?.let { lim ->
            gpuBaselineMb?.let { it + lim + props.run.gpuOverheadMb + (deviceBytes shr 20) }
        }
        val stats = GpuStats()
        val session = Session(problem, prepared, runDir, outDir, nonce, timing, tlNs, wallNs, stats, onTestStart)

        val process = sandbox.start(spec.copy(command = command))
        session.process = process
        log.info(
            "Started solution on {} ({} tests, timing={}, TL={}ms, wall={}ms, host limit={}MB, gpu limit={}MB)",
            slot, prepared.tests.size, timing, problem.limits.timeLimitMs, problem.limits.effectiveWallTimeLimitMs,
            hostLimit shr 20, gpuLimitMb ?: "-",
        )
        val stderr = BoundedBuffer(props.run.maxStderrBytes)
        val stderrThread = stderr.drain(process.errorStream, "solution-stderr")
        val lines = LinkedBlockingQueue<String>()
        val mdc = MDC.getCopyOfContextMap()
        val reader = Thread.ofPlatform().daemon().name("solution-status").start {
            mdc?.let { MDC.setContextMap(it) }
            try {
                process.inputStream.bufferedReader().useLines { seq -> seq.forEach { if (it.length < 8192) lines.put(it) } }
            } catch (_: java.io.IOException) {
            } finally {
                lines.put(EOF)
            }
        }
        val gpuListener: (GpuSample) -> Unit = { s ->
            stats.add(s)
            if (gpuLimitMb != null && s.memoryUsedMb > gpuLimitMb && session.verdict == null) {
                session.fail(Verdict.MEMORY_LIMIT_EXCEEDED, "GPU memory limit exceeded (${s.memoryUsedMb - (gpuBaselineMb ?: 0)} MB used)", kill = true)
            }
        }
        slot.monitor?.addListener(gpuListener)
        val memWatch = startHostMemoryWatch(process, hostLimit, session)
        try {
            session.loop(lines, cancelled)
        } finally {
            memWatch.interrupt()
            slot.monitor?.removeListener(gpuListener)
            if (process.isAlive) Proc.killTree(process)
            process.waitFor(10, TimeUnit.SECONDS)
            reader.join(2000)
            stderrThread.join(2000)
        }
        val exit = if (process.isAlive) -1 else process.exitValue()
        return session.finish(exit, stderr.text())
    }

    private fun bytesOf(params: List<gpucodeforces.judge.api.ParamSpec>, t: PreparedTest) =
        params.sumOf { t.counts.getValue(it.name) * it.type.bytes }

    private fun ioAllowance(bytes: Long): Duration =
        Duration.ofMillis(2000 + bytes * 1000 / (props.run.ioMbPerSecond shl 20))

    private fun totalBudget(problem: ProblemSpec, prepared: PreparedProblem, wallNs: Long): Duration {
        val sig = problem.signature
        var ms = props.run.startupTimeout.toMillis()
        val runs = problem.timing.maxRuns
        prepared.tests.forEachIndexed { i, t ->
            val perRun = wallNs / 1_000_000 + ioAllowance(bytesOf(sig.arrays, t)).toMillis()
            ms += perRun * (runs + if (i == 0) problem.timing.warmupRuns else 0)
        }
        return Duration.ofMillis(ms)
    }

    private fun buildManifest(
        problem: ProblemSpec, prepared: PreparedProblem, spec: SandboxSpec, outDir: Path, nonce: String,
        timing: TimingMode, tlNs: Long, wallNs: Long,
    ): String = buildString {
        val sig = problem.signature
        appendLine("nonce $nonce")
        appendLine("timing ${if (timing == TimingMode.GPU_KERNELS) "kernels" else "wall"}")
        appendLine("warmup ${problem.timing.warmupRuns}")
        appendLine("max_runs ${problem.timing.maxRuns}")
        appendLine("time_limit_ns $tlNs")
        appendLine("wall_limit_ns $wallNs")
        appendLine("grey_factor $GREY_FACTOR")
        appendLine("params ${sig.params.size}")
        for (t in prepared.tests) {
            appendLine("test ${t.index}")
            sig.params.forEachIndexed { pi, p ->
                if (p.role == ParamRole.SCALAR) {
                    val v = t.spec.scalars.getValue(p.name)
                    appendLine("scalar $pi ${if (p.type.floating) v.toDouble().toString() else v.toLong().toString()}")
                } else {
                    val bytes = t.counts.getValue(p.name) * p.type.bytes
                    val input = t.inputs[p.name]?.let { sandbox.inside(spec, it) } ?: "-"
                    val output = if (p.role == ParamRole.INPUT) "-" else sandbox.inside(spec, outFile(outDir, t.index, p.name))
                    appendLine("array $pi $bytes $input $output")
                }
            }
            appendLine("end")
        }
    }

    private fun outFile(outDir: Path, test: Int, param: String) = outDir.resolve("t${test}_$param.bin")

    /** Polls the RSS of the sandboxed process tree; kills it on overflow (works without cgroups, also on WSL2). */
    private fun startHostMemoryWatch(process: Process, limit: Long, session: Session): Thread {
        val mdc = MDC.getCopyOfContextMap()
        return Thread.ofPlatform().daemon().name("solution-memwatch").start {
            mdc?.let { MDC.setContextMap(it) }
            val pageSize = 4096L
            try {
                while (process.isAlive) {
                    val pids = listOf(process.pid()) + process.toHandle().descendants().map { it.pid() }.toList()
                    val rss = pids.sumOf { pid ->
                        runCatching { Path.of("/proc/$pid/statm").readText().split(' ')[1].toLong() * pageSize }.getOrDefault(0L)
                    }
                    session.peakRss = maxOf(session.peakRss, rss)
                    if (rss > limit && session.verdict == null) {
                        session.fail(Verdict.MEMORY_LIMIT_EXCEEDED, "Memory limit exceeded (${rss shr 20} MB RSS)", kill = true)
                        break
                    }
                    Thread.sleep(props.run.samplingInterval.toMillis())
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    private enum class Phase { STARTING, LOADING, RUNNING, SAVING }

    /** State of one solution process; [fail] may be called from watcher threads. */
    private inner class Session(
        val problem: ProblemSpec,
        val prepared: PreparedProblem,
        val runDir: Path,
        val outDir: Path,
        val nonce: String,
        val timing: TimingMode,
        val tlNs: Long,
        val wallNs: Long,
        val stats: GpuStats,
        val onTestStart: (Int) -> Unit,
    ) {
        @Volatile var process: Process? = null
        @Volatile var verdict: Verdict? = null
        @Volatile var message: String? = null
        @Volatile var debug: String? = null
        @Volatile var killedByJudge = false
        @Volatile var peakRss = 0L
        var internalReason: String? = null
        var retryable = false
        var ready = false
        var done = false
        var currentTest = 1
        var phase = Phase.STARTING
        var deadline = System.nanoTime() + props.run.startupTimeout.toNanos()
        val results = mutableListOf<TestResult>()
        val sig = problem.signature

        @Synchronized
        fun fail(v: Verdict, msg: String, kill: Boolean, debugInfo: String? = null) {
            if (verdict != null) return
            verdict = v
            message = msg
            debug = debugInfo
            if (kill) {
                killedByJudge = true
                process?.let { Proc.killTree(it) }
            }
        }

        fun loop(lines: LinkedBlockingQueue<String>, cancelled: () -> Boolean) {
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
                    "Time limit exceeded on test $currentTest (killed after ${problem.limits.effectiveWallTimeLimitMs} ms)",
                    kill = true,
                )
                Phase.STARTING -> {
                    internalReason = "harness did not become ready within ${props.run.startupTimeout.seconds}s (CUDA context creation stuck?)"
                    retryable = true
                    fail(Verdict.INTERNAL_ERROR, "Judging failed", kill = true)
                }
                else -> fail(Verdict.TIME_LIMIT_EXCEEDED, "Time limit exceeded on test $currentTest (solution stalled the judge)", kill = true)
            }
            log.info("Watchdog fired in phase {} on test {}", phase, currentTest)
        }

        private fun testBytes(index: Int) = prepared.tests.getOrNull(index - 1)?.let { bytesOf(sig.arrays, it) } ?: 0L

        private fun handle(line: String) {
            val parts = line.split(' ')
            if (parts.size < 2 || parts[0] != nonce) {
                log.debug("ignoring non-status stdout line from solution")
                return
            }
            val now = System.nanoTime()
            when (parts[1]) {
                "ready" -> {
                    ready = true
                    phase = Phase.LOADING
                    deadline = now + ioAllowance(testBytes(1)).toNanos()
                }
                "load" -> {
                    currentTest = parts.getOrNull(2)?.toIntOrNull() ?: currentTest
                    phase = Phase.LOADING
                    deadline = now + ioAllowance(testBytes(currentTest)).toNanos()
                }
                "run" -> {
                    currentTest = parts.getOrNull(2)?.toIntOrNull() ?: currentTest
                    if (parts.getOrNull(3) == "0") onTestStart(currentTest)
                    phase = Phase.RUNNING
                    deadline = now + wallNs + GRACE_NS
                }
                "test" -> onTestLine(parts)
                "fatal" -> {
                    val msg = parts.drop(2).joinToString(" ")
                    internalReason = "harness fatal error: $msg"
                    retryable = true
                    fail(Verdict.INTERNAL_ERROR, "Judging failed", kill = false)
                }
                "done" -> done = true
            }
        }

        private fun onTestLine(parts: List<String>) {
            val idx = parts.getOrNull(2)?.toIntOrNull() ?: return
            val test = prepared.tests[idx - 1]
            val kind = parts.getOrNull(3)
            val rest = parts.drop(4).joinToString(" ")
            when (kind) {
                "ok" -> {
                    val kv = parts.drop(4).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
                    val t = kv["time_ns"]?.toLongOrNull() ?: 0
                    val w = kv["wall_ns"]?.toLongOrNull() ?: 0
                    val runs = kv["runs"]?.toIntOrNull()
                    phase = Phase.SAVING
                    deadline = System.nanoTime() + ioAllowance(testBytes(idx) + testBytes(idx + 1)).toNanos()
                    val timeMs = t / 1e6
                    val wallMs = w / 1e6
                    val base = TestResult(idx, test.spec.name, test.spec.sample, Verdict.ACCEPTED, timeMs, wallMs, runs)
                    when {
                        t > tlNs || w > wallNs -> {
                            val what = if (t > tlNs) "%.1f ms".format(timeMs) else "wall %.1f ms".format(wallMs)
                            results += base.copy(verdict = Verdict.TIME_LIMIT_EXCEEDED, message = "Time limit exceeded ($what)")
                            fail(Verdict.TIME_LIMIT_EXCEEDED, "Time limit exceeded on test $idx", kill = true)
                        }
                        else -> {
                            val check = checkOutputs(test)
                            if (check == null) {
                                results += base
                            } else {
                                val msg = if (test.spec.sample) "${check.summary}; ${check.detail}" else check.summary
                                results += base.copy(verdict = Verdict.WRONG_ANSWER, message = msg, debug = check.detail)
                                fail(Verdict.WRONG_ANSWER, "Wrong answer on test $idx", kill = true)
                            }
                        }
                    }
                }
                "runtime_error" -> {
                    results += TestResult(idx, test.spec.name, test.spec.sample, Verdict.RUNTIME_ERROR, message = "CUDA error: $rest")
                    fail(Verdict.RUNTIME_ERROR, "Runtime error on test $idx", kill = true)
                }
                "alloc_failed" -> {
                    results += TestResult(idx, test.spec.name, test.spec.sample, Verdict.MEMORY_LIMIT_EXCEEDED, message = rest)
                    fail(Verdict.MEMORY_LIMIT_EXCEEDED, "Memory limit exceeded on test $idx", kill = true)
                }
            }
        }

        private fun checkOutputs(test: PreparedTest): CheckResult? {
            try {
                for (p in sig.outputs) {
                    val actual = outFile(outDir, test.index, p.name)
                    val r = OutputChecker.check(p.name, p.type, actual, test.expected.getValue(p.name), problem.checker)
                    if (!r.ok) return r
                }
                return null
            } finally {
                for (p in sig.outputs) outFile(outDir, test.index, p.name).deleteIfExists()
            }
        }

        fun finish(exitCode: Int, stderr: String): RunOutcome {
            if (verdict == null) {
                when {
                    done && results.size == prepared.tests.size -> verdict = Verdict.ACCEPTED
                    !ready && exitCode == 255 -> {
                        internalReason = "sandbox failed to start (exit 255): ${nsjailLog()}"
                        retryable = true
                        verdict = Verdict.INTERNAL_ERROR
                        message = "Judging failed"
                    }
                    !ready && exitCode in 119..121 -> {
                        internalReason = "harness failed before start (exit $exitCode): ${stderr.takeLast(1000)}"
                        retryable = true
                        verdict = Verdict.INTERNAL_ERROR
                        message = "Judging failed"
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
            // Failures detected outside a test result line (watchdog, memory watch, crash) still get a test entry.
            val t = prepared.tests.getOrNull(currentTest - 1)
            if (t != null && v != Verdict.ACCEPTED && v != Verdict.INTERNAL_ERROR && v != Verdict.CANCELLED &&
                results.none { it.verdict != Verdict.ACCEPTED } && results.none { it.index == currentTest }
            ) {
                results += TestResult(currentTest, t.spec.name, t.spec.sample, v, message = debug ?: message)
            }
            val failed = results.lastOrNull()?.takeIf { it.verdict != Verdict.ACCEPTED }?.index
                ?: if (v != Verdict.ACCEPTED && v != Verdict.INTERNAL_ERROR && v != Verdict.CANCELLED) currentTest else null
            // stderr may print hidden test data: public only on samples, always in debug
            val stderrTail = stderr.trim().takeLast(2000)
            val finalResults = results.map { r ->
                if (r.verdict == Verdict.ACCEPTED || stderrTail.isEmpty()) r
                else r.copy(
                    message = if (r.sample) listOfNotNull(r.message, "stderr:\n$stderrTail").joinToString("\n") else r.message,
                    debug = listOfNotNull(r.debug, "stderr:\n$stderrTail").joinToString("\n"),
                )
            }
            val hardStop = killedByJudge || exitCode > 128 || exitCode < 0 || v == Verdict.RUNTIME_ERROR || v == Verdict.INTERNAL_ERROR
            return RunOutcome(v, finalResults, failed, message, stats, needsHealthCheck = hardStop, internalReason = internalReason, retryable = retryable)
        }

        private fun nsjailLog(): String = runCatching {
            runDir.resolveSibling(runDir.fileName.toString() + ".nsjail.log").readText().takeLast(2000)
        }.getOrDefault("-")
    }

    companion object {
        private const val EOF = "\u0000EOF"
        private const val GREY_FACTOR = 1.25
        private const val GRACE_NS = 1_000_000_000L

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
