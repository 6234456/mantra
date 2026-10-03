package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.workbench.WorkspaceCatalog.Companion.writeLocks
import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.WorkspaceCatalog.EditHistory
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal fun WorkspaceCatalog.boundSources(caseId: String): DocumentResult {
    val file = path(caseId)
    val case = loadCase(file)
    val overridden = runCatching { resolve(caseId, scan()).sourceOverrides }.getOrNull().orEmpty()
    return DocumentResult(
        sourceRevision(file, case),
        linkedMapOf(
            "sources" to case.sources.mapIndexed {
                    index,
                    binding,
                ->
                linkedMapOf(
                    "index" to index,
                    "kind" to binding.kind,
                    "path" to (binding.options["path"] as? Value.Text)?.value,
                    "overridden" to overridden.getOrNull(index).orEmpty(),
                    "options" to binding.options.mapValues { (_, value) -> WorkbenchJson.value(value) },
                )
            },
        ),
    )
}

/** A broken source must remain removable without evaluating that same broken source first. */
internal fun WorkspaceCatalog.removeBoundSource(caseId: String, baseRevision: String, index: Int): DocumentResult {
    val history = histories.computeIfAbsent(caseId) { EditHistory() }
    return synchronized(history) {
        val file = path(caseId)
        val original = source(file).text
        val case = loadCase(file)
        val current = sourceRevision(file, case)
        checkRevision(baseRevision, current)
        val candidate = try {
            CaseTextEditor.apply(original, listOf(CaseTextEditor.Operation.RemoveSource(index)))
        } catch (
            error: IllegalArgumentException,
        ) {
            throw WorkspaceException(
                WorkspaceProblem.REQUEST,
                error.message ?: "Invalid source index",
            )
        } catch (
            error: IllegalStateException,
        ) {
            throw WorkspaceException(
                WorkspaceProblem.REQUEST,
                error.message ?: "Invalid source index",
            )
        }
        if (candidate != original) {
            try {
                Mantra.loadCase(SourceText(caseId, candidate, file.parent.toString()))
            } catch (
                error: MantraException,
            ) {
                throw WorkspaceException(WorkspaceProblem.INVALID, "Case document is invalid", error.diagnostics)
            }
            writeCase(caseId, file, candidate, original, current) {
                runCatching { sourceRevision(file, loadCase(file)) }.getOrNull()
            }
            history.undo.addLast(original)
            while (history.undo.size > 50) history.undo.removeFirst()
            history.redo.clear()
        }
        sources(caseId)
    }
}

internal fun WorkspaceCatalog.sourceRevision(file: Path, case: CaseData): String {
    val bound = case.sources.mapNotNull { binding ->
        val name = (binding.options["path"] as? Value.Text)?.value ?: return@mapNotNull null
        val candidate = file.parent.resolve(name).toAbsolutePath().normalize()
        candidate.takeIf {
            it.startsWith(root) && Files.isRegularFile(it) && Files.size(it) <= ImportFiles.MAX_BYTES &&
                it.toRealPath().startsWith(root)
        }
    }
    return revision(listOf(file) + bound)
}

internal fun WorkspaceCatalog.applyImport(
    caseId: String,
    baseRevision: String,
    name: String,
    format: String,
    bytes: ByteArray,
    options: Map<String, Value>,
): DocumentResult {
    ImportFiles.inspect(name, format, bytes)
    if ("path" in options) throw WorkspaceException(WorkspaceProblem.REQUEST, "Import path is assigned by the server")
    val history = histories.computeIfAbsent(caseId) { EditHistory() }
    return synchronized(history) {
        val current = resolve(caseId, scan())
        checkRevision(baseRevision, current.revision)
        val caseFile = path(caseId)
        val directory = caseFile.parent.resolve("imports")
        Files.createDirectories(directory)
        if (!directory.toRealPath().startsWith(root)) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Import directory is outside the workspace")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val file = directory.resolve("$digest-$safeName")
        var created = false
        try {
            try {
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                created = true
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                    !Files.readAllBytes(file).contentEquals(bytes)
                ) {
                    throw WorkspaceException(
                        WorkspaceProblem.CONFLICT,
                        "Import file already exists with different content",
                    )
                }
            }
            val binding = options + ("path" to Value.Text("imports/${file.fileName}"))
            commitEdits(caseId, baseRevision, listOf(CaseTextEditor.Operation.AddSource(format, binding)))
        } catch (error: Exception) {
            if (created) Files.deleteIfExists(file)
            throw error
        }
    }
}

internal fun WorkspaceCatalog.listImportTemplates(): DocumentResult {
    val directory = root.resolve("import-templates")
    if (!Files.exists(
            directory,
        )
    ) {
        return DocumentResult(workspaceStamp().revision, mapOf("templates" to emptyList<Any>()))
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Import template directory is invalid")
    }
    val files = Files.list(directory).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".json") }
            .sorted().limit(129).toList()
    }
    if (files.size > 128) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Too many import templates")
    val templates = files.map { file ->
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65_536) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Import template is invalid")
        }
        val entry = try {
            Json.parse(Files.readString(file)) as? Value.MapV
        } catch (_: Exception) {
            null
        }
            ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Import template is invalid")
        val fields = entry.entries
        val name = (fields[Value.Kw("name")] as? Value.Text)?.value
        val format = (fields[Value.Kw("format")] as? Value.Text)?.value
        val options = fields[Value.Kw("options")] as? Value.MapV
        if (name == null || format !in setOf("csv", "json", "xlsx") || options == null) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Import template is invalid")
        }
        mapOf("name" to name, "format" to format, "options" to plain(options))
    }
    return DocumentResult(workspaceStamp().revision, mapOf("templates" to templates))
}

internal fun WorkspaceCatalog.storeImportTemplate(
    name: String,
    format: String,
    options: Map<String, Value>,
): DocumentResult {
    if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}").matches(name) || format !in setOf("csv", "json", "xlsx") ||
        "path" in options
    ) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid import template")
    }
    val directory = root.resolve("import-templates")
    Files.createDirectories(directory)
    if (!directory.toRealPath().startsWith(
            root,
        )
    ) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Template directory is outside the workspace")
    }
    return synchronized(writeLocks.computeIfAbsent(directory) { Any() }) {
        val existing = importTemplates().data["templates"] as List<*>
        if (existing.size >= 128) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Too many import templates")
        val body = WorkbenchJson.write(
            mapOf(
                "name" to name,
                "format" to format,
                "options" to options.mapValues { plain(it.value) },
            ),
        )
        if (body.toByteArray(Charsets.UTF_8).size >
            65_536
        ) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Import template is too large")
        }
        val file = directory.resolve("$name.json")
        try {
            Files.writeString(file, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (
            _: java.nio.file.FileAlreadyExistsException,
        ) {
            throw WorkspaceException(WorkspaceProblem.CONFLICT, "Import template already exists")
        }
        try {
            importTemplates()
        } catch (error: Exception) {
            Files.deleteIfExists(file)
            throw error
        }
    }
}

internal fun WorkspaceCatalog.plain(value: Value): Any? = when (value) {
    is Value.Text -> value.value
    is Value.Kw -> value.name
    is Value.MapV -> value.entries.entries.associate { (key, entry) ->
        (
            (key as? Value.Text)?.value ?: (key as? Value.Kw)?.name
                ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Invalid import mapping key")
            ) to plain(entry)
    }
    else -> throw WorkspaceException(WorkspaceProblem.INVALID, "Import template options must be text or mappings")
}
