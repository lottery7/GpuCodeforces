package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.judge.api.CustomTest
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.api.SubmissionStatus as JudgeStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.JacksonException
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Entity
@Table(name = "hacks", schema = "gpucodeforces")
class Hack(
    @Id val id: UUID,
    @Column(name = "contest_id") val contestId: UUID,
    @Column(name = "user_id") val userId: UUID,
    @Column(name = "submission_id") val submissionId: UUID,
    @Column(name = "problem_id") val problemId: String,
    @Column(name = "test_json") val testJson: String,
    @Column(name = "test_hash") val testHash: String,
    var status: String = "queued",
    var diagnostic: String = "",
    @Column(name = "created_at") val createdAt: Instant = Instant.now(),
    @Column(name = "job_id") var jobId: String? = null,
    @Column(name = "retry_count") var retryCount: Int = 0,
)

interface HackRepository : JpaRepository<Hack, UUID> {
    fun findByContestIdOrderByCreatedAt(contestId: UUID): List<Hack>
    fun findByStatus(status: String): List<Hack>
}

@Service
class HackService(
    private val repository: HackRepository,
    private val contests: ContestRepository,
    private val submissions: SubmissionRepository,
    private val catalog: ProblemCatalog,
    private val gateway: JudgeGateway,
    private val transactions: TransactionTemplate,
    private val suites: TestSuiteRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    fun create(submissionId: UUID, testJson: String, user: User): Hack {
        if (testJson.toByteArray().size > 65536) throw BadRequestException("Тест больше 64 КБ")
        val tree = try { json.readTree(testJson) } catch (e: JacksonException) { throw BadRequestException("Некорректный JSON теста") }
        if (!tree.isObject || tree.properties().map { it.key }.toSet() != setOf("scalars", "inputs")) {
            throw BadRequestException("Тест должен содержать только scalars и inputs")
        }
        val test = try { json.readValue<CustomTest>(testJson) } catch (e: JacksonException) { throw BadRequestException("Некорректные типы данных теста") }
        val canonical = json.writeValueAsString(test)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        val target = submissions.findById(submissionId).orElseThrow { BadRequestException("Посылка не найдена") }
        val contestId = target.contestId ?: throw BadRequestException("Взламывать можно конкурсные решения")
        val hack = transactions.execute {
            val contest = contests.findForUpdate(contestId)!!
            if (!contest.hacksOpen(Instant.now())) throw BadRequestException("Период взломов закрыт")
            if (submissions.findByContestId(contestId).any { it.official && !it.status.terminal }) {
                throw BadRequestException("Дождитесь окончания проверок решений")
            }
            // an answer to a guess is accepted too, yet there is no code to run on a test
            if (!target.official || target.status != SubmissionStatus.ACCEPTED || target.guessChoice != null) {
                throw BadRequestException("Нужно принятое официальное решение")
            }
            if (target.userId == user.id) throw ForbiddenException("Нельзя взломать собственное решение")
            val existing = list(contestId)
            existing.firstOrNull { it.submissionId == target.id && it.testHash == hash }?.let { return@execute it }
            val problemTests = existing.filter { it.problemId == target.problemId }.map { it.testHash }.toSet()
            if (hash !in problemTests && problemTests.size >= 32) throw BadRequestException("Лимит тестов для задачи исчерпан")
            if (existing.count { it.userId == user.id && it.status == "queued" } >= 2) throw BadRequestException("Дождитесь проверки предыдущих взломов")
            val spec = catalog.spec(catalog.versions(listOf(target.problemVersionId)).getValue(target.problemVersionId))
            if (!spec.allowCustomTests) throw BadRequestException("Автор задачи не включил взломы")
            val errors = SpecValidator.validateCustomTest(spec.signature, test)
            if (errors.isNotEmpty()) throw BadRequestException(errors.joinToString("; "))
            repository.save(Hack(UUID.randomUUID(), contestId, user.id, target.id, target.problemId, canonical, hash))
        }!!
        send(hack)
        return repository.findById(hack.id).orElseThrow()
    }

    fun list(contestId: UUID): List<Hack> = repository.findByContestIdOrderByCreatedAt(contestId)

    fun suite(contestId: UUID?, problemId: String): String {
        val tests = contestId?.let { list(it).filter { h -> h.problemId == problemId && h.status == "successful" }.distinctBy { h -> h.testHash }.sortedBy { h -> h.testHash }.map { h -> json.readValue<CustomTest>(h.testJson) } }.orEmpty()
        if (tests.isEmpty()) return TestSuite.BASE
        val contents = json.writeValueAsString(tests)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()))
        suites.store(hash, contents)
        return hash
    }

    fun tests(suite: String): List<CustomTest> = json.readValue(suites.findById(suite).orElseThrow().testsJson)

    @Scheduled(fixedDelayString = "PT15S")
    fun resend() = repository.findByStatus("queued").filter { it.jobId == null }.forEach { send(it) }

    private fun send(hack: Hack) {
        if (hack.status != "queued" || hack.jobId != null) return
        val target = submissions.findById(hack.submissionId).orElseThrow()
        val spec = catalog.spec(catalog.versions(listOf(target.problemVersionId)).getValue(target.problemVersionId))
        val retryCount = hack.retryCount
        // on the processor and in the language of the hacked solution: that is where it was accepted
        val outcome = gateway.send(
            SubmissionRequest(
                submissionId = "$JOB_PREFIX${hack.id}.r$retryCount",
                problem = spec.copy(customTests = listOf(json.readValue(hack.testJson)), customOnly = true),
                source = target.code,
                processor = target.processor,
                language = target.language,
            ),
        )
        transactions.executeWithoutResult {
            contests.findForUpdate(hack.contestId)
            val current = repository.findById(hack.id).orElseThrow()
            if (current.status != "queued" || current.retryCount != retryCount) return@executeWithoutResult
            when (outcome) {
                is SendOutcome.Accepted -> if (current.jobId == null) current.jobId = outcome.jobId
                is SendOutcome.Rejected -> { current.status = "system_error"; current.diagnostic = "Проверяющая система отклонила взлом" }
                is SendOutcome.Retry -> if (current.createdAt.isBefore(Instant.now().minusSeconds(600))) {
                    current.status = "system_error"; current.diagnostic = "Проверяющая система недоступна"
                }
            }
        }
    }

    fun apply(event: JudgeEvent) {
        if (event.status != JudgeStatus.FINISHED) return
        val id = runCatching { UUID.fromString(event.submissionId.removePrefix(JOB_PREFIX).substringBefore(".r")) }.getOrNull()
            ?: return log.warn("Judge event for an unknown hack identifier: {}", event.submissionId)
        val before = repository.findById(id).orElse(null)
            ?: return log.warn("Judge event for a hack that does not exist: {}", id)
        transactions.executeWithoutResult {
            contests.findForUpdate(before.contestId)
            val hack = repository.findById(id).orElseThrow()
            if (event.submissionId != "$JOB_PREFIX${hack.id}.r${hack.retryCount}" || hack.status != "queued" || hack.jobId != null && hack.jobId != event.jobId) return@executeWithoutResult
            val result = event.result!!
            hack.jobId = event.jobId
            hack.status = when {
                result.message == "Invalid custom test" -> "invalid"
                result.verdict == Verdict.WRONG_ANSWER || result.verdict == Verdict.RUNTIME_ERROR -> "successful"
                result.verdict == Verdict.INTERNAL_ERROR || result.verdict == Verdict.CANCELLED -> "system_error"
                else -> "unsuccessful"
            }
            hack.diagnostic = when (hack.status) {
                "successful" -> "Решение не прошло допустимый тест"
                "invalid" -> "Тест нарушает ограничения задачи"
                "system_error" -> "Ошибка проверки; автор может повторить взлом"
                else -> "Ошибка решения на этом тесте не подтверждена"
            }
        }
    }

    fun retry(id: UUID, user: User): Hack {
        val initial = repository.findById(id).orElseThrow { BadRequestException("Взлом не найден") }
        val hack = transactions.execute {
            val contest = contests.findForUpdate(initial.contestId)!!
            if (contest.finalizedAt != null) throw BadRequestException("Официальные результаты уже зафиксированы")
            val current = repository.findById(id).orElseThrow()
            if (current.userId != user.id) throw ForbiddenException("Повторить взлом может только его автор")
            if (current.status != "system_error") throw BadRequestException("Повтор доступен только после сбоя")
            current.status = "queued"
            current.diagnostic = ""
            current.jobId = null
            current.retryCount++
            current
        }!!
        send(hack)
        return repository.findById(id).orElseThrow()
    }

    companion object {
        /** Judge jobs of hacks: `hack-<id>.r<retry>`. */
        const val JOB_PREFIX = "hack-"

        fun isHack(judgeSubmissionId: String): Boolean = judgeSubmissionId.startsWith(JOB_PREFIX)
    }
}
