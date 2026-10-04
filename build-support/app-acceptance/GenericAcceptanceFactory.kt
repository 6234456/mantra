package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.normein.dsl.form.DslForm
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Shared host wiring: documents are parsed publicly and all calculations use the public graph runner. */
fun loadGenericAcceptanceEnvironment(applicationDirectory: Path): AcceptanceEnvironment {
    val application = applicationDirectory.toRealPath()
    require(Files.isDirectory(application))
    val workspace = if (application.parent?.fileName?.toString() == "apps") application.parent else application
    val paths = AcceptancePaths(workspace)
    val schemas = mutableListOf<AcceptanceSchemaDocument>()
    val parameters = mutableListOf<AcceptanceParameterDocument>()
    val layouts = mutableListOf<AcceptanceLayoutDocument>()
    val cases = mutableListOf<AcceptanceCaseDocument>()
    val registered = mutableSetOf<Path>()
    val files = Files.walk(workspace).use { stream ->
        stream.filter { file ->
            file.toString().endsWith(".mantra") && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                workspace.relativize(file).none { it.toString() in setOf("build", ".git", ".deps") }
        }.sorted().toList()
    }
    files.forEach { file ->
        val source = paths.source(file)
        val sink = DiagnosticSink()
        val document = requireNotNull(Document.read(source, sink)) { "Cannot index $file: ${sink.all}" }
        sink.throwIfErrors()
        val root = document.root as? DslForm.Sequence ?: return@forEach
        when (root.listHead) {
            "schema" -> schemas += AcceptanceSchemaDocument(file, Mantra.loadSchema(source, paths.sources))
            "parameters" -> parameters += AcceptanceParameterDocument(file, Mantra.loadParameters(source))
            "layout" -> {
                val id = root.values.getOrNull(1)?.let { it.string ?: it.symbol }
                    ?: error("Layout lacks its public document id: $file")
                layouts += AcceptanceLayoutDocument(file, id, LayoutReader.read(source))
            }
            "case" -> {
                val case = Mantra.loadCase(source)
                val role = when (val declared = case.meta["acceptance-role"]) {
                    null -> AcceptanceCaseRole.ORDINARY
                    Value.Kw("technical-failure") -> AcceptanceCaseRole.TECHNICAL_FAILURE
                    Value.Kw("focused-amount") -> AcceptanceCaseRole.FOCUSED_AMOUNT
                    Value.Kw("ordinary") -> AcceptanceCaseRole.ORDINARY
                    else -> error("Unknown acceptance role $declared at $file")
                }
                val targets = case.links.flatMap { link ->
                    link.mappings.map { AcceptanceScalarAddress(it.to.nodeId, it.to.coord) }
                }
                cases += AcceptanceCaseDocument(file, case, role, targets)
                if (file.fileName.toString().startsWith("case-") || "acceptance-role" in case.meta) registered.add(file)
            }
        }
    }
    val defaults = schemas.mapNotNull { schema ->
        layouts.singleOrNull { it.path == schema.path.parent.resolve("layout.mantra") }
            ?.let { schema.path to it.id }
    }.toMap()
    val documents = AcceptanceDocumentCatalog(schemas, parameters, layouts, cases, defaults)
    val owned = cases.filter { it.path.startsWith(application) && it.path in registered }
    require(owned.isNotEmpty()) { "No registered acceptance cases in $application" }
    // Only conventional root documents produce legacy variants. Explicit case parameters own their base.
    val legacy = owned.filter { "parameters" !in it.case.meta }.associate { entry ->
        val binding = AcceptanceCaseBinder.bind(documents, application, workspace, entry)
        entry.path to parameters.filter { document ->
            document.path.parent == application && document.path.fileName.toString().startsWith("params-") &&
                (document.parameters.forSchema == null || document.parameters.forSchema == binding.schema.id)
        }.map { document ->
            val stem = document.path.fileName.toString().removeSuffix(".mantra")
            val suffix = stem.removePrefix("params-")
            val preserved = if (suffix.all(Char::isDigit)) stem else suffix
            AcceptanceParameterVariant("-$preserved", listOf(document.parameters.id))
        }
    }
    val layoutVariants = owned.associate { entry ->
        val binding = AcceptanceCaseBinder.bind(documents, application, workspace, entry)
        entry.path to layouts.filter { layout ->
            layout.path.parent == binding.schemaDocument.path.parent &&
                layout.path.fileName.toString().startsWith("layout-")
        }.map { layout ->
            val suffix = layout.path.fileName.toString().removeSuffix(".mantra").removePrefix("layout-")
            AcceptanceLayoutVariant("-$suffix", layout.id)
        }
    }
    return AcceptanceEnvironment(
        application, workspace, documents, owned,
        GenericAcceptanceGraphRunner(paths), paths, GenericAcceptanceWorkbench(paths),
        legacy, layoutVariants, importExamples(application, documents, owned, paths),
    )
}

internal class AcceptancePaths(val root: Path) : AcceptanceSourcePathResolver {
    fun confined(candidate: Path): Path {
        val lexical = candidate.toAbsolutePath().normalize()
        require(lexical.startsWith(root)) { "Acceptance source escapes its workspace: $candidate" }
        return lexical.toRealPath().also { require(it.startsWith(root)) { "Symlink escapes acceptance workspace" } }
    }
    override fun resolve(authoringCase: Path, relativePath: String): Path =
        confined(authoringCase.parent.resolve(relativePath))
    fun relative(path: Path): String = root.relativize(confined(path)).toString().replace('\\', '/')
    fun source(path: Path): SourceText {
        val actual = confined(path)
        return SourceText(relative(actual), Files.readString(actual), actual.parent.toString())
    }
    val sources = SourceResolver { name, from ->
        source((from?.base?.let(Path::of) ?: root).resolve(name))
    }
}

private fun importExamples(
    application: Path,
    documents: AcceptanceDocumentCatalog,
    owned: List<AcceptanceCaseDocument>,
    paths: AcceptancePaths,
): List<AcceptanceImportExample> {
    val directory = application.resolve("import-templates")
    if (!Files.isDirectory(directory)) return emptyList()
    val files = Files.list(directory).use { stream ->
        stream.filter { it.toString().endsWith(".json") && Files.isRegularFile(it) }.sorted().toList()
    }
    require(files.isNotEmpty()) { "Import template directory cannot be empty: $directory" }
    return files.map { file ->
        val value =
            Json.parse(Files.readString(file)) as? Value.MapV ?: error("Import template is not an object: $file")
        fun text(name: String) = (value.entries[Value.Kw(name)] as? Value.Text)?.value
            ?.takeIf(String::isNotBlank) ?: error("Import template requires explicit $name: $file")
        val declarationPath = paths.confined(application.resolve(text("case")))
        val declaration = owned.single { it.path == declarationPath }
        val binding = AcceptanceCaseBinder.bind(documents, application, paths.root, declaration)
        require(binding.schemaId == AcceptanceSchemaId(text("schema"), text("schema-version"))) {
            "Import template does not name its case's exact schema identity: $file"
        }
        val options = (value.entries[Value.Kw("options")] as? Value.MapV)?.entries?.map { (key, option) ->
            (key as? Value.Kw)?.name?.let { it to option } ?: error("Import option key is not text: $file")
        }?.toMap() ?: error("Import template lacks options: $file")
        val sample = (options["path"] as? Value.Text)?.value?.takeIf(String::isNotBlank)
            ?: error("Import template requires options.path: $file")
        require(Files.size(paths.resolve(declaration.path, sample)) > 0) { "Import sample is empty: $file" }
        AcceptanceImportExample(text("name"), declaration, text("format"), options)
    }
}
