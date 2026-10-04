package com.xqiou.mantra.packages

import java.io.InputStream
import java.net.JarURLConnection
import java.nio.channels.Channels
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.jar.JarFile

/** The source container is read-only. It never extracts a JAR or writes to a package directory. */
object PackageLoader {
    fun directory(
        root: Path,
        engineVersion: SemanticVersion,
        limits: PackageLimits,
        policy: DirectoryPolicy = DirectoryPolicy.STRICT_HANDLES,
    ): PackageSnapshot {
        if (policy ==
            DirectoryPolicy.TRUSTED_LOCAL
        ) {
            return directory(DirectorySources.trustedLocal(root), engineVersion, limits)
        }
        val realRoot = try {
            root.toRealPath()
        } catch (error: java.io.IOException) {
            fail("MANTRA-PACKAGE-RESOURCE", "Package root is missing or unreadable", error)
        }
        return secureRoot(realRoot, "MANTRA-PACKAGE-SECURE-READ") { secure ->
            capture(engineVersion, limits) { path, limit -> secureRead(secure, path, limit) }
        }
    }

    /** Explicit host-supplied directory capability; the default Path overload remains strict. */
    fun directory(access: DirectoryAccess, engineVersion: SemanticVersion, limits: PackageLimits): PackageSnapshot {
        val snapshot = capture(engineVersion, limits) { path, maximum ->
            val bytes = access.read(path, maximum)
            if (bytes.size.toLong() >
                maximum
            ) {
                fail("MANTRA-PACKAGE-LIMIT", "Host directory capture exceeded its byte limit: $path")
            }
            bytes.copyOf()
        }
        access.verifyUnchanged()
        return snapshot
    }

    /** Pins the single manifest URL's container. Resource lookups cannot drift to another JAR. */
    fun classpath(
        root: String,
        loader: ClassLoader,
        engineVersion: SemanticVersion,
        limits: PackageLimits,
        directoryPolicy: DirectoryPolicy = DirectoryPolicy.STRICT_HANDLES,
    ): PackageSnapshot {
        val manifestName = if (root.isEmpty()) "manifest.json" else "${logicalPath(root)}/manifest.json"
        val urls = Collections.list(loader.getResources(manifestName))
        if (urls.size !=
            1
        ) {
            fail(
                "MANTRA-PACKAGE-CONTAINER",
                "Expected exactly one classpath manifest, found ${urls.size}: $manifestName",
            )
        }
        val url = urls.single()
        return try {
            when (url.protocol) {
                "file" -> directory(Path.of(url.toURI()).parent, engineVersion, limits, directoryPolicy)
                "jar" -> {
                    val connection = url.openConnection() as JarURLConnection
                    connection.useCaches = false
                    if (connection.jarFileURL.protocol != "file" || connection.entryName != manifestName) {
                        fail("MANTRA-PACKAGE-CONTAINER", "Only a local, fixed classpath JAR container is supported")
                    }
                    JarFile(Path.of(connection.jarFileURL.toURI()).toFile()).use { jar ->
                        val prefix = manifestName.removeSuffix("manifest.json")
                        val names = mutableSetOf<String>()
                        var count = 0
                        jar.entries().asSequence().forEach { entry ->
                            if (++count >
                                limits.containerEntries
                            ) {
                                fail("MANTRA-PACKAGE-LIMIT", "JAR exceeds its container entry limit")
                            }
                            if (entry.name.startsWith(prefix) &&
                                !names.add(entry.name)
                            ) {
                                fail("MANTRA-PACKAGE-DUPLICATE", "Duplicate JAR entry: ${entry.name}")
                            }
                        }
                        capture(engineVersion, limits) { path, limit ->
                            val entry =
                                jar.getJarEntry(prefix + path)
                                    ?: fail("MANTRA-PACKAGE-RESOURCE", "Missing JAR resource: $path")
                            if (entry.isDirectory) fail("MANTRA-PACKAGE-RESOURCE", "Resource is a directory: $path")
                            if (entry.size >
                                limit
                            ) {
                                fail("MANTRA-PACKAGE-LIMIT", "Resource exceeds its byte limit: $path")
                            }
                            jar.getInputStream(entry).use { bounded(it, limit, path) }
                        }
                    }
                }
                else -> fail("MANTRA-PACKAGE-CONTAINER", "Unsupported classpath protocol: ${url.protocol}")
            }
        } catch (error: java.io.IOException) {
            fail("MANTRA-PACKAGE-CONTAINER", "Cannot capture classpath container", error)
        }
    }

    private fun capture(
        engine: SemanticVersion,
        limits: PackageLimits,
        read: (String, Long) -> ByteArray,
    ): PackageSnapshot {
        val manifestBytes = read("manifest.json", minOf(limits.manifestBytes, limits.totalBytes))
        val manifest = ManifestReader.read(manifestBytes, limits)
        if (!manifest.engine.contains(
                engine,
            )
        ) {
            fail("MANTRA-PACKAGE-ENGINE", "Engine $engine is outside ${manifest.engine.text}")
        }
        var remaining = limits.totalBytes - manifestBytes.size
        val bytes = linkedMapOf<String, ByteArray>()
        manifest.resources.forEach { resource ->
            if (resource.byteLength > limits.resourceBytes || resource.byteLength > remaining) {
                fail("MANTRA-PACKAGE-LIMIT", "Declared resource exceeds a byte limit: ${resource.path}")
            }
            // Read at most the declared length plus one; mismatches cannot consume the entire budget.
            val bound = if (resource.byteLength == Long.MAX_VALUE) resource.byteLength else resource.byteLength + 1
            val captured = read(resource.path, minOf(limits.resourceBytes, bound))
            if (captured.size.toLong() != resource.byteLength || digest(captured) != resource.sha256) {
                fail("MANTRA-PACKAGE-INTEGRITY", "Length or SHA-256 mismatch: ${resource.path}")
            }
            remaining -= captured.size
            bytes[resource.path] = captured
        }
        return PackageSnapshot(manifest, manifestBytes, bytes)
    }

    private fun secureRead(root: SecureDirectoryStream<Path>, path: String, limit: Long): ByteArray {
        logicalPath(path)
        val segments = path.split('/')
        fun readAt(directory: SecureDirectoryStream<Path>, index: Int): ByteArray {
            val segment = Path.of(segments[index])
            return try {
                if (index < segments.lastIndex) {
                    directory.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS).use { nested ->
                        readAt(
                            nested,
                            index + 1,
                        )
                    }
                } else {
                    directory.newByteChannel(
                        segment,
                        setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    ).use { channel ->
                        if (channel.size() >
                            limit
                        ) {
                            fail("MANTRA-PACKAGE-LIMIT", "Resource exceeds its byte limit: $path")
                        }
                        Channels.newInputStream(channel).use { bounded(it, limit, path) }
                    }
                }
            } catch (error: java.io.IOException) {
                fail("MANTRA-PACKAGE-RESOURCE", "Cannot read confined resource $path (symlinks are rejected)", error)
            }
        }
        return readAt(root, 0)
    }

    private fun bounded(input: InputStream, limit: Long, name: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = minOf(limit, Int.MAX_VALUE.toLong())
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
            if (count < 0) return output.toByteArray()
            if (count.toLong() > remaining) fail("MANTRA-PACKAGE-LIMIT", "Resource exceeds its capture limit: $name")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
}
