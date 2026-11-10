package dev.gpucodeforces.judge.executor.problem

import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.executor.compile.Toolchain
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.gpu.GpuManager
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.sandbox.SandboxSpec
import dev.gpucodeforces.judge.executor.util.FileTrees
import dev.gpucodeforces.judge.executor.util.Hashing
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class PreparedTest(
    /** 1-based. */
    val index: Int,
    val spec: GeneratedTest,
    /** Element counts of all arrays. */
    val counts: Map<String, Long>,
    /** INPUT/INOUT arrays (host paths). */
    val inputs: Map<String, Path>,
    /** OUTPUT/INOUT reference answers (host paths, never mounted into the sandbox). */
    val expected: Map<String, Path>,
)

data class PreparedProblem(val hash: String, val dir: Path, val inputsDir: Path, val tests: List<PreparedTest>)

/**
 * @param problemFault true if the problem's generator/reference is broken (retrying will not help),
 * false for infrastructure failures.
 */
class ProblemPreparationException(msg: String, val problemFault: Boolean) : RuntimeException(msg)

/**
 * Generates the tests a problem's generator declares, their inputs and reference answers once per problem version
 * (Python + PyTorch, on a GPU slot) and caches them on disk keyed by a content hash of signature + generator source.
 *
 * The generator comes from a problem repository that accepts pull requests, so it runs in the same sandbox as
 * solutions: no network, and only its own scratch directory is writable.
 */
