package dev.gpucodeforces.backend.problem

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.intellij.lang.annotations.Language
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
        // A package without processors is a gpu-cuda problem.
        assertThat(problem.starters.keys).containsExactly("gpu-cuda")
        assertThat(problem.starters.getValue("gpu-cuda").getValue("cuda"))
            .contains("void solve(const float* A, const float* B, float* C, int N) {").contains("__global__ void kernel")
    }

    private fun withProcessors(directory: Path, @Language("JSON") processors: String) {
        val spec = directory.resolve("problem.json")
        spec.writeText(spec.readText().replaceFirst("{", "{\"processors\": $processors,"))
    }

    @Test
    fun `starters come per processor and language, from files or from the signature`() {
        val directory = copyOf("vector_add")
        withProcessors(
            directory,
            """[{"id": "gpu-cuda", "languages": ["cuda"]}, {"id": "cuda-sim", "languages": ["cuda"], "limit": 100000},
               {"id": "rtl", "languages": ["systemverilog"], "limit": 1e6}, {"id": "riscv-rvv", "languages": ["c", "asm"], "limit": 1e6}]""",
        )
        directory.resolve("starter.cu").writeText("// cuda starter")
        directory.resolve("starter.cuda-sim.cu").writeText("// simulator starter")

        val starters = reader.read(directory).starters

        assertThat(starters.getValue("gpu-cuda").getValue("cuda")).isEqualTo("// cuda starter")
        assertThat(starters.getValue("cuda-sim").getValue("cuda")).isEqualTo("// simulator starter")
        assertThat(starters.getValue("rtl").getValue("systemverilog")).startsWith("module solve (").contains("output logic done")
        assertThat(starters.getValue("riscv-rvv").getValue("c")).contains("void solve(const float* A, const float* B, float* C, int32_t N) {")
        assertThat(starters.getValue("riscv-rvv").getValue("asm")).contains(".globl solve")
    }

    @Test
    fun `adding a processor changes the content hash, a statement-only package keeps its old one`() {
        val directory = copyOf("vector_add")
        val before = reader.read(directory)
        withProcessors(directory, """[{"id": "gpu-cuda", "languages": ["cuda"]}, {"id": "rtl", "languages": ["systemverilog"], "limit": 1e6}]""")
        val after = reader.read(directory)

        assertThat(after.contentHash).isNotEqualTo(before.contentHash)
        assertThat(after.judgeHash).isNotEqualTo(before.judgeHash)
    }

    @Test
    fun `a starter no processor uses is rejected`() {
        val directory = copyOf("vector_add")
        directory.resolve("starter.rtl.sv").writeText("module solve; endmodule")

        assertThatThrownBy { reader.read(directory) }
            .isInstanceOf(ProblemPackageException::class.java).hasMessageContaining("starter.rtl.sv").hasMessageContaining("starter.gpu-cuda.cu")
    }

    @Test
    fun `a processor the judge does not know is rejected`() {
        val directory = copyOf("vector_add")
        withProcessors(directory, """[{"id": "gpu", "languages": ["cuda"]}]""")

        assertThatThrownBy { reader.read(directory) }
            .isInstanceOf(ProblemPackageException::class.java).hasMessageContaining("unknown processor 'gpu'")
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
