package dev.gpucodeforces.backend.problem

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

    fun problem(id: String, context: ProblemContext, viewer: User?): ContextProblem {
        val version = when (context) {
            ProblemContext.Practice -> problemCatalog.currentVersion(id)?.takeIf { !it.draft || it.isAuthor(viewer?.handle) }
            is ProblemContext.InContest -> {
                val problem = contestService.problemOf(context.contest.id, id)
                    ?: throw NotFoundApiException("Задача не найдена")
                problem.problemVersionId?.let { problemCatalog.versions(listOf(it))[it] }
                    ?: problemCatalog.currentVersion(id)
            }
        }
        return ContextProblem(version ?: throw NotFoundApiException("Задача не найдена"), context)
    }

    companion object {
        const val PRACTICE = "practice"
    }
}
