package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.common.toUuidOrBadRequest
import dev.gpucodeforces.backend.contest.ContestPhase
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.submission.Submission
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionStatus
import dev.gpucodeforces.backend.submission.MeasurementData
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.judge.api.ProblemSpec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
class ComparisonService(
    private val submissionRepository: SubmissionRepository,
    private val versionRepository: ProblemVersionRepository,
    private val problemRepository: ProblemRepository,
    private val contestRepository: ContestRepository,
    private val gpuProperties: GpuProperties,
    private val jsonMapper: JsonMapper,
) {
    @Transactional
    fun create(problemId: String, firstSubmissionId: String, secondSubmissionId: String, explanation: String, author: User): ProblemVersion {
        val firstId = firstSubmissionId.toUuidOrBadRequest("Неверный идентификатор первой посылки")
        val secondId = secondSubmissionId.toUuidOrBadRequest("Неверный идентификатор второй посылки")
        if (firstId == secondId) throw BadRequestException("Выберите две разные посылки")
        val description = explanation.trim()
        if (description.isEmpty() || description.length > 4000) throw BadRequestException("Пояснение должно содержать от 1 до 4000 символов")
        val submissions = listOf(firstId, secondId).sorted().associateWith { id ->
            submissionRepository.findForUpdate(id)?.takeIf { it.userId == author.id }
                ?: throw NotFoundApiException("Посылка не найдена")
        }
        val first = submissions.getValue(firstId)
        val second = submissions.getValue(secondId)
        val now = Instant.now()
        val firstVersion = validate(first, problemId, now)
        val secondVersion = validate(second, problemId, now)
        val firstFormat = jsonMapper.readValue(firstVersion.formatData, ProblemFormatData::class.java)
        val secondFormat = jsonMapper.readValue(secondVersion.formatData, ProblemFormatData::class.java)
        val firstMeasurement = first.measurement?.let { jsonMapper.readValue(it, MeasurementData::class.java) }
        val secondMeasurement = second.measurement?.let { jsonMapper.readValue(it, MeasurementData::class.java) }
        if (firstFormat.aggregation() != secondFormat.aggregation() ||
            firstMeasurement?.aggregation?.let { it != firstFormat.aggregation() } == true ||
            secondMeasurement?.aggregation?.let { it != secondFormat.aggregation() } == true ||
            (firstFormat.aggregation() != BenchmarkDefinition.MAX && (firstMeasurement == null || secondMeasurement == null)) ||
            (firstMeasurement != null && secondMeasurement != null &&
                (firstMeasurement.timingMode != secondMeasurement.timingMode || firstMeasurement.gpuName != secondMeasurement.gpuName ||
                    firstMeasurement.toolchain != secondMeasurement.toolchain || firstMeasurement.synthetic != secondMeasurement.synthetic))) {
            throw BadRequestException("Нельзя сравнивать разные метрики или условия измерения")
        }
        if (first.judgeHash != second.judgeHash || first.environmentMarkdown != second.environmentMarkdown ||
            jsonMapper.readValue(firstVersion.judgeSpec, ProblemSpec::class.java) != jsonMapper.readValue(secondVersion.judgeSpec, ProblemSpec::class.java)) {
            throw BadRequestException("Посылки должны быть проверены на одинаковых тестах и в одинаковом окружении")
        }
        val data = jsonMapper.writeValueAsString(
            ProblemFormatData(
                format = ProblemFormatData.GUESS,
                firstTimeMs = first.timeMs,
                secondTimeMs = second.timeMs,
                explanation = description,
                firstCode = first.code,
                secondCode = second.code,
                benchmark = firstFormat.benchmark,
                benchmarkSynthetic = firstMeasurement?.synthetic ?: secondMeasurement?.synthetic,
            ),
        )
        val id = "comparison-${UUID.randomUUID()}"
        val problem = problemRepository.save(Problem(id, source = "comparison"))
        val version = versionRepository.save(
            ProblemVersion(
                id = UUID.randomUUID(),
                problemId = id,
                code = firstVersion.code,
                title = firstVersion.title,
                difficulty = firstVersion.difficulty,
                tags = firstVersion.tags,
                authors = listOf(author.handle),
                draft = false,
                statementMarkdown = firstVersion.statementMarkdown,
                starterCode = "",
                judgeSpec = firstVersion.judgeSpec,
                contentHash = sha256(firstVersion.contentHash, data),
                judgeHash = sha256(firstVersion.judgeHash, data),
                revision = null,
                format = ProblemFormatData.GUESS,
                formatData = data,
            ),
        )
        problem.currentVersionId = version.id
        return version
    }

    private fun validate(submission: Submission, problemId: String, now: Instant): ProblemVersion {
        if (submission.problemId != problemId || submission.status != SubmissionStatus.ACCEPTED ||
            submission.guessChoice != null || submission.timeMs == null || submission.timeMs!! <= BigDecimal.ZERO ||
            submission.gpuProfile != gpuProperties.profile) {
            throw BadRequestException("Нужны принятые CUDA-посылки этой задачи с замерами на текущей GPU")
        }
        submission.contestId?.let { id ->
            val contest = contestRepository.findById(id).orElse(null)
            if (contest == null || contest.phaseAt(now) != ContestPhase.FINISHED) {
                throw BadRequestException("Посылки контеста можно публиковать после его завершения")
            }
        }
        val version = versionRepository.findById(submission.problemVersionId).orElseThrow { NotFoundApiException("Версия задачи не найдена") }
        if (version.problemId != problemId || version.judgeHash != submission.judgeHash || version.draft || version.format == ProblemFormatData.GUESS) {
            throw BadRequestException("Посылки должны относиться к опубликованным CUDA-задачам с той же конфигурацией проверки")
        }
        return version
    }

    private fun sha256(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            digest.update(it.toByteArray())
            digest.update(0)
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
