package dev.gpucodeforces.backend

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.security.MessageDigest
import java.util.Base64
import org.intellij.lang.annotations.Language

internal fun jsonDocument(json: JsonMapper, @Language("JSON") text: String): JsonNode = json.readTree(text)

class PackageReaderTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    internal val manifest = ProblemManifest(
        "A",
        "Vectors",
        "easy",
        listOf("CUDA"),
        "statement.md",
        "starter.cu",
        "generator.cu",
        "reference.cu",
        3,
        1.0,
        128,
        3.0,
        "environment.md"
    )

    internal fun packageTree(
        value: ProblemManifest = manifest,
        tasks: Map<String, ProblemManifest> = mapOf("legacy" to value),
        index: RepositoryIndex = RepositoryIndex(tasks.keys.map { IndexedTask(it, "tasks/$it.json") }),
    ): Pair<JsonNode, Map<String, JsonNode>> {
        val files = mapOf(
            "index.json" to json.writeValueAsString(index),
            "statement.md" to "Add vectors",
            "starter.cu" to "void solve() {}",
            "generator.cu" to "generator",
            "reference.cu" to "reference",
            "environment.md" to "A100, CUDA 13"
        ) + tasks.mapKeys { "tasks/${it.key}.json" }.mapValues { json.writeValueAsString(it.value) }
        val blobs = mutableMapOf<String, JsonNode>()
        val tree = files.map { (path, content) ->
            val bytes = content.toByteArray()
            val sha = MessageDigest.getInstance("SHA-1").run {
                update("blob ${bytes.size}\u0000".toByteArray()); digest(bytes).joinToString("") {
                "%02x".format(it)
            }
            }
            blobs[sha] = json.valueToTree(
                mapOf(
                    "size" to bytes.size,
                    "encoding" to "base64",
                    "content" to Base64.getEncoder().encodeToString(bytes)
                )
            )
            mapOf("path" to path, "sha" to sha, "type" to "blob", "mode" to "100644", "size" to bytes.size)
        }
        return json.valueToTree<JsonNode>(mapOf("truncated" to false, "tree" to tree)) to blobs
    }

    @Test
    fun `reads immutable package artifacts and test metadata`() {
        val (tree, blobs) = packageTree()
        val result = PackageReader(PlatformConfig(), json).read(tree) { blobs.getValue(it) }.getValue("legacy")
        assertThat(result.testCount).isEqualTo(3)
        assertThat(result.generator).isEqualTo("generator")
        assertThat(result.referenceSolution).isEqualTo("reference")
    }

    @Test
    fun `reads several independent tasks and enforces repository-wide budgets`() {
        val tasks = mapOf("vectors" to manifest, "reduction" to manifest.copy(title = "Reduction"))
        val (tree, blobs) = packageTree(tasks = tasks)
        val result = PackageReader(PlatformConfig(), json).read(tree) { blobs.getValue(it) }
        assertThat(result.keys).containsExactly("vectors", "reduction")
        assertThat(result.getValue("reduction").title).isEqualTo("Reduction")
        val limited = PlatformConfig(github = GithubConfig(maxTasksPerRepository = 1))
        assertThatThrownBy { PackageReader(limited, json).read(tree) { blobs.getValue(it) } }
            .isInstanceOf(ImportRejected::class.java)
        val tiny = PlatformConfig(github = GithubConfig(maxImportedBytesPerRepository = 1))
        assertThatThrownBy { PackageReader(tiny, json).read(tree) { error("must not download over budget") } }
            .isInstanceOf(ImportRejected::class.java)
    }

    @Test
    fun `rejects duplicate task keys invalid manifests and unsafe index paths`() {
        val indexes = listOf(
            RepositoryIndex(listOf(null)),
            RepositoryIndex(listOf(IndexedTask("same", "a.json"), IndexedTask("same", "b.json"))),
            RepositoryIndex(listOf(IndexedTask("../unsafe", "a.json"))),
            RepositoryIndex(listOf(IndexedTask("valid", "../a.json"))),
        )
        indexes.forEach { index ->
            val (tree, blobs) = packageTree(index = index)
            assertThatThrownBy { PackageReader(PlatformConfig(), json).read(tree) { blobs.getValue(it) } }
                .isInstanceOf(ImportRejected::class.java)
        }
        val (tree, blobs) = packageTree(tasks = mapOf("ok" to manifest, "bad" to manifest.copy(testCount = 0)))
        assertThatThrownBy { PackageReader(PlatformConfig(), json).read(tree) { blobs.getValue(it) } }
            .isInstanceOf(ImportRejected::class.java)
    }

    @Test
    fun `rejects traversal and missing files`() {
        for (path in listOf("../secret", "/secret", "a//b", "missing.cu")) {
            val (tree, blobs) = packageTree(manifest.copy(generator = path))
            assertThatThrownBy { PackageReader(PlatformConfig(), json).read(tree) { blobs.getValue(it) } }.isInstanceOf(
                ImportRejected::class.java
            )
        }
    }

    @Test
    fun `rejects oversized trees and packages before downloading artifacts`() {
        val (tree, _) = packageTree()
        val config = PlatformConfig(github = GithubConfig(maxTreeEntries = 1))
        assertThatThrownBy { PackageReader(config, json).read(tree) { error("must not fetch blobs") } }.isInstanceOf(
            ImportRejected::class.java
        )
        assertThatThrownBy {
            PackageReader(PlatformConfig(github = GithubConfig(maxRepositoryBytes = 1)), json).read(
                tree
            ) { error("must not fetch blobs") }
        }.isInstanceOf(ImportRejected::class.java)
    }

    @Test
    fun `rejects truncated trees symlinks corrupt blobs and invalid test count`() {
        val truncated = jsonDocument(
            json, """
                {"truncated":true,"tree":[]}
            """.trimIndent()
        )
        assertThatThrownBy {
            PackageReader(
                PlatformConfig(),
                json
            ).read(truncated) { error("unexpected fetch") }
        }.isInstanceOf(ImportRejected::class.java)
        val (tree, blobs) = packageTree(manifest.copy(testCount = 0))
        assertThatThrownBy { PackageReader(PlatformConfig(), json).read(tree) { blobs.getValue(it) } }.isInstanceOf(
            ImportRejected::class.java
        )
        val (validTree, _) = packageTree()
        assertThatThrownBy {
            PackageReader(PlatformConfig(), json).read(validTree) {
                jsonDocument(
                    json, """
                        {
                          "size": 0,
                          "encoding": "base64",
                          "content": ""
                        }
                    """.trimIndent()
                )
            }
        }.isInstanceOf(ImportRejected::class.java)
        val symlink = jsonDocument(
            json, """
                {
                  "truncated": false,
                  "tree": [
                    {
                      "path": "index.json",
                      "type": "blob",
                      "mode": "120000",
                      "size": 1
                    }
                  ]
                }
            """.trimIndent()
        )
        assertThatThrownBy {
            PackageReader(
                PlatformConfig(),
                json
            ).read(symlink) { error("unexpected fetch") }
        }.isInstanceOf(ImportRejected::class.java)
    }
}
