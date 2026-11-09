package dev.gpucodeforces.judge.executor.problem

import dev.gpucodeforces.judge.api.ParamRole
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.SpecValidator
import dev.gpucodeforces.judge.api.TestSpec
import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.gpu.GpuManager
import dev.gpucodeforces.judge.executor.util.Hashing
import dev.gpucodeforces.judge.executor.util.Proc
import org.slf4j.LoggerFactory
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    val spec: TestSpec,
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
 * Generates test inputs and reference answers once per problem version (Python + PyTorch, on a GPU slot) and
 * caches them on disk keyed by a content hash of signature + tests + generator source.
 */
class ProblemStore(private val props: ExecutorProperties, private val gpus: GpuManager) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = props.paths.cache.resolve("problems")
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val inUse = ConcurrentHashMap<String, AtomicInteger>()

    private val canonical: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

    fun hash(spec: ProblemSpec): String =
        Hashing.sha256(canonical.writeValueAsString(mapOf("signature" to spec.signature, "tests" to spec.tests, "generator" to spec.generator)), PREP_FORMAT)

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
            return layout(spec, hash, dir).also { acquired = true }
        } finally {
            if (!acquired) release(hash)
        }
    }

    fun release(hash: String) {
        inUse[hash]?.decrementAndGet()
    }

    private fun layout(spec: ProblemSpec, hash: String, dir: Path): PreparedProblem {
        val sig = spec.signature
        val tests = spec.tests.mapIndexed { i, t ->
            val idx = i + 1
            val counts = SpecValidator.elementCounts(sig, t)
            PreparedTest(
                index = idx,
                spec = t,
                counts = counts,
                inputs = sig.arrays.filter { it.role == ParamRole.INPUT || it.role == ParamRole.INOUT }
                    .associate { it.name to dir.resolve("inputs/t$idx/${it.name}.bin") },
                expected = sig.outputs.associate { it.name to dir.resolve("expected/t$idx/${it.name}.bin") },
            )
        }
        return PreparedProblem(hash, dir, dir.resolve("inputs"), tests)
    }

    private fun prepare(spec: ProblemSpec, hash: String, dir: Path, cancelled: () -> Boolean) {
        val started = System.nanoTime()
        Files.createDirectories(root)
        val tmp = Files.createTempDirectory(root, ".tmp-${hash.take(12)}-")
        try {
            val specFile = tmp.resolve("spec.json")
            specFile.writeText(canonical.writeValueAsString(mapOf("signature" to spec.signature, "tests" to spec.tests)))
            val source = tmp.resolve("problem.py")
            source.writeText(spec.generator.source)
            val out = tmp.resolve("data")

            val slot = gpus.pool.acquire("prepare:${spec.id}", Duration.ofMinutes(2), cancelled)
            val res = try {
                log.info("Preparing problem {} v{} ({} tests) on {}", spec.id, spec.version, spec.tests.size, slot)
                Proc.run(
                    listOf(props.toolchain.python, "-m", "judge_problem.prepare", "--spec", specFile.toString(),
                        "--source", source.toString(), "--out", out.toString(), "--device", props.prepare.device),
                    props.prepare.timeLimit,
                    env = mapOf(
                        "PYTHONPATH" to props.paths.pytools.toString(),
                        "PYTHONDONTWRITEBYTECODE" to "1",
                        "CUDA_DEVICE_ORDER" to "PCI_BUS_ID",
                        "CUDA_VISIBLE_DEVICES" to slot.index.toString(),
                    ),
                    cwd = tmp,
                    maxOutputBytes = 64 * 1024,
                )
            } finally {
                gpus.pool.release(slot)
            }
            if (!res.ok) {
                val problemFault = res.exitCode == 2 && !res.timedOut
                log.error("Problem {} preparation failed: {}", spec.id, res.describe())
                throw ProblemPreparationException(
                    if (res.timedOut) "test generation exceeded ${props.prepare.timeLimit.toMinutes()} min"
                    else "test generation failed: ${res.stderr.trim().takeLast(4000)}",
                    problemFault,
                )
            }
            verifySizes(spec, out)
            val bytes = Files.walk(out).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { it.fileSize() }.sum() }
            out.resolve("size").writeText(bytes.toString())
            out.resolve(READY).writeText(hash)
            if (dir.exists()) dir.toFile().deleteRecursively()
            Files.move(out, dir, StandardCopyOption.ATOMIC_MOVE)
            log.info(
                "Prepared problem {} in {}ms: {} MB of test data, generator log: {}",
                spec.id, (System.nanoTime() - started) / 1_000_000, bytes shr 20, res.stderr.lines().takeLast(3).joinToString(" | "),
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun verifySizes(spec: ProblemSpec, out: Path) {
        val sig = spec.signature
        spec.tests.forEachIndexed { i, t ->
            val counts = SpecValidator.elementCounts(sig, t)
            for (p in sig.arrays) {
                val files = buildList {
                    if (p.role == ParamRole.INPUT || p.role == ParamRole.INOUT) add(out.resolve("inputs/t${i + 1}/${p.name}.bin"))
                    if (p.role == ParamRole.OUTPUT || p.role == ParamRole.INOUT) add(out.resolve("expected/t${i + 1}/${p.name}.bin"))
                }
                for (f in files) {
                    val want = counts.getValue(p.name) * p.type.bytes
                    if (!f.exists() || f.fileSize() != want) {
                        throw ProblemPreparationException("prepared file ${out.relativize(f)} has ${if (f.exists()) f.fileSize() else -1} bytes, expected $want", true)
                    }
                }
            }
        }
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
            if ((inUse[hash]?.get() ?: 0) > 0) continue
            log.info("Evicting cached problem data {} ({} MB)", hash.take(12), size shr 20)
            Files.deleteIfExists(dir.resolve(READY))
            dir.toFile().deleteRecursively()
            total -= size
        }
    }

    companion object {
        private const val READY = "READY"

        /** Bump when the on-disk format of prepared data changes. */
        private const val PREP_FORMAT = "v1"
    }
}
