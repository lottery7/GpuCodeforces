package dev.gpucodeforces.judge.executor.processor

import dev.gpucodeforces.judge.api.Verdict
import dev.gpucodeforces.judge.executor.sandbox.Sandbox
import dev.gpucodeforces.judge.executor.util.FileTrees
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.slf4j.LoggerFactory

/** How a build ended; [text] is what its directory keeps, [verdict] what a failed one means for the submission. */
enum class BuildStatus(val text: String, val verdict: Verdict?) {
    OK("ok", null),
    COMPILATION_ERROR("ce", Verdict.COMPILATION_ERROR),
    POLICY_VIOLATION("violation", Verdict.POLICY_VIOLATION),
}

/** A build as its directory keeps it, next to whatever files the builder left there. */
class StoredBuild(val dir: Path, val status: BuildStatus, val compileLog: String) {
    val message: String get() = dir.resolve(BuildCache.MESSAGE).readText()

    /** A build that failed, as the processor reports it. */
    fun rejected(message: String = this.message): EmulatorBuild.Rejected =
        EmulatorBuild.Rejected(checkNotNull(status.verdict) { "a successful build is not rejected" }, message, compileLog)
}

/**
 * What a builder made in its temporary directory. With [keep] false the directory is deleted once the result is
 * read, so the build runs again next time: for failures that may not repeat, such as a timeout on a loaded machine.
 */
class FreshBuild(val status: BuildStatus, val compileLog: String, val message: String = "", val keep: Boolean = true) {
    init {
        require(keep || status != BuildStatus.OK) { "a successful build leaves files the processor runs: it has to be kept" }
    }
}

/**
 * Builds of one emulated processor under [root], by key: a build runs once, in a temporary directory that becomes
 * `<root>/<key>` when it is done; failures are kept like successes. Builds of one key wait for each other, and at
 * most `workers` builds use their tools at once ([withSlot]).
 */
class BuildCache(private val root: Path, workers: Int) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val slots = Semaphore(workers)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** Runs the compilers of a build: they are what loads the machine. */
    fun <T> withSlot(block: () -> T): T {
        slots.acquire()
        try {
            return block()
        } finally {
            slots.release()
        }
    }

    /** The build of [key]: [read] from its directory, which [fresh] fills first if nobody has built it yet. */
    fun <T : Any> build(key: String, read: (StoredBuild) -> T, fresh: (tmp: Path) -> FreshBuild): T {
        val dir = root.resolve(key.take(2)).resolve(key)
        return locks.computeIfAbsent(key) { ReentrantLock() }.withLock {
            val cached = load(dir)
            val build = cached ?: return@withLock buildFresh(key, dir, read, fresh)
            log.info("Build {} of {}: {} (cached)", key.take(12), root.fileName, build.status)
            read(build)
        }
    }

    private fun load(dir: Path): StoredBuild? {
        val status = dir.resolve(STATUS)
        if (!status.exists()) return null
        Files.setLastModifiedTime(status, FileTime.fromMillis(System.currentTimeMillis()))
        val text = status.readText().trim()
        val parsed = BuildStatus.entries.find { it.text == text } ?: error("unknown build status '$text' in $dir")
        return StoredBuild(dir, parsed, dir.resolve(LOG).readText())
    }

    private fun <T : Any> buildFresh(key: String, dir: Path, read: (StoredBuild) -> T, fresh: (tmp: Path) -> FreshBuild): T {
        val tmp = Files.createDirectories(root.resolve("tmp")).let { Files.createTempDirectory(it, "b-") }
        try {
            FileTrees.makeWritableByAll(tmp)
            val started = System.nanoTime()
            val built = fresh(tmp)
            log.info(
                "Build {} of {}: {} in {} ms{}", key.take(12), root.fileName, built.status, (System.nanoTime() - started) / 1_000_000,
                if (built.keep) "" else " (not kept)",
            )
            tmp.resolve(LOG).writeText(built.compileLog)
            tmp.resolve(MESSAGE).writeText(built.message)
            tmp.resolve(STATUS).writeText(built.status.text)
            if (!built.keep) return read(StoredBuild(tmp, built.status, built.compileLog))
            Files.createDirectories(dir.parent)
            if (!dir.exists()) Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE)
            return read(load(dir)!!)
        } finally {
            FileTrees.delete(tmp)
            Sandbox.deleteLog(tmp)
        }
    }

    companion object {
        private const val STATUS = "status"
        private const val LOG = "compile.log"
        const val MESSAGE = "message"
    }
}
