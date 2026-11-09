package dev.gpucodeforces.judge.executor.gpu

import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.util.Proc
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import org.slf4j.LoggerFactory

/**
 * Owns GPU lifecycle: persistence mode and clock locking at startup, the health probe binary, health checks
 * after killed runs and periodically, and clock reset at shutdown.
 */
class GpuManager(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val smi = props.toolchain.nvidiaSmi
    val wsl: Boolean = Path.of("/dev/dxg").exists()
    val pool = GpuPool(props.gpus.map { GpuSlot(it) })
    private lateinit var probeBinary: Path
    private val probeLock = ReentrantLock()

    fun start() {
        buildProbe()
        for (slot in pool.slots) {
            configureClocks(slot)
            val monitor = GpuMonitor(smi, slot.index, props.run.samplingInterval.multipliedBy(2).coerceAtLeast(Duration.ofMillis(50)))
            if (monitor.start()) slot.monitor = monitor
            val probe = probe(slot)
            if (probe == null) pool.markHealthy(slot) else pool.markUnhealthy(slot, "startup probe failed: $probe")
        }
        check(pool.healthyCount() > 0) { "no healthy GPU at startup: ${pool.slots.map { "$it: ${it.unhealthyReason}" }}" }
        log.info("GPUs ready: {}", pool.slots.map { "${it.index}=${it.name} healthy=${it.healthy} clocksLocked=${it.clocksLocked}" })
    }

    fun stop() {
        for (slot in pool.slots) {
            slot.monitor?.stop()
            if (slot.clocksLocked) {
                Proc.run(listOf(smi, "-i", "${slot.index}", "-rgc"), Duration.ofSeconds(15))
                if (!slot.config.lockMemoryClocksMhz.isNullOrBlank()) Proc.run(listOf(smi, "-i", "${slot.index}", "-rmc"), Duration.ofSeconds(15))
                log.info("{}: clocks reset", slot)
            }
        }
    }

    private fun configureClocks(slot: GpuSlot) {
        if (wsl) {
            if (!slot.config.lockGraphicsClocksMhz.isNullOrBlank()) {
                log.warn("{}: clock locking is not available on WSL2; lock clocks from Windows (nvidia-smi.exe -lgc as admin) if needed", slot)
            }
            return
        }
        val pm = Proc.run(listOf(smi, "-i", "${slot.index}", "-pm", "1"), Duration.ofSeconds(15))
        if (!pm.ok) log.warn("{}: cannot enable persistence mode (context creation will be slower): {}", slot, pm.describe())
        slot.config.lockGraphicsClocksMhz?.takeIf { it.isNotBlank() }?.let { range ->
            val r = Proc.run(listOf(smi, "-i", "${slot.index}", "-lgc", range), Duration.ofSeconds(15))
            if (r.ok) {
                slot.clocksLocked = true
                log.info("{}: graphics clocks locked to {} MHz", slot, range)
            } else {
                log.warn("{}: cannot lock graphics clocks to {} (timings will be noisier): {}", slot, range, r.describe())
            }
        }
        slot.config.lockMemoryClocksMhz?.takeIf { it.isNotBlank() }?.let { range ->
            val r = Proc.run(listOf(smi, "-i", "${slot.index}", "-lmc", range), Duration.ofSeconds(15))
            if (r.ok) log.info("{}: memory clocks locked to {} MHz", slot, range)
            else log.warn("{}: cannot lock memory clocks to {}: {}", slot, range, r.describe())
        }
    }

    private fun buildProbe() {
        val arch = props.profile.arch
        val dir = props.paths.cache.resolve("probe").resolve(arch)
        Files.createDirectories(dir)
        probeBinary = dir.resolve("probe")
        if (probeBinary.exists()) return
        val src = props.paths.harness.resolve("probe/probe.cu")
        val tmp = dir.resolve("probe.tmp")
        val r = Proc.run(
            listOf(props.toolchain.nvcc, "-ccbin", props.toolchain.hostCompiler, "-O2", "-arch=$arch", "-o", tmp.toString(), src.toString()),
            Duration.ofMinutes(2),
        )
        check(r.ok) { "cannot build GPU probe: ${r.describe()}" }
        Files.move(tmp, probeBinary, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Runs the probe on the slot. Returns null if healthy, otherwise the reason. */
    fun probe(slot: GpuSlot): String? = probeLock.withLock {
        val r = Proc.run(
            listOf(probeBinary.toString()),
            Duration.ofSeconds(30),
            env = mapOf("CUDA_DEVICE_ORDER" to "PCI_BUS_ID", "CUDA_VISIBLE_DEVICES" to "${slot.index}"),
        )
        slot.lastProbe = Instant.now()
        val line = r.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (r.ok && line.startsWith("ok ")) {
            slot.name = line.removePrefix("ok ").substringBeforeLast(" sm_").trim()
            null
        } else {
            r.describe()
        }
    }

    /** After a solution was killed or crashed hard: verify the GPU still works before giving it to the next job. */
    fun checkAfterFailure(slot: GpuSlot, why: String): Boolean {
        val reason = probe(slot)
        if (reason == null) {
            log.info("{} healthy after {}", slot, why)
            return true
        }
        pool.markUnhealthy(slot, "probe after $why failed: $reason")
        return false
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
