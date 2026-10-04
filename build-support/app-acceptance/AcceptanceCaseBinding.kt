package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.render.layout.LayoutSpec
import java.nio.file.Path

/** Test-side document identities. The root host supplies canonical, workspace-confined paths. */
data class AcceptanceSchemaId(val id: String, val version: String?) {
    init {
        require(id.isNotBlank())
        require(version == null || version.isNotBlank())
    }
}

data class AcceptanceSchemaDocument(val path: Path, val schema: Schema)
data class AcceptanceParameterDocument(val path: Path, val parameters: ParameterSet)
data class AcceptanceLayoutDocument(val path: Path, val id: String, val layout: LayoutSpec)

enum class AcceptanceCaseRole { ORDINARY, TECHNICAL_FAILURE, FOCUSED_AMOUNT }

data class AcceptanceCaseDocument(
    val path: Path,
    val case: CaseData,
    val role: AcceptanceCaseRole = AcceptanceCaseRole.ORDINARY,
    /** The host loader records this from the parsed public link declarations, not a case-id rule. */
    val linkedTargets: List<AcceptanceScalarAddress> = emptyList(),
) {
    val hasLinks: Boolean get() = linkedTargets.isNotEmpty()
}

/** Immutable documents supplied by the root file/graph loader; this class performs no file I/O. */
data class AcceptanceDocumentCatalog(
    val schemas: List<AcceptanceSchemaDocument>,
    val parameters: List<AcceptanceParameterDocument>,
    val layouts: List<AcceptanceLayoutDocument>,
    val cases: List<AcceptanceCaseDocument>,
    /** Host-declared conventional layout; authored :layout always has precedence. */
    val defaultLayoutIdsBySchema: Map<Path, String> = emptyMap(),
)

data class AcceptanceCaseBinding(
    val applicationDirectory: Path,
    val workspaceRoot: Path,
    val declaration: AcceptanceCaseDocument,
    val schemaDocument: AcceptanceSchemaDocument,
    val parameterDocuments: List<AcceptanceParameterDocument>,
    val layoutDocument: AcceptanceLayoutDocument?,
) {
    val schema: Schema get() = schemaDocument.schema
    val schemaId: AcceptanceSchemaId get() = AcceptanceSchemaId(schema.id, schema.meta.text("version"))
    val parameters: List<ParameterSet> get() = parameterDocuments.map { it.parameters }
}

/** Mirrors existing :parameters/:layout semantics and adds exact, opaque schema-version selection. */
object AcceptanceCaseBinder {
    fun bind(
        catalog: AcceptanceDocumentCatalog,
        applicationDirectory: Path,
        workspaceRoot: Path,
        declaration: AcceptanceCaseDocument,
        parameterOverride: List<String>? = null,
        layoutOverride: String? = null,
    ): AcceptanceCaseBinding {
        val case = declaration.case
        val schemaId = requireNotNull(case.schemaId) { "Case has no :schema" }
        val version = textMetadata(case, "schema-version")
        val schemaMatches = catalog.schemas.filter { entry ->
            entry.schema.id == schemaId && (version == null || entry.schema.meta.text("version") == version)
        }
        require(schemaMatches.size == 1) {
            "Expected one schema for $schemaId${version?.let { "@$it" }.orEmpty()}, found ${schemaMatches.size}"
        }
        val selected = schemaMatches.single()
        val parameterIds = parameterOverride ?: parameterIds(case)
        val parameterDocuments = parameterIds.map { id ->
            val matches = catalog.parameters.filter { it.parameters.id == id }
            require(matches.size == 1) { "Parameter set $id cannot be resolved uniquely" }
            matches.single().also { document ->
                require(document.parameters.forSchema == null || document.parameters.forSchema == schemaId) {
                    "Parameter set $id is declared for a different schema"
                }
            }
        }
        val layoutId = layoutOverride ?: textMetadata(case, "layout") ?: catalog.defaultLayoutIdsBySchema[selected.path]
        val layoutDocument = layoutId?.let { id ->
            val matches = catalog.layouts.filter { it.id == id }
            require(matches.size == 1) { "Layout $id cannot be resolved uniquely" }
            matches.single()
        }
        // Source declarations and local inputs stay authored: the injected root graph runner binds them.
        return AcceptanceCaseBinding(
            applicationDirectory,
            workspaceRoot,
            declaration,
            selected,
            parameterDocuments,
            layoutDocument,
        )
    }

    fun parameterIds(case: CaseData): List<String> {
        val value = case.meta["parameters"] ?: return emptyList()
        require(value is Value.Vec) { ":parameters must be a vector of text IDs" }
        return value.items.map { item ->
            require(item is Value.Text && item.value.isNotBlank()) { "Parameter id must be nonblank text" }
            item.value
        }
    }

    private fun textMetadata(case: CaseData, key: String): String? {
        val value = case.meta[key] ?: return null
        require(value is Value.Text && value.value.isNotBlank()) { ":$key must be nonblank text" }
        return value.value
    }
}

/** Explicit extra variants preserve old M0–M2 regression runs; modern metadata always owns its base. */
data class AcceptanceParameterVariant(val suffix: String, val parameterIds: List<String>) {
    init {
        require(suffix.isNotEmpty() && '/' !in suffix && '\\' !in suffix)
    }
}

data class AcceptanceLayoutVariant(val suffix: String, val layoutId: String) {
    init {
        require(suffix.isNotEmpty() && '/' !in suffix && '\\' !in suffix)
        require(layoutId.isNotBlank())
    }
}

/** Keep legacy root names, while making same-basename cases in different version directories safe. */
object AcceptanceArtifactNames {
    fun base(binding: AcceptanceCaseBinding, suffix: String = ""): Path {
        require('/' !in suffix && '\\' !in suffix)
        val relative = binding.applicationDirectory.relativize(binding.declaration.path).normalize()
        require(!relative.isAbsolute && !relative.startsWith("..")) { "Case is outside its application" }
        val file = relative.fileName.toString()
        require(file.endsWith(".mantra"))
        return (relative.parent ?: Path.of("")).resolve(file.removeSuffix(".mantra") + suffix)
    }
}
