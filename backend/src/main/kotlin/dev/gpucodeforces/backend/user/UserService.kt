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
        val passwordHash = passwordEncoder.encode(password) ?: error("PasswordEncoder returned null")
        return userRepository.save(User(handle = handle, email = email, passwordHash = passwordHash))
    }

    fun getById(id: String): User {
        val uuid = id.toUuidOrNotFound("Пользователь не найден")
        return userRepository.findById(uuid).orElseThrow { NotFoundApiException("Пользователь не найден") }
    }

    fun search(query: String?, sortByRating: Boolean, offset: Int, limit: Int): UserPage {
        val trimmed = query?.trim() ?: ""
        if (trimmed.isEmpty()) {
            val items = if (sortByRating) userRepository.pageRated(limit, offset) else userRepository.pageByHandle(limit, offset)
            val total = if (sortByRating) userRepository.countByRatingIsNotNull() else userRepository.count()
            return UserPage(items, total.toInt())
        }
        if (trimmed.length < 3) {
            val items = if (sortByRating) userRepository.searchShortRated(trimmed, limit, offset)
                else userRepository.searchShortByHandle(trimmed, limit, offset)
            val total = if (sortByRating) userRepository.countShortRated(trimmed) else userRepository.countShortByHandle(trimmed)
            return UserPage(items, total.toInt())
        }
        val pattern = "%${trimmed.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val items = if (sortByRating) {
            userRepository.searchRated(pattern, limit, offset)
        } else {
            userRepository.searchByHandle(pattern, limit, offset)
        }
        val total = if (sortByRating) userRepository.countRated(pattern) else userRepository.countByHandle(pattern)
        return UserPage(items, total.toInt())
    }
}
