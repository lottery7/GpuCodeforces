package dev.gpucodeforces.judge.executor.util

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * File operations on directories that sandboxed code could write to. Such a directory may contain symbolic links to
 * anywhere on the host, FIFOs and device nodes, and the executor runs as root: nothing here follows a link.
 */
object FileTrees {

    /** Deletes [root] and everything below it. A symbolic link is removed, never its target. */
    fun delete(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Copies the directories and regular files of [from] into [to], skipping everything else and any file that
     * would take the copy over [maxBytes]: a link to /dev/zero or a FIFO must not fill the disk or block forever.
     */
    fun copyRegularFiles(from: Path, to: Path, maxBytes: Long) {
        var remaining = maxBytes
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && attrs.size() <= remaining) {
                    remaining -= attrs.size()
                    Files.copy(file, to.resolve(from.relativize(file).toString()), LinkOption.NOFOLLOW_LINKS)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Total size of regular files below [root], or null once more than [maxEntries] entries were seen. */
    fun regularBytes(root: Path, maxEntries: Int): Long? {
        var bytes = 0L
        var entries = 0
        var tooMany = false
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            private fun count(): FileVisitResult {
                if (++entries <= maxEntries) return FileVisitResult.CONTINUE
                tooMany = true
                return FileVisitResult.TERMINATE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = count()

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) bytes += attrs.size()
                return count()
            }

            // files vanish while the solution runs and the judge deletes checked outputs
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return if (tooMany) null else bytes
    }

    /** The sandboxed process runs as another uid: it may read and traverse [dir] but not change it. */
    fun makeReadableByAll(dir: Path) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** The sandboxed process runs as another uid and writes its results into [dir]. */
    fun makeWritableByAll(dir: Path) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))
    }
}
