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
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
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
) {
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
        val contest = contestRepository.save(
            Contest(
                id = UUID.randomUUID(),
                title = input.title,
                startAt = input.startAt.toInstant(),
                endAt = input.endAt.toInstant(),
                gpu = gpuProperties.name,
                authors = resolveAuthors(input.authorIds).toMutableSet(),
            ),
        )
        saveProblems(contest.id, input.problemIds)
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
        contest.title = input.title
        contest.startAt = input.startAt.toInstant()
        contest.endAt = input.endAt.toInstant()
        contest.authors = resolveAuthors(input.authorIds).toMutableSet()
        saveProblems(contest.id, input.problemIds)
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
        }
    }

    // No accepted-submission data exists yet (step 3 wires the submissions module in), so every
    // registered participant is tied at rank 1 with nothing solved until then.
    fun standings(id: String): List<GetContestStandings200ResponseInner> {
        val contest = get(id)
        if (contest.phaseAt(Instant.now()) == ContestPhase.UPCOMING) return emptyList()
        val userIds = contestRegistrationRepository.findByIdContestId(contest.id).map { it.id.userId }
        return userRepository.findAllById(userIds).map { participant ->
            GetContestStandings200ResponseInner(1, participant.toApiModel(), 0, BigDecimal.ZERO, emptyMap())
        }
    }

    fun buildRulesMarkdown(contest: Contest, problems: List<ContestProblem>, viewer: User?): String {
        val header = "## Правила\n\n" +
            "Место определяется числом решённых задач, затем суммой ускорений относительно эталонных времён. " +
            "Равные результаты делят место."
        val isAuthor = viewer != null && contest.authors.any { it.id == viewer.id }
        if (contest.phaseAt(Instant.now()) == ContestPhase.UPCOMING && !isAuthor) return header
        val table = problems.sortedBy { it.position }.joinToString("\n") { problem ->
            val baseline = problem.baselineTimeMs?.let { "$it мс" } ?: "будет определён при старте"
            "- ${problem.id.problemId}: эталон $baseline"
        }
        return "$header\n\n## Эталоны\n\n$table"
    }

    private fun validate(input: ContestInputApi) {
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
