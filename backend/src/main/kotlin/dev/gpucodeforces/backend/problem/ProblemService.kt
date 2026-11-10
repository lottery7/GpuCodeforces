package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestPhase
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.user.User
import org.springframework.stereotype.Service
import java.time.Instant

/** Where a problem is viewed or solved: the practice bank or a contest with its own problem versions. */
sealed interface ProblemContext {
    data object Practice : ProblemContext

    data class InContest(val contest: Contest) : ProblemContext
}

/** One problem as seen in a context. */
class ContextProblem(val version: ProblemVersion, val context: ProblemContext)

@Service
class ProblemService(
    private val problemCatalog: ProblemCatalog,
    private val contestService: ContestService,
) {
    fun context(context: String?, viewer: User?): ProblemContext {
        if (context == null || context == PRACTICE) return ProblemContext.Practice
        val contest = contestService.get(context)
        val isAuthor = viewer != null && contest.authors.any { it.id == viewer.id }
        if (contest.phaseAt(Instant.now()) == ContestPhase.UPCOMING && !isAuthor) {
            throw ForbiddenException("Задачи контеста доступны после его начала")
        }
        return ProblemContext.InContest(contest)
    }

    /** Problems visible in [context]; a contest keeps its order, practice is sorted by the caller. */
    fun problems(context: ProblemContext, viewer: User?): List<ContextProblem> = when (context) {
        ProblemContext.Practice -> problemCatalog.currentVersions()
            .filter { !it.draft || it.isAuthor(viewer?.handle) }
            .map { ContextProblem(it, context) }
        is ProblemContext.InContest -> {
            val contestProblems = contestService.problemsOf(context.contest.id)
            val pinned = problemCatalog.versions(contestProblems.mapNotNull { it.problemVersionId })
            contestProblems.mapNotNull { contestProblem ->
                val version = contestProblem.problemVersionId?.let { pinned[it] }
                    ?: problemCatalog.currentVersion(contestProblem.id.problemId)
                version?.let { ContextProblem(it, context) }
            }
        }
    }

    fun problem(id: String, context: ProblemContext, viewer: User?): ContextProblem =
        problems(context, viewer).firstOrNull { it.version.problemId == id } ?: throw NotFoundApiException("Задача не найдена")

    fun filter(
        problems: List<ContextProblem>,
        query: String?,
        difficulty: String?,
        tag: String?,
    ): List<ContextProblem> {
        if (difficulty != null && difficulty !in ProblemPackageReader.DIFFICULTIES) throw BadRequestException("Неизвестная сложность")
        val needle = query?.trim()?.lowercase().orEmpty()
        return problems.filter { problem ->
            val version = problem.version
            (needle.isEmpty() || "${version.code} ${version.title}".lowercase().contains(needle)) &&
                (difficulty == null || version.difficulty == difficulty) &&
                (tag == null || version.tags.any { it.equals(tag, ignoreCase = true) })
        }
    }

    companion object {
        const val PRACTICE = "practice"
    }
}
