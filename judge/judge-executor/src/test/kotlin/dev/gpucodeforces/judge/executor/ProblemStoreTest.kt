package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.api.DType
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ParamSpec
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.Signature
import dev.gpucodeforces.judge.executor.compile.Toolchain
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.config.PathsConfig
import dev.gpucodeforces.judge.executor.config.ToolchainConfig
import dev.gpucodeforces.judge.executor.gpu.GpuManager
import dev.gpucodeforces.judge.executor.problem.GeneratedTest
import dev.gpucodeforces.judge.executor.problem.ProblemPreparationException
import dev.gpucodeforces.judge.executor.problem.ProblemStore
import dev.gpucodeforces.judge.executor.sandbox.DirectSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.io.TempDir

/**
 * Drives ProblemStore with a fake interpreter that stands in for `python -m judge_problem.prepare`; the generator
 * "source" selects how it behaves, so no torch or GPU is needed.
 */
class ProblemStoreTest {
    @TempDir
    lateinit var tmp: Path

    private fun problem(behaviour: String) = ProblemSpec(
        id = "double",
        signature = Signature(
            params = listOf(
                ParamSpec("A", DType.FLOAT32, ParamRole.INPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("C", DType.FLOAT32, ParamRole.OUTPUT, MemorySpace.DEVICE, "N"),
                ParamSpec("N", DType.INT32, ParamRole.SCALAR),
            ),
        ),
        generator = GeneratorSpec(source = behaviour),
    )

    private fun store(): ProblemStore {
        val interpreter = tmp.resolve("fake-python")
        @Language("Python")
        val source = """
            #!/usr/bin/env python3
            import os, sys
            args = dict(zip(sys.argv[3::2], sys.argv[4::2]))
            behaviour = open(args["--source"]).read().strip()
            out = args["--out"]
            if behaviour == "broken":
                sys.stderr.write("problem error: reference must return keys ['C']\n")
                sys.exit(2)
            if behaviour == "invalid-custom":
                sys.exit(3)
            tests = {
                "bad-tests": '[{"name": "only", "scalars": {"M": 4}}]',
                "huge-test": '[{"name": "only", "scalars": {"N": 4000000000}}]',
            }.get(behaviour, '[{"name": "only", "sample": true, "scalars": {"N": 4}}]')
            os.makedirs(out, exist_ok=True)
            if behaviour != "no-tests":
                open(out + "/tests.json", "w").write(tests)
            os.makedirs(out + "/inputs/t1")
            os.makedirs(out + "/expected/t1")
            open(out + "/inputs/t1/A.bin", "wb").write(bytes(16))
            if behaviour == "extra":
                open(out + "/inputs/t1/answers.bin", "wb").write(bytes(16))
            if behaviour == "symlink":
                os.symlink(out + "/inputs/t1/A.bin", out + "/expected/t1/C.bin")
            else:
                open(out + "/expected/t1/C.bin", "wb").write(bytes(16))
            """.trimIndent() + "\n"
        interpreter.writeText(source)
        interpreter.toFile().setExecutable(true)
        val props = ExecutorProperties(
            paths = PathsConfig(cache = tmp.resolve("cache"), pytools = tmp.resolve("pytools")),
            toolchain = ToolchainConfig(python = interpreter.toString()),
        )
        val gpus = GpuManager(props)
        gpus.pool.slots.forEach(gpus.pool::markHealthy)
        return ProblemStore(props, gpus, Toolchain(props), DirectSandbox())
    }

    @Test
    fun `tests are generated once and reused`() {
        val store = store()
        var preparations = 0
        val first = store.acquire(problem("ok"), { preparations++ }, { false })
        store.release(first.hash)
        val second = store.acquire(problem("ok"), { preparations++ }, { false })
        store.release(second.hash)

        assertEquals(1, preparations)
        assertEquals(first.dir, second.dir)
        val test = second.tests.single()
        assertEquals(GeneratedTest("only", sample = true, scalars = mapOf("N" to 4)), test.spec)
        assertEquals(16, test.inputs.getValue("A").fileSize())
        assertEquals(16, test.expected.getValue("C").fileSize())
        assertTrue(test.inputs.getValue("A").startsWith(second.inputsDir))
        assertTrue(!test.expected.getValue("C").startsWith(second.inputsDir), "reference answers must stay out of the mounted inputs")
        val leftovers = tmp.resolve("cache/problems").listDirectoryEntries().filter { it != second.dir }
        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun `symlink in place of generated data is rejected`() {
        val e = assertFailsWith<ProblemPreparationException> { store().acquire(problem("symlink"), {}, { false }) }
        assertTrue(e.problemFault)
        assertContains(e.message!!, "unexpected expected/t1/C.bin")
    }

    @Test
    fun `files the problem does not describe are rejected`() {
        val e = assertFailsWith<ProblemPreparationException> { store().acquire(problem("extra"), {}, { false }) }
        assertTrue(e.problemFault)
        assertContains(e.message!!, "unexpected inputs/t1/answers.bin")
    }

    @Test
    fun `tests the generator declares are checked against the signature`() {
        val missing = assertFailsWith<ProblemPreparationException> { store().acquire(problem("bad-tests"), {}, { false }) }
        assertTrue(missing.problemFault)
        assertContains(missing.message!!, "missing scalars [N]")
        val huge = assertFailsWith<ProblemPreparationException> { store().acquire(problem("huge-test"), {}, { false }) }
        assertContains(huge.message!!, "out of range of INT32")
        val none = assertFailsWith<ProblemPreparationException> { store().acquire(problem("no-tests"), {}, { false }) }
        assertContains(none.message!!, "no tests.json")
    }

    @Test
    fun `broken generator is the problem's fault and its error is kept`() {
        val store = store()
        val e = assertFailsWith<ProblemPreparationException> { store.acquire(problem("broken"), {}, { false }) }
        assertTrue(e.problemFault)
        assertContains(e.message!!, "reference must return keys")
        assertTrue(!store.isReady(problem("broken")))
        assertTrue(Files.notExists(tmp.resolve("cache/problems").resolve(store.hash(problem("broken")))))
    }

    @Test
    fun `invalid custom input is distinct from a broken reference`() {
        val store = store()
        val error = assertFailsWith<ProblemPreparationException> { store.acquire(problem("invalid-custom"), {}, { false }) }
        assertTrue(error.invalidInput)
        assertTrue(!store.isReady(problem("invalid-custom")))
    }
}
