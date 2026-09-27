package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
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
import java.security.MessageDigest

enum class WorkspaceProblem { REQUEST, NOT_FOUND, INVALID, TOO_LARGE }

data class ExportBudget(val maxSheets: Int = 64, val maxCells: Int = 50_000, val maxBytes: Int = 8 * 1024 * 1024) {
    init { require(maxSheets > 0 && maxCells > 0 && maxBytes > 0) }
}

class WorkspaceException(
    val problem: WorkspaceProblem,
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
) : RuntimeException(message)

data class ExplainAddress(val node: String, val coord: List<String> = emptyList(),
                          val cell: Pair<String, String>? = null)

/** Rebuilds read-only documents from workspace files for every request. No calculation state lives in the server. */
class WorkspaceCatalog(directory: Path, private val mantraVersion: String = "0.1.0-SNAPSHOT", normeinVersion: String? = null,
                       private val exportBudget: ExportBudget = ExportBudget()) {
    val root: Path = directory.toRealPath().also { require(Files.isDirectory(it)) { "Workspace must be a directory" } }
    private val normeinVersion = normeinVersion ?: lockedNormein(root)

    private data class Indexed(val path: Path, val id: String, val kind: String, val name: String?, val diagnostics: List<Diagnostic>)
    private data class Snapshot(val files: List<Indexed>) {
        fun kind(name: String) = files.filter { it.kind == name }
    }

    data class DocumentResult(val revision: String, val data: Map<String, Any?>)

    fun workspace(): DocumentResult {
        val snapshot = scan()
        val all = snapshot.files.map { it.path }
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
                        explain: ExplainAddress? = null): Resolved {
        val casePath = path(caseId)
        val entry = snapshot.kind("case").singleOrNull { it.path == casePath }
            ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Case was not found")
        if (entry.diagnostics.isNotEmpty()) throw WorkspaceException(WorkspaceProblem.INVALID, "Case document is invalid", entry.diagnostics)
        val case = try { loadCase(casePath) } catch (error: MantraException) {
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
        val result = try {
            if (explain == null) Mantra.calculate(schema, case, parameters)
            else Mantra.calculateForExplain(schema, case, parameters, explain.node, explain.coord)
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
        val sourceFiles = schema.sources.map { path(it) }
        val revision = revision(listOf(casePath) + sourceFiles + parameterFiles + listOfNotNull(layoutFile))
        return Resolved(view, layout, revision, parameterIds, result.explainTrace)
    }

    private fun scan(): Snapshot {
        val files = Files.walk(root).use { stream ->
            stream.filter { it.toString().endsWith(".mantra") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted().limit(4097).toList()
        }
        if (files.size > 4096) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace exceeds 4096 Mantra files")
        return Snapshot(files.map { file ->
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

    private fun revision(files: List<Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.distinct().sortedBy(::relative).forEach { file ->
            val bytes = Files.readAllBytes(checked(file))
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
