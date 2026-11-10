package com.example.gpuplatform.sync.service

import com.example.gpuplatform.config.JacksonConfig
import com.example.gpuplatform.sync.domain.ProblemParseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProblemParserTest {

    private lateinit var parser: ProblemParser

    @BeforeEach
    fun setUp() {
        parser = ProblemParser(JacksonConfig().problemYamlMapper())
    }

    private val validDocument = """
        ---
        title: Vector Addition

        tags:
          - EASY
          - vector

        author: platform

        interface:
          function_name: solve
          parameters:
            - name: A
              type: float*
              direction: input
            - name: N
              type: int
              direction: input

        constraints:
          N:
            min: 1
            max: 10000000

        execution:
          gpu:
            min_compute_capability: "7.0"
            min_memory_mb: 2048
          limits:
            gpu_time_ms: 1000
            host_memory_mb: 4096
            device_memory_mb: 2048
        ---

        # Vector Addition

        Body with **markdown**.
    """.trimIndent()

    @Test
    fun `parses title tags and author`() {
        val parsed = parser.parse(validDocument, "vector-addition", "/problems/vector-addition/problem.md")
        assertThat(parsed.title).isEqualTo("Vector Addition")
        assertThat(parsed.tags).containsExactly("EASY", "vector")
        assertThat(parsed.author).isEqualTo("platform")
        assertThat(parsed.slug).isEqualTo("vector-addition")
    }

    @Test
    fun `extracts markdown body`() {
        val parsed = parser.parse(validDocument, "vector-addition", "/x/problem.md")
        assertThat(parsed.statement).isEqualTo("# Vector Addition\n\nBody with **markdown**.")
    }

    @Test
    fun `parses typed metadata - interface constraints execution`() {
        val parsed = parser.parse(validDocument, "vector-addition", "/x/problem.md")
        val iface = parsed.metadata.interfaceSpec
        assertThat(iface.functionName).isEqualTo("solve")
        assertThat(iface.parameters).hasSize(2)
        assertThat(iface.parameters.first().name).isEqualTo("A")
        assertThat(iface.parameters.first().type).isEqualTo("float*")
        assertThat(iface.parameters.first().direction).isEqualTo("input")

        val n = parsed.metadata.constraints["N"]
        assertThat(n).isNotNull()
        assertThat(n!!.min).isEqualTo(1)
        assertThat(n.max).isEqualTo(10000000)

        assertThat(parsed.metadata.execution.gpu.minComputeCapability).isEqualTo("7.0")
        assertThat(parsed.metadata.execution.gpu.minMemoryMb).isEqualTo(2048)
        assertThat(parsed.metadata.execution.limits.gpuTimeMs).isEqualTo(1000)
        assertThat(parsed.metadata.execution.limits.hostMemoryMb).isEqualTo(4096)
        assertThat(parsed.metadata.execution.limits.deviceMemoryMb).isEqualTo(2048)
    }

    @Test
    fun `handles CRLF line endings`() {
        val crlf = validDocument.replace("\n", "\r\n")
        val parsed = parser.parse(crlf, "vector-addition", "/x/problem.md")
        assertThat(parsed.title).isEqualTo("Vector Addition")
        assertThat(parsed.statement).isEqualTo("# Vector Addition\n\nBody with **markdown**.")
    }

    @Test
    fun `missing front matter is rejected`() {
        val doc = "# Vector Addition\n\nJust a body.\n"
        assertThatThrownBy {
            parser.parse(doc, "vector-addition", "/missing/problem.md")
        }
            .isInstanceOf(ProblemParseException::class.java)
            .hasMessageContaining("/missing/problem.md")
            .hasMessageContaining("Missing YAML front matter")
    }

    @Test
    fun `malformed front matter (no closing marker) is rejected`() {
        val doc = "---\ntitle: Vector Addition\n\n# body\n"
        assertThatThrownBy {
            parser.parse(doc, "vector-addition", "/bad/problem.md")
        }
            .isInstanceOf(ProblemParseException::class.java)
            .hasMessageContaining("/bad/problem.md")
    }

    @Test
    fun `invalid yaml is rejected`() {
        val doc = "---\ntitle: [unclosed\n---\n\n# body\n"
        assertThatThrownBy {
            parser.parse(doc, "vector-addition", "/bad/problem.md")
        }
            .isInstanceOf(ProblemParseException::class.java)
            .hasMessageContaining("/bad/problem.md")
    }

    @Test
    fun `missing required title is rejected`() {
        val doc = "---\ntags:\n  - EASY\n---\n\n# body\n"
        assertThatThrownBy {
            parser.parse(doc, "vector-addition", "/bad/problem.md")
        }
            .isInstanceOf(ProblemParseException::class.java)
            .hasMessageContaining("title")
    }

    @Test
    fun `content hash is stable and differs for different content`() {
        val hash1 = parser.sha256Hex(parser.normalizedContent(validDocument))
        val reprised = parser.sha256Hex(parser.normalizedContent(validDocument))
        assertThat(hash1).isEqualTo(reprised)
        assertThat(hash1).matches("^[0-9a-f]{64}\$")

        val changed = parser.sha256Hex(parser.normalizedContent(validDocument.replace("Vector", "Scalar")))
        assertThat(changed).isNotEqualTo(hash1)
    }

    @Test
    fun `content hash is stable across CRLF and LF`() {
        val lf = parser.sha256Hex(parser.normalizedContent(validDocument))
        val crlfDoc = validDocument.replace("\n", "\r\n")
        val crlf = parser.sha256Hex(parser.normalizedContent(crlfDoc))
        assertThat(crlf).isEqualTo(lf)
    }

    @Test
    fun `author is optional`() {
        val doc = "---\ntitle: No Author\ntags: []\n---\n\n# body\n"
        val parsed = parser.parse(doc, "no-author", "/x/problem.md")
        assertThat(parsed.author).isNull()
    }

    @Test
    fun `carries solution and tests scripts through when present`() {
        val parsed = parser.parse(
            validDocument,
            "vector-addition",
            "/x/problem.md",
            solutionPy = "def solve(inputs):\n    return {}\n",
            testsPy = "def generate_tests(seed):\n    yield {}\n",
        )
        assertThat(parsed.solutionPy).isEqualTo("def solve(inputs):\n    return {}")
        assertThat(parsed.testsPy).isEqualTo("def generate_tests(seed):\n    yield {}")
    }

    @Test
    fun `scripts are null when absent`() {
        val parsed = parser.parse(validDocument, "vector-addition", "/x/problem.md")
        assertThat(parsed.solutionPy).isNull()
        assertThat(parsed.testsPy).isNull()
    }

    @Test
    fun `composite content hash changes when a script changes`() {
        val md = validDocument
        val solution = "def solve(inputs):\n    return {}\n"

        val h1 = parser.contentHash(md, solution, null)
        val h2 = parser.contentHash(md, solution, null)
        assertThat(h1).isEqualTo(h2)
        assertThat(h1).matches("^[0-9a-f]{64}\$")

        val changedSolution = parser.contentHash(md, solution.replace("{}", "{'C': 1}"), null)
        assertThat(changedSolution).isNotEqualTo(h1)

        val addedTests = parser.contentHash(md, solution, "def generate_tests(seed):\n    yield {}\n")
        assertThat(addedTests).isNotEqualTo(h1)
    }
}