package dev.gpucodeforces.backend.problem

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import org.intellij.lang.annotations.Language
import dev.gpucodeforces.judge.api.SpecValidator
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
    fun `optimization requires a nonempty bounded starting solution without an authored time`() {
        val directory = copyOf("vector_add")
        @Language("JSON")
        val metadata = """{"format":"optimization"}"""
        directory.resolve("format.json").writeText(metadata)
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("starter.cu")

        directory.resolve("starter.cu").writeText("void solve() {}")
        val problem = reader.read(directory)
        assertThat(problem.format.format).isEqualTo("optimization")
        assertThat(problem.format.baselineTimeMs).isNull()
        assertThat(problem.starterCode).isEqualTo("void solve() {}")

        listOf(" ", "void solve() {}\u0000", "a".repeat(SpecValidator.Limits().maxSourceBytes + 1)).forEach { source ->
            directory.resolve("starter.cu").writeText(source)
            assertThatThrownBy { reader.read(directory) }.isInstanceOf(ProblemPackageException::class.java).hasMessageContaining("starter.cu")
        }
    }

    @Test
    fun `guess packages import code for automatic measurements and version changes to the candidates`() {
        val directory = copyOf("vector_add")
        @Language("JSON")
        val metadata = """{"format":"guess","explanation":"Пояснение"}"""
        directory.resolve("format.json").writeText(metadata)
        directory.resolve("first.cu").writeText("void first() {}")
        directory.resolve("second.cu").writeText("void second() {}")
        val before = reader.read(directory)
        assertThat(before.format.firstCode).isEqualTo("void first() {}")
        assertThat(before.format.secondCode).isEqualTo("void second() {}")
        assertThat(before.format.firstTimeMs).isNull()
        assertThat(before.format.secondTimeMs).isNull()
        assertThat(before.format.explanation).isEqualTo("Пояснение")

        directory.resolve("second.cu").writeText("void second() { int value = 1; }")
        val after = reader.read(directory)
        assertThat(after.judgeHash).isNotEqualTo(before.judgeHash)
        assertThat(after.contentHash).isNotEqualTo(before.contentHash)
        directory.resolve("problem.py").writeText(directory.resolve("problem.py").readText() + "\n")
        assertThat(reader.read(directory).judgeHash).isNotEqualTo(after.judgeHash)
    }

    @Test
    fun `guess packages reject missing candidates and invalid metadata`() {
        val directory = copyOf("vector_add")
        @Language("JSON")
        val metadata = """{"format":"guess","explanation":"Пояснение"}"""
        directory.resolve("format.json").writeText(metadata)
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("first.cu")
        directory.resolve("first.cu").writeText("void first() {}")
        directory.resolve("second.cu").writeText(" ")
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("варианты кода")
        directory.resolve("format.json").writeText(metadata.replace("guess", "unknown"))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("неизвестный format")
        directory.resolve("format.json").writeText(metadata.replace("Пояснение", ""))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("explanation")
    }

    @Test
    fun `optimization measurement scope includes the starting code benchmark and generator`() {
        val directory = copyOf("vector_add")
        val standardHash = reader.read(directory).judgeHash
        directory.resolve("starter.cu").writeText("void solve() {}")
        @Language("JSON")
        val metadata = """{"format":"optimization","benchmark":{"inputDescription":"N = 1 и 4","aggregation":"geometric_mean"}}"""
        directory.resolve("format.json").writeText(metadata)
        val geometric = reader.read(directory)
        assertThat(geometric.format.aggregation()).isEqualTo("geometric_mean")
        assertThat(geometric.judgeHash).isNotEqualTo(standardHash)

        directory.resolve("format.json").writeText(metadata.replace("geometric_mean", "max"))
        assertThat(reader.read(directory).judgeHash).isNotEqualTo(geometric.judgeHash)
        directory.resolve("format.json").writeText(metadata.replace("N = 1 и 4", "N = 8"))
        assertThat(reader.read(directory).judgeHash).isNotEqualTo(geometric.judgeHash)
        directory.resolve("format.json").writeText(metadata)
        directory.resolve("starter.cu").writeText("void solve() { int value = 1; }")
        val changedCode = reader.read(directory)
        assertThat(changedCode.judgeHash).isNotEqualTo(geometric.judgeHash)
        directory.resolve("problem.py").writeText(directory.resolve("problem.py").readText() + "\n")
        assertThat(reader.read(directory).judgeHash).isNotEqualTo(changedCode.judgeHash)

        directory.resolve("format.json").writeText("""{"format":"optimization"}""")
        val maximum = reader.read(directory)
        assertThat(maximum.format.aggregation()).isEqualTo("max")
        directory.resolve("starter.cu").writeText("void solve() {}")
        assertThat(reader.read(directory).judgeHash).isNotEqualTo(maximum.judgeHash)

        directory.resolve("format.json").writeText(metadata.replace("N = 1 и 4", " "))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("inputDescription")
        directory.resolve("format.json").writeText(metadata.replace("geometric_mean", "average"))
        assertThatThrownBy { reader.read(directory) }.hasMessageContaining("aggregation")
    }

    @Test
    fun `authored times are rejected in every format including explicitly null values`() {
        val directory = copyOf("vector_add")
        for (format in listOf("standard", "optimization", "guess")) {
            for (field in listOf("baselineTimeMs", "firstTimeMs", "secondTimeMs")) {
                for (value in listOf("2.5", "null")) {
                    directory.resolve("format.json").writeText("""{"format":"$format","$field":$value}""")
                    assertThatThrownBy { reader.read(directory) }.isInstanceOf(ProblemPackageException::class.java).hasMessageContaining(field)
                }
            }
        }
    }

    @Test
    fun `legacy stored times remain readable without making them package inputs`() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        @Language("JSON")
        val source = """{"format":"guess","baselineTimeMs":3.0,"firstTimeMs":1.2,"secondTimeMs":2.4,"benchmarkSynthetic":true}"""
        val data = mapper.readValue(source, ProblemFormatData::class.java)

        assertThat(data.baselineTimeMs).isEqualByComparingTo("3.0")
        assertThat(data.firstTimeMs).isEqualByComparingTo("1.2")
        assertThat(data.secondTimeMs).isEqualByComparingTo("2.4")
        assertThat(data.benchmarkSynthetic).isTrue()
    }
}
