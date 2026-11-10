package dev.gpucodeforces.backend.problem

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import org.intellij.lang.annotations.Language
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

// copyToRecursively/deleteRecursively are still experimental.
@OptIn(ExperimentalPathApi::class)
class ProblemPackageReaderTest {
    private val reader = ProblemPackageReader(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
    private val examples = Path.of("../judge/problems/examples")

    @TempDir
    lateinit var temp: Path

    private fun copyOf(example: String): Path =
        examples.resolve(example).copyToRecursively(temp.resolve(example), followLinks = false)

    @Test
    fun `reads a judge example with its statement`() {
        val problem = reader.read(examples.resolve("vector_add"))

        assertThat(problem.id).isEqualTo("vector_add")
        assertThat(problem.frontMatter.code).isEqualTo("1")
        assertThat(problem.frontMatter.difficulty).isEqualTo("easy")
        assertThat(problem.statementMarkdown).startsWith("Даны два вектора").doesNotContain("difficulty:")
        // The backend id replaces the one judge_cli.py needs in problem.json; the generator comes from problem.py.
        assertThat(problem.spec.id).isEqualTo("vector_add")
        assertThat(problem.spec.generator.source).contains("def tests()")
        assertThat(problem.starterCode).contains("void solve(const float* A, const float* B, float* C, int N) {").contains("__global__ void kernel")
    }

    @Test
    fun `judge hash ignores the statement, content hash does not`() {
        val directory = copyOf("vector_add")
        val before = reader.read(directory)
        directory.resolve("statement.md").writeText(directory.resolve("statement.md").readText() + "\nУточнение.")
        val after = reader.read(directory)

        assertThat(after.judgeHash).isEqualTo(before.judgeHash)
        assertThat(after.contentHash).isNotEqualTo(before.contentHash)
    }

    @Test
    fun `rejects a statement without front matter`() {
        val directory = copyOf("vector_add")
        directory.resolve("statement.md").writeText("# Условие без метаданных")

        assertThatThrownBy { reader.read(directory) }
            .isInstanceOf(ProblemPackageException::class.java).hasMessageContaining("YAML")
    }

    @Test
    fun `rejects an unknown difficulty`() {
        val directory = copyOf("vector_add")
        directory.resolve("statement.md").writeText(directory.resolve("statement.md").readText().replace("difficulty: easy", "difficulty: trivial"))

        assertThatThrownBy { reader.read(directory) }
            .isInstanceOf(ProblemPackageException::class.java).hasMessageContaining("difficulty")
    }

    @Test
    fun `rejects a spec the judge would reject`() {
        val directory = copyOf("vector_add")
        directory.resolve("problem.json").writeText(directory.resolve("problem.json").readText().replace("\"size\": \"N\"", "\"size\": \"Q\""))

        assertThatThrownBy { reader.read(directory) }
            .isInstanceOf(ProblemPackageException::class.java).hasMessageContaining("проверку судьи")
    }

    @Test
    fun `rejects a generator inlined into problem json`() {
        val directory = copyOf("vector_add")
        directory.resolve("problem.json").writeText(
            directory.resolve("problem.json").readText().replaceFirst("{", "{\"generator\": {\"source\": \"x\"},"),
        )

        assertThatThrownBy { reader.read(directory) }
            .isInstanceOf(ProblemPackageException::class.java).hasMessageContaining("problem.py")
    }

    @Test
    fun `optimization requires a real starting solution and a positive baseline`() {
        val directory = copyOf("vector_add")
        @Language("JSON")
        val metadata = """{"format":"optimization","baselineTimeMs":2.5}"""
        directory.resolve("format.json").writeText(metadata)
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("starter.cu")

        directory.resolve("starter.cu").writeText("void solve() {}")
        val problem = reader.read(directory)
        assertThat(problem.format.format).isEqualTo("optimization")
        assertThat(problem.format.baselineTimeMs).isEqualByComparingTo("2.5")
        assertThat(problem.starterCode).isEqualTo("void solve() {}")

        directory.resolve("format.json").writeText(metadata.replace("2.5", "0"))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("baselineTimeMs")
    }

    @Test
    fun `guess packages preserve private measurements and change comparison scope with the answer`() {
        val directory = copyOf("vector_add")
        @Language("JSON")
        val metadata = """{"format":"guess","firstTimeMs":1.2,"secondTimeMs":2.4,"explanation":"Пояснение"}"""
        directory.resolve("format.json").writeText(metadata)
        directory.resolve("first.cu").writeText("void first() {}")
        directory.resolve("second.cu").writeText("void second() {}")
        val before = reader.read(directory)
        assertThat(before.format.correctChoice()).isEqualTo("first")
        assertThat(before.format.firstCode).isEqualTo("void first() {}")
        assertThat(before.format.explanation).isEqualTo("Пояснение")

        directory.resolve("format.json").writeText(metadata.replace("1.2", "3.6"))
        val after = reader.read(directory)
        assertThat(after.format.correctChoice()).isEqualTo("second")
        assertThat(after.judgeHash).isNotEqualTo(before.judgeHash)
        assertThat(after.contentHash).isNotEqualTo(before.contentHash)
    }

    @Test
    fun `guess packages reject missing candidates and invalid metadata`() {
        val directory = copyOf("vector_add")
        @Language("JSON")
        val metadata = """{"format":"guess","firstTimeMs":1.2,"secondTimeMs":2.4,"explanation":"Пояснение"}"""
        directory.resolve("format.json").writeText(metadata)
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("first.cu")
        directory.resolve("first.cu").writeText("void first() {}")
        directory.resolve("second.cu").writeText(" ")
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("варианты кода")
        directory.resolve("format.json").writeText(metadata.replace("2.4", "-1"))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("secondTimeMs")
        directory.resolve("format.json").writeText(metadata.replace("guess", "unknown"))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("неизвестный format")
        directory.resolve("format.json").writeText(metadata.replace("Пояснение", ""))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("explanation")
    }
}
