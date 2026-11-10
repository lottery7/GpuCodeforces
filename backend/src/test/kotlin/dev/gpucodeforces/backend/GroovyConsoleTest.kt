package dev.gpucodeforces.backend

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.support.GenericApplicationContext
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GroovyConsoleTest {
    private fun audit(): RuntimeSettings {
        val settings = Mockito.mock(RuntimeSettings::class.java)
        Mockito.`when`(settings.audit(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(UUID.randomUUID())
        return settings
    }

    @Test
    fun `console bounds both printed output and returned values`() {
        GenericApplicationContext().use { ctx ->
            ctx.refresh()
            val console = GroovyConsole(ctx, PlatformConfig(admin = AdminConfig(maxOutputCharacters = 8)), audit())
            try {
                val printed = console.execute("admin", ConsoleInput("print 'abcdefghijkl'; return 42"))
                assertThat(printed.status).isEqualTo(200)
                assertThat(printed.body.output).isEqualTo("abcdefgh")
                assertThat(printed.body.truncated).isTrue()
                val returned = console.execute("admin", ConsoleInput("return 'abcdefghijkl'"))
                assertThat(returned.body.result).isEqualTo("abcdefgh")
                assertThat(returned.body.truncated).isTrue()
            } finally { console.close() }
        }
    }

    @Test
    fun `console rejects parallel scripts while the active script holds the slot`() {
        GenericApplicationContext().use { ctx ->
            val started = CountDownLatch(1)
            val gate = CountDownLatch(1)
            ctx.beanFactory.registerSingleton("started", started)
            ctx.beanFactory.registerSingleton("gate", gate)
            ctx.refresh()
            val console = GroovyConsole(ctx, PlatformConfig(), audit())
            Executors.newSingleThreadExecutor().use { executor ->
                val running = executor.submit<ConsoleExecution> {
                    console.execute("admin", ConsoleInput(
                        "ctx.getBean('started').countDown(); ctx.getBean('gate').await(); return 42",
                    ))
                }
                try {
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
                    assertThatThrownBy { console.execute("admin", ConsoleInput("1 + 1")) }
                        .hasMessageContaining("409")
                    gate.countDown()
                    assertThat(running.get(10, TimeUnit.SECONDS).status).isEqualTo(200)
                } finally { gate.countDown(); console.close() }
            }
        }
    }

    @Test
    fun `console timeout reports failure and interrupts cooperative scripts`() {
        GenericApplicationContext().use { ctx ->
            ctx.refresh()
            val config = PlatformConfig(admin = AdminConfig(consoleTimeout = Duration.ofMillis(100)))
            val console = GroovyConsole(ctx, config, audit())
            try {
                val result = console.execute("admin", ConsoleInput("Thread.sleep(10000); return 42"))
                assertThat(result.status).isEqualTo(504)
                assertThat(result.body.error).isNotBlank()
            } finally { console.close() }
        }
    }
}
