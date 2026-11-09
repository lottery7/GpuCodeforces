package dev.gpucodeforces.judge.executor.gpu

import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.sandbox.isWsl2
import dev.gpucodeforces.judge.executor.util.Proc
import dev.gpucodeforces.judge.executor.util.ProcResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import org.slf4j.LoggerFactory

/**
 * Owns GPU lifecycle: persistence mode and clock locking at startup, the health probe binary, health checks
 * after killed runs and periodically, and clock reset at shutdown.
 *
 * It also makes the GPU profile mean a GPU type: jobs are routed by the profile's name alone, so an executor whose
 * hardware is not what its profile says would silently judge on another card.
 */
class GpuManager(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val wsl: Boolean = isWsl2()
    val pool = GpuPool(props.gpus.map { GpuSlot(it) })
    private val probeBinary: Path = props.paths.cache.resolve("probe").resolve(props.profile.arch).resolve("probe")

    fun start() {
        buildProbe()
        for (slot in pool.slots) {
            configureClocks(slot)
            val monitor = GpuMonitor(props.toolchain.nvidiaSmi, slot.index, props.run.samplingInterval.multipliedBy(2).coerceAtLeast(Duration.ofMillis(50)))
            if (monitor.start()) slot.monitor = monitor
            val probe = probe(slot)
            if (probe == null) {
                verifyHardware(slot)
                pool.markHealthy(slot)
            } else {
                pool.markUnhealthy(slot, "startup probe failed: $probe")
            }
        }
        check(pool.healthyCount() > 0) { "no healthy GPU at startup: ${pool.slots.map { "$it: ${it.unhealthyReason}" }}" }
        log.info("GPUs ready: {}", pool.slots.map { "${it.index}=${it.name} healthy=${it.healthy} clocksLocked=${it.clocksLocked}" })
    }

    fun stop() {
        for (slot in pool.slots) {
            slot.monitor?.stop()
            if (slot.clocksLocked) {
                smi(slot, "-rgc")
                if (!slot.config.lockMemoryClocksMhz.isNullOrBlank()) smi(slot, "-rmc")
                log.info("{}: clocks reset", slot)
            }
        }
    }

    /** Refuses to start on a GPU other than the one the profile describes. Called after a successful probe. */
    private fun verifyHardware(slot: GpuSlot) {
        val profile = props.profile
        val expectedName = profile.gpuName?.takeIf { it.isNotBlank() }
        val mismatch = "$slot reports ${slot.name} (${slot.arch}), but this executor serves GPU profile " +
            "'${props.gpuProfile}' (${expectedName ?: "any model"}, ${profile.arch}): submissions for that profile would be " +
            "judged on another GPU. Set JUDGE_GPU_PROFILE, JUDGE_GPU_ARCH and JUDGE_GPU_NAME to describe this machine's GPU"
        check(slot.arch == profile.arch) { mismatch }
        if (expectedName == null) {
            log.warn("GPU profile '{}' does not name a GPU model (gpu-name): any {} card is accepted, {} here", props.gpuProfile, profile.arch, slot.name)
        } else {
            check(slot.name == expectedName) { mismatch }
        }
    }

    private fun smi(slot: GpuSlot, vararg args: String): ProcResult =
        Proc.run(listOf(props.toolchain.nvidiaSmi, "-i", "${slot.index}") + args, Duration.ofSeconds(15))

    private fun configureClocks(slot: GpuSlot) {
        val graphics = slot.config.lockGraphicsClocksMhz?.takeIf { it.isNotBlank() }
        val memory = slot.config.lockMemoryClocksMhz?.takeIf { it.isNotBlank() }
        if (wsl) {
            if (graphics != null) {
                log.warn("{}: clock locking is not available on WSL2; lock clocks from Windows (nvidia-smi.exe -lgc as admin) if needed", slot)
            }
            return
        }
        val pm = smi(slot, "-pm", "1")
        if (!pm.ok) log.warn("{}: cannot enable persistence mode (context creation will be slower): {}", slot, pm.describe())
        if (graphics != null) {
            val r = smi(slot, "-lgc", graphics)
            if (r.ok) {
                slot.clocksLocked = true
                log.info("{}: graphics clocks locked to {} MHz", slot, graphics)
            } else {
                log.warn("{}: cannot lock graphics clocks to {} (timings will be noisier): {}", slot, graphics, r.describe())
            }
        }
        if (memory != null) {
            val r = smi(slot, "-lmc", memory)
            if (r.ok) log.info("{}: memory clocks locked to {} MHz", slot, memory)
            else log.warn("{}: cannot lock memory clocks to {}: {}", slot, memory, r.describe())
        }
    }

    private fun buildProbe() {
        if (probeBinary.exists()) return
        Files.createDirectories(probeBinary.parent)
        val src = props.paths.harness.resolve("probe/probe.cu")
        val tmp = probeBinary.resolveSibling("probe.tmp")
        val r = Proc.run(
            listOf(props.toolchain.nvcc, "-ccbin", props.toolchain.hostCompiler, "-O2", "-arch=${props.profile.arch}", "-o", tmp.toString(), src.toString()),
            Duration.ofMinutes(2),
        )
        check(r.ok) { "cannot build GPU probe: ${r.describe()}" }
        Files.move(tmp, probeBinary, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Runs the probe on the slot. Returns null if healthy, otherwise the reason. */
    fun probe(slot: GpuSlot): String? = slot.probeLock.withLock {
        val r = Proc.run(listOf(probeBinary.toString()), Duration.ofSeconds(30), env = slot.access().cudaEnv())
        slot.lastProbe = Instant.now()
        // "ok <device name> sm_<major><minor>"
        val line = r.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (r.ok && line.startsWith("ok ")) {
            slot.name = line.removePrefix("ok ").substringBeforeLast(" sm_").trim()
            slot.arch = "sm_" + line.substringAfterLast(" sm_")
            null
        } else {
            r.describe()
        }
    }

    /** After a solution was killed or crashed hard: verify the GPU still works before giving it to the next job. */
    fun checkAfterFailure(slot: GpuSlot, why: String) {
        val reason = probe(slot)
        if (reason == null) log.info("{} healthy after {}", slot, why)
        else pool.markUnhealthy(slot, "probe after $why failed: $reason")
    }

    /** Scheduled: re-probe unhealthy GPUs and occasionally idle healthy ones. */
    fun periodicCheck() {
        val now = Instant.now()
        for (slot in pool.slots) {
            if (!slot.healthy && slot.busyWith == null) {
                if (Duration.between(slot.lastProbe, now) < props.unhealthyProbeInterval) continue
                val reason = probe(slot)
                if (reason == null) pool.markHealthy(slot) else log.warn("{} still unhealthy: {}", slot, reason)
            } else if (slot.healthy && Duration.between(slot.lastProbe, now) >= props.idleProbeInterval) {
                if (!pool.tryAcquire(slot, "health-probe")) continue
                val reason = try {
                    probe(slot)
                } finally {
                    if (slot.busyWith == "health-probe") slot.busyWith = null
                }
                if (reason == null) pool.release(slot) else pool.markUnhealthy(slot, "idle probe failed: $reason")
            }
        }
    }
}
