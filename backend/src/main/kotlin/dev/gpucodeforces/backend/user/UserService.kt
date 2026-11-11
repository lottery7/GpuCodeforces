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

    /** By handle, or, with [ratingProcessor], the users rated on that processor by their place there. */
    fun search(query: String?, ratingProcessor: String?, offset: Int, limit: Int): UserPage {
        val trimmed = query?.trim() ?: ""
        val items = if (ratingProcessor != null) {
            userRepository.searchRated(ratingProcessor, trimmed, limit, offset)
        } else {
            userRepository.searchByHandle(trimmed, limit, offset)
        }
        val total = if (ratingProcessor != null) userRepository.countRated(ratingProcessor, trimmed) else userRepository.countByHandle(trimmed)
        return UserPage(items, total.toInt())
    }
}
