package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.Formula
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslNamedDefinition
import com.xqiou.normein.dsl.compiler.DslSourcePosition
import com.xqiou.normein.dsl.diagnostic.DslDiagnostic
import com.xqiou.normein.dsl.diagnostic.DslDiagnosticPhase
import com.xqiou.normein.dsl.diagnostic.DslDiagnosticSeverity
import com.xqiou.normein.dsl.environment.DslAnalysisScope
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuildResult
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuilder
import com.xqiou.normein.dsl.environment.DslRootDeclaration
import com.xqiou.normein.dsl.reference.DslReferenceKind
import com.xqiou.normein.dsl.type.DslFieldPresence
import com.xqiou.normein.dsl.type.DslObjectField
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes

/** The host compiler boundary, independent from vertex ordering and member scheduling. */
internal class FormulaCompiler(
    private val sink: DiagnosticSink,
    private val dimensions: Map<String, DimensionDecl>,
    private val vertices: Map<String, Vertex>,
    private val definitions: List<DslNamedDefinition>,
    private val namedSources: Map<String, NamedSource>,
    private val types: PlanTypes,
    private val elementType: (ValueVertex) -> DslType,
    compiling: CompilationMeter? = null,
) {
    private data class ScopeKey(val dims: List<String>, val rowTable: String?, val roots: List<DslRootDeclaration>)
    private val scopes = hashMapOf<ScopeKey, DslAnalysisScope>()
    private val lowering = PrevLowering(compiling = compiling)
    private val relationRoots: Map<String, String> get() = dimensions.values
        .filter { it.parentDimension != null }.associate { "relation_${it.id}" to it.id }

    fun scopeFor(
        dims: List<String>,
        rowTable: String? = null,
        previousRoots: List<DslRootDeclaration> = emptyList(),
    ): DslAnalysisScope = scopes.getOrPut(ScopeKey(dims.toList(), rowTable, previousRoots.toList())) {
        val scopeId = "mantra.scope.${dims.joinToString(".").ifEmpty { "scalar" }}"
        val builder = DslAnalysisScopeBuilder.create(scopeId, "2")
        types.definitions.forEach(builder::type)
        vertices.values.filterIsInstance<ValueVertex>().filter { !it.isValidation }.forEach { vertex ->
            val type = mapOver(vertex.dims.filter { it !in dims }, elementType(vertex))
            if (vertex.id !in MantraKernel.callableNames && (rowTable == null || vertex.id != "row")) {
                builder.root(DslRootDeclaration(vertex.id, type, DslFieldPresence.OPTIONAL))
            }
            builder.root(DslRootDeclaration(MantraKernel.qualifiedRoot(vertex.id), type, DslFieldPresence.OPTIONAL))
        }
        dims.filter { rowTable == null || it != "row" }.forEach { dim ->
            builder.root(
                DslRootDeclaration(dim, DslTypes.ref(types.dimensionRecord.getValue(dim)), DslFieldPresence.OPTIONAL),
            )
        }
        relationRoots.keys.forEach { root ->
            builder.root(
                DslRootDeclaration(root, DslTypes.map(DslType.Keyword, DslType.Keyword), DslFieldPresence.OPTIONAL),
            )
        }
        builder.root(DslRootDeclaration("periods", periodsRootType(dimensions.values), DslFieldPresence.OPTIONAL))
        builder.root(DslRootDeclaration("all", DslTypes.ref(types.all), DslFieldPresence.OPTIONAL))
        rowTable?.let {
            builder.root(
                DslRootDeclaration("row", DslTypes.ref(types.tableRow.getValue(it)), DslFieldPresence.REQUIRED),
            )
        }
        previousRoots.forEach(builder::root)
        when (val result = builder.build()) {
            is DslAnalysisScopeBuildResult.Success -> result.scope
            is DslAnalysisScopeBuildResult.Failure ->
                error("Analysis scope for $dims is invalid: ${result.diagnostics}")
        }
    }

    fun compile(
        formula: Formula,
        dims: List<String>,
        expected: DslType,
        logical: String,
        rowTable: String? = null,
    ): CompiledFormula? {
        val request = DslCompileRequest(
            source = Qualified.rewrite(formula.source),
            namedDefinitions = definitions,
            expectedType = expected,
            logicalLocation = logical.take(200),
            hostPosition = hostPosition(formula.location, formula.source),
        )
        val compiled = when (
            val result = lowering.compile(
                request,
                MantraKernel.environment,
                previousTargets(dims),
            ) { roots -> scopeFor(dims, rowTable, roots) }
        ) {
            is PrevCompileResult.Failure -> {
                result.kernelDiagnostics.forEach { report(it, formula, logical) }
                result.hostProblems.forEach { problem ->
                    sink.error("MANTRA-FORMULA", problem.message, formula.location, logical.substringBefore('.'))
                }
                return null
            }
            is PrevCompileResult.Success -> result
        }
        val expression = compiled.expression
        val roots = expression.requiredRoots.keys
        fun staticRefs(root: String): Set<String> = expression.references
            .filter { it.rootName == root && it.kind == DslReferenceKind.FIELD_PATH }
            .mapNotNull { reference ->
                reference.staticPath?.let { path ->
                    if (path.firstOrNull() == root) path.getOrNull(1) else path.firstOrNull()
                }
            }.toSet()
        val allRefs = staticRefs("all")
        if ("all" in roots && allRefs.isEmpty()) {
            sink.error(
                "MANTRA-ALL-DYNAMIC",
                "`all` must be used with a static field such as all.<line-id>",
                formula.location,
                logical,
            )
        }
        allRefs.filter { it !in vertices }.forEach {
            sink.error("MANTRA-ALL-UNKNOWN", "all.$it does not name a dimensioned line", formula.location, logical)
        }
        val rootNames = roots.mapNotNull { root ->
            if (rowTable != null && root == "row") return@mapNotNull null
            val node = root.removePrefix("mantra_")
            if (vertices[node] is ValueVertex) root to node else null
        }.toMap()
        val dimRefs = roots.filter { it in dims && (rowTable == null || it != "row") }.toSet()
        val relations = roots.mapNotNull { root -> relationRoots[root]?.let { root to it } }.toMap()
        return CompiledFormula(
            formula = formula,
            expression = expression,
            namedSources = namedSources,
            dims = dims,
            nodeRefs = rootNames.values.toSet(),
            rootNames = rootNames,
            allRefs = allRefs,
            dimRefs = dimRefs,
            relationRefs = relations,
            rowTable = rowTable,
            previousBindings = compiled.bindings,
            dependencies = FormulaDependencies.collect(
                expression.normalizedAst,
                rootNames,
                dimRefs,
                relations,
                compiled.bindings,
            ),
            authorSourceIndex = compiled.authorSourceIndex,
            periodRefs = staticRefs("periods"),
        )
    }

    /** Checks editable source through the same typed host lowering used by calculation. */
    fun authoringCheck(source: String, dims: List<String>, expected: DslType): List<DslDiagnostic> = when (
        val result = lowering.compile(
            DslCompileRequest(Qualified.rewrite(source), namedDefinitions = definitions, expectedType = expected),
            MantraKernel.environment,
            previousTargets(dims),
        ) { roots -> scopeFor(dims, previousRoots = roots) }
    ) {
        is PrevCompileResult.Success -> emptyList()
        is PrevCompileResult.Failure -> result.kernelDiagnostics + result.hostProblems.map { problem ->
            DslDiagnostic(
                "DSL-MANTRA-PREV",
                DslDiagnosticSeverity.ERROR,
                DslDiagnosticPhase.NORMALIZATION,
                problem.message.take(2048),
                span = problem.span,
            )
        }
    }

    private fun previousTargets(dims: List<String>): PreviousTargetResolver = PreviousTargetResolver { target ->
        val nodeId = target.name.value.removePrefix("mantra_")
        val vertex = vertices[nodeId] as? ValueVertex
            ?: throw IllegalArgumentException("prev target $nodeId is not a schema value node")
        require(!vertex.isValidation) { "prev target $nodeId is a validation, not a measure" }
        val axes = vertex.dims.filter { it in dims && dimensions[it]?.periods != null }
        require(axes.size == 1) { "prev target $nodeId requires one shared continuous period dimension" }
        val extra = vertex.dims.filter { it !in dims }
        val element = elementType(vertex).let { if (extra.isEmpty()) it else DslTypes.nullable(it) }
        PreviousTarget(nodeId, axes.single(), mapOver(extra, element))
    }

    private fun report(diagnostic: DslDiagnostic, formula: Formula, nodeId: String) {
        val source = diagnostic.logicalLocation?.removePrefix("defn.")?.let(namedSources::get)
        val owner = source?.location ?: formula.location
        val location = diagnostic.span?.let {
            SourceLocation(owner.source, it.line, it.column, it.startOffset, it.endOffset)
        } ?: owner
        val detail = diagnostic.attributes.entries.joinToString(", ") { (key, value) -> "$key=$value" }.take(300)
        sink.error(
            "MANTRA-FORMULA",
            "${diagnostic.code}: ${diagnostic.message}${if (detail.isNotEmpty()) " ($detail)" else ""}",
            location,
            nodeId.substringBefore('.'),
        )
    }

    private fun mapOver(dims: List<String>, element: DslType): DslType = dims.foldRight(element) { _, inner ->
        DslTypes.map(DslType.Keyword, inner)
    }
}

/** Shared structural contract used by compilation and the host runtime importer. */
internal fun periodsRootType(dimensions: Collection<DimensionDecl>): DslType = DslTypes.objectType(
    dimensions.filter { it.periods != null }.map { dimension ->
        DslObjectField(
            Names.field(dimension.id)!!,
            DslTypes.objectType(
                listOf(
                    DslObjectField(
                        Names.field("keys")!!,
                        DslTypes.sequence(DslType.Keyword),
                        DslFieldPresence.REQUIRED,
                    ),
                ),
            ),
            DslFieldPresence.OPTIONAL,
        )
    },
)
