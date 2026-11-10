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
import dev.gpucodeforces.backend.submission.ResultScope
import dev.gpucodeforces.backend.submission.SubmissionStats
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.backend.user.toApiModel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
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

    /**
     * Registered participants by solved problems, then by the sum of speedups `baseline / time` over solved problems.
     * The baseline of a problem is its practice record before the start; without one, the best time in the contest.
     * Equal results share a place.
     */
    fun standings(id: String): List<GetContestStandings200ResponseInner> {
        val contest = get(id)
        if (contest.phaseAt(Instant.now()) == ContestPhase.UPCOMING) return emptyList()
        val versions = problemCatalog.versions(problemsOf(contest.id).mapNotNull { it.problemVersionId }).values
        val times = submissionStats.contestBestTimes(
            ResultScope(contest.id, versions.associate { it.problemId to it.judgeHash }, gpuProperties.profile),
        )
        val baselines = baselines(contest)
        val userIds = contestRegistrationRepository.findByIdContestId(contest.id).map { it.id.userId }
        val rows = userRepository.findAllById(userIds).map { participant ->
            val solved = times[participant.id].orEmpty()
            val speedup = solved.entries.sumOf { (problemId, time) ->
                val baseline = baselines[problemId] ?: times.values.mapNotNull { it[problemId] }.min()
                baseline.divide(time, SPEEDUP_SCALE, RoundingMode.HALF_UP)
            }.setScale(SPEEDUP_SCALE, RoundingMode.HALF_UP)
            Triple(participant, solved, speedup)
        }
        val order = compareByDescending<Triple<User, Map<String, BigDecimal>, BigDecimal>> { it.second.size }.thenByDescending { it.third }
        return rows.sortedWith(order.thenBy { it.first.handle.lowercase() }).map { row ->
            val rank = 1 + rows.count { order.compare(it, row) < 0 }
            GetContestStandings200ResponseInner(rank, row.first.toApiModel(), row.second.size, row.third, row.second)
        }
    }

    /** Practice record of each contest problem version before the start, see [standings]. */
    fun baselines(contest: Contest): Map<String, BigDecimal> {
        val versions = problemCatalog.versions(problemsOf(contest.id).mapNotNull { it.problemVersionId }).values
        return versions.mapNotNull { version ->
            submissionStats.practiceRecord(version.problemId, version.judgeHash, gpuProperties.profile, contest.startAt)?.let { version.problemId to it }
        }.toMap()
    }

    fun buildRulesMarkdown(contest: Contest, problems: List<ContestProblem>, viewer: User?): String {
        val header = "## Правила\n\n" +
            "Место определяется числом решённых задач, затем суммой ускорений относительно эталонных времён. " +
            "Равные результаты делят место."
        val isAuthor = viewer != null && contest.authors.any { it.id == viewer.id }
        if (contest.phaseAt(Instant.now()) == ContestPhase.UPCOMING && !isAuthor) return header
        val started = contest.phaseAt(Instant.now()) != ContestPhase.UPCOMING
        val baselines = if (started) baselines(contest) else emptyMap()
        val table = problems.sortedBy { it.position }.joinToString("\n") { problem ->
            val baseline = baselines[problem.id.problemId]?.let { "${it.stripTrailingZeros().toPlainString()} мс" }
                ?: if (started) "лучшее время в контесте" else "рекорд практики на момент старта"
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

private const val SPEEDUP_SCALE = 3
