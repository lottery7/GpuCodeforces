package dev.gpucodeforces.backend.duel

import dev.gpucodeforces.backend.common.ContentLanguage
import dev.gpucodeforces.backend.api.DuelsApi
import dev.gpucodeforces.backend.api.model.CreateDuelRequest
import dev.gpucodeforces.backend.api.model.DuelMode
import dev.gpucodeforces.backend.api.model.DuelStatus
import dev.gpucodeforces.backend.api.model.DuelPage
import dev.gpucodeforces.backend.api.model.DuelProblem
import dev.gpucodeforces.backend.api.model.DuelProblemPage
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import dev.gpucodeforces.backend.api.model.DuelRequest as DuelRequestApi

@RestController
class DuelsController(private val service: DuelService, private val views: DuelViews) : DuelsApi {
    override fun listDuelProblems(processor: String?, q: String?, difficulty: String?, offset: Int, limit: Int): ResponseEntity<DuelProblemPage> {
        val page = service.problems(processor, q, difficulty, offset, limit)
        val language = ContentLanguage.current()
        val items = page.items.map {
            DuelProblem().id(it.problemId).code(it.code).title(it.title(language)).difficulty(DuelProblem.DifficultyEnum.fromValue(it.difficulty))
        }
        return ResponseEntity.ok(DuelProblemPage(items, page.total))
    }

    override fun listDuels(processor: String?, phase: String, offset: Int, limit: Int): ResponseEntity<DuelPage> {
        val page = service.list(processor, phase, offset, limit, CurrentUser.resolve()!!)
        return ResponseEntity.ok(DuelPage(views.models(page.items), page.total))
    }

    override fun createDuel(createDuelRequest: CreateDuelRequest): ResponseEntity<DuelRequestApi> {
        val user = CurrentUser.resolve()!!
        val request = with(createDuelRequest) { service.create(mode.value, opponentHandle, problemId, processor, user) }
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
class DuelViews(private val users: UserRepository, private val catalog: ProblemCatalog, private val contests: ContestRepository) {
    fun model(request: DuelRequest): DuelRequestApi = models(listOf(request)).single()

    /** For the request being answered: the title of the problem comes in its language. */
    fun models(requests: List<DuelRequest>): List<DuelRequestApi> {
        val language = ContentLanguage.current()
        val people = users.findAllById(requests.flatMap { listOfNotNull(it.creatorId, it.opponentId) }.toSet()).associateBy { it.id }
        val versions = catalog.versions(requests.mapNotNull { it.problemVersionId }.toSet())
        val rooms = contests.findAllById(requests.mapNotNull { it.contestId }.toSet()).associateBy { it.id }
        return requests.map { request ->
            val problem = request.problemVersionId?.let { versions[it] }
            val contest = request.contestId?.let { rooms[it] }
            DuelRequestApi()
            .id(request.id.toString())
            .mode(DuelMode.fromValue(request.mode))
            .status(DuelStatus.fromValue(request.status))
            .processor(request.processor)
            .creator(people.getValue(request.creatorId).toApiModel())
            .opponent(request.opponentId?.let { people.getValue(it).toApiModel() })
            .problemId(problem?.problemId)
            .problemTitle(problem?.title(language))
            .difficulty(problem?.difficulty?.let { DuelRequestApi.DifficultyEnum.fromValue(it) })
            .startAt(contest?.startAt?.atOffset(ZoneOffset.UTC))
            .surrenderedUserId(contest?.surrenderedUserId?.toString())
            .createdAt(request.createdAt.atOffset(ZoneOffset.UTC))
            .expiresAt((contest?.endAt ?: request.expiresAt).atOffset(ZoneOffset.UTC))
            .contestId(request.contestId?.toString())
        }
    }
}
