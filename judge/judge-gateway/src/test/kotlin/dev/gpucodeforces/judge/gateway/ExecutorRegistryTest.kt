package dev.gpucodeforces.judge.gateway

import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.gateway.config.GatewayProperties
import dev.gpucodeforces.judge.gateway.messaging.JudgeMessaging
import dev.gpucodeforces.judge.gateway.service.ExecutorRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitAdmin

class ExecutorRegistryTest {
    private val messaging = mock<JudgeMessaging>()
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val registry = ExecutorRegistry(
        GatewayProperties(processors = listOf(Processors.GPU_CUDA, Processors.RTL)), messaging, mock<RabbitAdmin>(),
        Clock.fixed(now, ZoneOffset.UTC), SimpleMeterRegistry(),
    )

    private fun beat(id: String, vararg processors: ProcessorStatus) {
        val message = Message(ByteArray(0), MessageProperties())
        whenever(messaging.read(message, ExecutorHeartbeat::class.java))
            .thenReturn(ExecutorHeartbeat(id, "host-$id", processors.toList(), "dev", now, now, emptyList(), inFlight = 1, capacity = 3))
        registry.onHeartbeat(message)
    }

    @Test
    fun `the catalog shows which processors are accepted and which can be judged now`() {
        beat("emu-1", ProcessorStatus(Processors.RTL, "verilator 5", "Verilator + Yosys", healthy = true, busy = false, capacity = 3))
        val processors = registry.processors().associateBy { it.id }
        assertEquals(Processors.ids, registry.processors().map { it.id })
        with(processors.getValue(Processors.RTL)) {
            assertEquals(true, accepted)
            assertEquals(true, available)
            assertEquals("Verilator + Yosys", model)
        }
        with(processors.getValue(Processors.GPU_CUDA)) {
            assertEquals(true, accepted)
            assertEquals(false, available)
            assertEquals(null, model)
        }
        assertEquals(false, processors.getValue(Processors.CORAL_NPU).accepted)
    }

    @Test
    fun `an executor counts for every processor it serves`() {
        beat(
            "both",
            ProcessorStatus(Processors.GPU_CUDA, "nvcc", "RTX 3060", healthy = true, busy = false, capacity = 3),
            ProcessorStatus(Processors.RTL, "verilator", "Verilator", healthy = true, busy = false, capacity = 2),
        )
        assertEquals(listOf("both"), registry.alive(Processors.RTL).map { it.heartbeat.executorId })
        assertEquals(listOf("both"), registry.alive(Processors.GPU_CUDA).map { it.heartbeat.executorId })
        val rtlQueue = registry.queues().single { it.processor == Processors.RTL }
        assertEquals("judge.jobs.rtl", rtlQueue.queue)
        assertEquals(2, rtlQueue.capacity)
    }
}
