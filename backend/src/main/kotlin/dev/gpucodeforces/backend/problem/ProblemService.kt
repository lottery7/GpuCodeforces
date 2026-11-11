package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestPhase
import dev.gpucodeforces.backend.contest.ContestService
import dev.gpucodeforces.backend.contest.VirtualParticipation
import dev.gpucodeforces.backend.contest.VirtualParticipationService
import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.submission.PRACTICE_CONTEXT
import dev.gpucodeforces.backend.user.User
import org.springframework.stereotype.Service
import java.time.Instant

/** Where a problem is viewed or solved: the practice bank or a contest with its own problem versions. */
sealed interface ProblemContext {
    data object Practice : ProblemContext

    /**
     * A contest: official participation, or - once its results are final - [upsolving] or a [virtual] attempt. All
     * three use the contest's problem versions and processor and keep separate results.
     */
    data class InContest(val contest: Contest, val upsolving: Boolean = false, val virtual: VirtualParticipation? = null) : ProblemContext

    /** Where the results of the context are kept: the context key of its submissions. */
    val key: String get() = when (this) {
        Practice -> PRACTICE_CONTEXT
        is InContest -> virtual?.contextKey ?: if (upsolving) "$UPSOLVE_PREFIX${contest.id}" else contest.id.toString()
    }

    companion object {
        const val UPSOLVE_PREFIX = "upsolve:"
    }
}

/** One problem as seen in a context. */
class ContextProblem(val version: ProblemVersion, val context: ProblemContext)

@Service
class ProblemService(
    private val problemCatalog: ProblemCatalog,
    private val contestService: ContestService,
    private val virtuals: VirtualParticipationService,
    private val processorCatalog: ProcessorCatalog,
) {
    fun context(context: String?, viewer: User?): ProblemContext {
        if (context == null || context == PRACTICE) return ProblemContext.Practice
        if (context.startsWith(VirtualParticipation.CONTEXT_PREFIX)) {
            val id = context.removePrefix(VirtualParticipation.CONTEXT_PREFIX).toUuidOrBadRequest("Неверная попытка")
            val participation = virtuals.get(id, viewer)
            return ProblemContext.InContest(contestService.get(participation.contestId.toString()), virtual = participation)
        }
        if (context.startsWith(ProblemContext.UPSOLVE_PREFIX)) {
            val contest = contestService.get(context.removePrefix(ProblemContext.UPSOLVE_PREFIX))
            if (contest.finalizedAt == null) throw BadRequestException("Дождитесь окончательных результатов контеста")
            return ProblemContext.InContest(contest, upsolving = true)
        }
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

    /**
     * Processors [problem] can be solved on here: those it declares and the platform offers, in the order of the
     * package; in a contest only the contest's processor.
     */
    fun processorsOf(problem: ContextProblem): List<String> {
        val offered = problem.version.processors.filter(processorCatalog::isEnabled)
        return when (val context = problem.context) {
            ProblemContext.Practice -> offered
            is ProblemContext.InContest -> offered.filter { it == context.contest.processor }
        }
    }

    /** Checks that a submission or an example check may be judged on [processor] in [language]. */
    fun requireTarget(problem: ContextProblem, processor: String, language: String) {
        val context = problem.context
        if (context is ProblemContext.InContest && context.contest.processor != processor) {
            throw BadRequestException("Контест проходит на процессоре ${context.contest.processor}")
        }
        processorCatalog.require(processor)
        if (processor !in processorsOf(problem)) throw BadRequestException("Задачу нельзя сдавать на процессоре $processor")
        val languages = problemCatalog.spec(problem.version).processorSpec(processor)!!.languages
        if (language !in languages) throw BadRequestException("На процессоре $processor задача принимает ${languages.joinToString()}, а не $language")
    }

    companion object {
        const val PRACTICE = PRACTICE_CONTEXT
    }
}
