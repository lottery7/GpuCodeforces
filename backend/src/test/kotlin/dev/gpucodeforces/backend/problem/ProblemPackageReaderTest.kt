package dev.gpucodeforces.backend.problem

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
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
        assertThat(problem.starterCode).contains("void solve(const float* A, const float* B, float* C, int N) {")
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
}
