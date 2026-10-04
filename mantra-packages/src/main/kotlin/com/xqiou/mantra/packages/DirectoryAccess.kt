package com.xqiou.mantra.packages

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** STRICT_HANDLES never falls back when the filesystem lacks SecureDirectoryStream. */
enum class DirectoryPolicy { STRICT_HANDLES, TRUSTED_LOCAL }

/**
 * An explicitly supplied, read-only host capability for one package directory.
 * Paths are canonical package-relative paths. Reads must be bounded before allocation and must
 * reject escapes and symlinks. The loader defensively copies each returned buffer. After all reads,
 * verifyUnchanged must reject ordinary changes since capture. A native host may implement stronger
 * handle-relative guarantees; this interface does not itself promise protection against hostile
 * processes with the same filesystem permissions.
 */
interface DirectoryAccess {
    fun read(path: String, maximumBytes: Long): ByteArray
    fun verifyUnchanged()
}

object DirectorySources {
    /**
     * Cooperative local-directory capture. Every canonical component is checked with NOFOLLOW,
     * and file identity, size, modification time and type are checked before/after reading and at
     * the end of capture. These checks cannot defeat a malicious rename-and-restore race by a
     * process with the same permissions. Choose this policy explicitly only for trusted host roots.
     * The explicitly provided root is canonicalized once, permitting normal host aliases such as
     * /tmp; subsequent resource paths are never resolved through symlinks.
     */
    fun trustedLocal(root: Path): DirectoryAccess = TrustedLocalDirectoryAccess(root)
}

internal class LocalDirectoryRoot(root: Path, private val code: String) {
    val path: Path = try {
        root.toRealPath()
    } catch (error: java.io.IOException) {
        fail(code, "Explicit local directory is missing or unreadable", error)
    }
    private val rootIdentity = inspectDirectory(path)

    fun validateRoot() {
        var current = path.root
        inspectDirectory(current)
        path.forEach { component ->
            current = current.resolve(component)
            inspectDirectory(current)
        }
        if (!rootIdentity.sameIdentity(inspectDirectory(path))) {
            fail(code, "Explicit local directory identity changed")
        }
    }

    fun parent(resource: String): Pair<Path, Path> {
        val parts = logicalPath(resource).split('/')
        validateRoot()
        var directory = path
        parts.dropLast(1).forEach { component ->
            directory = directory.resolve(component)
            inspectDirectory(directory)
        }
        return directory to directory.resolve(parts.last())
    }

    fun inspectDirectory(directory: Path): LocalFileStamp {
        val stamp = stamp(directory)
        if (!stamp.directory || stamp.symbolic || realPath(directory) != directory) {
            fail(code, "Directory component must be a real confined directory: $directory")
        }
        return stamp
    }

    fun inspectFile(file: Path): LocalFileStamp {
        val stamp = stamp(file)
        if (!stamp.regular || stamp.symbolic || realPath(file) != file || !file.startsWith(path)) {
            fail(code, "Resource must be a regular confined file: $file")
        }
        return stamp
    }

    private fun realPath(file: Path): Path = try {
        file.toRealPath()
    } catch (error: java.io.IOException) {
        fail(code, "Cannot resolve inspected local path: $file", error)
    }

    private fun stamp(file: Path): LocalFileStamp = try {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        LocalFileStamp(
            attributes.fileKey(),
            attributes.size(),
            attributes.lastModifiedTime(),
            attributes.isRegularFile,
            attributes.isDirectory,
            attributes.isSymbolicLink,
        )
    } catch (error: java.io.IOException) {
        fail(code, "Cannot inspect confined local path: $file", error)
    }
}

internal data class LocalFileStamp(
    val key: Any?,
    val size: Long,
    val modified: java.nio.file.attribute.FileTime,
    val regular: Boolean,
    val directory: Boolean,
    val symbolic: Boolean,
) {
    fun sameIdentity(other: LocalFileStamp): Boolean =
        key == other.key && regular == other.regular && directory == other.directory && symbolic == other.symbolic
}

private class TrustedLocalDirectoryAccess(root: Path) : DirectoryAccess {
    private val directory = LocalDirectoryRoot(root, "MANTRA-PACKAGE-RESOURCE")
    private val captured = linkedMapOf<Path, LocalFileStamp>()
    private val capturedDirectories = linkedMapOf<Path, LocalFileStamp>()

    override fun read(path: String, maximumBytes: Long): ByteArray {
        require(maximumBytes >= 0)
        val (parent, file) = directory.parent(path)
        rememberDirectories(parent)
        val parentBefore = directory.inspectDirectory(parent)
        val before = directory.inspectFile(file)
        captured[file]?.let { if (it != before) changed(path) }
        if (before.size > maximumBytes || before.size > Int.MAX_VALUE) {
            fail("MANTRA-PACKAGE-LIMIT", "Resource exceeds its byte limit: $path")
        }
        val bytes = try {
            FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != before.size) changed(path)
                val output = ByteArrayOutputStream()
                val buffer = ByteBuffer.allocate(8192)
                while (true) {
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > minOf(maximumBytes, Int.MAX_VALUE.toLong())) {
                        fail("MANTRA-PACKAGE-LIMIT", "Resource exceeds its capture limit: $path")
                    }
                    output.write(buffer.array(), 0, count)
                    buffer.clear()
                }
                if (channel.size() != before.size) changed(path)
                output.toByteArray()
            }
        } catch (error: java.io.IOException) {
            fail("MANTRA-PACKAGE-RESOURCE", "Cannot read confined resource $path (symlinks are rejected)", error)
        }
        directory.parent(path)
        if (directory.inspectFile(file) != before || parentBefore != directory.inspectDirectory(parent) ||
            bytes.size.toLong() != before.size
        ) {
            changed(path)
        }
        captured[file] = before
        return bytes
    }

    override fun verifyUnchanged() {
        directory.validateRoot()
        capturedDirectories.forEach { (path, before) ->
            if (directory.inspectDirectory(path) !=
                before
            ) {
                changed(path.toString())
            }
        }
        captured.forEach { (file, before) ->
            val relative = directory.path.relativize(file).joinToString("/")
            directory.parent(relative)
            if (directory.inspectFile(file) != before) changed(relative)
        }
    }

    private fun rememberDirectories(parent: Path) {
        var path = directory.path
        fun remember() {
            val stamp = directory.inspectDirectory(path)
            capturedDirectories[path]?.let { if (it != stamp) changed(path.toString()) }
            capturedDirectories[path] = stamp
        }
        remember()
        directory.path.relativize(parent).filter { it.toString().isNotEmpty() }.forEach { component ->
            path = path.resolve(component)
            remember()
        }
    }

    private fun changed(path: String): Nothing =
        fail("MANTRA-PACKAGE-SOURCE-CHANGED", "Local resource changed during capture: $path")
}
