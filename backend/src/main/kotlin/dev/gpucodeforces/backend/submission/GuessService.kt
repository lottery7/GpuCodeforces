package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemContext
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemService
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class GuessService(
    private val problemService: ProblemService,
    private val problemCatalog: ProblemCatalog,
    private val submissionService: SubmissionService,
    private val submissionRepository: SubmissionRepository,
    private val userRepository: UserRepository,
    private val gpuProperties: GpuProperties,
) {
    @Transactional
    fun submit(problemId: String, context: String, choice: String, author: User): Submission {
        if (choice !in ProblemFormatData.CHOICES) throw BadRequestException("Неизвестный вариант ответа")
        val problem = problemService.problem(problemId, problemService.context(context, author), author)
        val version = problem.version
        if (version.format != ProblemFormatData.GUESS) throw BadRequestException("Это не задача на угадывание")
        val contestId = (problem.context as? ProblemContext.InContest)?.contest?.id
        checkNotNull(userRepository.findForUpdate(author.id)) { "User ${author.id} disappeared" }
        submissionRepository.findGuess(author.id, version.id, contestId)?.let { previous ->
            if (previous.guessChoice != choice) throw BadRequestException("Ответ уже сохранён, изменить его нельзя")
            return previous
        }
        submissionService.submissionContext(contestId?.toString(), author)
        val format = problemCatalog.format(version)
        val correct = choice == format.correctChoice()
        return submissionRepository.save(
            Submission(
                id = UUID.randomUUID(),
                userId = author.id,
                problemId = version.problemId,
                problemVersionId = version.id,
                contestId = contestId,
                code = "",
                environmentMarkdown = "Времена двух вариантов заданы автором задачи. Ответ проверяется без запуска GPU.",
                judgeHash = version.judgeHash,
                gpuProfile = gpuProperties.profile,
                previousBestTimeMs = null,
                guessChoice = choice,
            ).apply {
                status = if (correct) SubmissionStatus.ACCEPTED else SubmissionStatus.WRONG_ANSWER
                passedTests = if (correct) 1 else 0
                totalTests = 1
                diagnostic = format.explanation
                finishedAt = Instant.now()
                updatedAt = finishedAt!!
            },
        )
    }
}
