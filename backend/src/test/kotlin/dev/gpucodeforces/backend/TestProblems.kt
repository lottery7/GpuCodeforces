package dev.gpucodeforces.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Problem banks for integration tests, built from the judge's example packages. */
object TestProblems {
    val examples: Path = Path.of("../judge/problems/examples")

    /**
     * A copy of the examples where `vector_add` can also be solved on `rtl` with a limit of [RTL_LIMIT]; the backend
     * never runs the generator, so the package needs no tests for it.
     */
    // copyToRecursively is still experimental.
    @OptIn(ExperimentalPathApi::class)
    fun withRtl(): Path {
        val root = Files.createTempDirectory("problems-rtl")
        examples.copyToRecursively(root, followLinks = false, overwrite = true)
        val spec = root.resolve("vector_add/problem.json")
        spec.writeText(
            spec.readText().replaceFirst(
                "{",
                """{"processors": [{"id": "gpu-cuda", "languages": ["cuda"]}, {"id": "rtl", "languages": ["systemverilog"], "limit": $RTL_LIMIT}],""",
            ),
        )
        return root
    }

    const val RTL_LIMIT = 100000
}
