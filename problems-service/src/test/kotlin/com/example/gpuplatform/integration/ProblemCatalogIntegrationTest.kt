package com.example.gpuplatform.integration

import com.example.gpuplatform.common.api.ApiException
import com.example.gpuplatform.problem.repository.ProblemRepository
import com.example.gpuplatform.problem.service.ProblemService
import com.example.gpuplatform.sync.domain.ProblemSource
import com.example.gpuplatform.sync.domain.RepositorySnapshot
import com.example.gpuplatform.sync.service.ProblemImportService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Sort
import java.nio.file.Files
import java.nio.file.Path

class ProblemCatalogIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var importService: ProblemImportService

    @Autowired
    lateinit var problemRepository: ProblemRepository

    @Autowired
    lateinit var problemService: ProblemService

    private fun writeProblem(root: Path, slug: String, title: String, tags: List<String>, body: String): String {
        val dir = Files.createDirectories(root.resolve(slug))
        val file = dir.resolve("problem.md")
        val content = buildString {
            appendLine("---")
            appendLine("title: $title")
            appendLine("tags:")
            tags.forEach { appendLine("  - $it") }
            appendLine("author: platform")
            appendLine("interface:")
            appendLine("  function_name: solve")
            appendLine("---")
            appendLine()
            appendLine(body)
        }
        Files.writeString(file, content)
        return file.toString()
    }

    private fun writeScripts(root: Path, slug: String, solutionPy: String, testsPy: String) {
        val dir = root.resolve(slug)
        Files.writeString(dir.resolve("solution.py"), solutionPy)
        Files.writeString(dir.resolve("tests.py"), testsPy)
    }

    private fun snapshot(sha: String, sources: List<String>): RepositorySnapshot =
        RepositorySnapshot(
            sha,
            sources.map { p -> Path.of(p).let { ProblemSource(it.parent.fileName.toString(), p) } },
        )

    @Test
    fun `flyway migration creates schema and import inserts problem`() {
        val root = Files.createTempDirectory("gpu-test")
        val path = writeProblem(root, "insert-only", "Vector Addition", listOf("EASY", "vector"), "# Vector Addition")
        importService.importAll(snapshot("aaaa0001", listOf(path)))

        val problem = problemRepository.findBySlug("insert-only")
        assertThat(problem).isNotNull()
        assertThat(problem!!.title).isEqualTo("Vector Addition")
        assertThat(problem.tags).containsExactly("EASY", "vector")
        assertThat(problem.gitCommitSha).isEqualTo("aaaa0001")
        assertThat(problem.statement).startsWith("# Vector Addition")
    }

    @Test
    fun `re-importing unchanged content is skipped`() {
        val root = Files.createTempDirectory("gpu-test")
        val path = writeProblem(root, "skip-check", "Softmax", listOf("MEDIUM"), "# Softmax")
        importService.importAll(snapshot("aaaa0002", listOf(path)))

        val first = problemRepository.findBySlug("skip-check")!!
        val firstUpdated = first.updatedAt

        val summary = importService.importAll(snapshot("bbbb0002", listOf(path)))
        assertThat(summary.imported).isEqualTo(0)
        assertThat(summary.updated).isEqualTo(0)
        assertThat(summary.unchanged).isEqualTo(1)

        val second = problemRepository.findBySlug("skip-check")!!
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.updatedAt).isEqualTo(firstUpdated)
        assertThat(second.gitCommitSha).isEqualTo("aaaa0002")
    }

    @Test
    fun `updating existing problem preserves id and updates content`() {
        val root = Files.createTempDirectory("gpu-test")
        val path = writeProblem(root, "update-check", "Matrix Multiplication", listOf("UPDATE"), "# Matrix Multiplication")
        importService.importAll(snapshot("aaaa0003", listOf(path)))

        val before = problemRepository.findBySlug("update-check")!!

        Files.writeString(
            Path.of(path),
            Files.readString(Path.of(path)).replace("Matrix Multiplication", "Matmul Optimized"),
        )
        val summary = importService.importAll(snapshot("bbbb0003", listOf(path)))
        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.imported).isEqualTo(0)

        val after = problemRepository.findBySlug("update-check")!!
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.title).isEqualTo("Matmul Optimized")
        assertThat(after.updatedAt).isAfterOrEqualTo(before.updatedAt)

        // Only one row exists for this slug (no duplicates created).
        val sameSlug = problemRepository.findAll().filter { it.slug == "update-check" }
        assertThat(sameSlug).hasSize(1)
    }

    @Test
    fun `listing honours pagination`() {
        val root = Files.createTempDirectory("gpu-test")
        (1..5).forEach { i ->
            writeProblem(root, "paged-$i", "Problem $i", listOf("PAGED"), "# Problem $i")
        }
        importService.importAll(snapshot("aaaa0004", listProblems(root)))

        val page = problemService.list(page = 0, size = 2, tag = "PAGED", sortBy = "title", sortDir = Sort.Direction.ASC)
        assertThat(page.content).hasSize(2)
        assertThat(page.totalElements).isEqualTo(5)
        assertThat(page.totalPages).isEqualTo(3)
        assertThat(page.page).isEqualTo(0)
        assertThat(page.content.first().slug).isEqualTo("paged-1")
    }

    @Test
    fun `tag filtering returns only matching problems`() {
        val root = Files.createTempDirectory("gpu-test")
        writeProblem(root, "easy-a", "Easy A", listOf("EASY"), "# Easy A")
        writeProblem(root, "hard-b", "Hard B", listOf("HARD"), "# Hard B")
        importService.importAll(snapshot("aaaa0005", listProblems(root)))

        val page = problemService.list(page = 0, size = 20, tag = "HARD", sortBy = "title", sortDir = Sort.Direction.ASC)
        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content.single().slug).isEqualTo("hard-b")
    }

    @Test
    fun `fetching by slug returns details`() {
        val root = Files.createTempDirectory("gpu-test")
        writeProblem(root, "detail-check", "Vector Addition", listOf("EASY"), "# Vector Addition body")
        importService.importAll(snapshot("aaaa0006", listProblems(root)))

        val detail = problemService.getBySlug("detail-check")
        assertThat(detail.title).isEqualTo("Vector Addition")
        assertThat(detail.tags).contains("EASY")
        assertThat(detail.gitHash).isEqualTo("aaaa0006")
        assertThat(detail.statement).startsWith("# Vector Addition body")
        assertThat(detail.metadata.interfaceSpec.functionName).isEqualTo("solve")
    }

    @Test
    fun `detail git hash changes when content is updated`() {
        val root = Files.createTempDirectory("gpu-test")
        val path = writeProblem(root, "hash-check", "Softmax", listOf("MEDIUM"), "# Softmax")
        importService.importAll(snapshot("aaaa0007", listProblems(root)))

        assertThat(problemService.getBySlug("hash-check").gitHash).isEqualTo("aaaa0007")

        Files.writeString(
            Path.of(path),
            Files.readString(Path.of(path)).replace("Softmax", "FlashAttention"),
        )
        importService.importAll(snapshot("cccc0007", listProblems(root)))

        assertThat(problemService.getBySlug("hash-check").gitHash).isEqualTo("cccc0007")
        assertThat(problemService.getBySlug("hash-check").title).isEqualTo("FlashAttention")
    }

    @Test
    fun `detail exposes solution and test scripts`() {
        val root = Files.createTempDirectory("gpu-test")
        writeProblem(root, "scripts", "Vector Addition", listOf("EASY"), "# Vector Addition")
        writeScripts(
            root, "scripts",
            solutionPy = "def solve(inputs):\n    return {'C': inputs['A'] + inputs['B']}\n",
            testsPy = "def generate_tests(seed):\n    yield {'inputs': {'A': 1}}\n",
        )
        importService.importAll(snapshot("aaaa0008", listProblems(root)))

        val detail = problemService.getBySlug("scripts")
        assertThat(detail.solutionPy).isEqualTo("def solve(inputs):\n    return {'C': inputs['A'] + inputs['B']}")
        assertThat(detail.testsPy).isEqualTo("def generate_tests(seed):\n    yield {'inputs': {'A': 1}}")
    }

    @Test
    fun `changing only the solution script updates the row and git hash`() {
        val root = Files.createTempDirectory("gpu-test")
        writeProblem(root, "script-hash", "Vector Addition", listOf("EASY"), "# Vector Addition")
        writeScripts(root, "script-hash", solutionPy = "def solve(inputs):\n    return {}\n", testsPy = "")
        importService.importAll(snapshot("aaaa0009", listProblems(root)))
        assertThat(problemService.getBySlug("script-hash").gitHash).isEqualTo("aaaa0009")

        writeScripts(root, "script-hash", solutionPy = "def solve(inputs):\n    return {'C': 1}\n", testsPy = "")
        val summary = importService.importAll(snapshot("dddd0009", listProblems(root)))
        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.imported).isEqualTo(0)

        val detail = problemService.getBySlug("script-hash")
        assertThat(detail.gitHash).isEqualTo("dddd0009")
        assertThat(detail.solutionPy).isEqualTo("def solve(inputs):\n    return {'C': 1}")
    }

    @Test
    fun `unknown slug returns 404`() {
        assertThatThrownBy { problemService.getBySlug("does-not-exist") }
            .isInstanceOf(ApiException::class.java)
            .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND)
    }

    private fun listProblems(root: Path): List<String> {
        Files.list(root).use { stream ->
            return stream
                .filter { Files.isDirectory(it) }
                .map { it.resolve("problem.md").toString() }
                .toList()
        }
    }
}