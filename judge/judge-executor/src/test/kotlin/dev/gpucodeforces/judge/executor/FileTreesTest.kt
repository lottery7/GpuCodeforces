package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.executor.util.FileTrees
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** A run directory as a hostile solution could leave it: links pointing out of it, next to ordinary files. */
class FileTreesTest {
    @TempDir
    lateinit var tmp: Path

    private fun hostileRunDir(): Pair<Path, Path> {
        val outside = Files.createDirectories(tmp.resolve("outside"))
        outside.resolve("precious.txt").writeText("keep me")
        val run = Files.createDirectories(tmp.resolve("run/out"))
        run.resolve("result.bin").writeText("1234")
        Files.createSymbolicLink(run.resolve("escape"), outside)
        Files.createSymbolicLink(run.resolve("file-link"), outside.resolve("precious.txt"))
        return run.parent to outside
    }

    @Test
    fun `deleting a tree removes links and leaves what they point to`() {
        val (run, outside) = hostileRunDir()
        FileTrees.delete(run)
        assertTrue(!run.exists())
        assertEquals("keep me", outside.resolve("precious.txt").readText())
    }

    @Test
    fun `copying keeps regular files only`() {
        val (run, _) = hostileRunDir()
        val copy = tmp.resolve("copy")
        FileTrees.copyRegularFiles(run, copy, maxBytes = 1024)
        assertEquals("1234", copy.resolve("out/result.bin").readText())
        assertEquals(listOf("result.bin"), Files.list(copy.resolve("out")).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `copying stops at the size budget`() {
        val (run, _) = hostileRunDir()
        val copy = tmp.resolve("copy")
        FileTrees.copyRegularFiles(run, copy, maxBytes = 3)
        assertTrue(!copy.resolve("out/result.bin").exists())
    }

    @Test
    fun `usage counts regular files and gives up on too many entries`() {
        val (run, _) = hostileRunDir()
        assertEquals(4, FileTrees.regularBytes(run, maxEntries = 100))
        assertNull(FileTrees.regularBytes(run, maxEntries = 2))
    }
}
