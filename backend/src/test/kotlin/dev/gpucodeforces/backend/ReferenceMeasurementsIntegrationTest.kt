package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.benchmark.ReferenceEnvironmentMonitor
import dev.gpucodeforces.backend.benchmark.ReferenceJob
import dev.gpucodeforces.backend.benchmark.ReferenceJobRepository
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.benchmark.ReferenceVersionLockRepository
import dev.gpucodeforces.backend.common.ServiceUnavailableException
import dev.gpucodeforces.backend.contest.Contest
import dev.gpucodeforces.backend.contest.ContestProblem
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.contest.ContestRepository
import dev.gpucodeforces.backend.problem.ProblemCatalog
import dev.gpucodeforces.backend.problem.ProblemSyncService
import dev.gpucodeforces.backend.problem.ProblemVersion
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.backend.submission.JudgeExecutorView
import dev.gpucodeforces.backend.submission.JudgeGateway
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.backend.submission.MeasuredTest
import dev.gpucodeforces.backend.submission.MeasurementData
import dev.gpucodeforces.backend.submission.SendOutcome
import dev.gpucodeforces.backend.submission.SubmissionRepository
import dev.gpucodeforces.backend.submission.SubmissionService
import dev.gpucodeforces.backend.submission.Submission as StoredSubmission
import dev.gpucodeforces.backend.submission.SubmissionStatus as StoredStatus
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import dev.gpucodeforces.judge.api.ExecutorHeartbeat
import dev.gpucodeforces.judge.api.GpuInfo
import dev.gpucodeforces.judge.api.GpuSlotStatus
import dev.gpucodeforces.judge.api.JudgeEvent
import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.ProcessorRun
import dev.gpucodeforces.judge.api.ProcessorStatus
import dev.gpucodeforces.judge.api.Progress
import dev.gpucodeforces.judge.api.Score
import dev.gpucodeforces.judge.api.ScoreType
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
import org.intellij.lang.annotations.Language
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.client.RestTestClient
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
@AllowFullScan("reference_jobs", "from gpucodeforces\\.reference_jobs rj1_0\\s*$", "Every test starts without reference jobs: its setup deletes them all")
class ReferenceMeasurementsIntegrationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var references: ReferenceMeasurements
    @Autowired lateinit var catalog: ProblemCatalog
    @Autowired lateinit var sync: ProblemSyncService
    @Autowired lateinit var jobs: ReferenceJobRepository
    @Autowired lateinit var locks: ReferenceVersionLockRepository
    @Autowired lateinit var properties: JudgeProperties
    @Autowired lateinit var processors: ProcessorCatalog
    @Autowired lateinit var transactions: TransactionTemplate
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var submissions: SubmissionRepository
    @Autowired lateinit var submissionService: SubmissionService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var contests: ContestRepository
    @Autowired lateinit var contestProblems: ContestProblemRepository
    @Autowired lateinit var users: UserRepository
    @MockitoBean lateinit var gateway: JudgeGateway

    private var model = "NVIDIA GeForce RTX 3060"
    private var online = true
    private var emulator = false
    private val sent = mutableListOf<SubmissionRequest>()
    private val requestPlaceholder = mock(SubmissionRequest::class.java)
    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    @BeforeEach
    fun setup() {
        @Language("SQL")
        val cleanup = "TRUNCATE TABLE gpucodeforces.judging_runs, gpucodeforces.hacks, gpucodeforces.submissions"
        jdbc.execute(cleanup)
        Files.list(root).use { children -> children.forEach { directory ->
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        } }
        sync.sync()
        jobs.deleteAll()
        sent.clear()
        model = "NVIDIA GeForce RTX 3060"
        online = true
        emulator = false
        given(gateway.executors()).willAnswer {
            if (!online) null else listOfNotNull(
                JudgeExecutorView(
                    true, Instant.now(), ExecutorHeartbeat(
                        "reference-executor", "local", listOf(ProcessorStatus(GPU, "nvcc test", model, true, false, 1)), "test-version",
                        Instant.now(), Instant.now(), listOf(GpuSlotStatus(0, model, true, false)), 0, 1,
                    ),
                ),
                if (!emulator) null else JudgeExecutorView(
                    true, Instant.now(), ExecutorHeartbeat(
                        "emu-executor", "local", listOf(ProcessorStatus(RTL, "Yosys, Verilator", "Verilator + Yosys", true, false, 2)), "test-version",
                        Instant.now(), Instant.now(), emptyList(), 0, 2,
                    ),
                ),
            )
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

    private fun frozenProblem(version: ProblemVersion, environment: String): GetProblem200Response {
        fun player(): User {
            val handle = "reference_${UUID.randomUUID().toString().take(8)}"
            return users.save(User(handle, "$handle@example.com", "hash"))
        }
        val first = player()
        val second = player()
        val now = Instant.now()
        val contest = contests.save(Contest(
            id = UUID.randomUUID(), title = "Дуэль", startAt = now.minusSeconds(60), endAt = now.plusSeconds(3600),
            processor = GPU, format = "duel", challengerId = first.id, opponentId = second.id, benchmarkEnvironmentKey = environment,
        ))
        contestProblems.save(ContestProblem(contest.id, version.problemId, 0, version.id))
        return client.get().uri("/problems/${version.problemId}?context=${contest.id}").exchange().expectStatus().isOk
            .expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
    }

    private fun complete(request: SubmissionRequest, times: List<Double>, verdict: Verdict = Verdict.ACCEPTED, device: String = model) {
        val jobId = "job-${request.submissionId}"
        references.apply(JudgeEvent(request.submissionId, 1, 1, SubmissionStatus.TESTING, Instant.now(), "reference-executor", Progress(1, times.size), jobId = jobId))
        val result = JudgeResult(
            verdict, message = if (verdict == Verdict.ACCEPTED) null else "private failure details",
            tests = times.mapIndexed { index, time ->
                TestResult(index + 1, "test-${index + 1}", index == 0, Verdict.ACCEPTED, score = Score(time, ScoreType.TIME_MS), wallTimeMs = time, runs = 1)
            },
            score = times.maxOrNull()?.let { Score(it, ScoreType.TIME_MS) }, timingMode = request.problem.timing.effectiveMode,
            gpu = GpuInfo("rtx3060", device, 0, "reference-executor", clocksLocked = false),
            processor = ProcessorRun(GPU, "nvcc test", device, "reference-executor", executorVersion = "test-version"),
        )
        assertThat(references.apply(JudgeEvent(request.submissionId, 1, 2, SubmissionStatus.FINISHED, Instant.now(), "reference-executor", result = result, jobId = jobId))).isTrue()
    }

    @Test
    @AllowFullScan("submissions", "select count\\(\\*\\) from gpucodeforces\\.submissions", "The test checks that reference runs create no submissions at all")
    fun `baseline is measured through full judge and refreshed for actual hardware and changed tests`() {
        val (directory, original) = importProblem("optimization")
        assertThat(references.view(original, GPU)!!.baselineScore).isNull()
        assertThatThrownBy { references.requireReady(original, GPU) }.isInstanceOf(ServiceUnavailableException::class.java)
        references.refresh()
        val first = sent.single()
        assertThat(first.source).isEqualTo(source(128))
        assertThat(first.processor to first.language).isEqualTo(GPU to "cuda")
        assertThat(first.problem).isEqualTo(catalog.spec(original))
        complete(first, listOf(1.0, 9.0))
        assertTime(references.requireReady(original, GPU).baselineScore, 3.0)
        val oldKey = references.requireReady(original, GPU).key
        val oldEnvironment = references.environmentKey(GPU)!!
        references.refresh()
        assertThat(sent).hasSize(1)

        model = "NVIDIA GeForce RTX 4090"
        references.refresh()
        assertThat(references.view(original, GPU)!!.status).isEqualTo("running")
        val historical = references.viewInEnvironment(original, GPU, oldEnvironment)!!
        assertTime(historical.baselineScore, 3.0)
        assertThat(historical.model).isEqualTo("NVIDIA GeForce RTX 3060")
        assertThat(historical.synthetic).isFalse()
        val frozen = frozenProblem(original, oldEnvironment)
        val frozenProcessor = frozen.processors.single()
        val frozenDetail = frozen.processorDetails.single()
        val frozenBenchmark = checkNotNull(frozenDetail.benchmark)
        assertTime(frozenProcessor.baselineScore?.value, 3.0)
        assertThat(frozenBenchmark.model).isEqualTo("NVIDIA GeForce RTX 3060")
        assertThat(frozenBenchmark.synthetic).isFalse()
        assertThat(frozenDetail.environmentMarkdown).contains("NVIDIA GeForce RTX 3060").doesNotContain("NVIDIA GeForce RTX 4090")
        assertThat(frozenDetail.environmentMarkdown).contains("геометрическое среднее").doesNotContain("максимум по замерным тестам")
        assertThat(references.view(original, GPU)!!.baselineScore).isNull()
        assertThat(references.view(original, GPU)!!.key).isNotEqualTo(oldKey)
        complete(first, listOf(0.01, 0.01), device = "NVIDIA GeForce RTX 3060")
        assertThat(references.view(original, GPU)!!.baselineScore).isNull()
        complete(sent.last(), listOf(4.0, 16.0))
        assertTime(references.requireReady(original, GPU).baselineScore, 8.0)

        Files.writeString(directory.resolve("problem.py"), Files.readString(directory.resolve("problem.py")) + "\n")
        assertThat(sync.sync().imported).isEqualTo(1)
        val updated = catalog.currentVersion(original.problemId)!!
        assertThat(updated.judgeHash).isNotEqualTo(original.judgeHash)
        assertThat(references.view(updated, GPU)!!.key).isNotEqualTo(references.view(original, GPU)!!.key)
        references.refresh()
        complete(sent.last(), listOf(2.0, 8.0))
        assertTime(references.requireReady(updated, GPU).baselineScore, 4.0)
        assertTime(references.requireReady(original, GPU).baselineScore, 8.0)
        assertThat(submissions.count()).isZero()
    }

    @Test
    fun `a frozen synthetic baseline stays marked and hides GPU metadata under a real runtime`() {
        val (_, version) = importProblem("optimization")
        val environment = "historical-mock-${UUID.randomUUID()}"
        jobs.save(ReferenceJob(
            id = UUID.randomUUID(), problemVersionId = version.id, processor = GPU, language = "cuda", environmentKey = environment, role = "baseline", status = "ready",
            score = BigDecimal("3"), measuredAt = Instant.now(), measurement = json.writeValueAsString(MeasurementData(
                "geometric_mean", catalog.spec(version).timing.effectiveMode.name.lowercase(), "Mock GPU", "mock test", true,
                listOf(MeasuredTest(BigDecimal.ONE, 1), MeasuredTest(BigDecimal("9"), 1)),
            )),
        ))

        val measured = references.viewInEnvironment(version, GPU, environment)!!
        val frozen = frozenProblem(version, environment)
        val frozenDetail = frozen.processorDetails.single()
        val frozenBenchmark = checkNotNull(frozenDetail.benchmark)

        assertThat(properties.synthetic).isFalse()
        assertThat(measured.synthetic).isTrue()
        assertThat(measured.model).isEqualTo("Mock GPU")
        assertTime(frozen.processors.single().baselineScore?.value, 3.0)
        assertThat(frozenBenchmark.synthetic).isTrue()
        assertThat(frozenBenchmark.model).isNull()
        assertThat(frozenDetail.environmentMarkdown).doesNotContain("Mock GPU", model)
    }

    @Test
    fun `both guess codes must finish in the current environment and failed code never exposes times`() {
        val (_, version) = importProblem("guess")
        references.refresh()
        assertThat(sent.map { it.source }).containsExactlyInAnyOrder(source(128), source(256))
        val first = sent.first { it.source == source(128) }
        val second = sent.first { it.source == source(256) }
        complete(first, listOf(1.0, 4.0))
        assertThat(references.view(version, GPU)!!.firstScore).isNull()
        assertThatThrownBy { references.requireReady(version, GPU) }.isInstanceOf(ServiceUnavailableException::class.java)

        model = "NVIDIA GeForce RTX 4090"
        references.refresh()
        complete(second, listOf(0.01, 0.01), device = "NVIDIA GeForce RTX 3060")
        assertThat(references.view(version, GPU)!!.firstScore).isNull()
        val currentFirst = sent.last { it.source == source(128) }
        val currentSecond = sent.last { it.source == source(256) }
        complete(currentFirst, listOf(4.0, 16.0))
        complete(currentSecond, listOf(1.0, 4.0))
        val ready = references.requireReady(version, GPU)
        assertTime(ready.firstScore, 8.0)
        assertTime(ready.secondScore, 2.0)

        online = false
        references.refresh()
        assertThat(references.view(version, GPU)!!.status).isEqualTo("pending")
        assertThat(references.view(version, GPU)!!.firstScore).isNull()
        online = true
        references.refresh()
        assertThat(references.requireReady(version, GPU).key).isEqualTo(ready.key)

        val (_, invalid) = importProblem("guess")
        references.refresh()
        val bad = sent.last { it.problem.id == invalid.problemId }
        complete(bad, emptyList(), Verdict.COMPILATION_ERROR)
        val failed = references.view(invalid, GPU)!!
        assertThat(failed.status).isEqualTo("failed")
        assertThat(failed.firstScore).isNull()
        assertThat(failed.secondScore).isNull()
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
            val job = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, references.environmentKey(GPU)!!).single()
            jobs.findForUpdate(job.id)!!.nextAttemptAt = Instant.now().minusSeconds(1)
        }
        doAnswer {
            val request = it.arguments[0] as SubmissionRequest
            sent += request
            SendOutcome.Accepted("job-${request.submissionId}")
        }.`when`(gateway).send(any(SubmissionRequest::class.java) ?: requestPlaceholder)
        val restored = ReferenceMeasurements(
            catalog, processors, jobs, locks, ReferenceEnvironmentMonitor(gateway, properties, processors), gateway, properties, transactions, json,
        )
        restored.refresh()
        assertThat(sent.last().submissionId).isEqualTo(original.submissionId)
        complete(sent.last(), listOf(1.0, 4.0), Verdict.INTERNAL_ERROR)
        transactions.executeWithoutResult {
            val job = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, references.environmentKey(GPU)!!).single()
            assertThat(job.generation).isEqualTo(1)
            jobs.findForUpdate(job.id)!!.nextAttemptAt = Instant.now().minusSeconds(1)
        }
        restored.refresh()
        val retry = sent.last()
        assertThat(retry.submissionId).isNotEqualTo(original.submissionId)
        complete(original, listOf(0.01, 0.01))
        assertThat(references.view(version, GPU)!!.baselineScore).isNull()
        complete(retry, listOf(1.0, 9.0))
        assertTime(restored.requireReady(version, GPU).baselineScore, 3.0)
        assertThat(jobs.findByProblemVersionIdAndEnvironmentKey(version.id, references.environmentKey(GPU)!!)).hasSize(1)
    }

    @Test
    fun `a delayed reference result can complete its original persisted environment after hardware changes`() {
        val (_, version) = importProblem("optimization")
        references.refresh()
        val original = sent.single()
        val environment = references.environmentKey(GPU)!!
        val stored = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, environment).single()
        assertThat(stored.expectedEnvironment).isNotNull()
        model = "NVIDIA GeForce RTX 4090"
        references.refresh()
        complete(original, listOf(1.0, 9.0), device = "NVIDIA GeForce RTX 3060")
        assertTime(references.viewInEnvironment(version, GPU, environment)!!.baselineScore, 3.0)
        assertThat(references.view(version, GPU)!!.baselineScore).isNull()
        val current = sent.last()
        complete(current, listOf(1.0, 1.0), device = "NVIDIA GeForce RTX 3060")
        assertThat(references.view(version, GPU)!!.baselineScore).isNull()
        assertThat(jobs.findByProblemVersionIdAndEnvironmentKey(version.id, references.environmentKey(GPU)!!).single().generation).isEqualTo(1)
    }

    @Test
    fun `foreign failed reference results retry instead of failing the captured environment`() {
        for (verdict in listOf(Verdict.COMPILATION_ERROR, Verdict.WRONG_ANSWER)) {
            model = "NVIDIA GeForce RTX 3060"
            references.refresh()
            val (_, version) = importProblem("optimization")
            references.refresh()
            val original = sent.last { it.problem.id == version.problemId }
            val environment = references.environmentKey(GPU)!!
            model = "NVIDIA GeForce RTX 4090"
            references.refresh()
            complete(original, listOf(1.0), verdict, device = model)
            val stored = jobs.findByProblemVersionIdAndEnvironmentKey(version.id, environment).single()
            assertThat(stored.status).isEqualTo("pending")
            assertThat(stored.generation).isEqualTo(1)
            assertThat(stored.failures).isEqualTo(1)
        }
    }

    @Test
    fun `a historical compiler failure without GPU telemetry proves its own environment`() {
        val (_, version) = importProblem("optimization")
        references.refresh()
        val original = sent.single()
        val environment = references.environmentKey(GPU)!!
        model = "NVIDIA GeForce RTX 4090"
        references.refresh()
        val result = JudgeResult(
            Verdict.COMPILATION_ERROR, compileLog = "private compiler failure",
            processor = ProcessorRun(GPU, "nvcc test", "NVIDIA GeForce RTX 3060", "reference-executor", "test-version"),
        )
        references.apply(JudgeEvent(
            original.submissionId, 1, 1, SubmissionStatus.FINISHED, Instant.now(), "reference-executor",
            result = result, jobId = "job-${original.submissionId}",
        ))
        assertThat(jobs.findByProblemVersionIdAndEnvironmentKey(version.id, environment).single().status).isEqualTo("failed")
        assertThat(references.viewInEnvironment(version, GPU, environment)!!.diagnostic).doesNotContain("private compiler failure")
        assertThat(references.view(version, GPU)!!.status).isEqualTo("running")
    }

    @Test
    fun `submission provenance survives a runtime change and previous best uses the actual result cohort`() {
        val (_, version) = importProblem("optimization")
        val handle = "cohort_${UUID.randomUUID().toString().take(8)}"
        val author = users.save(User(handle, "$handle@example.com", "hash"))
        val environmentA = references.environmentKey(GPU)!!
        fun accepted(score: Double, device: String, full: Boolean = true) = JudgeResult(
            Verdict.ACCEPTED, tests = listOf(TestResult(1, "test", false, Verdict.ACCEPTED, score = Score(score, ScoreType.TIME_MS), runs = 1)),
            score = Score(score, ScoreType.TIME_MS), timingMode = catalog.spec(version).timing.effectiveMode,
            gpu = GpuInfo("rtx3060", device, 0, "reference-executor", clocksLocked = if (full) false else null),
            processor = ProcessorRun(GPU, "nvcc test", device, "reference-executor", executorVersion = if (full) "test-version" else null),
        )
        fun finish(submission: StoredSubmission, result: JudgeResult) {
            submissionService.apply(JudgeEvent(
                submission.judgeSubmissionId, 1, 2, SubmissionStatus.FINISHED, Instant.now(), "reference-executor",
                result = result, jobId = "job-${submission.judgeSubmissionId}",
            ))
        }
        val first = submissionService.submit(version.problemId, null, GPU, "cuda", source(128), author)
        finish(first, accepted(100.0, model))
        val lateA = submissionService.submit(version.problemId, null, GPU, "cuda", source(128), author)
        assertTime(lateA.previousBestScore, 100.0)
        val changed = submissionService.submit(version.problemId, null, GPU, "cuda", source(256), author)
        assertThat(submissions.findById(lateA.id).orElseThrow().expectedEnvironment).isNotNull()
        model = "NVIDIA GeForce RTX 4090"
        references.refresh()
        val environmentB = references.environmentKey(GPU)!!
        finish(lateA, accepted(50.0, "NVIDIA GeForce RTX 3060"))
        val late = submissions.findById(lateA.id).orElseThrow()
        assertThat(late.status).isEqualTo(StoredStatus.ACCEPTED)
        assertThat(late.benchmarkEnvironmentKey).isEqualTo(environmentA)
        assertTime(late.previousBestScore, 100.0)
        finish(changed, accepted(10.0, model))
        val actualB = submissions.findById(changed.id).orElseThrow()
        assertThat(actualB.benchmarkEnvironmentKey).isEqualTo(environmentB)
        assertThat(actualB.previousBestScore).isNull()
        val legacy = submissionService.submit(version.problemId, null, GPU, "cuda", source(128), author)
        finish(legacy, accepted(5.0, model, full = false))
        assertThat(submissions.findById(legacy.id).orElseThrow().status).isEqualTo(StoredStatus.ACCEPTED)
        val retry = submissionService.submit(version.problemId, null, GPU, "cuda", source(128), author)
        finish(retry, JudgeResult(Verdict.INTERNAL_ERROR))
        model = "NVIDIA GeForce RTX 3060"
        references.refresh()
        val retried = submissionService.recheck(retry.id.toString(), author)
        finish(retried, accepted(25.0, model))
        assertTime(submissions.findById(retry.id).orElseThrow().previousBestScore, 50.0)
        assertThat(submissions.findById(retry.id).orElseThrow().benchmarkEnvironmentKey).isEqualTo(environmentA)
    }

    @Test
    fun `an optimization on a circuit measures its baseline on rtl, in that processor's score, from the rtl starter`() {
        val directory = Files.createDirectory(root.resolve("reference-rtl-${UUID.randomUUID()}"))
        val template = Path.of("../judge/problems/examples/vector_add_rtl")
        listOf("problem.json", "problem.py").forEach { Files.copy(template.resolve(it), directory.resolve(it)) }
        // the judge's example has no statement of the bank format
        Files.copy(Path.of("../judge/problems/examples/vector_add/statement.md"), directory.resolve("statement.md"))
        val circuit = Files.readString(template.resolve("solutions/accepted.sv"))
        Files.writeString(directory.resolve("starter.rtl.sv"), circuit)
        Files.writeString(
            directory.resolve("format.json"),
            json.writeValueAsString(mapOf("format" to "optimization", "benchmark" to mapOf("inputDescription" to "Тесты", "aggregation" to "max"))),
        )
        assertThat(sync.sync().failed).isEmpty()
        val version = catalog.currentVersion(directory.fileName.toString())!!

        // no executor of rtl yet: the baseline waits
        references.refresh()
        assertThat(references.view(version, RTL)!!.status).isEqualTo("pending")
        assertThat(sent).isEmpty()

        emulator = true
        references.refresh()
        val request = sent.single()
        assertThat(request.processor to request.language).isEqualTo(RTL to "systemverilog")
        assertThat(request.source).isEqualTo(circuit)
        val jobId = "job-${request.submissionId}"
        val type = ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS
        val result = JudgeResult(
            Verdict.ACCEPTED,
            tests = listOf(
                TestResult(1, "sample", true, Verdict.ACCEPTED, score = Score(1000.0, type)),
                TestResult(2, "large", false, Verdict.ACCEPTED, score = Score(5000.0, type)),
            ),
            score = Score(5000.0, type), processor = ProcessorRun(RTL, "Yosys, Verilator", "Verilator + Yosys", "emu-executor"),
        )
        references.apply(JudgeEvent(request.submissionId, 1, 1, SubmissionStatus.FINISHED, Instant.now(), "emu-executor", result = result, jobId = jobId))

        val ready = references.requireReady(version, RTL)
        assertThat(ready.scoreType).isEqualTo(type)
        assertTime(ready.baselineScore, 5000.0)
        assertThat(references.views(version).keys).containsExactly(RTL)
    }

    companion object {
        private const val GPU = "gpu-cuda"
        private const val RTL = "rtl"
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
