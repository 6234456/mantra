package com.xqiou.mantra.workbench

import com.xqiou.mantra.workbench.WorkspaceCatalog.FileMarker
import com.xqiou.mantra.workbench.WorkspaceCatalog.WorkspaceStamp
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal fun WorkspaceCatalog.scanWorkspaceStamp(previous: WorkspaceStamp? = null): WorkspaceStamp {
    val documents = mantraFiles()
    val paths = workspaceFiles(documents)
    val metadata = paths.associate { file ->
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.size() >
            fileLimit(file, documents)
        ) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace file is too large")
        }
        relative(file) to FileMarker(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()?.toString())
    }
    if (previous != null && previous.metadata == metadata) {
        if (paths.isEmpty()) return previous
        var index = previous.nextVerifyIndex % paths.size
        var verifiedBytes = 0L
        var verifiedFiles = 0
        // Metadata catches ordinary saves immediately. This rotating content check also catches
        // replacements whose size and timestamp were deliberately preserved, without rereading
        // a multi-gigabyte workspace on every polling tick.
        while (verifiedFiles < paths.size && (verifiedBytes < 8L * 1024 * 1024 || verifiedFiles == 0)) {
            val path = paths[index]
            val bytes = Files.readAllBytes(checked(path))
            if (bytes.size >
                fileLimit(path, documents)
            ) {
                throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace file is too large")
            }
            val contentHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (contentHash != previous.files[relative(path)]) return fullWorkspaceStamp(paths, documents, metadata)
            verifiedBytes += bytes.size
            verifiedFiles++
            index = (index + 1) % paths.size
        }
        return previous.copy(nextVerifyIndex = index)
    }
    return fullWorkspaceStamp(paths, documents, metadata)
}

internal fun WorkspaceCatalog.fullWorkspaceStamp(
    paths: List<Path>,
    documents: List<Path>,
    metadata: Map<String, FileMarker>,
): WorkspaceStamp {
    val digest = MessageDigest.getInstance("SHA-256")
    val files = linkedMapOf<String, String>()
    paths.forEach { file ->
        val bytes = Files.readAllBytes(checked(file))
        if (bytes.size >
            fileLimit(file, documents)
        ) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace file is too large")
        }
        val name = relative(file)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        digest.update(nameBytes.size.toString().toByteArray())
        digest.update(0.toByte())
        digest.update(nameBytes)
        digest.update(0.toByte())
        digest.update(bytes.size.toString().toByteArray())
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
        files[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    return WorkspaceStamp(digest.digest().take(8).joinToString("") { "%02x".format(it) }, files, metadata)
}
