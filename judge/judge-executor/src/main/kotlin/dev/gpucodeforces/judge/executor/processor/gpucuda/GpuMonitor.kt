package dev.gpucodeforces.judge.executor.processor.gpucuda

import dev.gpucodeforces.judge.executor.util.Proc
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

data class GpuSample(
    val memoryUsedMb: Long,
    val smClockMhz: Int?,
    val temperatureC: Int?,
    /** Thermal/power slowdown reasons active (bitmask from nvidia-smi, idle/app-clock bits ignored). */
    val throttled: Boolean?,
)

/** Aggregates samples taken while a solution runs. */
class GpuStats {
    @Volatile var minSm: Int? = null
    @Volatile var maxSm: Int? = null
    @Volatile var maxTemp: Int? = null
    @Volatile var throttled: Boolean? = null

    @Synchronized
    fun add(s: GpuSample) {
        s.smClockMhz?.let { minSm = minOf(minSm ?: it, it); maxSm = maxOf(maxSm ?: it, it) }
        s.temperatureC?.let { maxTemp = maxOf(maxTemp ?: it, it) }
        s.throttled?.let { throttled = (throttled ?: false) || it }
    }
}

/**
 * Continuous `nvidia-smi -lms` sampler for one GPU. Device-wide memory usage is accurate for us because a GPU
 * runs one solution at a time; it also works on WSL2 where per-process accounting is unavailable.
 */
class GpuMonitor(private val nvidiaSmi: String, private val index: Int, private val interval: Duration) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val latest = AtomicReference<GpuSample?>(null)
    private val listeners = CopyOnWriteArrayList<(GpuSample) -> Unit>()
    @Volatile private var process: Process? = null
    @Volatile private var stopped = false
    private var fields: List<String> = emptyList()

    fun latest(): GpuSample? = latest.get()

    fun addListener(l: (GpuSample) -> Unit) = listeners.add(l)
    fun removeListener(l: (GpuSample) -> Unit) = listeners.remove(l)

    /** Returns false if nvidia-smi cannot report even memory usage (monitoring disabled). */
    fun start(): Boolean {
        fields = FIELD_SETS.firstOrNull { set ->
            Proc.run(listOf(nvidiaSmi, "-i", "$index", "--query-gpu=${set.joinToString(",")}", "--format=csv,noheader,nounits"), Duration.ofSeconds(10)).let {
                it.ok && it.stdout.isNotBlank() && !it.stdout.contains("Not Supported") && !it.stdout.contains("Field")
            }
        } ?: run {
            log.warn("GPU {}: nvidia-smi monitoring unavailable, GPU memory limits and telemetry are disabled", index)
            return false
        }
        log.info("GPU {}: monitoring {} every {}ms", index, fields, interval.toMillis())
        Thread.ofPlatform().daemon().name("gpu-monitor-$index").start { loop() }
        return true
    }

    fun stop() {
        stopped = true
        process?.let { Proc.killTree(it) }
    }

    private fun loop() {
        while (!stopped) {
            try {
                val p = ProcessBuilder(
                    nvidiaSmi, "-i", "$index", "--query-gpu=${fields.joinToString(",")}", "--format=csv,noheader,nounits",
                    "-lms", interval.toMillis().coerceAtLeast(10).toString(),
                ).redirectErrorStream(true).start()
                process = p
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { parse(it)?.let(::publish) } }
                p.waitFor()
            } catch (e: IOException) {
                // also how stop() ends the loop: it kills nvidia-smi, which closes the stream being read
                if (!stopped) log.warn("GPU {}: monitor failed, restarting", index, e)
            }
            if (!stopped) Thread.sleep(1000)
        }
    }

    private fun publish(s: GpuSample) {
        latest.set(s)
        for (listener in listeners) {
            try {
                listener(s)
            } catch (e: RuntimeException) {
                log.error("GPU {}: sample listener failed", index, e)
            }
        }
    }

    private fun parse(line: String): GpuSample? {
        val parts = line.split(',').map { it.trim() }
        if (parts.size != fields.size) return null
        fun intAt(name: String) = fields.indexOf(name).takeIf { it >= 0 }?.let { parts[it].toIntOrNull() }
        val mem = intAt("memory.used")?.toLong() ?: return null
        val reasonsIdx = fields.indexOfFirst { it.endsWith("reasons.active") }
        val throttled = reasonsIdx.takeIf { it >= 0 }?.let { idx ->
            parts[idx].removePrefix("0x").toLongOrNull(16)?.let { it and SLOWDOWN_MASK != 0L }
        }
        return GpuSample(mem, smClockMhz = intAt("clocks.sm"), temperatureC = intAt("temperature.gpu"), throttled)
    }

    companion object {
        /** SW power cap, HW slowdown, SW/HW thermal slowdown, HW power brake. */
        private const val SLOWDOWN_MASK = 0x4L or 0x8L or 0x20L or 0x40L or 0x80L
        private val FIELD_SETS = listOf(
            listOf("memory.used", "clocks.sm", "temperature.gpu", "clocks_event_reasons.active"),
            listOf("memory.used", "clocks.sm", "temperature.gpu", "clocks_throttle_reasons.active"),
            listOf("memory.used", "clocks.sm", "temperature.gpu"),
            listOf("memory.used"),
        )
    }
}
