package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.engine.ExplainTrace
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.excel.ExcelOptions
import com.xqiou.mantra.excel.ExcelWorkbook
import com.xqiou.mantra.excel.ExcelExportLimitException
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import com.xqiou.normein.dsl.form.DslForm
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.ConcurrentHashMap

enum class WorkspaceProblem { REQUEST, NOT_FOUND, INVALID, TOO_LARGE, CONFLICT }

data class ExportBudget(val maxSheets: Int = 64, val maxCells: Int = 50_000, val maxBytes: Int = 8 * 1024 * 1024) {
    init { require(maxSheets > 0 && maxCells > 0 && maxBytes > 0) }
}

class WorkspaceException(
    val problem: WorkspaceProblem,
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
    val currentRevision: String? = null,
) : RuntimeException(message)

data class ExplainAddress(val node: String, val coord: List<String> = emptyList(),
                          val cell: Pair<String, String>? = null)

/** Rebuilds read-only documents from workspace files for every request. No calculation state lives in the server. */
class WorkspaceCatalog(directory: Path, private val mantraVersion: String = "0.1.0-SNAPSHOT", normeinVersion: String? = null,
                       private val exportBudget: ExportBudget = ExportBudget()) {
    companion object {
        /** Serialize distinct catalog instances in this JVM; the channel lock covers other processes. */
        private val writeLocks = ConcurrentHashMap<Path, Any>()
    }
    val root: Path = directory.toRealPath().also { require(Files.isDirectory(it)) { "Workspace must be a directory" } }
    private val normeinVersion = normeinVersion ?: lockedNormein(root)
    /** Test seam for an external file change after calculation and before the final write check. */
    internal var beforeWriteCheck: (() -> Unit)? = null
    private data class EditHistory(val undo: ArrayDeque<String> = ArrayDeque(), val redo: ArrayDeque<String> = ArrayDeque())
    private val histories = ConcurrentHashMap<String, EditHistory>()

    private data class Indexed(val path: Path, val id: String, val kind: String, val name: String?, val diagnostics: List<Diagnostic>)
    private data class Snapshot(val files: List<Indexed>) {
        fun kind(name: String) = files.filter { it.kind == name }
    }

    data class DocumentResult(val revision: String, val data: Map<String, Any?>)

    fun sources(caseId: String): DocumentResult {
        val snapshot = scan()
        val resolved = resolve(caseId, snapshot)
        val case = loadCase(path(caseId))
        return DocumentResult(resolved.revision, linkedMapOf("sources" to case.sources.mapIndexed { index, binding ->
            linkedMapOf("index" to index, "kind" to binding.kind,
                "path" to (binding.options["path"] as? Value.Text)?.value,
                "options" to binding.options.mapValues { (_, value) -> WorkbenchJson.value(value) })
        }))
    }

    fun importApply(caseId: String, baseRevision: String, name: String, format: String,
                    bytes: ByteArray, options: Map<String, Value>): DocumentResult {
        ImportFiles.inspect(name, format, bytes)
        if ("path" in options) throw WorkspaceException(WorkspaceProblem.REQUEST, "Import path is assigned by the server")
        val history = histories.computeIfAbsent(caseId) { EditHistory() }
        return synchronized(history) {
            val current = resolve(caseId, scan())
            checkRevision(baseRevision, current.revision)
            val caseFile = path(caseId)
            val directory = caseFile.parent.resolve("imports")
            Files.createDirectories(directory)
            if (!directory.toRealPath().startsWith(root))
                throw WorkspaceException(WorkspaceProblem.REQUEST, "Import directory is outside the workspace")
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
                        !Files.readAllBytes(file).contentEquals(bytes))
                        throw WorkspaceException(WorkspaceProblem.CONFLICT, "Import file already exists with different content")
                }
                val binding = options + ("path" to Value.Text("imports/${file.fileName}"))
                commitEdits(caseId, baseRevision, listOf(CaseTextEditor.Operation.AddSource(format, binding)))
            } catch (error: Exception) {
                if (created) Files.deleteIfExists(file)
                throw error
            }
        }
    }

    /** The event scanner retains digests, not document contents. Its revision equals /workspace's revision. */
    data class FileMarker(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: String?)
    data class WorkspaceStamp(val revision: String, val files: Map<String, String>,
                              val metadata: Map<String, FileMarker>, val nextVerifyIndex: Int = 0)

    fun workspaceStamp(previous: WorkspaceStamp? = null): WorkspaceStamp {
        val documents = mantraFiles()
        val paths = workspaceFiles(documents)
        val metadata = paths.associate { file ->
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.size() > fileLimit(file, documents)) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace file is too large")
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
                if (bytes.size > fileLimit(path, documents)) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace file is too large")
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

    private fun fullWorkspaceStamp(paths: List<Path>, documents: List<Path>, metadata: Map<String, FileMarker>): WorkspaceStamp {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = linkedMapOf<String, String>()
        paths.forEach { file ->
            val bytes = Files.readAllBytes(checked(file))
            if (bytes.size > fileLimit(file, documents)) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace file is too large")
            val name = relative(file)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            digest.update(nameBytes.size.toString().toByteArray()); digest.update(0.toByte()); digest.update(nameBytes)
            digest.update(0.toByte()); digest.update(bytes.size.toString().toByteArray()); digest.update(0.toByte()); digest.update(bytes)
            digest.update(0.toByte())
            files[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        return WorkspaceStamp(digest.digest().take(8).joinToString("") { "%02x".format(it) }, files, metadata)
    }

    fun workspace(): DocumentResult {
        val snapshot = scan()
        val all = workspaceFiles()
        val schemas = snapshot.kind("schema")
        val params = snapshot.kind("parameters")
        val layouts = snapshot.kind("layout")
        val cases = snapshot.kind("case").map { entry ->
            val diagnostics = entry.diagnostics.toMutableList()
            val case = try { loadCase(entry.path) } catch (error: MantraException) {
                diagnostics += error.diagnostics
                null
            } catch (error: WorkspaceException) {
                diagnostics += diagnostic("MANTRA-WORKBENCH-DOCUMENT", error.message.orEmpty())
                null
            }
            val schemaId = case?.schemaId
            if (schemaId == null) diagnostics += diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Case does not declare :schema")
            else if (schemas.none { it.name == schemaId }) diagnostics += diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Schema $schemaId was not found")
            val caseRevision = if (case != null && schemaId != null && schemas.any { it.name == schemaId }) {
                try { resolve(entry.id, snapshot).revision } catch (error: WorkspaceException) {
                    diagnostics += error.diagnostics.ifEmpty { listOf(diagnostic("MANTRA-WORKBENCH-DOCUMENT", error.message.orEmpty())) }
                    null
                }
            } else null
            linkedMapOf(
                "id" to entry.id,
                "title" to (case?.text("title") ?: case?.id ?: entry.path.fileName.toString()),
                "schema" to schemaId,
                "period" to case?.text("period"),
                "revision" to caseRevision,
                "diagnostics" to diagnostics.map(WorkbenchDocuments::diagnostic),
            )
        }
        val data = linkedMapOf<String, Any?>(
            "cases" to cases,
            "schemas" to schemas.map { linkedMapOf("id" to it.name, "path" to it.id) },
            "parameters" to params.map { linkedMapOf("id" to it.name, "path" to it.id) },
            "layouts" to layouts.map { linkedMapOf("id" to it.name, "path" to it.id) },
            "diagnostics" to snapshot.files.flatMap { it.diagnostics }.map(WorkbenchDocuments::diagnostic),
        )
        return DocumentResult(revision(all), data)
    }

    fun document(caseId: String, name: String, panel: String? = null, layoutId: String? = null): DocumentResult {
        val resolved = resolve(caseId, scan(), layoutId)
        val view = resolved.view
        val data = when (name) {
            "structure" -> WorkbenchDocuments.structure(view)
            "run" -> WorkbenchDocuments.run(view, resolved.layout)
            "parameters" -> WorkbenchDocuments.parameters(view)
            "paper" -> {
                if (panel != null && view.structure.panels.none { it.id == panel })
                    throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Panel was not found")
                WorkbenchDocuments.paper(view, resolved.layout, panel)
            }
            "diagnostics" -> WorkbenchDocuments.diagnostics(view.diagnostics)
            else -> throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Document was not found")
        }
        return DocumentResult(resolved.revision, data)
    }

    fun exportPreview(caseId: String, sheet: String? = null, layoutId: String? = null): DocumentResult {
        val resolved = resolve(caseId, scan(), layoutId)
        val export = exportWorkbook(resolved)
        export.use {
            val description = export.describe(sheet)
                ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Worksheet was not found")
            return DocumentResult(resolved.revision, ExportDocuments.preview(description))
        }
    }

    fun export(caseId: String, format: String, layoutId: String? = null): ByteArray {
        val resolved = resolve(caseId, scan(), layoutId)
        return when (format) {
            "xlsx" -> {
                val export = exportWorkbook(resolved)
                try { export.use { it.bytes(exportBudget.maxBytes) } }
                catch (error: ExcelExportLimitException) { throw WorkspaceException(WorkspaceProblem.TOO_LARGE, error.message.orEmpty()) }
            }
            "html" -> Render.html(resolved.view, resolved.layout).toByteArray(Charsets.UTF_8)
            "txt" -> Render.text(resolved.view, resolved.layout).toByteArray(Charsets.UTF_8)
            else -> throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Export format was not found")
        }
    }

    private fun exportWorkbook(resolved: Resolved): ExcelWorkbook = try {
        ExcelExport.workbook(resolved.view, resolved.layout,
            ExcelOptions(maxSheets = exportBudget.maxSheets, maxCells = exportBudget.maxCells))
    } catch (error: ExcelExportLimitException) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, error.message.orEmpty())
    }

    fun compare(caseId: String, variantCaseId: String?, variantParameters: List<String>?): DocumentResult {
        val snapshot = scan()
        val base = resolve(caseId, snapshot)
        val variant = resolve(variantCaseId ?: caseId, snapshot, parameterOverride = variantParameters, includeLayout = false)
        if (base.view.schema.id != variant.view.schema.id)
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Comparison requires the same schema id")
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(caseId, base.revision, variantCaseId ?: caseId, variant.revision,
            variant.parameterIds.size.toString(), *variant.parameterIds.toTypedArray()).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        val compareRevision = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        return DocumentResult(compareRevision,
            WorkbenchDocuments.compare(base.view, variant.view, base.layout, variant.parameterIds,
                variantCaseId?.takeIf { it != caseId }))
    }

    /** Calculates an edited in-memory case, including the same semantic checks as a committed edit. */
    fun previewEdits(caseId: String, baseRevision: String, operations: List<CaseTextEditor.Operation>): DocumentResult =
        synchronized(histories.computeIfAbsent(caseId) { EditHistory() }) {
            val snapshot = scan()
            val base = resolve(caseId, snapshot)
            checkRevision(baseRevision, base.revision)
            val candidate = editCandidate(caseId, operations)
            val variant = resolve(caseId, snapshot, caseText = candidate)
            checkEditDiagnostics(variant)
            validateFinalCoordinates(variant, operations)
            DocumentResult(base.revision, editData(base, variant, caseId, true))
        }

    /** Serializes writes for each case and records the previous exact source for bounded undo. */
    fun commitEdits(caseId: String, baseRevision: String, operations: List<CaseTextEditor.Operation>): DocumentResult {
        val history = histories.computeIfAbsent(caseId) { EditHistory() }
        return synchronized(history) {
            val snapshot = scan()
            val base = resolve(caseId, snapshot)
            checkRevision(baseRevision, base.revision)
            val original = source(path(caseId)).text
            val candidate = editCandidate(caseId, operations)
            val variant = resolve(caseId, snapshot, caseText = candidate)
            checkEditDiagnostics(variant)
            validateFinalCoordinates(variant, operations)
            if (candidate != original) {
                writeCase(caseId, path(caseId), candidate, original, base.revision)
                history.undo.addLast(original)
                while (history.undo.size > 50) history.undo.removeFirst()
                history.redo.clear()
            }
            DocumentResult(variant.revision, editData(base, variant, caseId, false))
        }
    }

    fun undo(caseId: String, baseRevision: String): DocumentResult = restore(caseId, baseRevision, undo = true)
    fun redo(caseId: String, baseRevision: String): DocumentResult = restore(caseId, baseRevision, undo = false)

    /** Parses author text by the declared input/parameter type; numerals follow German layout syntax. */
    fun parseEditText(caseId: String, id: String, parameter: Boolean, text: String, column: String? = null): Value =
        parseEditText(resolve(caseId, scan()), id, parameter, text, column)

    /** Resolves the case once for all columns of an imported row. */
    fun parseEditRowText(caseId: String, table: String, columns: Map<String, String>): Value.MapV {
        val resolved = resolve(caseId, scan())
        val input = resolved.view.nodes[table]?.input
        if (input?.type != ValueType.TABLE) throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown table input $table")
        return Value.MapV(columns.map { (name, text) -> Value.Kw(name) to parseEditText(resolved, table, false, text, name) }.toMap())
    }

    private fun parseEditText(resolved: Resolved, id: String, parameter: Boolean, text: String, column: String?): Value {
        val view = resolved.view
        val type = if (parameter) {
            val declared = view.nodes[id]?.parameter?.value
                ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown parameter $id")
            when (declared) {
                is Value.Num -> ValueType.DECIMAL
                is Value.Bool -> ValueType.BOOLEAN
                is Value.Kw -> ValueType.KEYWORD
                else -> ValueType.TEXT
            }
        } else {
            val input = view.nodes[id]?.input ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown input $id")
            if (column == null) input.type else input.columns.firstOrNull { it.name == column }?.type
                ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown table column $column")
        }
        return try {
            when (type) {
                ValueType.DECIMAL, ValueType.INTEGER -> {
                    val raw = text.trim()
                    val symbols = DecimalFormatSymbols.getInstance(resolved.layout.number.locale)
                    val group = symbols.groupingSeparator
                    val decimal = symbols.decimalSeparator
                    val pattern = Regex("-?(?:\\d{1,3}(?:${Regex.escape(group.toString())}\\d{3})+|\\d+)(?:${Regex.escape(decimal.toString())}\\d+)?")
                    require(pattern.matches(raw)) { "Invalid decimal text" }
                    val number = BigDecimal(raw.replace(group.toString(), "").replace(decimal, '.'))
                    require(type != ValueType.INTEGER || number.stripTrailingZeros().scale() <= 0) { "Expected an integer" }
                    Value.Num(number)
                }
                ValueType.BOOLEAN -> when (text.trim().lowercase()) {
                    "true", "ja" -> Value.Bool(true)
                    "false", "nein" -> Value.Bool(false)
                    else -> throw IllegalArgumentException("Expected a boolean")
                }
                ValueType.KEYWORD -> Value.Kw(text.trim().removePrefix(":"))
                ValueType.DATE -> Value.Date(LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)))
                ValueType.TEXT, ValueType.ANY -> Value.Text(text)
                ValueType.TABLE -> throw IllegalArgumentException("Table input requires encoded rows")
            }
        } catch (error: RuntimeException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Input text was rejected: ${error.message}", listOf(diagnostic("MANTRA-WORKBENCH-EDIT", error.message.orEmpty())))
        }
    }

    /** A table-backed dimension supplies the stable row key; other tables use revision-local indexes. */
    fun tableKeyColumn(caseId: String, table: String): String? {
        val view = resolve(caseId, scan()).view
        if (view.nodes[table]?.input?.type != ValueType.TABLE)
            throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown table input $table")
        return view.dimensions.values.firstOrNull { it.fromTable == table }?.keyColumn
    }

    private fun restore(caseId: String, baseRevision: String, undo: Boolean): DocumentResult {
        val history = histories.computeIfAbsent(caseId) { EditHistory() }
        return synchronized(history) {
            val snapshot = scan()
            val base = resolve(caseId, snapshot)
            checkRevision(baseRevision, base.revision)
            val from = if (undo) history.undo else history.redo
            val to = if (undo) history.redo else history.undo
            if (from.isEmpty()) throw WorkspaceException(WorkspaceProblem.REQUEST, "No ${if (undo) "undo" else "redo"} version")
            val original = source(path(caseId)).text
            val candidate = from.last()
            val variant = resolve(caseId, snapshot, caseText = candidate)
            checkEditDiagnostics(variant)
            writeCase(caseId, path(caseId), candidate, original, base.revision)
            from.removeLast()
            to.addLast(original)
            while (to.size > 50) to.removeFirst()
            DocumentResult(variant.revision, editData(base, variant, caseId, false))
        }
    }

    private fun editCandidate(caseId: String, operations: List<CaseTextEditor.Operation>): String = try {
        require(operations.isNotEmpty() && operations.size <= 100) { "Expected 1–100 edit operations" }
        var candidate = source(path(caseId)).text
        operations.forEach { operation ->
            candidate = CaseTextEditor.apply(candidate, listOf(operation))
            require(candidate.length <= 65_536 && candidate.toByteArray(Charsets.UTF_8).size <= 1_048_576) {
                "Edited document exceeds reader limit"
            }
        }
        candidate
    } catch (error: IllegalArgumentException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Edit was rejected", listOf(diagnostic("MANTRA-WORKBENCH-EDIT", error.message.orEmpty())))
    } catch (error: IllegalStateException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Edit was rejected", listOf(diagnostic("MANTRA-WORKBENCH-EDIT", error.message.orEmpty())))
    }

    private fun editData(base: Resolved, variant: Resolved, caseId: String, preview: Boolean): Map<String, Any?> = linkedMapOf(
        "document" to caseId,
        "preview" to preview,
        "proposedRevision" to variant.revision,
        "diagnostics" to variant.view.diagnostics.map(WorkbenchDocuments::diagnostic),
        "run" to WorkbenchDocuments.run(variant.view, variant.layout),
        "difference" to WorkbenchDocuments.compare(base.view, variant.view, base.layout),
    )

    private fun checkRevision(expected: String, actual: String) {
        if (expected != actual) throw WorkspaceException(WorkspaceProblem.CONFLICT, "Base revision is stale", currentRevision = actual)
    }

    private fun checkEditDiagnostics(candidate: Resolved) {
        val rejected = candidate.view.diagnostics.filter { finding ->
            val code = finding.code
            code.startsWith("MANTRA-READ-") || code.startsWith("MANTRA-CASE-") ||
                code.startsWith("MANTRA-INPUT-") || code.startsWith("MANTRA-FORMULA") || code.startsWith("MANTRA-CYCLE")
                || code.startsWith("MANTRA-DIMENSION-")
        }
        if (rejected.isNotEmpty()) throw WorkspaceException(WorkspaceProblem.INVALID, "Edit was rejected", rejected)
    }

    private fun validateFinalCoordinates(final: Resolved, operations: List<CaseTextEditor.Operation>) {
        fun reject(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.INVALID, "Edit was rejected",
            listOf(diagnostic("MANTRA-WORKBENCH-EDIT", message)))
        val view = final.view
        fun memberMap(value: Value, levels: Int): Boolean = when {
            levels == 0 -> true
            value !is Value.MapV -> false
            else -> value.entries.all { (key, child) ->
                (key is Value.Kw || key is Value.Text) && memberMap(child, levels - 1)
            }
        }
        view.nodes.values.filter { it.input != null && it.dims.isNotEmpty() }.forEach { node ->
            val supplied = view.case.inputs[node.id] ?: return@forEach
            if (!memberMap(supplied, node.dims.size))
                reject("Dimensioned input ${node.id} requires ${node.dims.size} level(s) of member maps")
        }
        // A whole-map replacement must use members available in the final view. Existing maps may
        // retain dormant members when a separate edit changes the active member selection.
        fun validMemberKeys(value: Value, dims: List<String>, level: Int = 0): Boolean {
            if (level == dims.size) return true
            val entries = (value as? Value.MapV)?.entries ?: return false
            val allowed = view.members[dims[level]].orEmpty().mapTo(mutableSetOf()) { it.key }
            return entries.all { (key, child) ->
                val member = when (key) {
                    is Value.Kw -> key.name
                    is Value.Text -> key.value
                    else -> null
                }
                member != null && member in allowed && validMemberKeys(child, dims, level + 1)
            }
        }
        operations.filterIsInstance<CaseTextEditor.Operation.SetInput>()
            .filter { it.coord.isEmpty() }.map { it.id }.distinct().forEach { id ->
                val node = view.nodes[id]?.takeIf { it.input != null && it.dims.isNotEmpty() } ?: return@forEach
                val supplied = view.case.inputs[id] ?: return@forEach
                if (!validMemberKeys(supplied, node.dims))
                    reject("Dimensioned input $id contains an invalid member key")
            }
        operations.forEach { op ->
            if (op !is CaseTextEditor.Operation.SetInput || op.coord.isEmpty()) return@forEach
            var retained: Value? = view.case.inputs[op.id]
            op.coord.forEach { key ->
                retained = (retained as? Value.MapV)?.entries?.let { it[Value.Kw(key)] ?: it[Value.Text(key)] }
            }
            if (retained == null) return@forEach // A later operation cleared this member.
            val node = view.nodes[op.id]?.takeIf { it.input != null } ?: reject("Unknown input ${op.id}")
            if (op.coord.size != node.dims.size || node.dims.zip(op.coord).any { (dim, member) ->
                    view.members[dim]?.none { it.key == member } != false
                }) reject("Invalid member coordinate for input ${op.id}")
        }
    }

    private fun writeCase(caseId: String, target: Path, text: String, expected: String, baseRevision: String) {
        val temp = Files.createTempFile(target.parent, ".mantra-edit-", ".tmp")
        try {
            Files.writeString(temp, text)
            beforeWriteCheck?.invoke()
            synchronized(writeLocks.computeIfAbsent(target) { Any() }) {
                // A file lock serializes other catalog processes that open this case before writing.
                // Re-read the complete dependency revision while holding it, immediately before replace.
                FileChannel.open(checked(target), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use {
                        val current = runCatching { resolve(caseId, scan()).revision }.getOrNull()
                        if (current != baseRevision || Files.readString(target) != expected)
                            throw WorkspaceException(WorkspaceProblem.CONFLICT, "Workspace changed during edit",
                                currentRevision = current)
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun explain(caseId: String, address: ExplainAddress, depth: Int = 1): DocumentResult {
        if (depth !in 1..5) throw WorkspaceException(WorkspaceProblem.REQUEST, "Explain depth must be between 1 and 5")
        val snapshot = scan()
        var remaining = 64
        lateinit var revision: String
        fun project(target: ExplainAddress, level: Int): Map<String, Any?> {
            if (--remaining < 0) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Explain exceeds 64 nodes")
            val memberMap = target.node.startsWith("all.")
            if (memberMap && target.cell != null)
                throw WorkspaceException(WorkspaceProblem.REQUEST, "Member-map address cannot have a cell")
            val nodeId = if (memberMap) target.node.removePrefix("all.") else target.node
            val resolved = resolve(caseId, snapshot, explain = target.takeUnless { memberMap })
            revision = resolved.revision
            val node = resolved.view.nodes[nodeId]
                ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain node was not found")
            if (!memberMap && (node.dims.size != target.coord.size || target.coord !in node.values))
                throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain coordinate was not found")
            if (memberMap && node.dims.isEmpty())
                throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain member map was not found")
            val fixed = if (memberMap) {
                val bindings = linkedMapOf<String, String>()
                target.coord.forEach { part ->
                    val split = part.split('=', limit = 2)
                    if (split.size != 2 || split.any(String::isBlank) || split[0] !in node.dims ||
                        bindings.put(split[0], split[1]) != null)
                        throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed member-map coordinate")
                    if (resolved.view.members[split[0]].orEmpty().none { it.key == split[1] })
                        throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain member was not found")
                }
                if (target.coord != node.dims.mapNotNull { dim -> bindings[dim]?.let { "$dim=$it" } } ||
                    bindings.size == node.dims.size)
                    throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed member-map coordinate")
                bindings
            } else emptyMap()
            val cellValue = target.cell?.let { (row, column) ->
                val rows = node.value(target.coord) as? Value.Vec
                val item = row.toIntOrNull()?.let { rows?.items?.getOrNull(it) } as? Value.MapV
                item?.entries?.entries?.firstOrNull { (key, _) ->
                    key == Value.Kw(column) || key == Value.Text(column)
                }?.value ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain cell was not found")
            }
            val data = if (memberMap) WorkbenchDocuments.memberMap(resolved.view, resolved.layout, nodeId, fixed).toMutableMap()
                else WorkbenchDocuments.explain(resolved.view, resolved.layout, nodeId, target.coord,
                    resolved.explainTrace, target.cell, cellValue).toMutableMap()
            if (level > 1) {
                @Suppress("UNCHECKED_CAST")
                val refs = data["references"] as List<Map<String, Any?>>
                data["references"] = refs.map { ref ->
                    @Suppress("UNCHECKED_CAST")
                    val linked = ref["address"] as Map<String, Any?>
                    val child = ExplainAddress(linked.getValue("node") as String,
                        (linked["coord"] as? List<*>)?.filterIsInstance<String>().orEmpty())
                    ref + ("explanation" to project(child, level - 1))
                }
            }
            return data
        }
        val data = project(address, depth)
        return DocumentResult(revision, data)
    }

    fun envelope(document: DocumentResult): String = WorkbenchJson.write(
        WorkbenchJson.envelope(document.revision, mantraVersion, normeinVersion, document.data)
    )

    private data class Resolved(val view: CalculationView, val layout: LayoutSpec, val revision: String,
                                val parameterIds: List<String>, val explainTrace: ExplainTrace? = null)

    private fun resolve(caseId: String, snapshot: Snapshot, layoutOverride: String? = null,
                        parameterOverride: List<String>? = null, includeLayout: Boolean = true,
                        caseText: String? = null, explain: ExplainAddress? = null): Resolved {
        val casePath = path(caseId)
        val entry = snapshot.kind("case").singleOrNull { it.path == casePath }
            ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Case was not found")
        if (entry.diagnostics.isNotEmpty()) throw WorkspaceException(WorkspaceProblem.INVALID, "Case document is invalid", entry.diagnostics)
        val case = try { if (caseText == null) loadCase(casePath) else Mantra.loadCase(SourceText(caseId, caseText, casePath.parent.toString())) } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Case document is invalid", error.diagnostics)
        }
        val schemaId = case.schemaId ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Case has no schema",
            listOf(diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Case does not declare :schema")))
        val matches = snapshot.kind("schema").filter { it.name == schemaId }
        if (matches.size != 1) throw WorkspaceException(WorkspaceProblem.INVALID, "Schema cannot be resolved",
            listOf(diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Expected one schema for $schemaId, found ${matches.size}")))
        val schemaPath = matches.single().path
        val schema = try { Mantra.loadSchema(source(schemaPath), SourceResolver { name, relative ->
            val base = relative?.base?.let(Path::of) ?: schemaPath.parent
            runCatching { source(base.resolve(name)) }.getOrNull()
        }) } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Schema document is invalid", error.diagnostics)
        }
        val parameterBinding = case.meta["parameters"]
        if (parameterOverride == null && parameterBinding != null && parameterBinding !is Value.Vec)
            throw WorkspaceException(WorkspaceProblem.INVALID, ":parameters must be a list")
        val parameterIds = parameterOverride ?: (parameterBinding as? Value.Vec)?.items?.map { (it as? Value.Text)?.value
            ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter id must be text") }.orEmpty()
        val parameterFiles = parameterIds.map { id ->
            val matched = snapshot.kind("parameters").filter { it.name == id }
            if (matched.size != 1) throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter set $id cannot be resolved")
            matched.single().path
        }
        val parameters = try { parameterFiles.map(Mantra::loadParameters) } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter document is invalid", error.diagnostics)
        }
        val bound = BoundSources.load(case, schema, casePath, root)
        val result = try {
            if (explain == null) Mantra.calculate(schema, bound.case, parameters)
            else Mantra.calculateForExplain(schema, bound.case, parameters, explain.node, explain.coord)
        } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Case cannot be calculated", error.diagnostics)
        }
        val view = CalculationView.of(result)
        if (includeLayout && case.meta["layout"] != null && case.meta["layout"] !is Value.Text)
            throw WorkspaceException(WorkspaceProblem.INVALID, ":layout must be text")
        val selectedLayout = if (includeLayout) layoutOverride ?: case.text("layout") else null
        val layoutFile = selectedLayout?.let { id ->
            val matched = snapshot.kind("layout").filter { it.name == id }
            if (matched.size != 1) throw WorkspaceException(WorkspaceProblem.INVALID, "Layout $id cannot be resolved")
            matched.single().path
        }
        val layout = layoutFile?.let { try { LayoutReader.read(source(it)) } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Layout document is invalid", error.diagnostics)
        } } ?: Render.defaultLayout(view)
        val revision = revision(listOf(casePath) + schema.sources.map { path(it) } + bound.files + parameterFiles + listOfNotNull(layoutFile),
            if (caseText == null) emptyMap() else mapOf(casePath to caseText.toByteArray(Charsets.UTF_8)))
        return Resolved(view, layout, revision, parameterIds, result.explainTrace)
    }

    private fun scan(): Snapshot {
        return Snapshot(mantraFiles().map { file ->
            val diagnostics = DiagnosticSink()
            val document = try { Document.read(source(file), diagnostics) } catch (error: WorkspaceException) {
                if (error.problem == WorkspaceProblem.TOO_LARGE) throw error
                diagnostics.error("MANTRA-WORKBENCH-DOCUMENT", error.message.orEmpty())
                null
            }
            val form = document?.root as? DslForm.Sequence
            val kind = form?.listHead.orEmpty()
            val name = form?.values?.getOrNull(1)?.let { it.symbol ?: (it as? DslForm.Atom)?.value }
            Indexed(file, relative(file), kind, name, diagnostics.all)
        })
    }

    private fun mantraFiles(): List<Path> {
        val files = Files.walk(root).use { stream ->
            stream.filter { it.toString().endsWith(".mantra") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted().limit(4097).toList()
        }
        if (files.size > 4096) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace exceeds 4096 Mantra files")
        return files
    }

    private fun workspaceFiles(documents: List<Path> = mantraFiles()): List<Path> {
        val sources = documents.mapNotNull { file ->
            runCatching { loadCase(file) }.getOrNull()?.let { case -> file to case.sources }
        }.flatMap { (caseFile, bindings) ->
            bindings.mapNotNull { binding ->
                val name = (binding.options["path"] as? Value.Text)?.value ?: return@mapNotNull null
                val candidate = caseFile.parent.resolve(name).toAbsolutePath().normalize()
                candidate.takeIf { it.startsWith(root) && Files.isRegularFile(it) && it.toRealPath().startsWith(root) }
            }
        }
        if (sources.size > 4096) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace exceeds 4096 bound sources")
        return (documents + sources).distinct().sortedBy(::relative)
    }

    private fun fileLimit(file: Path, documents: List<Path>): Int = if (file in documents) 1_048_576 else ImportFiles.MAX_BYTES

    private fun loadCase(file: Path): CaseData = Mantra.loadCase(source(file))

    private fun source(file: Path): SourceText {
        val target = checked(file)
        if (Files.size(target) > 1_048_576) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Document is too large")
        val contents = Files.readString(target)
        if (contents.length > 65_536) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Document exceeds reader limit")
        return SourceText(relative(target), contents, target.parent.toString())
    }

    private fun path(relative: String): Path {
        if (relative.isBlank() || '\u0000' in relative) throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Path was not found")
        return checked(root.resolve(relative))
    }

    private fun checked(file: Path): Path {
        val target = file.toAbsolutePath().normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target)) throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Path was not found")
        if (!target.toRealPath().startsWith(root)) throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Path was not found")
        return target
    }

    private fun relative(path: Path): String = root.relativize(path).toString().replace('\\', '/')

    private fun revision(files: List<Path>, replacements: Map<Path, ByteArray> = emptyMap()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.distinct().sortedBy(::relative).forEach { file ->
            val bytes = replacements[file] ?: Files.readAllBytes(checked(file))
            val name = relative(file).toByteArray(Charsets.UTF_8)
            digest.update(name.size.toString().toByteArray()); digest.update(0.toByte()); digest.update(name)
            digest.update(0.toByte()); digest.update(bytes.size.toString().toByteArray()); digest.update(0.toByte()); digest.update(bytes)
            digest.update(0.toByte())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private fun diagnostic(code: String, message: String) = Diagnostic(Severity.ERROR, code, message)

    private fun lockedNormein(from: Path): String {
        var cursor: Path? = from
        while (cursor != null) {
            val lock = cursor.resolve("normein-build.lock")
            if (Files.isRegularFile(lock)) return Files.readAllLines(lock)
                .firstOrNull { it.startsWith("normeinCommit=") }?.substringAfter('=')?.take(8) ?: "unknown"
            cursor = cursor.parent
        }
        return "unknown"
    }
}
