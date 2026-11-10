package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.benchmark.ReferenceEnvironmentMonitor
import dev.gpucodeforces.backend.benchmark.ReferenceJobRepository
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.benchmark.ReferenceVersionLockRepository
import dev.gpucodeforces.backend.common.GpuProperties
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemSyncService
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.submission.JudgeExecutorView
import dev.gpucodeforces.backend.submission.JudgeGateway
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.backend.submission.SendOutcome
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Progress
import dev.gpucodeforces.judge.api.SubmissionRequest
import dev.gpucodeforces.judge.api.SubmissionStatus
import dev.gpucodeforces.judge.api.TestResult
import dev.gpucodeforces.judge.api.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReferenceMeasurementsIntegrationTest {
    @Autowired lateinit var references: ReferenceMeasurements
    @Autowired lateinit var catalog: ProblemCatalog
    @Autowired lateinit var sync: ProblemSyncService
    @Autowired lateinit var jobs: ReferenceJobRepository
    @Autowired lateinit var locks: ReferenceVersionLockRepository
    @Autowired lateinit var properties: JudgeProperties
    @Autowired lateinit var gpu: GpuProperties
    @Autowired lateinit var transactions: TransactionTemplate
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var submissions: SubmissionRepository
    @MockitoBean lateinit var gateway: JudgeGateway

    private var model = "NVIDIA GeForce RTX 3060"
    private var online = true
    private val sent = mutableListOf<SubmissionRequest>()
    private val requestPlaceholder = mock(SubmissionRequest::class.java)

    @BeforeEach
    fun setup() {
        Files.list(root).use { children -> children.forEach { directory ->
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        } }
        sync.sync()
        jobs.deleteAll()
        sent.clear()
        model = "NVIDIA GeForce RTX 3060"
        online = true
        given(gateway.executors()).willAnswer {
            if (!online) null else listOf(JudgeExecutorView(
                true, Instant.now(), ExecutorHeartbeat(
                    "reference-executor", "local", "rtx3060", "test-version", Instant.now(), Instant.now(),
                    listOf(GpuSlotStatus(0, model, true, false)), 0, 1, toolchain = "nvcc test",
                ),
            ))
        }
        doAnswer {
            val request = it.arguments[0] as SubmissionRequest
            sent += request
            SendOutcome.Accepted("job-${request.submissionId}")
        }.`when`(gateway).send(any(SubmissionRequest::class.java) ?: requestPlaceholder)
        references.refresh()
    }

    private fun source(threads: Int) = """
        #include <cuda_runtime.h>
        __global__ void add(const float* A, const float* B, float* C, int N) {
            int i = blockIdx.x * blockDim.x + threadIdx.x;
            if (i < N) C[i] = A[i] + B[i];
        }
        void solve(const float* A, const float* B, float* C, int N) {
            add<<<(N + $threads - 1) / $threads, $threads>>>(A, B, C, N);
        }
    """.trimIndent()

    private fun importProblem(format: String): Pair<Path, ProblemVersion> {
        val directory = Files.createDirectory(root.resolve("reference-${UUID.randomUUID()}"))
        val template = Path.of("../judge/problems/examples/vector_add")
        listOf("problem.json", "problem.py", "statement.md").forEach { Files.copy(template.resolve(it), directory.resolve(it)) }
        Files.writeString(directory.resolve("starter.cu"), source(128))
        val metadata = mutableMapOf<String, Any>(
            "format" to format,
            "benchmark" to mapOf("inputDescription" to "Полный набор тестов генератора", "aggregation" to "geometric_mean"),
        )
        if (format == "guess") {
            metadata["explanation"] = "Размер блока влияет на выполнение этих входов"
            Files.writeString(directory.resolve("first.cu"), source(128))
            Files.writeString(directory.resolve("second.cu"), source(256))
        }
        Files.writeString(directory.resolve("format.json"), json.writeValueAsString(metadata))
        assertThat(sync.sync().failed).isEmpty()
        return directory to catalog.currentVersion(directory.fileName.toString())!!
    }

    private fun assertTime(value: BigDecimal?, expected: Double) {
        assertThat(value).isNotNull()
        assertThat(value!!.toDouble()).isCloseTo(expected, within(1e-9))
    }

    private fun complete(request: SubmissionRequest, times: List<Double>, verdict: Verdict = Verdict.ACCEPTED, device: String = model) {
        val jobId = "job-${request.submissionId}"
        references.apply(JudgeEvent(request.submissionId, 1, 1, SubmissionStatus.TESTING, Instant.now(), "reference-executor", Progress(1, times.size), jobId = jobId))
        val result = JudgeResult(
            verdict, message = if (verdict == Verdict.ACCEPTED) null else "private failure details",
            tests = times.mapIndexed { index, time -> TestResult(index + 1, "test-${index + 1}", index == 0, Verdict.ACCEPTED, time, time, 1) },
            maxTimeMs = times.maxOrNull(), timingMode = request.problem.timing.effectiveMode,
            gpu = GpuInfo("rtx3060", device, 0, "reference-executor"), toolchain = "nvcc test",
        )
        assertThat(references.apply(JudgeEvent(request.submissionId, 1, 2, SubmissionStatus.FINISHED, Instant.now(), "reference-executor", result = result, jobId = jobId))).isTrue()
    }

    @Test
    fun `baseline is measured through full judge and refreshed for actual hardware and changed tests`() {
        val (directory, original) = importProblem("optimization")
        assertThat(references.view(original)!!.baselineTimeMs).isNull()
        assertThatThrownBy { references.requireReady(original) }.isInstanceOf(ServiceUnavailableException::class.java)
        references.refresh()
        val first = sent.single()
        assertThat(first.source).isEqualTo(source(128))
        assertThat(first.problem).isEqualTo(catalog.spec(original))
        complete(first, listOf(1.0, 9.0))
        assertTime(references.requireReady(original).baselineTimeMs, 3.0)
        val oldKey = references.requireReady(original).key
        references.refresh()
        assertThat(sent).hasSize(1)

        model = "NVIDIA GeForce RTX 4090"
        references.refresh()
        assertThat(references.view(original)!!.status).isEqualTo("running")
        assertThat(references.view(original)!!.baselineTimeMs).isNull()
        assertThat(references.view(original)!!.key).isNotEqualTo(oldKey)
        complete(first, listOf(0.01, 0.01), device = "NVIDIA GeForce RTX 3060")
        assertThat(references.view(original)!!.baselineTimeMs).isNull()
        complete(sent.last(), listOf(4.0, 16.0))
        assertTime(references.requireReady(original).baselineTimeMs, 8.0)

        Files.writeString(directory.resolve("problem.py"), Files.readString(directory.resolve("problem.py")) + "\n")
        assertThat(sync.sync().imported).isEqualTo(1)
        val updated = catalog.currentVersion(original.problemId)!!
        assertThat(updated.judgeHash).isNotEqualTo(original.judgeHash)
        assertThat(references.view(updated)!!.key).isNotEqualTo(references.view(original)!!.key)
        references.refresh()
        complete(sent.last(), listOf(2.0, 8.0))
        assertTime(references.requireReady(updated).baselineTimeMs, 4.0)
        assertTime(references.requireReady(original).baselineTimeMs, 8.0)
        assertThat(submissions.count()).isZero()
    }

    @Test
    fun `both guess codes must finish in the current environment and failed code never exposes times`() {
        val (_, version) = importProblem("guess")
        references.refresh()
        assertThat(sent.map { it.source }).containsExactlyInAnyOrder(source(128), source(256))
        val first = sent.first { it.source == source(128) }
        val second = sent.first { it.source == source(256) }
        complete(first, listOf(1.0, 4.0))
        assertThat(references.view(version)!!.firstTimeMs).isNull()
        assertThatThrownBy { references.requireReady(version) }.isInstanceOf(ServiceUnavailableException::class.java)

        model = "NVIDIA GeForce RTX 4090"
        references.refresh()
        complete(second, listOf(0.01, 0.01), device = "NVIDIA GeForce RTX 3060")
        assertThat(references.view(version)!!.firstTimeMs).isNull()
        val currentFirst = sent.last { it.source == source(128) }
        val currentSecond = sent.last { it.source == source(256) }
        complete(currentFirst, listOf(4.0, 16.0))
        complete(currentSecond, listOf(1.0, 4.0))
        val ready = references.requireReady(version)
        assertTime(ready.firstTimeMs, 8.0)
        assertTime(ready.secondTimeMs, 2.0)

        online = false
        references.refresh()
        assertThat(references.view(version)!!.status).isEqualTo("pending")
        assertThat(references.view(version)!!.firstTimeMs).isNull()
        online = true
        references.refresh()
        assertThat(references.requireReady(version).key).isEqualTo(ready.key)

        val (_, invalid) = importProblem("guess")
        references.refresh()
        val bad = sent.last { it.problem.id == invalid.problemId }
        complete(bad, emptyList(), Verdict.COMPILATION_ERROR)
        val failed = references.view(invalid)!!
        assertThat(failed.status).isEqualTo("failed")
        assertThat(failed.firstTimeMs).isNull()
        assertThat(failed.secondTimeMs).isNull()
        assertThat(failed.diagnostic).doesNotContain("private failure details")
    }

    @Test
    fun `a restored worker retries durable jobs and ignores events from previous attempts`() {
        val (_, version) = importProblem("optimization")
        doAnswer {
            sent += it.arguments[0] as SubmissionRequest
            SendOutcome.Retry("gateway unavailable")
        }.`when`(gateway).send(any(SubmissionRequest::class.java) ?: requestPlaceholder)
        references.refresh()
        val original = sent.single()
        transactions.executeWithoutResult {
            val job = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, references.environmentKey()!!).single()
            jobs.findForUpdate(job.id)!!.nextAttemptAt = Instant.now().minusSeconds(1)
        }
        doAnswer {
            val request = it.arguments[0] as SubmissionRequest
            sent += request
            SendOutcome.Accepted("job-${request.submissionId}")
        }.`when`(gateway).send(any(SubmissionRequest::class.java) ?: requestPlaceholder)
        val restored = ReferenceMeasurements(catalog, jobs, locks, ReferenceEnvironmentMonitor(gateway, properties, gpu), gateway, properties, gpu, transactions, json)
        restored.refresh()
        assertThat(sent.last().submissionId).isEqualTo(original.submissionId)
        complete(sent.last(), listOf(1.0, 4.0), Verdict.INTERNAL_ERROR)
        transactions.executeWithoutResult {
            val job = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, references.environmentKey()!!).single()
            assertThat(job.generation).isEqualTo(1)
            jobs.findForUpdate(job.id)!!.nextAttemptAt = Instant.now().minusSeconds(1)
        }
        restored.refresh()
        val retry = sent.last()
        assertThat(retry.submissionId).isNotEqualTo(original.submissionId)
        complete(original, listOf(0.01, 0.01))
        assertThat(references.view(version)!!.baselineTimeMs).isNull()
        complete(retry, listOf(1.0, 9.0))
        assertTime(restored.requireReady(version).baselineTimeMs, 3.0)
        assertThat(jobs.findByProblemVersionIdAndEnvironmentKey(version.id, references.environmentKey()!!)).hasSize(1)
    }

    companion object {
        private val root = Files.createTempDirectory("reference-measurements-integration")
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:18.1-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.problems.directory") { root.toString() }
            registry.add("platform.problems.sync-interval") { "PT24H" }
            registry.add("platform.benchmarks.initial-delay") { "PT24H" }
            registry.add("platform.benchmarks.refresh-interval") { "PT24H" }
        }
    }
}
