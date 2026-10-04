package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.workbench.WorkspaceCatalog.Indexed
import com.xqiou.mantra.workbench.WorkspaceCatalog.Snapshot
import com.xqiou.normein.dsl.form.DslForm
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Confines workspace file reads, indexes documents, and computes content revisions. */
internal fun WorkspaceCatalog.scan(): Snapshot = Snapshot(
    mantraFiles().map { file ->
        val diagnostics = DiagnosticSink()
        val document = try {
            Document.read(source(file), diagnostics)
        } catch (error: WorkspaceException) {
            if (error.problem == WorkspaceProblem.TOO_LARGE) throw error
            diagnostics.error("MANTRA-WORKBENCH-DOCUMENT", error.message.orEmpty())
            null
        }
        val form = document?.root as? DslForm.Sequence
        val kind = form?.listHead.orEmpty()
        val name = form?.values?.getOrNull(1)?.let { it.symbol ?: (it as? DslForm.Atom)?.value }
        Indexed(
            file,
            relative(file),
            kind,
            name,
            diagnostics.all,
            form?.values?.getOrNull(2)?.let { document?.options(it, diagnostics, "metadata")?.get("version")?.string },
        )
    },
)

internal fun WorkspaceCatalog.mantraFiles(): List<Path> {
    val files = Files.walk(root).use { stream ->
        stream.filter { it.toString().endsWith(".mantra") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .sorted().limit(4097).toList()
    }
    if (files.size > 4096) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace exceeds 4096 Mantra files")
    return files
}

internal fun WorkspaceCatalog.workspaceFiles(documents: List<Path> = mantraFiles()): List<Path> {
    val sources = documents.mapNotNull { file ->
        runCatching { loadCase(file) }.getOrNull()?.let { case -> file to case.sources }
    }.flatMap { (caseFile, bindings) ->
        bindings.mapNotNull { binding ->
            val name = (binding.options["path"] as? Value.Text)?.value ?: return@mapNotNull null
            val candidate = caseFile.parent.resolve(name).toAbsolutePath().normalize()
            candidate.takeIf {
                it.startsWith(root) && Files.isRegularFile(it) && Files.size(it) <= ImportFiles.MAX_BYTES &&
                    it.toRealPath().startsWith(root)
            }
        }
    }
    if (sources.size >
        4096
    ) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace exceeds 4096 bound sources")
    }
    val templates = root.resolve("import-templates").takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
        ?.let { directory ->
            Files.list(directory).use { stream ->
                stream.filter {
                    it.fileName.toString().endsWith(".json") &&
                        Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.size(it) <= 65_536
                }.limit(129).toList()
            }
        }
        .orEmpty()
    if (templates.size > 128) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Too many import templates")
    return (documents + sources + templates).distinct().sortedBy(::relative)
}

internal fun WorkspaceCatalog.fileLimit(file: Path, documents: List<Path>): Int = if (file in
    documents
) {
    1_048_576
} else {
    ImportFiles.MAX_BYTES
}

internal fun WorkspaceCatalog.loadCase(file: Path): CaseData = Mantra.loadCase(source(file))

internal fun WorkspaceCatalog.source(file: Path): SourceText {
    val target = checked(file)
    if (Files.size(target) > 1_048_576) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Document is too large")
    val contents = Files.readString(target)
    if (contents.length > 65_536) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Document exceeds reader limit")
    return SourceText(relative(target), contents, target.parent.toString())
}

internal fun WorkspaceCatalog.path(relative: String): Path {
    if (relative.isBlank() ||
        '\u0000' in relative
    ) {
        throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Path was not found")
    }
    return checked(root.resolve(relative))
}

internal fun WorkspaceCatalog.checked(file: Path): Path {
    val target = file.toAbsolutePath().normalize()
    if (!target.startsWith(root) ||
        !Files.isRegularFile(target)
    ) {
        throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Path was not found")
    }
    if (!target.toRealPath().startsWith(
            root,
        )
    ) {
        throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Path was not found")
    }
    return target
}

internal fun WorkspaceCatalog.relative(path: Path): String = root.relativize(path).toString().replace('\\', '/')

internal fun WorkspaceCatalog.revision(files: List<Path>, replacements: Map<Path, ByteArray> = emptyMap()): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.distinct().sortedBy(::relative).forEach { file ->
        val bytes = replacements[file] ?: Files.readAllBytes(checked(file))
        val name = relative(file).toByteArray(Charsets.UTF_8)
        digest.update(name.size.toString().toByteArray())
        digest.update(0.toByte())
        digest.update(name)
        digest.update(0.toByte())
        digest.update(bytes.size.toString().toByteArray())
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
    }
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}
