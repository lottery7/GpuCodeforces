package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.common.UnauthorizedException
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import dev.gpucodeforces.backend.common.toUuidOrNotFound
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.problem.limitName
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.toApiModel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.transaction.annotation.Transactional
import dev.gpucodeforces.judge.api.Processors
import java.time.Duration
import java.time.Instant
import java.util.UUID
import dev.gpucodeforces.backend.api.model.ContestInput as ContestInputApi
import jakarta.persistence.criteria.Predicate

@Service
class ContestService(
    private val contestRepository: ContestRepository,
    private val contestProblemRepository: ContestProblemRepository,
    private val contestRegistrationRepository: ContestRegistrationRepository,
    private val userRepository: UserRepository,
    private val problemCatalog: ProblemCatalog,
    private val processorCatalog: ProcessorCatalog,
    private val submissionStats: SubmissionStats,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(query: String?, phase: String?, mine: Boolean, processor: String?, viewer: User?): List<Contest> {
        if (mine && viewer == null) throw UnauthorizedException("Нужен вход")
        processor?.let(processorCatalog::require)
        val now = Instant.now()
        val trimmed = query?.trim()?.lowercase().orEmpty()
        if (phase != null && ContestPhase.entries.none { it.value == phase }) return emptyList()
        val filter = Specification<Contest> { root, criteria, builder ->
            val conditions = mutableListOf<Predicate>()
            when (phase) {
                "upcoming" -> conditions += builder.greaterThan(root.get("startAt"), now)
                "live" -> {
                    conditions += builder.lessThanOrEqualTo(root.get("startAt"), now)
                    conditions += builder.greaterThan(root.get("endAt"), now)
                }
                "finished" -> conditions += builder.lessThanOrEqualTo(root.get("endAt"), now)
            }
            if (processor != null) conditions += builder.equal(root.get<String>("processor"), processor)
            if (mine) conditions += builder.isMember(viewer!!, root.get("authors"))
            if (trimmed.isNotEmpty()) {
                val pattern = "%${trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
                val authorMatches = criteria.subquery(Int::class.javaObjectType)
                val authors = authorMatches.correlate(root).join<Contest, User>("authors")
                authorMatches.select(builder.literal(1))
                    .where(builder.like(builder.lower(authors.get("handle")), pattern, '\\'))
                conditions += builder.or(builder.like(builder.lower(root.get("title")), pattern, '\\'), builder.exists(authorMatches))
            }
            builder.and(*conditions.toTypedArray())
        }
        val direction = if (phase == "finished") Sort.Direction.DESC else Sort.Direction.ASC
        return contestRepository.findAll(filter, Sort.by(direction, "startAt", "id"))
    }

    @Transactional
    fun create(input: ContestInputApi, creator: User): Contest {
        validate(input)
        val versions = problemsFor(input)
        val contest = contestRepository.save(
            Contest(
                id = UUID.randomUUID(),
                title = input.title,
                startAt = input.startAt.toInstant(),
                endAt = input.endAt.toInstant(),
                processor = input.processor,
                authors = resolveAuthors(input.authorIds).toMutableSet(),
            ),
        )
        contest.hackEndAt = hackEnd(input, contest.endAt)
        saveProblems(contest.id, versions)
        log.info(
            "Contest {} created by {}: {} - {} on {}, problems {}",
            contest.id, creator.handle, contest.startAt, contest.endAt, contest.processor, input.problemIds,
        )
        return contest
    }

    fun get(id: String): Contest =
        contestRepository.findById(id.toUuidOrNotFound("Контест не найден"))
            .orElseThrow { NotFoundApiException("Контест не найден") }

    fun problemsOf(contestId: UUID): List<ContestProblem> = contestProblemRepository.findByIdContestIdOrderByPosition(contestId)

    fun problemOf(contestId: UUID, problemId: String): ContestProblem? =
        contestProblemRepository.findById(ContestProblemId(contestId, problemId)).orElse(null)

    fun isRegistered(contestId: UUID, userId: UUID): Boolean =
        contestRegistrationRepository.existsByIdContestIdAndIdUserId(contestId, userId)

    @Transactional
    fun update(id: String, input: ContestInputApi, currentUser: User): Contest {
        val contest = get(id)
        if (contest.format == "duel") throw ForbiddenException("Дуэль нельзя редактировать")
        if (contest.authors.none { it.id == currentUser.id }) {
            throw ForbiddenException("Изменять контест могут только его авторы")
        }
        if (contest.phaseAt(Instant.now()) != ContestPhase.UPCOMING) {
            throw ForbiddenException("Контест нельзя изменить после начала")
        }
        validate(input)
        val versions = problemsFor(input)
        contest.title = input.title
        contest.startAt = input.startAt.toInstant()
        contest.endAt = input.endAt.toInstant()
        contest.hackEndAt = hackEnd(input, contest.endAt)
        // Only before the start, checked above: once it runs, submissions are judged on this processor.
        contest.processor = input.processor
        contest.authors = resolveAuthors(input.authorIds).toMutableSet()
        saveProblems(contest.id, versions)
        log.info(
            "Contest {} updated by {}: {} - {} on {}, problems {}",
            contest.id, currentUser.handle, contest.startAt, contest.endAt, contest.processor, input.problemIds,
        )
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
     * Registered participants by solved problems, then by the smaller total solve time, on every processor alike. A
     * problem is solved by an accepted submission (correct within the problem's score limit on the contest's
     * processor); its solve time runs from the contest start to the creation of the first accepted submission, so
     * neither the judge queue nor the score matters. Wrong attempts cost nothing. Equal results share a place. Only
     * official submissions count: upsolving and virtual participation have results of their own.
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
        val processor = Processors.get(contest.processor)
        val header = "## Правила\n\n" +
            (if (contest.format == "duel") "Дуэль двух участников. " else "") +
            "Все посылки проверяются на процессоре ${processor.title} (`${processor.id}`, модель — ${processorCatalog.model(processor.id)}). " +
            "Задача решена, если принята хотя бы одна посылка: верный ответ на всех тестах и ${processor.scoreType.limitName()} задачи соблюдён. " +
            "Правильный ответ в угадывании считается принятой посылкой. " +
            "Место определяется числом решённых задач, затем меньшим суммарным временем сдачи. Время сдачи задачи — " +
            "от начала контеста до отправки первой принятой посылки; длительность проверки, score посылки " +
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
        if ((input.hackDurationMinutes ?: 0) !in 0..MAX_HACK_MINUTES) throw BadRequestException("Недопустимая длительность периода взломов")
        processorCatalog.require(input.processor)
        val format = input.format?.value ?: "standard"
        if (format != "standard" || input.opponentId != null) throw BadRequestException("Для дуэли используйте поиск соперника")
        val now = Instant.now()
        if (!input.startAt.toInstant().isAfter(now)) throw BadRequestException("Начало контеста должно быть в будущем")
        if (!input.endAt.toInstant().isAfter(input.startAt.toInstant())) {
            throw BadRequestException("Конец контеста должен быть позже начала")
        }
    }

    /** End of the hack period: `hackDurationMinutes` after the end, null when there is none. */
    private fun hackEnd(input: ContestInputApi, endAt: Instant): Instant? =
        input.hackDurationMinutes?.takeIf { it > 0 }?.let { endAt.plus(Duration.ofMinutes(it.toLong())) }

    private fun resolveAuthors(authorIds: Set<String>): List<User> = authorIds.map { id ->
        val uuid = id.toUuidOrBadRequest("Автор не найден")
        userRepository.findById(uuid).orElseThrow { BadRequestException("Автор не найден") }
    }

    /** Published versions of the contest's problems; each has to support the contest's processor. */
    private fun problemsFor(input: ContestInputApi): List<ProblemVersion> {
        val versions = problemCatalog.publishedVersions(input.problemIds)
        versions.filter { input.processor !in it.processors }.map { it.problemId }.takeIf { it.isNotEmpty() }?.let {
            throw BadRequestException("Задачи ${it.joinToString()} нельзя сдавать на процессоре ${input.processor}")
        }
        return versions
    }

    private fun saveProblems(contestId: UUID, versions: List<ProblemVersion>) {
        contestProblemRepository.deleteByIdContestId(contestId)
        contestProblemRepository.flush()
        contestProblemRepository.saveAll(
            versions.mapIndexed { index, version -> ContestProblem(contestId, version.problemId, index, version.id) },
        )
    }

    companion object {
        /** A week. */
        private const val MAX_HACK_MINUTES = 10080
    }
}
