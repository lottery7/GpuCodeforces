package dev.gpucodeforces.backend.duel

import dev.gpucodeforces.backend.api.DuelsApi
import dev.gpucodeforces.backend.api.model.CreateDuelRequest
import dev.gpucodeforces.backend.api.model.DuelMode
import dev.gpucodeforces.backend.api.model.DuelStatus
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import dev.gpucodeforces.backend.api.model.DuelRequest as DuelRequestApi

@RestController
class DuelsController(private val service: DuelService, private val views: DuelViews) : DuelsApi {
    override fun listDuelTopics(): ResponseEntity<List<String>> = ResponseEntity.ok(service.topics())

    override fun createDuel(createDuelRequest: CreateDuelRequest): ResponseEntity<DuelRequestApi> {
        val user = CurrentUser.resolve()!!
        val request = with(createDuelRequest) { service.create(mode.value, topics, opponentHandle, user) }
        return ResponseEntity.status(HttpStatus.CREATED).body(views.model(request))
    }

    override fun getCurrentDuel(): ResponseEntity<DuelRequestApi> {
        val request = service.current(CurrentUser.resolve()!!) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(views.model(request))
    }

    override fun getDuel(id: String): ResponseEntity<DuelRequestApi> =
        ResponseEntity.ok(views.model(service.get(id, CurrentUser.resolve()!!)))

    override fun acceptDuel(id: String): ResponseEntity<DuelRequestApi> =
        ResponseEntity.ok(views.model(service.accept(id, CurrentUser.resolve()!!)))

    override fun declineDuel(id: String): ResponseEntity<DuelRequestApi> =
        ResponseEntity.ok(views.model(service.decline(id, CurrentUser.resolve()!!)))

    override fun cancelDuel(id: String): ResponseEntity<DuelRequestApi> =
        ResponseEntity.ok(views.model(service.cancel(id, CurrentUser.resolve()!!)))
}

@Component
class DuelViews(private val users: UserRepository) {
    fun model(request: DuelRequest): DuelRequestApi {
        val people = users.findAllById(listOfNotNull(request.creatorId, request.opponentId)).associateBy { it.id }
        return DuelRequestApi()
            .id(request.id.toString())
            .mode(DuelMode.fromValue(request.mode))
            .status(DuelStatus.fromValue(request.status))
            .creator(people.getValue(request.creatorId).toApiModel())
            .opponent(request.opponentId?.let { people.getValue(it).toApiModel() })
            .topics(request.topics)
            .createdAt(request.createdAt.atOffset(ZoneOffset.UTC))
            .expiresAt(request.expiresAt.atOffset(ZoneOffset.UTC))
            .contestId(request.contestId?.toString())
    }
}
