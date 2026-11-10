package dev.gpucodeforces.backend

import groovy.lang.Binding
import groovy.lang.GroovyShell
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import org.intellij.lang.annotations.Language
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.PrintWriter
import java.io.Writer
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class BootstrapInput(val token: String)
data class ConsoleInput(@param:Language("Groovy") val script: String)
data class ConsoleResult(val output: String, val result: String?, val error: String?, val truncated: Boolean)
data class ConsoleExecution(val status: Int, val body: ConsoleResult)

@Component
class Administration(
    private val db: Database,
    private val accounts: Accounts,
    private val settings: RuntimeSettings,
    private val config: PlatformConfig,
) {
    init {
        require(config.admin.bootstrapToken.isEmpty() || config.admin.bootstrapToken.length >= 32)
    }

    fun bootstrapAvailable(): Boolean = config.admin.bootstrapToken.isNotEmpty() &&
        db.count("SELECT count(*) FROM admin_bootstrap") == 0L &&
        db.row("SELECT id FROM app_user WHERE is_admin LIMIT 1") == null

    fun bootstrap(userId: String, input: BootstrapInput) = db.tx {
        if (config.admin.bootstrapToken.isEmpty()) fail(404, "Назначение первого администратора отключено.")
        if (!MessageDigest.isEqual(input.token.toByteArray(), config.admin.bootstrapToken.toByteArray())) {
            fail(403, "Неверный секрет первоначальной настройки.")
        }
        db.lock(6)
        if (!bootstrapAvailable()) fail(409, "Первоначальная настройка уже выполнена.")
        db.update("UPDATE app_user SET is_admin = true WHERE id = ?", userId)
        db.update("INSERT INTO admin_bootstrap(singleton, claimed_by) VALUES (true, ?)", userId)
        settings.audit(userId, "admin.bootstrap", "first administrator")
        true
    }

    fun access(request: HttpServletRequest): Map<String, Any> {
        val userId = accounts.requireUser(request)
        return mapOf("userId" to userId, "admin" to accounts.isAdmin(userId),
            "bootstrapAvailable" to bootstrapAvailable())
    }
}

private class ConsoleWriter(private val limit: Int) : Writer() {
    private val text = StringBuilder()
    var truncated = false
        private set

    @Synchronized
    override fun write(buffer: CharArray, offset: Int, length: Int) {
        val allowed = minOf(length, limit - text.length)
        text.append(buffer, offset, allowed)
        if (allowed < length) truncated = true
    }

    @Synchronized
    fun snapshot(result: String?, error: String?) = ConsoleResult(text.toString(), result, error, truncated)
    @Synchronized
    fun truncateResult() {
        truncated = true
    }
    override fun flush() = Unit
    override fun close() = Unit
}

@Component
class GroovyConsole(
    private val ctx: ApplicationContext,
    private val config: PlatformConfig,
    private val settings: RuntimeSettings,
) {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val slot = Semaphore(1)

    init {
        require(config.admin.maxScriptBytes in 1..1048576 && config.admin.maxOutputCharacters in 1..1048576)
        require(!config.admin.consoleTimeout.isZero && !config.admin.consoleTimeout.isNegative)
    }

    fun execute(actor: String, input: ConsoleInput): ConsoleExecution {
        if (input.script.isBlank() || input.script.toByteArray().size > config.admin.maxScriptBytes) {
            fail(400, "Пустой или слишком большой скрипт.")
        }
        if (!slot.tryAcquire()) fail(409, "Предыдущий скрипт ещё выполняется.")
        val output = ConsoleWriter(config.admin.maxOutputCharacters)
        val thread = AtomicReference<Thread>()
        val stopRequested = AtomicBoolean()
        val hash = MessageDigest.getInstance("SHA-256").digest(input.script.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val executionId = try {
            settings.audit(actor, "console.start", hash)
        } catch (error: Exception) {
            slot.release()
            throw error
        }
        val future = try {
            executor.submit<String?> {
                thread.set(Thread.currentThread())
                try {
                    if (stopRequested.get()) throw InterruptedException("Console execution cancelled")
                    val binding = Binding(mapOf("ctx" to ctx, "out" to PrintWriter(output, true)))
                    val shell = GroovyShell(ctx.classLoader, binding)
                    try {
                        val value = shell.evaluate(input.script, "AdminConsole.groovy")?.toString()
                        if (value != null && value.length > config.admin.maxOutputCharacters) output.truncateResult()
                        value?.take(config.admin.maxOutputCharacters)
                    }
                    finally {
                        shell.classLoader.close()
                    }
                } finally {
                    thread.set(null)
                    slot.release()
                }
            }
        } catch (error: Exception) {
            slot.release()
            throw error
        }
        val result = try {
            ConsoleExecution(200, output.snapshot(
                future.get(config.admin.consoleTimeout.toMillis(), TimeUnit.MILLISECONDS), null,
            ))
        } catch (_: TimeoutException) {
            stopRequested.set(true)
            thread.get()?.interrupt()
            ConsoleExecution(504, output.snapshot(null, "Превышено время ожидания. Отправлен запрос прерывания."))
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            ConsoleExecution(422, output.snapshot(null, "${cause.javaClass.simpleName}: ${cause.message}".take(8192)))
        } catch (error: InterruptedException) {
            stopRequested.set(true)
            thread.get()?.interrupt()
            Thread.currentThread().interrupt()
            throw error
        }
        settings.audit(actor, "console.finish", "execution=$executionId status=${result.status}")
        return result
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }
}

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val accounts: Accounts,
    private val administration: Administration,
    private val console: GroovyConsole,
    private val settings: RuntimeSettings,
) {
    @GetMapping("/access")
    fun access(request: HttpServletRequest) = administration.access(request)

    @PostMapping("/bootstrap")
    fun bootstrap(request: HttpServletRequest, @RequestBody input: BootstrapInput): Map<String, Any> {
        administration.bootstrap(accounts.requireUser(request), input)
        return administration.access(request)
    }

    @GetMapping("/settings")
    fun settings(request: HttpServletRequest): SettingsSnapshot {
        accounts.requireAdmin(request)
        settings.invalidate()
        return settings.current()
    }

    @PutMapping("/settings")
    fun update(request: HttpServletRequest, @RequestBody input: SettingsUpdate) =
        settings.save(input, accounts.requireAdmin(request))

    @PostMapping("/console")
    fun console(request: HttpServletRequest, @RequestBody input: ConsoleInput): ResponseEntity<ConsoleResult> {
        val result = console.execute(accounts.requireAdmin(request), input)
        return ResponseEntity.status(result.status).body(result.body)
    }
}

@RestController
class AdminPage {
    @GetMapping("/admin", "/admin/", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(): ResponseEntity<Resource> = ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; " +
            "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        .body(ClassPathResource("admin/index.html"))

    @GetMapping("/admin/app.js", produces = ["text/javascript"])
    fun script(): Resource = ClassPathResource("admin/app.js")

    @GetMapping("/admin/style.css", produces = ["text/css"])
    fun style(): Resource = ClassPathResource("admin/style.css")
}
