package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.Contest
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetProblem200Response
import dev.gpucodeforces.backend.api.model.ListProblems200Response
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.api.model.ScoreType
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.backend.contest.ContestProblemRepository
import dev.gpucodeforces.backend.problem.ProblemPackageReader
import dev.gpucodeforces.backend.problem.ProblemRepository
import dev.gpucodeforces.backend.problem.ProblemSyncService
import dev.gpucodeforces.backend.problem.ProblemVersionRepository
import dev.gpucodeforces.backend.problem.ProblemsProperties
import org.eclipse.jgit.api.Git
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

// copyToRecursively/deleteRecursively are still experimental.
@OptIn(ExperimentalPathApi::class)
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProblemIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var syncService: ProblemSyncService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var packageReader: ProblemPackageReader

    @Autowired
    lateinit var problemRepository: ProblemRepository

    @Autowired
    lateinit var versionRepository: ProblemVersionRepository

    @Autowired
    lateinit var contestProblemRepository: ContestProblemRepository

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var jsonMapper: JsonMapper

    private val client by lazy { RestTestClient.bindToServer().baseUrl("http://localhost:$port/api").build() }

    private fun register(handle: String): Pair<User, String> {
        val result = client.post().uri("/users").contentType(MediaType.APPLICATION_JSON)
            .body(RegisterUserRequest(handle, "$handle@example.com", "password123"))
            .exchange().expectStatus().isCreated.expectBody(User::class.java).returnResult()
        return result.responseBody!! to result.responseCookies.getFirst("session")!!.value
    }

    private fun list(query: String = "", cookie: String? = null): ListProblems200Response =
        client.get().uri("/problems$query")
            .apply { if (cookie != null) cookie("session", cookie) }
            .exchange().expectStatus().isOk.expectBody(ListProblems200Response::class.java).returnResult().responseBody!!

    private fun createContest(cookie: String, authorId: String, problemIds: Set<String>, processor: String = "gpu-cuda") =
        client.post().uri("/contests").contentType(MediaType.APPLICATION_JSON).cookie("session", cookie)
            .body(
                ContestInput(
                    "Раунд",
                    Instant.now().plus(1, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
                    Instant.now().plus(2, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
                    setOf(authorId),
                    problemIds,
                    processor,
                ),
            )
            .exchange()

    @Test
    @AllowFullScan("problems", "select \\w+\\.id from gpucodeforces\\.problems .*current_version_id is not null", "Synchronization compares only active problem ids; failed packages remain present")
    @AllowFullScan("problems", "FROM gpucodeforces\\.problems p", "Exact catalogue totals and context-wide tags inspect current ids, never historical version payloads")
    fun `bank, drafts, problem page, contest versions and resync`() {
        val (author, authorCookie) = register("problem_author")
        val (_, otherCookie) = register("problem_reader")

        val publicBank = list()
        assertThat(publicBank.items.map { it.id }).containsExactly("vector_add", "matmul_host")
        assertThat(publicBank.tags).containsExactly("CUDA", "Векторы", "Матрицы")
        assertThat(publicBank.items.first().solved).isFalse()
        // codes 1, 2 and 10 in the order of numbers: as text "10" would go before "2"
        assertThat(list(cookie = authorCookie).items.map { it.id }).containsExactly("vector_add", "matmul_host", "draft_task")
        assertThat(list(cookie = otherCookie).items.map { it.id }).doesNotContain("draft_task")
        client.get().uri("/problems/draft_task").exchange().expectStatus().isNotFound

        assertThat(list("?difficulty=medium").items.map { it.id }).containsExactly("matmul_host")
        assertThat(list("?q=СЛОЖЕНИЕ").items.map { it.id }).containsExactly("vector_add")
        assertThat(list("?tag=матрицы").items.map { it.id }).containsExactly("matmul_host")
        val firstByDifficulty = list("?sort=difficulty&limit=1")
        assertThat(firstByDifficulty.total).isEqualTo(2)
        assertThat(firstByDifficulty.items.map { it.id }).containsExactly("vector_add")
        client.get().uri("/problems?sort=title").exchange().expectStatus().isBadRequest

        // Processors: vector_add is on gpu-cuda and rtl here, matmul_host only on gpu-cuda.
        assertThat(list("?processor=rtl").items.map { it.id }).containsExactly("vector_add")
        assertThat(list().items.first { it.id == "vector_add" }.processors.map { it.id }).containsExactly("gpu-cuda", "rtl")
        client.get().uri("/problems?processor=coral-npu").exchange().expectStatus().isBadRequest
        client.get().uri("/problems?sort=bestScore").exchange().expectStatus().isBadRequest
        assertThat(list("?sort=bestScore&processor=gpu-cuda").total).isEqualTo(2)

        val problem = client.get().uri("/problems/vector_add").exchange().expectStatus().isOk
            .expectBody(GetProblem200Response::class.java).returnResult().responseBody!!
        assertThat(problem.code).isEqualTo("1")
        assertThat(problem.memoryLimitMb).isEqualTo(256)
        assertThat(problem.statementMarkdown).startsWith("Даны два вектора")
        val (gpu, rtl) = problem.processorDetails
        assertThat(gpu.id).isEqualTo("gpu-cuda")
        assertThat(gpu.limit.value).isEqualByComparingTo(BigDecimal("20"))
        assertThat(gpu.limit.type).isEqualTo(ScoreType.TIME_MS)
        assertThat(gpu.starters.getValue("cuda")).contains("void solve(const float* A, const float* B, float* C, int N)")
        assertThat(gpu.environmentMarkdown).contains("NVIDIA GeForce RTX 3060").contains("20 мс")
        assertThat(rtl.limit.type).isEqualTo(ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS)
        assertThat(rtl.starters.getValue("systemverilog")).startsWith("module solve (")
        assertThat(rtl.environmentMarkdown).contains("тактов × глубины × транзисторов").contains("список появится после первой принятой посылки")

        createContest(authorCookie, author.id, setOf("missing")).expectStatus().isBadRequest
        createContest(authorCookie, author.id, setOf("draft_task")).expectStatus().isBadRequest
        // matmul_host cannot be solved on rtl, so a contest on rtl cannot take it.
        createContest(authorCookie, author.id, setOf("vector_add", "matmul_host"), "rtl").expectStatus().isBadRequest
        val contest = createContest(authorCookie, author.id, linkedSetOf("matmul_host", "vector_add"))
            .expectStatus().isCreated.expectBody(Contest::class.java).returnResult().responseBody!!
        // In a contest a problem shows the contest's processor only.
        assertThat(list("?context=${contest.id}", authorCookie).items.flatMap { problem -> problem.processors.map { it.id } }.distinct())
            .containsExactly("gpu-cuda")
        client.get().uri("/problems?context=${contest.id}").cookie("session", otherCookie).exchange().expectStatus().isForbidden
        assertThat(list("?context=${contest.id}", authorCookie).items.map { it.id }).containsExactly("matmul_host", "vector_add")

        // A new version of a problem moves the bank and the upcoming contest; a removed package leaves the bank only.
        val statement = problems.resolve("vector_add/statement.md")
        statement.writeText(statement.readText().replace("Сложение векторов", "Сумма векторов"))
        problems.resolve("matmul_host").deleteRecursively()
        val result = syncService.sync()

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.removed).isEqualTo(1)
        assertThat(list().items.map { it.title }).containsExactly("Сумма векторов")
        assertThat(list("?context=${contest.id}", authorCookie).items.map { it.title })
            .containsExactly("Умножение матриц из памяти CPU", "Сумма векторов")
        val stalePins = jdbc.queryForObject(
            """
            SELECT count(*) FROM gpucodeforces.contest_problems cp JOIN gpucodeforces.problems p ON p.id = cp.problem_id
            WHERE cp.contest_id = ? AND p.current_version_id IS NOT NULL AND cp.problem_version_id <> p.current_version_id
            """,
            Long::class.java,
            UUID.fromString(contest.id),
        )
        assertThat(stalePins).isZero()

        // A broken package remains present: failed validation must not unpublish its old version.
        statement.writeText("invalid front matter")
        val invalid = syncService.sync()
        assertThat(invalid.failed).containsKey("vector_add")
        assertThat(list().items.map { it.title }).containsExactly("Сумма векторов")

        // The real setup: a clone of the problem repository with the packages in problems/. A directory in a
        // foreign format is reported as invalid instead of being skipped silently.
        val origin = Files.createTempDirectory("problem-repo")
        Git.init().setDirectory(origin.toFile()).setInitialBranch("master").call().use { git ->
            val packages = Files.createDirectories(origin.resolve("problems"))
            examples.resolve("vector_add").copyToRecursively(packages.resolve("git_task"), followLinks = false)
            Files.createDirectories(packages.resolve("legacy")).resolve("problem.md").writeText("# old format")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("problems").setAuthor("test", "test@example.com").setCommitter("test", "test@example.com").call()
        }
        val fromGit = ProblemSyncService(
            ProblemsProperties(
                directory = Files.createTempDirectory("problem-clone").resolve("repo").toString(),
                gitUrl = origin.toUri().toString(),
                path = "problems",
            ),
            packageReader, problemRepository, versionRepository, contestProblemRepository, transactionTemplate, jsonMapper,
        ).sync()

        assertThat(fromGit.revision).hasSize(40)
        assertThat(fromGit.failed).containsOnlyKeys("legacy")
        assertThat(list().items.map { it.id }).containsExactly("git_task")
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.1-alpine")

        private val problems: Path = Files.createTempDirectory("problems")
        private val examples: Path = Path.of("../judge/problems/examples")

        @JvmStatic
        @DynamicPropertySource
        fun problemDirectory(registry: DynamicPropertyRegistry) {
            listOf("vector_add", "matmul_host").forEach { examples.resolve(it).copyToRecursively(problems.resolve(it), followLinks = false) }
            TestProblems.withRtl().resolve("vector_add/problem.json").copyTo(problems.resolve("vector_add/problem.json"), overwrite = true)
            val draft = examples.resolve("vector_add").copyToRecursively(problems.resolve("draft_task"), followLinks = false)
                .resolve("statement.md")
            draft.writeText(
                draft.readText()
                    .replace("code: \"1\"", "code: \"10\"")
                    .replace("authors: []", "authors: [Problem_Author]\ndraft: true"),
            )
            registry.add("platform.problems.directory") { problems.toString() }
            registry.add("platform.problems.sync-interval") { "PT1H" }
        }
    }
}
