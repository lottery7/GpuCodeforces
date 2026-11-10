package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemContext
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.problem.ProblemService
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class GuessSnapshot(
    val firstTimeMs: BigDecimal,
    val secondTimeMs: BigDecimal,
    val explanation: String,
    val benchmarkKey: String,
    val synthetic: Boolean,
) {
    fun correctChoice(): String = when (firstTimeMs.compareTo(secondTimeMs)) {
        -1 -> "first"
        1 -> "second"
        else -> "equal"
    }
}

@Service
class GuessService(
    private val problemService: ProblemService,
    private val problemCatalog: ProblemCatalog,
    private val submissionService: SubmissionService,
    private val submissionRepository: SubmissionRepository,
    private val userRepository: UserRepository,
    private val gpuProperties: GpuProperties,
    private val references: ReferenceMeasurements,
    private val jsonMapper: JsonMapper,
) {
    @Transactional
    fun submit(problemId: String, context: String, choice: String, author: User, benchmarkKey: String?): Submission {
        if (choice !in ProblemFormatData.CHOICES) throw BadRequestException("Неизвестный вариант ответа")
        val problem = problemService.problem(problemId, problemService.context(context, author), author)
        val version = problem.version
        if (version.format != ProblemFormatData.GUESS) throw BadRequestException("Это не задача на угадывание")
        val contestId = (problem.context as? ProblemContext.InContest)?.contest?.id
        checkNotNull(userRepository.findForUpdate(author.id)) { "User ${author.id} disappeared" }
        val measured = references.requireReady(version)
        val key = checkNotNull(measured.key)
        if (benchmarkKey != key) throw BadRequestException("Условия сравнения изменились. Обновите задачу и выберите ответ заново")
        submissionRepository.findGuess(author.id, version.id, contestId, key)?.let { previous ->
            if (previous.guessChoice != choice) throw BadRequestException("Ответ уже сохранён, изменить его нельзя")
            return previous
        }
        submissionService.submissionContext(contestId?.toString(), author)
        val format = problemCatalog.format(version)
        val snapshot = GuessSnapshot(checkNotNull(measured.firstTimeMs), checkNotNull(measured.secondTimeMs), format.explanation, key, measured.synthetic)
        val correct = choice == snapshot.correctChoice()
        return submissionRepository.save(
            Submission(
                id = UUID.randomUUID(),
                userId = author.id,
                problemId = version.problemId,
                problemVersionId = version.id,
                contestId = contestId,
                code = "",
                environmentMarkdown = "Оба варианта измерены проверяющей системой на полном наборе тестов задачи.",
                judgeHash = version.judgeHash,
                gpuProfile = gpuProperties.profile,
                previousBestTimeMs = null,
                guessChoice = choice,
            ).apply {
                guessBenchmarkKey = key
                benchmarkEnvironmentKey = measured.environmentKey
                guessResult = jsonMapper.writeValueAsString(snapshot)
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
