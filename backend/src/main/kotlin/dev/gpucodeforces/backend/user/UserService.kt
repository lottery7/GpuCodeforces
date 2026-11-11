package dev.gpucodeforces.backend.user

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.common.toUuidOrNotFound
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class UserPage(val items: List<User>, val total: Int)

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun register(handle: String, email: String, password: String): User {
        if (userRepository.existsByHandleIgnoreCase(handle)) {
            throw BadRequestException("Этот ник уже занят")
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw BadRequestException("Эта почта уже зарегистрирована")
        }
        // BCrypt hashes at most 72 bytes and refuses a longer password; in UTF-8 that is fewer characters than 72
        if (password.toByteArray().size > MAX_PASSWORD_BYTES) throw BadRequestException("Пароль длиннее $MAX_PASSWORD_BYTES байт: сократите его")
        val passwordHash = passwordEncoder.encode(password) ?: error("PasswordEncoder returned null")
        return userRepository.save(User(handle = handle, email = email, passwordHash = passwordHash))
    }

    fun getById(id: String): User {
        val uuid = id.toUuidOrNotFound("Пользователь не найден")
        return userRepository.findById(uuid).orElseThrow { NotFoundApiException("Пользователь не найден") }
    }

    /** By handle, or, with [ratingProcessor], the users rated on that processor by their place there. */
    fun search(query: String?, ratingProcessor: String?, offset: Int, limit: Int): UserPage {
        val trimmed = query?.trim() ?: ""
        if (trimmed.isEmpty()) {
            return if (ratingProcessor != null) {
                UserPage(userRepository.pageRated(ratingProcessor, limit, offset), userRepository.countRated(ratingProcessor).toInt())
            } else {
                UserPage(userRepository.pageByHandle(limit, offset), userRepository.count().toInt())
            }
        }
        // a trigram index cannot serve a pattern shorter than three characters
        if (trimmed.length < 3) {
            return if (ratingProcessor != null) {
                UserPage(
                    userRepository.searchShortRated(ratingProcessor, trimmed, limit, offset),
                    userRepository.countShortRated(ratingProcessor, trimmed).toInt(),
                )
            } else {
                UserPage(userRepository.searchShortByHandle(trimmed, limit, offset), userRepository.countShortByHandle(trimmed).toInt())
            }
        }
        val pattern = "%${trimmed.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val items = if (ratingProcessor != null) {
            userRepository.searchRated(ratingProcessor, pattern, limit, offset)
        } else {
            userRepository.searchByHandle(pattern, limit, offset)
        }
        val total = if (ratingProcessor != null) userRepository.countRated(ratingProcessor, pattern) else userRepository.countByHandle(pattern)
        return UserPage(items, total.toInt())
    }

    private companion object {
        const val MAX_PASSWORD_BYTES = 72
    }
}
