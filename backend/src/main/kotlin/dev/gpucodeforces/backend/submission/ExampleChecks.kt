package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.problem.ContextProblem
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemFormatData
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.Verdict
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ExampleOutcome(val passed: Boolean, val diagnostic: String)

/**
 * "Check the example": the solution runs on the open tests of the statement only, waits for the verdict and leaves
 * no submission. The verdict arrives through the same event queue as submissions, so this works with a single
 * backend instance consuming `judge.events`.
 */
@Service
class ExampleChecks(
    private val problemCatalog: ProblemCatalog,
    private val judgeGateway: JudgeGateway,
    private val judgeProperties: JudgeProperties,
    private val gpuProperties: GpuProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JudgeResult>>()
    private val runningUsers = ConcurrentHashMap.newKeySet<UUID>()

    fun check(problem: ContextProblem, code: String, author: User): ExampleOutcome {
        if (problem.version.format == ProblemFormatData.GUESS) throw BadRequestException("Проверка примера недоступна для угадывания")
        SubmissionService.validateSource(code)
        if (!runningUsers.add(author.id)) throw BadRequestException("Дождитесь окончания предыдущей проверки примера")
        val judgeId = "$EXAMPLE_PREFIX${UUID.randomUUID()}"
        log.info("Example check {} of {} for {}", judgeId, problem.version.problemId, author.handle)
        val verdict = CompletableFuture<JudgeResult>()
        pending[judgeId] = verdict
        try {
            val spec = problemCatalog.spec(problem.version)
            val request = SubmissionRequest(
                submissionId = judgeId,
                problem = spec.copy(generator = spec.generator.copy(source = spec.generator.source + SAMPLES_ONLY)),
                source = code,
                gpuProfile = gpuProperties.profile,
            )
            when (val outcome = judgeGateway.send(request)) {
                is SendOutcome.Accepted -> Unit
                is SendOutcome.Rejected -> {
                    log.error("Judge gateway rejected example check {}: {}", judgeId, outcome.reason)
                    throw BadRequestException("Проверяющая система отклонила решение")
                }
                is SendOutcome.Retry -> {
                    log.warn("Judge gateway did not accept example check {}: {}", judgeId, outcome.reason)
                    throw ServiceUnavailableException("Проверяющая система недоступна, попробуйте позже")
                }
            }
            val result = try {
                verdict.get(judgeProperties.exampleTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                log.warn("Example check {} got no verdict within {}", judgeId, judgeProperties.exampleTimeout)
                return ExampleOutcome(false, "Проверка примера не завершилась вовремя: очередь проверки занята.")
            }
            log.info("Example check {}: {} in {} ms", judgeId, result.verdict, result.judgeTimeMs)
            return ExampleOutcome(result.verdict == Verdict.ACCEPTED, result.diagnostic())
        } finally {
            pending.remove(judgeId)
            runningUsers.remove(author.id)
        }
    }

    fun isExample(judgeSubmissionId: String): Boolean = judgeSubmissionId.startsWith(EXAMPLE_PREFIX)

    fun complete(judgeSubmissionId: String, result: JudgeResult) {
        pending[judgeSubmissionId]?.complete(result)
            ?: log.info("Verdict of example check {} arrived after its request gave up", judgeSubmissionId)
    }

    companion object {
        private const val EXAMPLE_PREFIX = "example-"

        /** Appended to the problem's generator: the judge then generates and runs only the sample tests. */
        @Language("Python")
        private val SAMPLES_ONLY = """


_platform_all_tests = tests


def tests():
    return [test for test in _platform_all_tests() if test.get("sample")]
"""
    }
}
