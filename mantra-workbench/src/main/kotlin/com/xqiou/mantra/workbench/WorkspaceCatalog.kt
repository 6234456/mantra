package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.excel.ExcelExportLimitException
import com.xqiou.mantra.excel.ExcelOptions
import com.xqiou.mantra.excel.ExcelWorkbook
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class WorkspaceProblem { REQUEST, NOT_FOUND, INVALID, TOO_LARGE, CONFLICT }

sealed interface AuthoringTarget {
    data class Extension(val slot: String, val id: String, val title: String) : AuthoringTarget
    data class FormulaSlot(val id: String) : AuthoringTarget
}

data class ExportBudget(val maxSheets: Int = 64, val maxCells: Int = 50_000, val maxBytes: Int = 8 * 1024 * 1024) {
    init {
        require(maxSheets > 0 && maxCells > 0 && maxBytes > 0)
    }
}

class WorkspaceException(
    val problem: WorkspaceProblem,
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
    val currentRevision: String? = null,
) : RuntimeException(message)

data class ExplainAddress(
    val node: String,
    val coord: List<String> = emptyList(),
    val cell: Pair<String, String>? = null,
    val case: String? = null,
    val expectedRevision: String? = null,
)

/** Reads workspace files on every request and owns bounded calculation sessions for dependency reuse. */
class WorkspaceCatalog(
    directory: Path,
    private val mantraVersion: String = "0.4.0-SNAPSHOT",
    normeinVersion: String? = null,
    private val exportBudget: ExportBudget = ExportBudget(),
) : AutoCloseable {
    companion object {
        /** Serialize distinct catalog instances in this JVM; the channel lock covers other processes. */
        internal val writeLocks = ConcurrentHashMap<Path, Any>()
    }
    val root: Path = directory.toRealPath().also { require(Files.isDirectory(it)) { "Workspace must be a directory" } }
    private val normeinVersion = normeinVersion ?: lockedNormein(root)

    /** Test seam for an external file change after calculation and before the final write check. */
    internal var beforeWriteCheck: (() -> Unit)? = null
    internal data class EditHistory(
        val undo: ArrayDeque<String> = ArrayDeque(),
        val redo: ArrayDeque<String> = ArrayDeque(),
    )
    internal val histories = ConcurrentHashMap<String, EditHistory>()
    internal val sessions = WorkspaceSessions()

    override fun close() = sessions.close()

    internal data class Indexed(
        val path: Path,
        val id: String,
        val kind: String,
        val name: String?,
        val diagnostics: List<Diagnostic>,
        val version: String? = null,
    )
    internal data class Snapshot(val files: List<Indexed>) {
        fun kind(name: String) = files.filter { it.kind == name }
    }

    data class DocumentResult(val revision: String, val data: Map<String, Any?>)

    fun sources(caseId: String): DocumentResult = boundSources(caseId)

    /** A broken source remains removable without evaluating it first. */
    fun removeSource(caseId: String, baseRevision: String, index: Int): DocumentResult =
        removeBoundSource(caseId, baseRevision, index)

    fun importApply(
        caseId: String,
        baseRevision: String,
        name: String,
        format: String,
        bytes: ByteArray,
        options: Map<String, Value>,
    ): DocumentResult = applyImport(caseId, baseRevision, name, format, bytes, options)

    fun importTemplates(): DocumentResult = listImportTemplates()

    fun saveImportTemplate(name: String, format: String, options: Map<String, Value>): DocumentResult =
        storeImportTemplate(name, format, options)

    /** The event scanner retains digests, not document contents. Its revision equals /workspace's revision. */
    data class FileMarker(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: String?)
    data class WorkspaceStamp(
        val revision: String,
        val files: Map<String, String>,
        val metadata: Map<String, FileMarker>,
        val nextVerifyIndex: Int = 0,
    )

    fun workspaceStamp(previous: WorkspaceStamp? = null): WorkspaceStamp = scanWorkspaceStamp(previous)

    fun workspace(): DocumentResult {
        val snapshot = scan()
        val all = workspaceFiles()
        val schemas = snapshot.kind("schema")
        val params = snapshot.kind("parameters")
        val layouts = snapshot.kind("layout")
        val cases = snapshot.kind("case").map { entry ->
            val diagnostics = entry.diagnostics.toMutableList()
            val case = try {
                loadCase(entry.path)
            } catch (error: MantraException) {
                diagnostics += error.diagnostics
                null
            } catch (error: WorkspaceException) {
                diagnostics += diagnostic("MANTRA-WORKBENCH-DOCUMENT", error.message.orEmpty())
                null
            }
            val schemaId = case?.schemaId
            if (schemaId ==
                null
            ) {
                diagnostics += diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Case does not declare :schema")
            } else if (schemas.none { it.name == schemaId }) {
                diagnostics +=
                    diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Schema $schemaId was not found")
            }
            val caseRevision = if (case != null && schemaId != null && schemas.any { it.name == schemaId }) {
                try {
                    resolve(entry.id, snapshot).revision
                } catch (error: WorkspaceException) {
                    diagnostics +=
                        error.diagnostics.ifEmpty {
                            listOf(diagnostic("MANTRA-WORKBENCH-DOCUMENT", error.message.orEmpty()))
                        }
                    null
                }
            } else {
                null
            }
            linkedMapOf(
                "id" to entry.id,
                "title" to (case?.text("title") ?: case?.id ?: entry.path.fileName.toString()),
                "schema" to schemaId,
                "schemaVersion" to case?.schemaVersion,
                "period" to case?.text("period"),
                "revision" to caseRevision,
                "diagnostics" to diagnostics.map(WorkbenchDocuments::diagnostic),
            )
        }
        val data = linkedMapOf<String, Any?>(
            "cases" to cases,
            "schemas" to schemas.map { linkedMapOf("id" to it.name, "path" to it.id, "version" to it.version) },
            "parameters" to params.map { linkedMapOf("id" to it.name, "path" to it.id) },
            "layouts" to layouts.map { linkedMapOf("id" to it.name, "path" to it.id) },
            "diagnostics" to snapshot.files.flatMap { it.diagnostics }.map(WorkbenchDocuments::diagnostic),
        )
        return DocumentResult(revision(all), data)
    }

    fun document(caseId: String, name: String, panel: String? = null, layoutId: String? = null): DocumentResult {
        val resolved = resolve(caseId, scan(), layoutId, audit = name == "paper")
        val view = resolved.view
        val data = when (name) {
            "structure" -> WorkbenchDocuments.structure(view)
            "run" -> WorkbenchDocuments.run(view, resolved.layout, resolved.graph)
            "parameters" -> WorkbenchDocuments.parameters(view)
            "paper" -> {
                if (panel != null && view.structure.panels.none { it.id == panel }) {
                    throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Panel was not found")
                }
                WorkbenchDocuments.paper(view, resolved.layout, panel)
            }
            "diagnostics" -> WorkbenchDocuments.diagnostics(view.diagnostics)
            else -> throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Document was not found")
        }
        return DocumentResult(resolved.revision, data)
    }

    fun exportPreview(caseId: String, sheet: String? = null, layoutId: String? = null): DocumentResult {
        val resolved = resolve(caseId, scan(), layoutId, audit = true)
        val export = exportWorkbook(resolved)
        export.use {
            val description = export.describe(sheet)
                ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Worksheet was not found")
            return DocumentResult(resolved.revision, ExportDocuments.preview(description))
        }
    }

    fun export(caseId: String, format: String, layoutId: String? = null): ByteArray {
        val resolved = resolve(caseId, scan(), layoutId, audit = true)
        return when (format) {
            "xlsx" -> {
                val export = exportWorkbook(resolved)
                try {
                    export.use { it.bytes(exportBudget.maxBytes) }
                } catch (
                    error: ExcelExportLimitException,
                ) {
                    throw WorkspaceException(WorkspaceProblem.TOO_LARGE, error.message.orEmpty())
                }
            }
            "html" -> Render.html(resolved.view, resolved.layout).toByteArray(Charsets.UTF_8)
            "txt" -> Render.text(resolved.view, resolved.layout).toByteArray(Charsets.UTF_8)
            else -> throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Export format was not found")
        }
    }

    private fun exportWorkbook(resolved: Resolved): ExcelWorkbook = try {
        ExcelExport.workbook(
            resolved.view,
            resolved.layout,
            ExcelOptions(maxSheets = exportBudget.maxSheets, maxCells = exportBudget.maxCells),
        )
    } catch (error: ExcelExportLimitException) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, error.message.orEmpty())
    } catch (error: IllegalArgumentException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, error.message.orEmpty())
    }

    fun compare(caseId: String, variantCaseId: String?, variantParameters: List<String>?): DocumentResult {
        val snapshot = scan()
        val base = resolve(caseId, snapshot)
        val variant =
            resolve(variantCaseId ?: caseId, snapshot, parameterOverride = variantParameters, includeLayout = false)
        if (base.view.schema.id != variant.view.schema.id) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Comparison requires the same schema id")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            caseId,
            base.revision,
            variantCaseId ?: caseId,
            variant.revision,
            variant.parameterIds.size.toString(),
            *variant.parameterIds.toTypedArray(),
        ).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        val compareRevision = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        return DocumentResult(
            compareRevision,
            WorkbenchDocuments.compare(
                base.view,
                variant.view,
                base.layout,
                variant.parameterIds,
                variantCaseId?.takeIf { it != caseId },
            ),
        )
    }

    /** Calculates an edited in-memory case, including the same semantic checks as a committed edit. */
    fun previewEdits(caseId: String, baseRevision: String, operations: List<CaseTextEditor.Operation>): DocumentResult =
        synchronized(histories.computeIfAbsent(caseId) { EditHistory() }) {
            val snapshot = scan()
            val base = resolve(caseId, snapshot)
            checkRevision(baseRevision, base.revision)
            rejectLinkedEdits(base, operations)
            val candidate = editCandidate(caseId, operations)
            val variant = resolve(caseId, snapshot, caseText = candidate)
            checkEditDiagnostics(variant)
            validateFinalCoordinates(variant, operations)
            DocumentResult(base.revision, editData(base, variant, caseId, true))
        }

    /** Editor assistance uses the planner's typed scope and the edit preview's semantic checks. */
    fun authoring(
        caseId: String,
        target: AuthoringTarget,
        source: String,
        cursorOffset: Int?,
        action: String,
    ): DocumentResult = formulaAssistance(caseId, target, source, cursorOffset, action)

    /** Serializes writes for each case and records the previous exact source for bounded undo. */
    fun commitEdits(caseId: String, baseRevision: String, operations: List<CaseTextEditor.Operation>): DocumentResult =
        commitCaseEdits(caseId, baseRevision, operations)

    fun undo(caseId: String, baseRevision: String): DocumentResult = restore(caseId, baseRevision, undo = true)
    fun redo(caseId: String, baseRevision: String): DocumentResult = restore(caseId, baseRevision, undo = false)

    /** Parses text using the input or parameter's declared type and the active layout's locale. */
    fun parseEditText(caseId: String, id: String, parameter: Boolean, text: String, column: String? = null): Value =
        parseEditText(resolve(caseId, scan()), id, parameter, text, column)

    fun parseEditRowText(caseId: String, table: String, columns: Map<String, String>): Value.MapV =
        parseEditorRowText(caseId, table, columns)

    fun tableKeyColumn(caseId: String, table: String): String? = inputTableKeyColumn(caseId, table)

    fun explain(caseId: String, address: ExplainAddress, depth: Int = 1): DocumentResult =
        explainGraph(caseId, address, depth)

    fun envelope(document: DocumentResult): String = WorkbenchJson.write(
        WorkbenchJson.envelope(document.revision, mantraVersion, normeinVersion, document.data),
    )

    internal data class Resolved(
        val view: CalculationView,
        val layout: LayoutSpec,
        val revision: String,
        val parameterIds: List<String>,
        val explainTrace: ExplainTrace? = null,
        val schema: Schema,
        val parameters: List<ParameterSet>,
        val sourceOverrides: List<List<String>> = emptyList(),
        val graph: com.xqiou.mantra.core.api.CaseRunResult? = null,
        val caseLayouts: Map<com.xqiou.mantra.core.api.CanonicalCaseKey, LayoutSpec> = emptyMap(),
    )

    internal fun diagnostic(code: String, message: String) = Diagnostic(Severity.ERROR, code, message)

    private fun lockedNormein(from: Path): String {
        var cursor: Path? = from
        while (cursor != null) {
            val lock = cursor.resolve("normein-build.lock")
            if (Files.isRegularFile(lock)) {
                return Files.readAllLines(lock)
                    .firstOrNull { it.startsWith("normeinCommit=") }?.substringAfter('=')?.take(8) ?: "unknown"
            }
            cursor = cursor.parent
        }
        return "unknown"
    }
}
