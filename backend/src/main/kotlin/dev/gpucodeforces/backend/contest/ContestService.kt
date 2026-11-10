package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.common.UnauthorizedException
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import dev.gpucodeforces.backend.common.toUuidOrNotFound
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.toApiModel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import dev.gpucodeforces.backend.api.model.ContestInput as ContestInputApi

@Service
class ContestService(
    private val contestRepository: ContestRepository,
    private val contestProblemRepository: ContestProblemRepository,
    private val contestRegistrationRepository: ContestRegistrationRepository,
    private val userRepository: UserRepository,
    private val problemCatalog: ProblemCatalog,
    private val gpuProperties: GpuProperties,
    private val submissionStats: SubmissionStats,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(query: String?, phase: String?, mine: Boolean, viewer: User?): List<Contest> {
        if (mine && viewer == null) throw UnauthorizedException("Нужен вход")
        val now = Instant.now()
        val trimmed = query?.trim()?.lowercase().orEmpty()
        val filtered = contestRepository.findAll().filter { contest ->
            (phase == null || contest.phaseAt(now).value == phase) &&
                (!mine || contest.authors.any { it.id == viewer!!.id }) &&
                (
                    trimmed.isEmpty() ||
                        contest.title.lowercase().contains(trimmed) ||
                        contest.authors.any { it.handle.lowercase().contains(trimmed) }
                    )
        }
        return if (phase == "finished") filtered.sortedByDescending { it.startAt } else filtered.sortedBy { it.startAt }
    }

    @Transactional
    fun create(input: ContestInputApi, creator: User): Contest {
        validate(input)
        val format = input.format?.value ?: "standard"
        if (format == "duel" && input.authorIds != setOf(creator.id.toString())) {
            throw BadRequestException("Единственный автор дуэли - её инициатор")
        }
        val opponent = if (format == "duel") {
            val id = input.opponentId?.toUuidOrBadRequest("Соперник не найден") ?: throw BadRequestException("Выберите соперника")
            if (id == creator.id) throw BadRequestException("Нельзя вызвать на дуэль себя")
            userRepository.findById(id).orElseThrow { BadRequestException("Соперник не найден") }
        } else null
        val contest = contestRepository.save(
            Contest(
                id = UUID.randomUUID(),
                title = input.title,
                startAt = input.startAt.toInstant(),
                endAt = input.endAt.toInstant(),
                gpu = gpuProperties.name,
                authors = resolveAuthors(input.authorIds).toMutableSet(),
                format = format,
                challengerId = if (format == "duel") creator.id else null,
                opponentId = opponent?.id,
            ),
        )
        saveProblems(contest.id, input.problemIds)
        if (format == "duel") contestRegistrationRepository.save(ContestRegistration(contest.id, creator.id))
        log.info("Contest {} created by {}: {} - {}, problems {}", contest.id, creator.handle, contest.startAt, contest.endAt, input.problemIds)
        return contest
    }

    fun get(id: String): Contest =
        contestRepository.findById(id.toUuidOrNotFound("Контест не найден"))
            .orElseThrow { NotFoundApiException("Контест не найден") }

    fun problemsOf(contestId: UUID): List<ContestProblem> = contestProblemRepository.findByIdContestIdOrderByPosition(contestId)

    fun isRegistered(contestId: UUID, userId: UUID): Boolean =
        contestRegistrationRepository.existsByIdContestIdAndIdUserId(contestId, userId)

    @Transactional
    fun update(id: String, input: ContestInputApi, currentUser: User): Contest {
        val contest = get(id)
        if (contest.authors.none { it.id == currentUser.id }) {
            throw ForbiddenException("Изменять контест могут только его авторы")
        }
        if (contest.phaseAt(Instant.now()) != ContestPhase.UPCOMING) {
            throw ForbiddenException("Контест нельзя изменить после начала")
        }
        validate(input)
        if ((input.format?.value ?: "standard") != contest.format || input.opponentId != contest.opponentId?.toString()) {
            throw BadRequestException("Формат и участников дуэли нельзя изменить")
        }
        if (contest.format == "duel" && input.authorIds != setOf(contest.challengerId.toString())) {
            throw BadRequestException("Единственный автор дуэли - её инициатор")
        }
        contest.title = input.title
        contest.startAt = input.startAt.toInstant()
        contest.endAt = input.endAt.toInstant()
        contest.authors = resolveAuthors(input.authorIds).toMutableSet()
        saveProblems(contest.id, input.problemIds)
        log.info("Contest {} updated by {}: {} - {}, problems {}", contest.id, currentUser.handle, contest.startAt, contest.endAt, input.problemIds)
        return contestRepository.save(contest)
    }

    @Transactional
    fun register(id: String, currentUser: User) {
        val found = get(id)
        val contest = if (found.format == "duel") contestRepository.findForUpdate(found.id)!! else found
        if (contest.format == "duel" && currentUser.id != contest.challengerId && currentUser.id != contest.opponentId) {
            throw ForbiddenException("В дуэли участвуют только приглашённые участники")
        }
        if (contest.phaseAt(Instant.now()) == ContestPhase.FINISHED) {
            throw BadRequestException("Контест уже завершён")
        }
        if (!isRegistered(contest.id, currentUser.id)) {
            contestRegistrationRepository.save(ContestRegistration(contest.id, currentUser.id))
            log.info("{} registered for contest {}", currentUser.handle, contest.id)
        }
    }

    /**
     * Registered participants by solved problems, then by the smaller total solve time. A problem is solved by an
     * accepted submission (correct within the time limit); its solve time runs from the contest start to the
     * creation of the first accepted submission, so neither the judge queue nor the measured GPU time matters.
     * Wrong attempts cost nothing. Equal results share a place.
     */
    fun standings(id: String): List<GetContestStandings200ResponseInner> {
        val contest = get(id)
        if (contest.phaseAt(Instant.now()) == ContestPhase.UPCOMING) return emptyList()
        val solvedAt = submissionStats.firstAcceptedInContest(contest.id)
        val userIds = contestRegistrationRepository.findByIdContestId(contest.id).map { it.id.userId }
        val rows = userRepository.findAllById(userIds).map { participant ->
            val times = solvedAt[participant.id].orEmpty()
                .mapValues { (_, at) -> Duration.between(contest.startAt, at).seconds.coerceAtLeast(0).toInt() }
            Triple(participant, times, times.values.sum())
        }
        val order = compareByDescending<Triple<User, Map<String, Int>, Int>> { it.second.size }.thenBy { it.third }
        return rows.sortedWith(order.thenBy { it.first.handle.lowercase() }).map { row ->
            val rank = 1 + rows.count { order.compare(it, row) < 0 }
            GetContestStandings200ResponseInner(rank, row.first.toApiModel(), row.second.size, row.third, row.second)
        }
    }

    fun buildRulesMarkdown(contest: Contest, problems: List<ContestProblem>, viewer: User?): String {
        val header = "## Правила\n\n" +
            (if (contest.format == "duel") "Дуэль двух участников. " else "") +
            "Задача решена, если принята хотя бы одна посылка: верный ответ на всех тестах в пределах лимита времени. " +
            "Правильный ответ в угадывании считается принятой посылкой. " +
            "Место определяется числом решённых задач, затем меньшим суммарным временем сдачи. Время сдачи задачи - " +
            "от начала контеста до отправки первой принятой посылки; длительность проверки, время работы решения на GPU " +
            "и неверные попытки на место не влияют. Равные результаты делят место."
        val isAuthor = viewer != null && contest.authors.any { it.id == viewer.id }
        if (contest.phaseAt(Instant.now()) == ContestPhase.UPCOMING && !isAuthor) return header
        val versions = problemCatalog.versions(problems.mapNotNull { it.problemVersionId })
        val list = problems.sortedBy { it.position }.mapIndexed { index, problem ->
            val title = problem.problemVersionId?.let { versions[it]?.title } ?: problem.id.problemId
            "- ${'A' + index}. $title"
        }.joinToString("\n")
        return "$header\n\n## Задачи\n\n$list"
    }

    private fun validate(input: ContestInputApi) {
        val format = input.format?.value ?: "standard"
        if (format !in setOf("standard", "duel")) throw BadRequestException("Неизвестный формат контеста")
        if (format == "duel" && input.problemIds.size != 1) throw BadRequestException("Для дуэли нужна ровно одна задача")
        if (format == "standard" && input.opponentId != null) throw BadRequestException("Соперник задаётся только для дуэли")
        val now = Instant.now()
        if (!input.startAt.toInstant().isAfter(now)) throw BadRequestException("Начало контеста должно быть в будущем")
        if (!input.endAt.toInstant().isAfter(input.startAt.toInstant())) {
            throw BadRequestException("Конец контеста должен быть позже начала")
        }
    }

    private fun resolveAuthors(authorIds: Set<String>): List<User> = authorIds.map { id ->
        val uuid = id.toUuidOrBadRequest("Автор не найден")
        userRepository.findById(uuid).orElseThrow { BadRequestException("Автор не найден") }
    }

    private fun saveProblems(contestId: UUID, problemIds: Set<String>) {
        val versions = problemCatalog.publishedVersions(problemIds)
        contestProblemRepository.deleteByIdContestId(contestId)
        contestProblemRepository.flush()
        contestProblemRepository.saveAll(
            versions.mapIndexed { index, version -> ContestProblem(contestId, version.problemId, index, version.id) },
        )
    }
}