class ProblemStore(
    private val props: ExecutorProperties,
    private val gpus: GpuManager,
    private val toolchain: Toolchain,
    private val sandbox: Sandbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = props.paths.cache.resolve("problems")
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val inUse = ConcurrentHashMap<String, AtomicInteger>()
    private val limits = SpecValidator.Limits()

    private val canonical: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

    /**
     * Everything the generated data depends on: the problem's own content, and what produces the data from it -
     * the preparation scripts, the torch build and the device. An upgrade of any of them must not keep serving
     * reference answers computed by the old one.
     */
    fun hash(spec: ProblemSpec): String = Hashing.sha256(
        canonical.writeValueAsString(mapOf("signature" to spec.signature, "generator" to spec.generator)),
        pytoolsHash, toolchain.torchVersion ?: "", props.prepare.device, PREP_FORMAT,
    )

    private val pytoolsHash: String by lazy {
        val dir = props.paths.pytools
        if (!dir.exists()) return@lazy ""
        val sources = Files.walk(dir).use { files -> files.filter { it.toString().endsWith(".py") }.sorted().toList() }
        Hashing.sha256(*sources.flatMap { listOf(dir.relativize(it).toString(), it.readText()) }.toTypedArray())
    }

    fun isReady(spec: ProblemSpec): Boolean = root.resolve(hash(spec)).resolve(READY).exists()

    /**
     * Returns prepared data, generating it if needed ([onPreparing] is called first in that case). The caller must
     * [release] the problem when done so eviction never removes data of a running job.
     */
    fun acquire(spec: ProblemSpec, onPreparing: () -> Unit, cancelled: () -> Boolean): PreparedProblem {
        val hash = hash(spec)
        inUse.computeIfAbsent(hash) { AtomicInteger() }.incrementAndGet()
        var acquired = false
        try {
            val dir = root.resolve(hash)
            locks.computeIfAbsent(hash) { ReentrantLock() }.withLock {
                if (!dir.resolve(READY).exists()) {
                    onPreparing()
                    prepare(spec, hash, dir, cancelled)
                    evictIfNeeded()
                }
            }
            Files.setLastModifiedTime(dir.resolve(READY), FileTime.fromMillis(System.currentTimeMillis()))
            return layout(spec, hash, dir, readTests(spec, dir)).also { acquired = true }
        } finally {
            if (!acquired) release(hash)
        }
    }

    fun release(hash: String) {
        inUse.getValue(hash).decrementAndGet()
    }

    private fun layout(spec: ProblemSpec, hash: String, dir: Path, generated: List<GeneratedTest>): PreparedProblem {
        val sig = spec.signature
        val tests = generated.mapIndexed { i, t ->
            val idx = i + 1
            val counts = SpecValidator.elementCounts(sig, t.scalars)
            PreparedTest(
                index = idx,
                spec = t,
                counts = counts,
                inputs = sig.arrays.filter { it.role == ParamRole.INPUT || it.role == ParamRole.INOUT }
                    .associate { it.name to dir.resolve("inputs/t$idx/${it.name}.bin") },
                expected = sig.outputs.associate { it.name to dir.resolve("expected/t$idx/${it.name}.bin") },
            )
        }
        return PreparedProblem(hash, dir = dir, inputsDir = dir.resolve("inputs"), tests)
    }

    /**
     * The tests the generator declared, from `tests.json` in [dir]. The file is its output: it is read only if it
     * is a regular file of a sane size, and every test is checked like the rest of the problem.
     */
    private fun readTests(spec: ProblemSpec, dir: Path): List<GeneratedTest> {
        val file = dir.resolve(TESTS)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) broken("the generator produced no $TESTS")
        if (file.fileSize() > MAX_TESTS_FILE_BYTES) broken("$TESTS is larger than $MAX_TESTS_FILE_BYTES bytes")
        val tests = try {
            canonical.readValue<List<GeneratedTest>>(file.readText())
        } catch (e: JacksonException) {
            broken("$TESTS is not a list of tests: ${e.originalMessage}")
        }
        val errors = GeneratedTest.validate(spec.signature, tests, limits)
        if (errors.isNotEmpty()) broken("invalid tests: ${errors.take(20).joinToString("; ")}")
        return tests
    }

    private fun prepare(spec: ProblemSpec, hash: String, dir: Path, cancelled: () -> Boolean) {
        val started = System.nanoTime()
        Files.createDirectories(root)
        val tmp = Files.createTempDirectory(root, ".tmp-${hash.take(12)}-")
        try {
            FileTrees.makeWritableByAll(tmp)
            val specFile = tmp.resolve("spec.json")
            val prepareLimits = mapOf("maxTests" to limits.maxTests, "maxTestBytes" to limits.maxTestBytes)
            specFile.writeText(canonical.writeValueAsString(mapOf("signature" to spec.signature, "limits" to prepareLimits)))
            val source = tmp.resolve("problem.py")
            source.writeText(spec.generator.source)
            val out = tmp.resolve("data")
            val pytools = props.paths.pytools.toAbsolutePath().normalize()

            val slot = gpus.pool.acquire("prepare:${spec.id}", Duration.ofMinutes(2), cancelled)
            val res = try {
                log.info("Preparing problem {} v{} on {}", spec.id, spec.version, slot)
                val box = SandboxSpec(
                    command = emptyList(),
                    workDir = tmp,
                    readOnly = buildMap {
                        put(pytools, "/pytools")
                        pythonMount()?.let { put(it, it.toString()) }
                    },
                    gpu = slot.access(),
                    wallTimeLimit = props.prepare.timeLimit.plusSeconds(5),
                    // one file per array, and no array is larger than a whole test may be
                    maxFileBytes = limits.maxTestBytes,
                    tmpBytes = 1L shl 30,
                )
                val command = listOf(
                    props.toolchain.python, "-m", "judge_problem.prepare", "--spec", sandbox.inside(box, specFile),
                    "--source", sandbox.inside(box, source), "--out", sandbox.inside(box, out), "--device", props.prepare.device,
                )
                val env = mapOf("PYTHONPATH" to sandbox.inside(box, pytools), "PYTHONDONTWRITEBYTECODE" to "1")
                sandbox.runToCompletion(box.copy(command = command, env = env), props.prepare.timeLimit, 64 * 1024)
            } finally {
                gpus.pool.release(slot)
            }
            if (!res.ok) {
                val problemFault = res.exitCode == 2 && !res.timedOut
                log.error("Problem {} preparation failed: {}; sandbox log: {}", spec.id, res.describe(), Sandbox.logTail(tmp) ?: "-")
                throw ProblemPreparationException(
                    if (res.timedOut) "test generation exceeded ${props.prepare.timeLimit.toMinutes()} min"
                    else "test generation failed: ${res.stderr.trim().takeLast(4000)}",
                    problemFault,
                )
            }
            val (tests, bytes) = verifyOutput(spec, out)
            out.resolve("size").writeText(bytes.toString())
            out.resolve(READY).writeText(hash)
            FileTrees.delete(dir)
            Files.move(out, dir, StandardCopyOption.ATOMIC_MOVE)
            log.info(
                "Prepared problem {} in {}ms: {} tests, {} MB of test data, generator log: {}",
                spec.id, (System.nanoTime() - started) / 1_000_000, tests, bytes shr 20, res.stderr.lines().takeLast(3).joinToString(" | "),
            )
        } finally {
            FileTrees.delete(tmp)
            Sandbox.deleteLog(tmp)
        }
    }

    /** A Python with torch installed outside the system directories (a venv) has to be mounted explicitly. */
    private fun pythonMount(): Path? {
        val system = (props.sandbox.readOnlyMounts + props.sandbox.extraReadOnlyMounts).map { Path.of(it) }
        return toolchain.pythonPrefix?.takeUnless { prefix -> system.any { prefix.startsWith(it) } }
    }

    /**
     * The generator is untrusted and [out] is entirely its work. Only the expected directories and regular files
     * of the expected sizes for the tests it declared (and its `meta.json` and `tests.json`) are accepted: the judge
     * reads these files as root, writes its markers next to them and mounts `inputs` into every solution's sandbox,
     * so a symbolic link or a stray copy of the answers must not survive. Returns the number of tests and the size
     * of the test data.
     */
    private fun verifyOutput(spec: ProblemSpec, out: Path): Pair<Int, Long> {
        if (!Files.isDirectory(out, LinkOption.NOFOLLOW_LINKS)) broken("the generator produced no output directory")
        val tests = readTests(spec, out)
        val wanted = buildMap {
            for (t in layout(spec, "", out, tests).tests) {
                for (p in spec.signature.arrays) {
                    val bytes = t.counts.getValue(p.name) * p.type.bytes
                    t.inputs[p.name]?.let { put(it, bytes) }
                    t.expected[p.name]?.let { put(it, bytes) }
                }
            }
        }
        // setOf: a Path is itself an Iterable of its name elements, so `+ out` would add those instead
        val directories = wanted.keys.flatMap { listOf(it.parent, it.parent.parent) }.toSet() + setOf(out)
        val meta = setOf(out.resolve("meta.json"), out.resolve(TESTS))
        val found = Files.walk(out).use { entries -> entries.limit(wanted.size + directories.size + meta.size + 1L).toList() }
        for (p in found) {
            val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val expected = when {
                attrs.isDirectory -> p in directories
                attrs.isRegularFile -> p in meta || p in wanted
                else -> false
            }
            if (!expected) broken("the generator left unexpected ${out.relativize(p)} in its output")
        }
        for ((file, bytes) in wanted) {
            if (file !in found) broken("prepared file ${out.relativize(file)} is missing")
            if (file.fileSize() != bytes) broken("prepared file ${out.relativize(file)} has ${file.fileSize()} bytes, expected $bytes")
        }
        // The harness fills outputs with 0xFF bytes before every call (NaN for floats, -1 or the maximum for
        // integers) so that a solution cannot pass by writing nothing. A reference answer that is exactly that
        // pattern would defeat it: such a test has to be changed by the problem's author.
        for (file in wanted.keys.filter { it.startsWith(out.resolve("expected")) }) {
            if (bytesAllEqual(file, POISON)) {
                broken("reference answer ${out.relativize(file)} is all 0xFF bytes, the pattern outputs are pre-filled with: a solution that writes nothing would pass this test")
            }
        }
        return tests.size to wanted.values.sum()
    }

    private fun bytesAllEqual(file: Path, value: Byte): Boolean = Files.newInputStream(file).buffered().use { input ->
        val chunk = ByteArray(1 shl 16)
        var any = false
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            for (i in 0 until n) if (chunk[i] != value) return false
            any = any || n > 0
        }
        any
    }

    private fun evictIfNeeded() {
        val budget = props.prepare.cacheBudgetGb shl 30
        val entries = Files.list(root).use { s ->
            s.filter { it.resolve(READY).exists() }.map { dir ->
                Triple(dir, Files.getLastModifiedTime(dir.resolve(READY)).toMillis(), dir.resolve("size").readText().trim().toLong())
            }.toList()
        }.sortedBy { it.second }
        var total = entries.sumOf { it.third }
        for ((dir, _, size) in entries) {
            if (total <= budget) break
            val hash = dir.fileName.toString()
            // acquire() counts itself in before it takes the problem's lock, and reads READY under it: holding the
            // victim's lock while its count is zero means nobody is using the data or about to.
            val lock = locks.computeIfAbsent(hash) { ReentrantLock() }
            if (!lock.tryLock()) continue
            try {
                if ((inUse[hash]?.get() ?: 0) > 0) continue
                log.info("Evicting cached problem data {} ({} MB)", hash.take(12), size shr 20)
                Files.deleteIfExists(dir.resolve(READY))
                FileTrees.delete(dir)
                total -= size
            } finally {
                lock.unlock()
            }
        }
    }

    private fun broken(what: String): Nothing = throw ProblemPreparationException(what, problemFault = true)

    companion object {
        private const val READY = "READY"
        private const val TESTS = "tests.json"
        private const val MAX_TESTS_FILE_BYTES = 1L shl 20
        private const val POISON = 0xFF.toByte()

        /** Bump when the on-disk format of prepared data changes. */
        private const val PREP_FORMAT = "v2"
    }
}
