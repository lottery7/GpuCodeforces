package dev.gpucodeforces.backend.admin

import dev.gpucodeforces.backend.common.BadRequestException
import groovy.lang.Binding
import groovy.lang.GroovyShell
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@ConfigurationProperties("platform.admin")
data class AdminProperties(
    /** Handles of administrators; there is no other way to become one. */
    val handles: List<String> = emptyList(),
    val consoleTimeout: Duration = Duration.ofSeconds(30),
) {
    fun isAdmin(handle: String): Boolean = handles.any { it.equals(handle, ignoreCase = true) }
}

class ConsoleResult(val output: String, val result: String?, val error: String?)

/**
 * Runs Groovy scripts inside the running application for administrators: `ctx` is the Spring ApplicationContext,
 * `out` prints to the response. This is full access to the process, not a sandbox. One script at a time; on timeout
 * the script thread is interrupted, and a script that ignores the interrupt keeps the console busy until it ends.
 */
@Component
class GroovyConsole(private val context: ApplicationContext, private val properties: AdminProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val slot = Semaphore(1)

    fun run(script: String, actor: String): ConsoleResult {
        if (!slot.tryAcquire()) throw BadRequestException("Предыдущий скрипт ещё выполняется")
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        log.warn("Admin console: {} runs script sha256={}", actor, hash)
        val output = StringWriter()
        val future = try {
            executor.submit<String?> {
                try {
                    val shell = GroovyShell(context.classLoader, Binding(mapOf("ctx" to context, "out" to PrintWriter(output, true))))
                    shell.evaluate(script, "AdminConsole.groovy")?.toString()
                } finally {
                    slot.release()
                }
            }
        } catch (exception: Exception) {
            slot.release()
            throw exception
        }
        return try {
            val result = future.get(properties.consoleTimeout.toMillis(), TimeUnit.MILLISECONDS)
            ConsoleResult(output.toString().take(MAX_OUTPUT), result?.take(MAX_OUTPUT), null)
        } catch (_: TimeoutException) {
            future.cancel(true)
            ConsoleResult(output.toString().take(MAX_OUTPUT), null, "Превышено время ожидания, скрипту отправлено прерывание")
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            ConsoleResult(output.toString().take(MAX_OUTPUT), null, "${cause.javaClass.name}: ${cause.message}".take(MAX_OUTPUT))
        }
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_OUTPUT = 65_536
    }
}
