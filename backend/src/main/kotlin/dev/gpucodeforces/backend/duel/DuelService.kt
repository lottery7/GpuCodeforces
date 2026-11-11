package dev.gpucodeforces.backend.duel

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
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

@Service
class DuelService(
    private val requests: DuelRequestRepository,
    private val activeUsers: ActiveDuelRepository,
    private val matchmakingLock: MatchmakingLockRepository,
    private val users: UserRepository,
    private val catalog: ProblemCatalog,
    private val contests: ContestRepository,
    private val contestProblems: ContestProblemRepository,
    private val registrations: ContestRegistrationRepository,
    private val notifications: NotificationRepository,
    private val processors: ProcessorCatalog,
) {
    /** Topics of the problems a duel can take, on [processor] or on any processor of the platform. */
    fun topics(processor: String? = null): List<String> =
        codingPool(processor).flatMap { it.tags }.distinct().sortedBy { it.lowercase() }

    @Transactional
    fun create(mode: String, selectedTopics: Collection<String>, opponentHandle: String?, processor: String, creator: User): DuelRequest {
        val now = lockAndExpire()
        if (mode !in setOf("random", "invite")) throw BadRequestException("Неизвестный способ поиска соперника")
        processors.require(processor)
        if (activeRequest(creator.id) != null) throw BadRequestException("Завершите текущую дуэль или отмените поиск")
        val pool = codingPool(processor)
        val knownTopics = pool.flatMap { it.tags }.associateBy { it.lowercase() }
        if (selectedTopics.size > 20) throw BadRequestException("Можно выбрать не больше 20 тем")
        val topics = selectedTopics.map { topic ->
            knownTopics[topic.trim().lowercase()] ?: throw BadRequestException("Тема $topic недоступна для дуэли")
        }.distinct()
        val candidates = pool.filter { matchesTopics(it, topics) }
        if (candidates.isEmpty()) throw BadRequestException("Нет доступных задач по выбранным темам на процессоре $processor")
        val opponent = if (mode == "invite") {
            val handle = opponentHandle?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("Укажите ник соперника")
            users.findByHandleIgnoreCase(handle)?.also {
                if (it.id == creator.id) throw BadRequestException("Нельзя вызвать на дуэль себя")
            } ?: throw BadRequestException("Пользователь с таким ником не найден")
        } else {
            if (opponentHandle != null) throw BadRequestException("Ник соперника не нужен для случайного поиска")
            null
        }
        val request = requests.save(DuelRequest(
            id = UUID.randomUUID(), mode = mode, status = if (mode == "random") "queued" else "invited",
            creatorId = creator.id, opponentId = opponent?.id, topics = topics, processor = processor, createdAt = now,
            expiresAt = now.plus(if (mode == "random") QUEUE_TTL else INVITE_TTL),
        ))
        reserve(creator.id, request.id)
        if (opponent != null) {
            notify(opponent.id, "duel_invitation", "${creator.handle} приглашает вас на дуэль", request, now)
            return request
        }
        val waiting = requests.findByModeAndStatusOrderByCreatedAt("random", "queued")
        for (peer in waiting) {
            // a random opponent is one who waits for a duel on the same processor
            if (peer.creatorId == creator.id || peer.processor != processor || activeRequest(peer.creatorId)?.id != peer.id) continue
            val common = candidates.filter { matchesTopics(it, peer.topics) }
            if (common.isEmpty()) continue
            val peerUser = users.findById(peer.creatorId).orElseThrow()
            val contest = start(peerUser, creator, common.random(), processor, now)
            matched(peer, creator.id, contest)
            matched(request, peer.creatorId, contest)
            notify(peer.creatorId, "duel_started", "Соперник найден. Дуэль началась", peer, now)
            notify(creator.id, "duel_started", "Соперник найден. Дуэль началась", request, now)
            break
        }
        return request
    }

    @Transactional
    fun current(user: User): DuelRequest? {
        lockAndExpire()
        return activeRequest(user.id)
    }

    @Transactional
    fun get(id: String, viewer: User): DuelRequest {
        lockAndExpire()
        return visible(id, viewer)
    }

    @Transactional
    fun accept(id: String, recipient: User): DuelRequest {
        val now = lockAndExpire()
        val request = visible(id, recipient)
        if (request.mode != "invite" || request.opponentId != recipient.id) throw ForbiddenException("Принять приглашение может только соперник")
        if (request.status == "matched") return request
        if (request.status != "invited") throw BadRequestException("Приглашение уже недоступно")
        val recipientRequest = activeRequest(recipient.id)
        if (recipientRequest?.status == "matched") throw BadRequestException("Сначала завершите текущую дуэль")
        val creator = users.findById(request.creatorId).orElseThrow()
        if (activeRequest(creator.id)?.id != request.id) throw BadRequestException("Приглашение уже недоступно")
        val problem = codingPool(request.processor).filter { matchesTopics(it, request.topics) }.randomOrNull()
            ?: throw BadRequestException("Нет доступных задач по выбранным темам")
        if (recipientRequest != null) cancelWaiting(recipientRequest, now)
        val contest = start(creator, recipient, problem, request.processor, now)
        matched(request, recipient.id, contest)
        reserve(creator.id, request.id)
        reserve(recipient.id, request.id)
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
        release(request)
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
        cancelWaiting(request, now)
        return request
    }

    @Scheduled(fixedDelayString = "PT1M")
    @Transactional
    fun expire() {
        lockAndExpire()
    }

    private fun lockAndExpire(): Instant {
        checkNotNull(matchmakingLock.acquire()) { "Matchmaking lock is missing" }
        val now = Instant.now()
        requests.findByStatusInAndExpiresAtLessThanEqual(WAITING, now).forEach { it.status = "expired" }
        val active = activeUsers.findByRequestIdIsNotNull()
        val byId = requests.findAllById(active.mapNotNull { it.requestId }.distinct()).associateBy { it.id }
        active.forEach { slot ->
            val request = byId.getValue(slot.requestId!!)
            if (!request.expiresAt.isAfter(now) || request.status !in ACTIVE) slot.requestId = null
        }
        return now
    }

    /** Published problems one writes code for, solvable on [processor] (any processor of the platform when null). */
    private fun codingPool(processor: String? = null): List<ProblemVersion> = catalog.currentVersions().filter { version ->
        !version.draft && version.format != "guess" &&
            version.processors.any { (processor == null || it == processor) && processors.isEnabled(it) }
    }

    private fun matchesTopics(problem: ProblemVersion, topics: Collection<String>): Boolean =
        topics.isEmpty() || problem.tags.any { tag -> topics.any { it.equals(tag, ignoreCase = true) } }

    private fun activeRequest(userId: UUID): DuelRequest? =
        activeUsers.findById(userId).orElse(null)?.requestId?.let { requests.findById(it).orElseThrow() }

    private fun reserve(userId: UUID, requestId: UUID) {
        val slot = activeUsers.findById(userId).orElseGet { activeUsers.save(ActiveDuel(userId)) }
        slot.requestId = requestId
    }

    private fun release(request: DuelRequest) {
        activeUsers.findByRequestIdIsNotNull().filter { it.requestId == request.id }.forEach { it.requestId = null }
    }

    private fun cancelWaiting(request: DuelRequest, now: Instant) {
        request.status = "cancelled"
        release(request)
        if (request.mode == "invite") {
            notify(request.opponentId!!, "duel_cancelled", "Приглашение на дуэль отменено", request, now)
        }
    }

    private fun visible(id: String, viewer: User): DuelRequest {
        val request = requests.findById(id.toUuidOrNotFound("Дуэль не найдена")).orElseThrow { NotFoundApiException("Дуэль не найдена") }
        if (viewer.id != request.creatorId && viewer.id != request.opponentId) throw ForbiddenException("Эта дуэль доступна только её участникам")
        return request
    }

    private fun start(first: User, second: User, problem: ProblemVersion, processor: String, now: Instant): Contest {
        val contest = contests.save(Contest(
            id = UUID.randomUUID(), title = "Дуэль: ${first.handle} vs ${second.handle}", startAt = now, endAt = now.plus(DUEL_DURATION),
            processor = processor, authors = mutableSetOf(first), format = "duel", challengerId = first.id, opponentId = second.id,
        ))
        contestProblems.save(ContestProblem(contest.id, problem.problemId, 0, problem.id))
        registrations.saveAll(listOf(ContestRegistration(contest.id, first.id), ContestRegistration(contest.id, second.id)))
        return contest
    }

    private fun matched(request: DuelRequest, opponentId: UUID, contest: Contest) {
        request.status = "matched"
        request.opponentId = opponentId
        request.contestId = contest.id
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
        private val DUEL_DURATION = Duration.ofMinutes(30)
    }
}
