package dev.gpucodeforces.backend.notification

import dev.gpucodeforces.backend.api.model.DuelStatus
import dev.gpucodeforces.backend.api.model.NotificationPage
import dev.gpucodeforces.backend.api.model.NotificationType
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.common.toUuidOrNotFound
import dev.gpucodeforces.backend.duel.DuelRequestRepository
import dev.gpucodeforces.backend.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import dev.gpucodeforces.backend.api.model.Notification as NotificationApi

@Service
class NotificationService(private val notifications: NotificationRepository, private val requests: DuelRequestRepository) {
    @Transactional(readOnly = true)
    fun list(user: User, offset: Int, limit: Int): NotificationPage {
        if (offset < 0 || limit !in 1..100) throw BadRequestException("Неверные параметры списка")
        val rows = notifications.list(user.id, offset, limit)
        val duels = requests.findAllById(rows.map { it.duelRequestId }.distinct()).associateBy { it.id }
        val now = Instant.now()
        val items = rows.map { notification ->
            val request = duels.getValue(notification.duelRequestId)
            val status = if (request.status in setOf("queued", "invited") && !request.expiresAt.isAfter(now)) "expired" else request.status
            NotificationApi()
                .id(notification.id.toString())
                .type(NotificationType.fromValue(notification.type))
                .message(notification.message)
                .duelRequestId(notification.duelRequestId.toString())
                .duelStatus(DuelStatus.fromValue(status))
                .contestId((notification.contestId ?: request.contestId)?.toString())
                .createdAt(notification.createdAt.atOffset(ZoneOffset.UTC))
                .readAt(notification.readAt?.atOffset(ZoneOffset.UTC))
        }
        return NotificationPage(items, notifications.countByUserId(user.id).toInt(), notifications.countByUserIdAndReadAtIsNull(user.id).toInt())
    }

    @Transactional
    fun read(id: String, user: User) {
        val notification = notifications.findById(id.toUuidOrNotFound("Уведомление не найдено"))
            .orElseThrow { NotFoundApiException("Уведомление не найдено") }
        if (notification.userId != user.id) throw NotFoundApiException("Уведомление не найдено")
        if (notification.readAt == null) notification.readAt = Instant.now()
    }
}
