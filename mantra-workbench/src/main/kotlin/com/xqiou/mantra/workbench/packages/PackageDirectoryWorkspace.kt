package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.packages.DirectoryPolicy
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.SemanticVersion
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Mounts manifest packages from an explicitly supplied directory, with no inferred write rights.
 * Only the directory itself and its immediate children are examined. Resource reads remain bounded
 * and confined by [PackageLoader]; callers must explicitly opt into the cooperative local policy.
 */
object PackageDirectoryWorkspace {
    fun open(
        directory: Path,
        policy: DirectoryPolicy = DirectoryPolicy.STRICT_HANDLES,
        engine: SemanticVersion = SemanticVersion.parse(RuntimeVersions.mantra),
        limits: PackageLimits = PackageLimits(262_144, 2_097_152, 67_108_864, 2048, 32, 8192),
    ): PackageWorkspaceCatalog? {
        val root = directory.toRealPath()
        require(Files.isDirectory(root)) { "Workspace must be a directory" }
        val candidates = if (Files.exists(root.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
            listOf(root)
        } else {
            Files.list(root).use { paths ->
                paths.filter {
                    Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
                        Files.exists(it.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)
                }
                    .sorted().limit(65).toList()
            }
        }
        require(candidates.size <= 64) { "A package workspace supports at most 64 mounts" }
        if (candidates.isEmpty()) return null
        var capturedBytes = 0L
        return PackageWorkspaceCatalog(
            candidates.map { path ->
                val snapshot = PackageLoader.directory(path, engine, limits, policy)
                capturedBytes = Math.addExact(capturedBytes, snapshot.totalByteLength)
                require(capturedBytes <= 134_217_728L) { "Directory workspace capture exceeds 128 MiB" }
                PackageMount(path.fileName.toString(), snapshot)
            },
        )
    }
}
