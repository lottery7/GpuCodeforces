package dev.gpucodeforces.backend

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Component
class RequestGuard(private val config: PlatformConfig, private val live: RuntimeSettings) : OncePerRequestFilter() {
    private val buckets = mutableMapOf<String, Int>()
    private var currentMinute = -1L

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!request.requestURI.startsWith("/api/")) {
            chain.doFilter(request, response); return
        }
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        val values = live.current().values
        val auth = request.method == "POST" &&
            request.requestURI in setOf("/api/session", "/api/users", "/api/admin/bootstrap")
        val internal = request.requestURI.startsWith("/api/internal/")
        val worker = if (!internal) null else config.judge.workers.firstOrNull {
            request.requestURI.startsWith("/api/internal/judges/${it.id}/") && MessageDigest.isEqual(
                request.getHeader("Authorization").orEmpty().toByteArray(), "Bearer ${it.token}".toByteArray(),
            )
        }
        val key = worker?.let { "judge:${it.id}" } ?: (request.remoteAddr + if (auth) ":auth" else ":api")
        val rate = when {
            worker != null -> values.callbackRequestsPerMinute
            auth -> values.authRequestsPerMinute
            else -> values.requestsPerMinute
        }
        val minute = System.currentTimeMillis() / 60000
        val allowed = synchronized(buckets) {
            if (currentMinute != minute) {
                buckets.clear(); currentMinute = minute
            }
            if (worker == null && !buckets.containsKey(key) && buckets.size >= config.maxRateLimitKeys) false
            else {
                val count = (buckets[key] ?: 0) + 1
                buckets[key] = count.coerceAtMost(rate + 1)
                count <= rate
            }
        }

        fun reject(status: Int, message: String) {
            response.status = status
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"message\":\"$message\"}")
        }
        if (!allowed) {
            response.setHeader("Retry-After", "60"); reject(429, "Слишком много запросов."); return
        }
        if (internal && worker == null) {
            reject(401, "Недопустимый ключ тестировщика."); return
        }
        if (request.method !in setOf("GET", "HEAD", "OPTIONS") && !request.requestURI.startsWith("/api/internal/")) {
            if (request.getHeader("Origin") != config.publicOrigin) {
                reject(403, "Недопустимый Origin."); return
            }
        }
        val limit = if (request.requestURI.startsWith("/api/internal/")) 16384 else config.maxBodyBytes
        if (request.contentLengthLong > limit) {
            reject(413, "Слишком большой запрос."); return
        }
        val bytes = request.inputStream.readNBytes(limit + 1)
        if (bytes.size > limit) {
            reject(413, "Слишком большой запрос."); return
        }
        val wrapped = object : HttpServletRequestWrapper(request) {
            override fun getInputStream(): ServletInputStream {
                val stream = ByteArrayInputStream(bytes)
                return object : ServletInputStream() {
                    override fun read() = stream.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
                    override fun isFinished() = stream.available() == 0
                    override fun isReady() = true
                    override fun setReadListener(listener: ReadListener) {
                        error("Asynchronous request reading is not supported")
                    }
                }
            }
        }
        chain.doFilter(wrapped, response)
    }
}

@Component
class Accounts(private val db: Database, private val config: PlatformConfig) {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val dummyHash = hashPassword("invalid-password")
    fun digest(value: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun derive(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 210000, 256)).encoded

    private fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return encoder.encodeToString(salt) + ":" + encoder.encodeToString(derive(password, salt))
    }

    private fun matches(password: String, encoded: String): Boolean {
        val (salt, hash) = encoded.split(':')
        return MessageDigest.isEqual(
            Base64.getUrlDecoder().decode(hash),
            derive(password, Base64.getUrlDecoder().decode(salt))
        )
    }

    fun current(request: HttpServletRequest): String? {
        val token = request.cookies?.firstOrNull { it.name == "session" }?.value ?: return null
        if (token.length > 128) return null
        return db.row("SELECT user_id FROM app_session WHERE token_hash = ? AND expires_at > now()", digest(token))
            ?.get("user_id") as String?
    }

    fun requireUser(request: HttpServletRequest): String = current(request) ?: fail(401, "Необходимо войти.")

    fun isAdmin(userId: String): Boolean =
        db.count("SELECT count(*) FROM app_user WHERE id = ? AND is_admin", userId) > 0

    fun requireAdmin(request: HttpServletRequest): String {
        val id = requireUser(request)
        if (!isAdmin(id)) fail(403, "Нужны права администратора.")
        return id
    }
    private fun session(userId: String, response: HttpServletResponse) {
        val token = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        db.update(
            """
                INSERT INTO app_session VALUES (?, ?, ?)
            """.trimIndent(), digest(token), userId, Timestamp.from(Instant.now().plus(config.sessionTtl))
        )
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token).maxAge(config.sessionTtl).build().toString())
    }

    private fun cookie(value: String) =
        ResponseCookie.from("session", value).httpOnly(true).secure(config.secureCookies).sameSite("Lax").path("/")

    fun register(input: RegisterInput, response: HttpServletResponse): String {
        if (!input.handle.matches(Regex("[A-Za-z0-9_]{3,24}")) ||
            input.email.length > 254 ||
            !input.email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) ||
            input.password.length !in 8..256
        ) fail(
            400,
            "Проверьте ник, почту и пароль.",
        )
        val id = UUID.randomUUID().toString()
        val hash = hashPassword(input.password)
        return db.tx {
            db.update(
                "INSERT INTO app_user(id, handle, email, password_hash) VALUES (?, ?, ?, ?)",
                id,
                input.handle,
                input.email.lowercase(),
                hash,
            )
            session(id, response)
            id
        }
    }

    fun login(input: LoginInput, response: HttpServletResponse): String {
        if (input.identifier.length > 254 || input.password.length > 256) fail(400, "Слишком длинные данные входа.")
        val row = db.row(
            "SELECT * FROM app_user WHERE lower(handle) = lower(?) OR lower(email) = lower(?)",
            input.identifier.trim(),
            input.identifier.trim(),
        )
        val correct = matches(input.password, row?.get("password_hash") as String? ?: dummyHash)
        if (row == null || !correct) fail(401, "Неверный ник, почта или пароль.")
        val id = row["id"].toString()
        db.tx { session(id, response); true }
        return id
    }

    fun logout(request: HttpServletRequest, response: HttpServletResponse) {
        requireUser(request)
        val token = request.cookies.first { it.name == "session" }.value
        db.update("DELETE FROM app_session WHERE token_hash = ?", digest(token))
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("").maxAge(0).build().toString())
    }
}

data class LoginInput(val identifier: String, val password: String)
data class RegisterInput(val handle: String, val email: String, val password: String)

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException::class)
    fun status(error: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.statusCode).body(mapOf("message" to (error.reason ?: "Ошибка запроса.")))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("message" to "Данные конфликтуют с существующей записью."))

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun invalid(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("message" to "Некорректный запрос."))

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<Map<String, String>> {
        if (error is ErrorResponse) return ResponseEntity.status(error.statusCode)
            .body(mapOf("message" to "Некорректный запрос."))
        if (error is InterruptedException) Thread.currentThread().interrupt()
        LoggerFactory.getLogger(ApiErrors::class.java).error("Request failed: {}", error.javaClass.name)
        return ResponseEntity.internalServerError().body(mapOf("message" to "Внутренняя ошибка сервера."))
    }
}
