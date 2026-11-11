package dev.gpucodeforces.backend.duel

import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.common.toUuidOrNotFound
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRegistration
import dev.gpucodeforces.backend.contest.ContestRegistrationRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.notification.Notification
import dev.gpucodeforces.backend.notification.NotificationRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class DuelList(val items: List<DuelRequest>, val total: Int)
data class DuelProblemList(val items: List<ProblemVersion>, val total: Int)

@Service
class DuelService(
    private val requests: DuelRequestRepository,
    private val matchmakingLock: MatchmakingLockRepository,
    private val users: UserRepository,
    private val catalog: ProblemCatalog,
    private val contests: ContestRepository,
    private val contestProblems: ContestProblemRepository,
    private val registrations: ContestRegistrationRepository,
    private val notifications: NotificationRepository,
    private val processors: ProcessorCatalog,
    private val references: ReferenceMeasurements,
) {
    fun problems(processor: String?, query: String?, difficulty: String?, offset: Int, limit: Int): DuelProblemList {
        processor?.let { processors.require(it) }
        if (difficulty != null && difficulty !in setOf("easy", "medium", "hard") || offset < 0 || limit !in 1..100) {
            throw BadRequestException("Неверные параметры списка задач")
        }
        val term = query?.trim().orEmpty()
        val rows = codingPool(processor).filter { problem ->
            (difficulty == null || problem.difficulty == difficulty) &&
                (term.isEmpty() || problem.title.contains(term, true) || problem.problemId.contains(term, true) || problem.code.contains(term, true))
        }.sortedWith(compareBy<ProblemVersion> { it.code }.thenBy { it.title }.thenBy { it.problemId })
        return DuelProblemList(rows.drop(offset).take(limit), rows.size)
    }

    @Transactional
    fun create(mode: String, opponentHandle: String?, problemId: String?, processor: String, creator: User): DuelRequest {
        val now = lockAndExpire()
        if (mode !in setOf("random", "invite")) throw BadRequestException("Неизвестный способ поиска соперника")
        processors.require(processor)
        if (mode == "random" && (opponentHandle != null || problemId != null)) {
            throw BadRequestException("Случайный поиск сам выбирает соперника и задачу")
        }
        if (mode == "random") {
            requests.findByCreatorIdAndModeAndStatus(creator.id, "random", "queued")?.let {
                if (it.processor != processor) throw BadRequestException("Отмените поиск на процессоре ${it.processor}")
                matchWaiting(now)
                return it
            }
        }
        val selected = problemId?.let {
            catalog.currentVersion(it)?.takeIf { version -> !version.draft && version.format != "guess" && processor in version.processors }
                ?: throw BadRequestException("Задача недоступна для дуэли")
        }
        if (selected == null && codingPool(processor).isEmpty()) throw BadRequestException("Нет доступных задач для дуэли на процессоре $processor")
        val opponent = if (mode == "invite") {
            val handle = opponentHandle?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("Укажите ник соперника")
            users.findByHandleIgnoreCase(handle)?.also {
                if (it.id == creator.id) throw BadRequestException("Нельзя вызвать на дуэль себя")
            } ?: throw BadRequestException("Пользователь с таким ником не найден")
        } else null
        if (opponent != null) {
            requests.findByCreatorIdAndStatusInOrderByCreatedAtDesc(creator.id, WAITING)
                .firstOrNull { it.status == "invited" && it.opponentId == opponent.id && it.processor == processor && it.problemVersionId == selected?.id }
                ?.let { return it }
        }
        val request = requests.save(DuelRequest(
            id = UUID.randomUUID(), mode = mode, status = if (mode == "random") "queued" else "invited",
            creatorId = creator.id, opponentId = opponent?.id, topics = emptyList(), processor = processor, createdAt = now,
            expiresAt = now.plus(if (mode == "random") QUEUE_TTL else INVITE_TTL), problemVersionId = selected?.id,
        ))
        if (opponent != null) notify(opponent.id, "duel_invitation", "${creator.handle} приглашает вас на дуэль", request, now)
        else matchWaiting(now)
        return request
    }

    @Transactional
    fun current(user: User): DuelRequest? {
        val now = lockAndExpire()
        matchWaiting(now)
        return requests.findByCreatorIdAndStatusInOrderByCreatedAtDesc(user.id, WAITING).firstOrNull()
    }

    @Transactional
    fun list(processor: String?, phase: String, offset: Int, limit: Int, user: User): DuelList {
        processor?.let { processors.require(it) }
        if (phase !in setOf("active", "finished", "all") || offset < 0 || limit !in 1..100) throw BadRequestException("Неверные параметры списка")
        val now = lockAndExpire()
        matchWaiting(now)
        val rows = requests.visibleTo(user.id)
            .filter { processor == null || it.processor == processor }
            .groupBy { it.contestId ?: it.id }
            .values.map { group -> group.firstOrNull { it.creatorId == user.id } ?: group.first() }
            .filter { request ->
                val active = request.status in ACTIVE && request.expiresAt.isAfter(now)
                phase == "all" || phase == "active" && active || phase == "finished" && !active
            }
            .sortedWith(compareByDescending<DuelRequest> { it.createdAt }.thenBy { it.id })
        return DuelList(rows.drop(offset).take(limit), rows.size)
    }

    @Transactional
    fun get(id: String, viewer: User): DuelRequest {
        val now = lockAndExpire()
        matchWaiting(now)
        return visible(id, viewer)
    }

    @Transactional
    fun accept(id: String, recipient: User): DuelRequest {
        val now = lockAndExpire()
        val request = visible(id, recipient)
        if (request.mode != "invite" || request.opponentId != recipient.id) throw ForbiddenException("Принять приглашение может только соперник")
        if (request.status == "matched") return request
        if (request.status != "invited") throw BadRequestException("Приглашение уже недоступно")
        processors.require(request.processor)
        val environment = references.environmentKey(request.processor) ?: throw ServiceUnavailableException("Ожидаем доступное окружение проверки")
        val creator = users.findById(request.creatorId).orElseThrow()
        val pinned = request.problemVersionId
        val problem = if (pinned != null) catalog.versions(listOf(pinned))[pinned] ?: throw BadRequestException("Выбранная версия задачи недоступна")
            else codingPool(request.processor).randomOrNull() ?: throw BadRequestException("Нет доступных задач для дуэли")
        if (request.processor !in problem.processors) throw BadRequestException("Задача недоступна на процессоре ${request.processor}")
        val startedAt = Instant.now()
        if (!request.expiresAt.isAfter(startedAt)) throw BadRequestException("Срок приглашения истёк")
        val contest = start(creator, recipient, problem, request.processor, environment, startedAt)
        matched(request, recipient.id, contest, problem)
        notify(creator.id, "duel_started", "${recipient.handle} принял приглашение. Дуэль началась", request, now)
        notify(recipient.id, "duel_started", "Вы приняли приглашение. Дуэль началась", request, now)
        return request
    }

    @Transactional
    fun decline(id: String, recipient: User): DuelRequest {
        val now = lockAndExpire()
        val request = visible(id, recipient)
        if (request.mode != "invite" || request.opponentId != recipient.id) throw ForbiddenException("Отклонить приглашение может только соперник")
        if (request.status == "declined") return request
        if (request.status != "invited") throw BadRequestException("Приглашение уже недоступно")
        request.status = "declined"
        notify(request.creatorId, "duel_declined", "${recipient.handle} отклонил приглашение", request, now)
        return request
    }

    @Transactional
    fun cancel(id: String, creator: User): DuelRequest {
        val now = lockAndExpire()
        val request = visible(id, creator)
        if (request.creatorId != creator.id) throw ForbiddenException("Отменить поиск может только его автор")
        if (request.status == "cancelled") return request
        if (request.status !in WAITING) throw BadRequestException("Этот поиск уже завершён")
        request.status = "cancelled"
        if (request.mode == "invite") notify(request.opponentId!!, "duel_cancelled", "Приглашение на дуэль отменено", request, now)
        return request
    }

    @Transactional
    fun surrender(contestId: String, user: User) {
        lockAndExpire()
        val contest = contests.findForUpdate(contestId.toUuidOrNotFound("Дуэль не найдена")) ?: throw NotFoundApiException("Дуэль не найдена")
        val now = Instant.now()
        if (contest.format != "duel") throw BadRequestException("Сдаться можно только в дуэли")
        if (user.id != contest.challengerId && user.id != contest.opponentId) throw ForbiddenException("Сдаться может только участник дуэли")
        if (contest.surrenderedUserId == user.id) return
        if (contest.surrenderedUserId != null) throw BadRequestException("Соперник уже завершил дуэль")
        if (now.isBefore(contest.startAt) || !now.isBefore(contest.endAt)) throw BadRequestException("Дуэль уже не идёт")
        contest.surrenderedUserId = user.id
        contest.endAt = now
        val related = requests.findByContestId(contest.id)
        related.forEach { it.expiresAt = now }
        val opponentId = if (user.id == contest.challengerId) contest.opponentId!! else contest.challengerId!!
        val request = related.firstOrNull { it.creatorId == opponentId } ?: related.firstOrNull()
        if (request != null) notify(opponentId, "duel_surrendered", "${user.handle} сдался. Дуэль завершена", request, now)
    }

    @Scheduled(fixedDelayString = "\${platform.duels.match-interval:PT5S}")
    @Transactional
    fun expire() {
        val now = lockAndExpire()
        matchWaiting(now)
    }

    private fun lockAndExpire(): Instant {
        checkNotNull(matchmakingLock.acquire()) { "Matchmaking lock is missing" }
        val now = Instant.now()
        requests.findByStatusInAndExpiresAtLessThanEqual(WAITING, now).forEach { it.status = "expired" }
        return now
    }

    private fun codingPool(processor: String? = null): List<ProblemVersion> = catalog.currentVersions().filter { version ->
        !version.draft && version.format != "guess" &&
            version.processors.any { (processor == null || it == processor) && processors.isEnabled(it) }
    }

    private fun matchWaiting(now: Instant) {
        val waiting = requests.findByModeAndStatusOrderByCreatedAt("random", "queued")
        for (first in waiting) {
            if (first.status != "queued" || !processors.isEnabled(first.processor)) continue
            val second = waiting.firstOrNull { it.status == "queued" && it.creatorId != first.creatorId && it.processor == first.processor } ?: continue
            val environment = references.environmentKey(first.processor) ?: continue
            val pool = codingPool(first.processor)
            if (pool.isEmpty()) continue
            val firstUser = users.findById(first.creatorId).orElseThrow()
            val secondUser = users.findById(second.creatorId).orElseThrow()
            val problem = pool.random()
            val startedAt = Instant.now()
            val expired = listOf(first, second).filter { !it.expiresAt.isAfter(startedAt) }
            if (expired.isNotEmpty()) {
                expired.forEach { it.status = "expired" }
                continue
            }
            val contest = start(firstUser, secondUser, problem, first.processor, environment, startedAt)
            matched(first, second.creatorId, contest, problem)
            matched(second, first.creatorId, contest, problem)
            notify(first.creatorId, "duel_started", "Соперник найден. Дуэль началась", first, now)
            notify(second.creatorId, "duel_started", "Соперник найден. Дуэль началась", second, now)
        }
    }

    private fun visible(id: String, viewer: User): DuelRequest {
        val request = requests.findById(id.toUuidOrNotFound("Дуэль не найдена")).orElseThrow { NotFoundApiException("Дуэль не найдена") }
        if (viewer.id != request.creatorId && viewer.id != request.opponentId) throw ForbiddenException("Эта дуэль доступна только её участникам")
        return request
    }

    private fun start(first: User, second: User, problem: ProblemVersion, processor: String, environment: String, now: Instant): Contest {
        val hours = when (problem.difficulty) {
            "easy" -> 1L
            "medium" -> 2L
            "hard" -> 3L
            else -> throw BadRequestException("Неизвестная сложность задачи")
        }
        val contest = contests.save(Contest(
            id = UUID.randomUUID(), title = "Дуэль: ${first.handle} vs ${second.handle}", startAt = now, endAt = now.plus(Duration.ofHours(hours)),
            processor = processor, authors = mutableSetOf(first), format = "duel", challengerId = first.id, opponentId = second.id,
            benchmarkEnvironmentKey = environment,
        ))
        contestProblems.save(ContestProblem(contest.id, problem.problemId, 0, problem.id))
        registrations.saveAll(listOf(ContestRegistration(contest.id, first.id), ContestRegistration(contest.id, second.id)))
        return contest
    }

    private fun matched(request: DuelRequest, opponentId: UUID, contest: Contest, problem: ProblemVersion) {
        request.status = "matched"
        request.opponentId = opponentId
        request.contestId = contest.id
        request.problemVersionId = problem.id
        request.expiresAt = contest.endAt
    }

    private fun notify(userId: UUID, type: String, message: String, request: DuelRequest, now: Instant) {
        notifications.save(Notification(UUID.randomUUID(), userId, type, message, request.id, request.contestId, now))
    }

    companion object {
        private val WAITING = setOf("queued", "invited")
        private val ACTIVE = WAITING + "matched"
        private val QUEUE_TTL = Duration.ofMinutes(15)
        private val INVITE_TTL = Duration.ofHours(24)
    }
}
