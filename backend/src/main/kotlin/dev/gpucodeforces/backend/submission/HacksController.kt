package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.api.HacksApi
import dev.gpucodeforces.backend.api.model.CreateHackRequest
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import dev.gpucodeforces.backend.api.model.Hack as HackApiModel

@RestController
class HacksController(private val hacks: HackService) : HacksApi {
    override fun retryHack(id: String): ResponseEntity<HackApiModel> = ResponseEntity.status(HttpStatus.ACCEPTED).body(
        hacks.retry(id.toUuidOrBadRequest("Неверный взлом"), CurrentUser.resolve()!!).toApiModel(),
    )
    override fun createHack(createHackRequest: CreateHackRequest): ResponseEntity<HackApiModel> = ResponseEntity.status(HttpStatus.ACCEPTED).body(
        hacks.create(createHackRequest.submissionId.toUuidOrBadRequest("Неверная посылка"), createHackRequest.testJson, CurrentUser.resolve()!!).toApiModel(),
    )

    override fun listHacks(contestId: String): ResponseEntity<List<HackApiModel>> = ResponseEntity.ok(
        hacks.list(contestId.toUuidOrBadRequest("Неверный контест")).map { it.toApiModel() },
    )
}

private fun Hack.toApiModel() = HackApiModel(id.toString(), contestId.toString(), userId.toString(), submissionId.toString(), problemId,
    HackApiModel.StatusEnum.fromValue(status), diagnostic, createdAt.atOffset(ZoneOffset.UTC))
