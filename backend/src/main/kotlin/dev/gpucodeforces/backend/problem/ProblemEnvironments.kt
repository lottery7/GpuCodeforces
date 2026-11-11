package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.processor.KnownTest
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.processor.ProcessorTests
import dev.gpucodeforces.backend.processor.ProcessorTestsId
import dev.gpucodeforces.backend.processor.ProcessorTestsRepository
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.TestResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** "Environment" blocks of problems, with the tests the judge reported for each processor. */
@Service
class ProblemEnvironments(
    private val processorCatalog: ProcessorCatalog,
    private val testsRepository: ProcessorTestsRepository,
    private val judgeProperties: JudgeProperties,
) {
    /** [processor] must be one the problem declares. */
    fun markdown(spec: ProblemSpec, judgeHash: String, processor: String): String {
        val processorSpec = spec.processorSpec(processor) ?: error("problem ${spec.id} does not declare $processor")
        val tests = testsRepository.findById(ProcessorTestsId(judgeHash, processor)).orElse(null)?.tests
        // a mock judge runs nothing: naming hardware would mislead (JUDGE_MODE=mock)
        val model = if (judgeProperties.synthetic) "тестовый исполнитель, код не исполняется" else processorCatalog.model(processor)
        return environmentMarkdown(spec, processorSpec, model, tests)
    }

    /** Remembers the tests of an accepted submission, which ran every test of its processor. */
    @Transactional
    fun recordTests(judgeHash: String, processor: String, tests: List<TestResult>) {
        val known = tests.map { KnownTest(it.name, it.sample, it.measured) }
        val id = ProcessorTestsId(judgeHash, processor)
        val existing = testsRepository.findById(id).orElse(null)
        if (existing == null) {
            testsRepository.save(ProcessorTests(id, known))
        } else if (existing.tests != known) {
            existing.tests = known
            existing.updatedAt = Instant.now()
        }
    }
}
