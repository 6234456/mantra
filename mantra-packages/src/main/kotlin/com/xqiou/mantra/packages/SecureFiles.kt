package com.xqiou.mantra.packages

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

/** Open every real-path component from the filesystem root without following replacement symlinks. */
internal fun <T> secureRoot(realPath: Path, code: String, action: (SecureDirectoryStream<Path>) -> T): T {
    val path = realPath.toAbsolutePath().normalize()
    return try {
        Files.newDirectoryStream(path.root).use { stream ->
            val root = stream as? SecureDirectoryStream<Path>
                ?: fail(code, "Filesystem cannot provide race-safe directory-relative operations")
            val components = path.map { it }
            fun descend(directory: SecureDirectoryStream<Path>, index: Int): T = if (index == components.size) {
                action(directory)
            } else {
                directory.newDirectoryStream(components[index], LinkOption.NOFOLLOW_LINKS).use {
                    descend(
                        it,
                        index + 1,
                    )
                }
            }
            descend(root, 0)
        }
    } catch (error: java.io.IOException) {
        fail(code, "Cannot open confined directory without following replacement symlinks", error)
    }
}
