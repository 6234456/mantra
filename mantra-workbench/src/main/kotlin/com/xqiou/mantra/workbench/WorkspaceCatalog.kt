package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import com.xqiou.normein.dsl.form.DslForm
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

enum class WorkspaceProblem { REQUEST, NOT_FOUND, INVALID, TOO_LARGE }

class WorkspaceException(
    val problem: WorkspaceProblem,
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
) : RuntimeException(message)

/** Rebuilds read-only documents from workspace files for every request. No calculation state lives in the server. */
class WorkspaceCatalog(directory: Path, private val mantraVersion: String = "0.1.0-SNAPSHOT", normeinVersion: String? = null) {
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

    fun envelope(document: DocumentResult): String = WorkbenchJson.write(
        WorkbenchJson.envelope(document.revision, mantraVersion, normeinVersion, document.data)
    )

    private data class Resolved(val view: CalculationView, val layout: LayoutSpec, val revision: String)

    private fun resolve(caseId: String, snapshot: Snapshot, layoutOverride: String? = null): Resolved {
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
        if (parameterBinding != null && parameterBinding !is Value.Vec)
            throw WorkspaceException(WorkspaceProblem.INVALID, ":parameters must be a list")
        val parameterIds = (parameterBinding as? Value.Vec)?.items?.map { (it as? Value.Text)?.value
            ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter id must be text") }.orEmpty()
        val parameterFiles = parameterIds.map { id ->
            val matched = snapshot.kind("parameters").filter { it.name == id }
            if (matched.size != 1) throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter set $id cannot be resolved")
            matched.single().path
        }
        val parameters = try { parameterFiles.map(Mantra::loadParameters) } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter document is invalid", error.diagnostics)
        }
        val result = try { Mantra.calculate(schema, case, parameters) } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Case cannot be calculated", error.diagnostics)
        }
        val view = CalculationView.of(result)
        if (case.meta["layout"] != null && case.meta["layout"] !is Value.Text)
            throw WorkspaceException(WorkspaceProblem.INVALID, ":layout must be text")
        val selectedLayout = layoutOverride ?: case.text("layout")
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
        return Resolved(view, layout, revision)
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
