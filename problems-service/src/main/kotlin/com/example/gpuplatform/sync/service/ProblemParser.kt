package com.example.gpuplatform.sync.service

import com.example.gpuplatform.problem.domain.ConstraintSpec
import com.example.gpuplatform.problem.domain.ExecutionSpec
import com.example.gpuplatform.problem.domain.InterfaceSpec
import com.example.gpuplatform.problem.domain.ProblemMetadata
import com.example.gpuplatform.sync.domain.ParsedProblem
import com.example.gpuplatform.sync.domain.ProblemParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Parses a `problem.md` file: extracts the YAML front matter, parses it safely
 * into typed metadata, extracts the Markdown body and validates required fields.
 * The optional `solution.py` / `tests.py` sibling scripts are carried through as
 * raw source text.
 */
@Component
class ProblemParser(
    @Qualifier("problemYamlMapper") private val yamlMapper: ObjectMapper,
) {

    /**
     * Parses [content] (the raw file contents) into a [ParsedProblem].
     *
     * @param slug       slug derived from the problem directory name
     * @param sourcePath filesystem path used in error messages
     * @param solutionPy raw contents of `solution.py`, if present
     * @param testsPy    raw contents of `tests.py`, if present
     */
    fun parse(
        content: String,
        slug: String,
        sourcePath: String,
        solutionPy: String? = null,
        testsPy: String? = null,
    ): ParsedProblem {
        val normalized = content.replace("\r\n", "\n")
        val frontMatter = extractFrontMatter(normalized, sourcePath)
        val body = extractBody(normalized, sourcePath)

        val frontMatterObject: ProblemFrontMatter =
            try {
                yamlMapper.readValue(frontMatter, ProblemFrontMatter::class.java)
            } catch (e: Exception) {
                throw ProblemParseException(
                    "Failed to parse YAML front matter in $sourcePath: ${e.message}",
                    e,
                )
            }

        val title = frontMatterObject.title.trim()
        if (title.isEmpty()) {
            throw ProblemParseException("Missing required field 'title' in $sourcePath")
        }

        val metadata = ProblemMetadata(
            interfaceSpec = frontMatterObject.interfaceSpec,
            constraints = frontMatterObject.constraints,
            execution = frontMatterObject.execution,
        )

        return ParsedProblem(
            slug = slug,
            title = title,
            tags = frontMatterObject.tags,
            author = frontMatterObject.author?.takeIf { it.isNotBlank() },
            metadata = metadata,
            statement = body.trim(),
            solutionPy = solutionPy?.trim(),
            testsPy = testsPy?.trim(),
        )
    }

    /**
     * Composite, normalized SHA-256 over a problem's Markdown and both scripts,
     * so that a change to any of the three files invalidates the stored hash and
     * triggers a re-import (and a new `gitHash`).
     */
    fun contentHash(problemMarkdown: String, solutionPy: String?, testsPy: String?): String =
        sha256Hex(
            listOf(
                normalizedContent(problemMarkdown),
                solutionPy?.let(::normalizedContent).orEmpty(),
                testsPy?.let(::normalizedContent).orEmpty(),
            ).joinToString("\u0000")
        )

    /**
     * Returns the raw normalized source text used to compute the content hash.
     * CRLF is normalized to LF so that a checkout with different line endings
     * still produces a stable hash.
     */
    fun normalizedContent(content: String): String = content.replace("\r\n", "\n")

    fun sha256Hex(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun extractFrontMatter(content: String, sourcePath: String): String {
        if (!content.startsWith("---\n")) {
            throw ProblemParseException(
                "Missing YAML front matter in $sourcePath (expected the file to start with '---')",
            )
        }
        val end = content.indexOf("\n---", startIndex = 4)
        if (end < 0) {
            throw ProblemParseException(
                "Malformed YAML front matter in $sourcePath (missing closing '---')",
            )
        }
        return content.substring(4, end)
    }

    private fun extractBody(content: String, sourcePath: String): String {
        val end = content.indexOf("\n---")
        if (end < 0) {
            throw ProblemParseException(
                "Malformed YAML front matter in $sourcePath (missing closing '---')",
            )
        }
        // Skip past the closing '---' line plus its trailing newline.
        return content.substring(end + 5)
    }

    /** Typed representation of the YAML front matter root. */
    data class ProblemFrontMatter(
        val title: String = "",
        val tags: List<String> = emptyList(),
        val author: String? = null,
        @JsonProperty("interface")
        val interfaceSpec: InterfaceSpec = InterfaceSpec(),
        val constraints: Map<String, ConstraintSpec> = emptyMap(),
        val execution: ExecutionSpec = ExecutionSpec(),
    )
}