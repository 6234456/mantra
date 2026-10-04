package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.workbench.WorkspaceCatalog.Companion.writeLocks
import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.WorkspaceCatalog.EditHistory
import com.xqiou.mantra.workbench.WorkspaceCatalog.Resolved
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import java.math.BigDecimal
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Serializes writes for each case and records the previous exact source for bounded undo. */
internal fun WorkspaceCatalog.commitCaseEdits(
    caseId: String,
    baseRevision: String,
    operations: List<CaseTextEditor.Operation>,
): DocumentResult {
    val history = histories.computeIfAbsent(caseId) { EditHistory() }
    return synchronized(history) {
        val snapshot = scan()
        val base = resolve(caseId, snapshot)
        checkRevision(baseRevision, base.revision)
        rejectLinkedEdits(base, operations)
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

/** Parses author text by the declared input/parameter type; numerals follow German layout syntax. */
internal fun WorkspaceCatalog.parseEditorRowText(
    caseId: String,
    table: String,
    columns: Map<String, String>,
): Value.MapV {
    val resolved = resolve(caseId, scan())
    val input = resolved.view.nodes[table]?.input
    if (input?.type != ValueType.TABLE) throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown table input $table")
    return Value.MapV(
        columns.map { (name, text) ->
            Value.Kw(name) to parseEditText(resolved, table, false, text, name)
        }.toMap(),
    )
}

internal fun WorkspaceCatalog.parseEditText(
    resolved: Resolved,
    id: String,
    parameter: Boolean,
    text: String,
    column: String?,
): Value = EditorValueParser.parse(resolved.view, resolved.layout, id, parameter, text, column)

/** A table-backed dimension supplies the stable row key; other tables use revision-local indexes. */
internal fun WorkspaceCatalog.inputTableKeyColumn(caseId: String, table: String): String? {
    val view = resolve(caseId, scan()).view
    if (view.nodes[table]?.input?.type != ValueType.TABLE) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown table input $table")
    }
    return view.dimensions.values.firstOrNull { it.fromTable == table }?.keyColumn
}

internal fun WorkspaceCatalog.restore(caseId: String, baseRevision: String, undo: Boolean): DocumentResult {
    val history = histories.computeIfAbsent(caseId) { EditHistory() }
    return synchronized(history) {
        val snapshot = scan()
        val base = resolve(caseId, snapshot)
        checkRevision(baseRevision, base.revision)
        val from = if (undo) history.undo else history.redo
        val to = if (undo) history.redo else history.undo
        if (from.isEmpty()) {
            throw WorkspaceException(
                WorkspaceProblem.REQUEST,
                "No ${if (undo) "undo" else "redo"} version",
            )
        }
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

internal fun WorkspaceCatalog.editCandidate(caseId: String, operations: List<CaseTextEditor.Operation>): String = try {
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
    throw WorkspaceException(
        WorkspaceProblem.INVALID,
        "Edit was rejected",
        listOf(diagnostic("MANTRA-WORKBENCH-EDIT", error.message.orEmpty())),
    )
} catch (error: IllegalStateException) {
    throw WorkspaceException(
        WorkspaceProblem.INVALID,
        "Edit was rejected",
        listOf(diagnostic("MANTRA-WORKBENCH-EDIT", error.message.orEmpty())),
    )
}

internal fun WorkspaceCatalog.editData(
    base: Resolved,
    variant: Resolved,
    caseId: String,
    preview: Boolean,
): Map<String, Any?> = linkedMapOf(
    "document" to caseId,
    "preview" to preview,
    "proposedRevision" to variant.revision,
    "diagnostics" to variant.view.diagnostics.map(WorkbenchDocuments::diagnostic),
    "run" to WorkbenchDocuments.run(variant.view, variant.layout),
    "difference" to WorkbenchDocuments.compare(base.view, variant.view, base.layout),
)

internal fun WorkspaceCatalog.checkRevision(expected: String, actual: String) {
    if (expected !=
        actual
    ) {
        throw WorkspaceException(WorkspaceProblem.CONFLICT, "Base revision is stale", currentRevision = actual)
    }
}

internal fun WorkspaceCatalog.checkEditDiagnostics(candidate: Resolved) {
    val rejected = candidate.view.diagnostics.filter { finding ->
        val code = finding.code
        val technicalRejection = listOf(
            "MANTRA-READ-",
            "MANTRA-CASE-",
            "MANTRA-INPUT-",
            "MANTRA-FORMULA",
            "MANTRA-CYCLE",
            "MANTRA-DIMENSION-",
        ).any(code::startsWith)
        finding.category != DiagnosticCategory.BUSINESS && technicalRejection
    }
    if (rejected.isNotEmpty()) throw WorkspaceException(WorkspaceProblem.INVALID, "Edit was rejected", rejected)
}

internal fun WorkspaceCatalog.validateFinalCoordinates(final: Resolved, operations: List<CaseTextEditor.Operation>) {
    fun reject(message: String): Nothing = throw WorkspaceException(
        WorkspaceProblem.INVALID,
        "Edit was rejected",
        listOf(diagnostic("MANTRA-WORKBENCH-EDIT", message)),
    )
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
        if (!memberMap(supplied, node.dims.size)) {
            reject("Dimensioned input ${node.id} requires ${node.dims.size} level(s) of member maps")
        }
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
            if (!validMemberKeys(supplied, node.dims)) {
                reject("Dimensioned input $id contains an invalid member key")
            }
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
            }
        ) {
            reject("Invalid member coordinate for input ${op.id}")
        }
    }
}

internal fun WorkspaceCatalog.writeCase(
    caseId: String,
    target: Path,
    text: String,
    expected: String,
    baseRevision: String,
    revisionCheck: () -> String? = { runCatching { resolve(caseId, scan()).revision }.getOrNull() },
) {
    val temp = Files.createTempFile(target.parent, ".mantra-edit-", ".tmp")
    try {
        Files.writeString(temp, text)
        beforeWriteCheck?.invoke()
        synchronized(writeLocks.computeIfAbsent(target) { Any() }) {
            // A file lock serializes other catalog processes that open this case before writing.
            // Re-read the complete dependency revision while holding it, immediately before replace.
            FileChannel.open(checked(target), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val current = revisionCheck()
                    if (current != baseRevision || Files.readString(target) != expected) {
                        throw WorkspaceException(
                            WorkspaceProblem.CONFLICT,
                            "Workspace changed during edit",
                            currentRevision = current,
                        )
                    }
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

/** Consumer edits cannot replace, clear, or overwrite an ancestor map of a linked input. */
internal fun rejectLinkedEdits(base: Resolved, operations: List<CaseTextEditor.Operation>) {
    operations.forEach { operation ->
        val (id, coord) = when (operation) {
            is CaseTextEditor.Operation.SetInput -> operation.id to operation.coord
            is CaseTextEditor.Operation.ClearInput -> operation.id to operation.coord
            else -> return@forEach
        }
        if (base.view.case.linkInputs.keys.any { address ->
                address.nodeId == id &&
                    (coord.isEmpty() || address.coord.take(coord.size) == coord)
            }
        ) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Linked input $id is read-only; edit its source case")
        }
    }
}
