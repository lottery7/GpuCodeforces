package dev.gpucodeforces.backend.notification

import dev.gpucodeforces.backend.api.NotificationsApi
import dev.gpucodeforces.backend.api.model.NotificationPage
import dev.gpucodeforces.backend.auth.CurrentUser
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class NotificationsController(private val service: NotificationService) : NotificationsApi {
    override fun listNotifications(offset: Int, limit: Int): ResponseEntity<NotificationPage> =
        ResponseEntity.ok(service.list(CurrentUser.resolve()!!, offset, limit))

    override fun readNotification(id: String): ResponseEntity<Void> {
        service.read(id, CurrentUser.resolve()!!)
        return ResponseEntity.noContent().build()
    }
}
