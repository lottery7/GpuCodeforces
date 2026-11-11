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
import org.springframework.transaction.annotation.Transactional
import dev.gpucodeforces.judge.api.Processors
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
    private val processorCatalog: ProcessorCatalog,
    private val submissionStats: SubmissionStats,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(query: String?, phase: String?, mine: Boolean, processor: String?, viewer: User?): List<Contest> {
        if (mine && viewer == null) throw UnauthorizedException("Нужен вход")
        processor?.let(processorCatalog::require)
        val now = Instant.now()
        val trimmed = query?.trim()?.lowercase().orEmpty()
        val filtered = contestRepository.findAll().filter { contest ->
            (phase == null || contest.phaseAt(now).value == phase) &&
                (processor == null || contest.processor == processor) &&
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
        val versions = problemsFor(input)
        contest.title = input.title
        contest.startAt = input.startAt.toInstant()
        contest.endAt = input.endAt.toInstant()
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
        val contest = get(id)
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
     * neither the judge queue nor the score matters. Wrong attempts cost nothing. Equal results share a place.
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
            "Все посылки проверяются на процессоре ${processor.title} (`${processor.id}`, модель — ${processorCatalog.model(processor.id)}). " +
            "Задача решена, если принята хотя бы одна посылка: верный ответ на всех тестах и ${processor.scoreType.limitName()} задачи соблюдён. " +
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
        processorCatalog.require(input.processor)
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
}
