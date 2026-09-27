package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.FieldItem
import com.xqiou.mantra.core.model.Formula
import com.xqiou.mantra.core.model.FunctionDecl
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Item
import com.xqiou.mantra.core.model.LineItem
import com.xqiou.mantra.core.model.NoteItem
import com.xqiou.mantra.core.model.Op
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.model.TotalItem
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslCompileResult
import com.xqiou.normein.dsl.compiler.DslNamedDefinition
import com.xqiou.normein.dsl.compiler.DslSemanticCompiler
import com.xqiou.normein.dsl.compiler.DslSourcePosition
import com.xqiou.normein.dsl.diagnostic.DslDiagnostic
import com.xqiou.normein.dsl.environment.DslAnalysisScope
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuildResult
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuilder
import com.xqiou.normein.dsl.environment.DslRootDeclaration
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormAtomKind
import com.xqiou.normein.dsl.form.DslFormPostfix
import com.xqiou.normein.dsl.form.DslFormReadResult
import com.xqiou.normein.dsl.form.DslFormReader
import com.xqiou.normein.dsl.reference.DslReferenceKind
import com.xqiou.normein.dsl.type.DslFieldPresence
import com.xqiou.normein.dsl.type.DslObjectField
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypeDefinition
import com.xqiou.normein.dsl.type.DslTypeId
import com.xqiou.normein.dsl.type.DslTypeSchema
import com.xqiou.normein.dsl.type.DslTypeSchemaResult
import com.xqiou.normein.dsl.type.DslTypes

/** Structural types registered for record-shaped roots (dimension members, table rows, `all`). */
class PlanTypes(
    val dimensionRecord: Map<String, DslTypeId>,
    val tableRow: Map<String, DslTypeId>,
    val all: DslTypeId,
    val definitions: List<DslTypeDefinition>,
)

/**
 * Builds a [CalculationPlan]: resolves the item tree (including case extensions in slots), derives
 * dimensions and section totals, compiles every formula with the Normein kernel against a typed
 * analysis scope for its dimension context, and orders all vertices topologically.
 */
class Planner(private val sink: DiagnosticSink) {
    private val compiler = DslSemanticCompiler()
    private val environment = MantraKernel.environment

    private lateinit var schema: Schema
    private lateinit var case: CaseData
    private val dimensions = linkedMapOf<String, DimensionDecl>()
    private val vertices = linkedMapOf<String, Vertex>()
    private val inputDecls = linkedMapOf<String, InputDecl>()
    private val fieldDims = hashMapOf<String, List<String>>()
    private val scopes = hashMapOf<List<String>, DslAnalysisScope>()
    private val relationRoots: Map<String, String> get() = dimensions.values
        .filter { it.parentDimension != null }
        .associate { "relation_${it.id}" to it.id }
    private lateinit var definitions: List<DslNamedDefinition>
    private lateinit var types: PlanTypes
    private lateinit var typeSchema: DslTypeSchema

    fun plan(schema: Schema, case: CaseData, parameterSets: List<com.xqiou.mantra.core.read.ParameterSet> = emptyList()): CalculationPlan? {
        this.schema = schema
        this.case = case
        val declared = schema.params.map { it.id }.toSet()
        parameterSets.forEach { set ->
            set.forSchema?.takeIf { it != schema.id }?.let {
                sink.warning("MANTRA-PARAMETERS-SCHEMA", "Parameter set ${set.id} is intended for schema $it, not ${schema.id}", set.location)
            }
            set.values.keys.filter { it !in declared }.forEach {
                sink.error("MANTRA-PARAMETERS-UNKNOWN", "Parameter set ${set.id} sets unknown parameter :$it", set.location)
            }
        }
        schema.dimensions.forEach { decl ->
            if (dimensions.put(decl.id, decl) != null) sink.error("MANTRA-ID-DUPLICATE", "Dimension ${decl.id} is declared twice", decl.location)
        }
        schema.inputs.forEach { inputDecls.putIfAbsent(it.id, it) }
        validateRelations()
        validateCase()

        // 1. Parameters and top-level inputs.
        schema.params.forEach { decl ->
            val fromSet = parameterSets.lastOrNull { decl.id in it.values }
            val layers = buildList {
                add(ParameterLayer("schema", decl.value, reference = decl.presentation.reference))
                parameterSets.forEach { set ->
                    set.values[decl.id]?.let { add(ParameterLayer("parameters", it, set.id, set.references[decl.id])) }
                }
                add(ParameterLayer("case", case.params[decl.id], declared = decl.id in case.params))
            }
            val (value, source) = when {
                decl.id in case.params -> case.params.getValue(decl.id) to "case"
                fromSet != null -> fromSet.values.getValue(decl.id) to fromSet.id
                else -> decl.value to "schema"
            }
            register(ParamVertex(decl, value, source, layers))
        }
        // 2. Item tree (fields register their inputs while walking).
        val tree = walkSection(schema.root, emptyList(), emptyList())
        schema.inputs.filter { it.id !in fieldDims }.forEach { decl ->
            register(InputVertex(decl, canonical(decl.per ?: emptyList(), decl.location)))
        }
        dimensions.values.forEach { register(DimensionVertex(it)) }
        if (sink.hasErrors) return null

        // 3. Types, definitions and compilation.
        types = buildTypes()
        typeSchema = when (val result = DslTypeSchema.create(types.definitions)) {
            is DslTypeSchemaResult.Success -> result.schema
            is DslTypeSchemaResult.Failure -> {
                sink.error("MANTRA-TYPES", "Internal record types are inconsistent: ${result.violations}")
                return null
            }
        }
        definitions = (schema.functions + case.functions).map {
            DslNamedDefinition(it.name, Qualified.rewrite(it.source), "defn.${it.name}", hostPosition = hostPosition(it.location, it.source))
        }
        if (!validateDefinitions(schema.functions + case.functions)) return null
        compileAll()
        if (sink.hasErrors) return null

        // 4. Dependencies and order.
        val order = order() ?: return null
        return CalculationPlan(schema, case, parameterSets, dimensions, vertices, order, tree, definitions, typeSchema, types)
    }

    // ── Tree walk ──────────────────────────────────────────────────────────────────────────────

    private class Walk {
        val contributions = mutableListOf<Component>()
    }

    private fun walkSection(section: SectionItem, parentDims: List<String>, guards: List<String>): ResolvedSection {
        val dims = canonical(section.per ?: parentDims, section.location)
        val guardId = section.condition?.let { formula ->
            val id = "${section.id}?when"
            register(ConditionVertex(id, section.id, dims, formula))
            id
        }
        val activeGuards = guards + listOfNotNull(guardId)
        val children: List<Item> = if (section.slot) case.extensions[section.id].orEmpty() else section.children
        val opaque = children.any { it is TotalItem }
        val walk = Walk()
        var lastTotal: String? = null
        val resolved = mutableListOf<ResolvedItem>()

        fun contribute(component: Component) {
            if (component.sign != 0) walk.contributions += component
        }

        for (child in children) {
            when (child) {
                is SectionItem -> {
                    val sub = walkSection(child, dims, activeGuards)
                    resolved += sub
                    if (sub.resultId != null) {
                        contribute(Component(sub.resultId, child.op.sign))
                    } else {
                        subPassThrough.remove(sub)?.forEach { contribute(Component(it.vertexId, it.sign * child.op.sign)) }
                    }
                }
                is LineItem -> {
                    val effective = case.formulaBindings[child.id]?.let { child.copy(formula = it, userDefined = true) } ?: child
                    val lineDims = canonical(effective.per ?: dims, effective.location)
                    if (effective.spread && lineDims.isEmpty()) {
                        sink.error("MANTRA-SPREAD-DIMS", "Line ${effective.id} uses :spread but has no dimension", effective.location)
                    }
                    register(LineVertex(effective, lineDims).also { it.guards += activeGuards })
                    resolved += ResolvedNode(effective, lineDims, effective.op.sign)
                    contribute(Component(effective.id, effective.op.sign))
                }
                is FieldItem -> {
                    val decl = inputDecls[child.id]
                    if (decl == null) {
                        sink.error("MANTRA-FIELD", "Field ${child.id} has no input declaration", child.location)
                    } else {
                        val fieldDimList = canonical(decl.per ?: dims, child.location)
                        fieldDims[child.id] = fieldDimList
                        register(InputVertex(decl, fieldDimList).also { it.guards += activeGuards })
                        resolved += ResolvedNode(child, fieldDimList, child.op.sign)
                        contribute(Component(child.id, child.op.sign))
                    }
                }
                is ChoiceItem -> {
                    val choiceDims = canonical(child.per ?: dims, child.location)
                    register(ChoiceVertex(child, choiceDims).also { it.guards += activeGuards })
                    resolved += ResolvedNode(child, choiceDims, child.op.sign)
                    contribute(Component(child.id, child.op.sign))
                }
                is TotalItem -> {
                    val components = listOfNotNull(lastTotal?.let { Component(it, 1) }) + walk.contributions
                    components.forEach { component ->
                        val componentDims = (vertices[component.vertexId] as? ValueVertex)?.dims ?: return@forEach
                        if (!componentDims.containsAll(dims)) {
                            sink.error(
                                "MANTRA-TOTAL-DIMS",
                                "Total ${child.id} is per ${dims.joinToString()} but component ${component.vertexId} is not; mark it :op :info",
                                child.location,
                                child.id,
                            )
                        }
                    }
                    register(TotalVertex(child, dims, components).also { it.guards += activeGuards })
                    resolved += ResolvedNode(child, dims, 0)
                    walk.contributions.clear()
                    lastTotal = child.id
                }
                is NoteItem -> resolved += ResolvedNote(child)
            }
        }
        val result = ResolvedSection(section, dims, resolved, if (opaque) lastTotal else null, guardId)
        if (opaque) {
            if (walk.contributions.isNotEmpty()) {
                sink.warning(
                    "MANTRA-TOTAL-TRAILING",
                    "Section ${section.id} has contributing items after its last total; they are not part of its result",
                    section.location,
                )
            }
        } else {
            subPassThrough[result] = walk.contributions.toList()
        }
        return result
    }

    private val subPassThrough = hashMapOf<ResolvedSection, List<Component>>()

    // ── Registration and validation ────────────────────────────────────────────────────────────

    private fun register(vertex: Vertex) {
        val id = vertex.id
        val isInternal = vertex is ConditionVertex
        if (!isInternal) {
            if (id in MantraKernel.reservedNames || id.startsWith("mantra_") || id in relationRoots) {
                sink.error("MANTRA-ID-RESERVED", "`$id` is a reserved name; choose another identifier", vertex.location)
            } else if (!Names.rootIsValid(id) || !Names.rootIsValid(MantraKernel.qualifiedRoot(id))) {
                sink.error("MANTRA-ID-INVALID", "`$id` is not a valid identifier", vertex.location)
            } else if (id in MantraKernel.callableNames && vertex !is DimensionVertex) {
                sink.warning(
                    "MANTRA-ID-SHADOWED",
                    "`$id` is also a Normein function; reference this node as ${MantraKernel.NAMESPACE}$id in formulas",
                    vertex.location,
                    id,
                )
            }
            if (vertex !is DimensionVertex && id in dimensions) {
                sink.error("MANTRA-ID-DUPLICATE", "`$id` is already used as a dimension", vertex.location)
            }
        }
        val previous = vertices.putIfAbsent(id, vertex)
        if (previous != null) {
            sink.error("MANTRA-ID-DUPLICATE", "Identifier `$id` is defined twice (first at ${previous.location})", vertex.location)
        }
    }

    private fun canonical(dims: List<String>, location: SourceLocation): List<String> {
        dims.filter { it !in dimensions }.forEach {
            sink.error("MANTRA-DIMENSION-UNKNOWN", "Unknown dimension `$it`", location)
        }
        return dimensions.keys.filter { it in dims }
    }

    private fun validateCase() {
        case.schemaId?.takeIf { it != schema.id }?.let { declared ->
            sink.error(
                "MANTRA-CASE-SCHEMA-MISMATCH",
                "Case ${case.id} declares schema $declared but is being calculated with ${schema.id}",
            )
        }
        val known = schema.inputs.map { it.id }.toSet()
        case.inputs.keys.filter { it !in known }.forEach {
            sink.error("MANTRA-CASE-INPUT-UNKNOWN", "Case supplies unknown input :$it")
        }
        val params = schema.params.map { it.id }.toSet()
        case.params.keys.filter { it !in params }.forEach {
            sink.error("MANTRA-CASE-PARAM-UNKNOWN", "Case overrides unknown parameter :$it")
        }
        val slots = mutableSetOf<String>()
        val formulaSlots = mutableSetOf<String>()
        fun collect(section: SectionItem) {
            if (section.slot) slots += section.id
            section.children.filterIsInstance<LineItem>().filter { it.formulaSlot }.forEach { formulaSlots += it.id }
            section.children.filterIsInstance<SectionItem>().forEach(::collect)
        }
        collect(schema.root)
        case.extensions.keys.filter { it !in slots }.forEach {
            sink.error("MANTRA-CASE-SLOT-UNKNOWN", "Case extends unknown slot `$it`")
        }
        case.formulaBindings.keys.filter { it !in formulaSlots }.forEach {
            sink.error("MANTRA-CASE-BIND-UNKNOWN", "Case binds `$it`, which is not an application-declared formula-slot")
        }
    }

    private fun validateRelations() {
        inputDecls.values.forEach { input ->
            if (input.references.isNotEmpty() && input.type != ValueType.TABLE) {
                sink.error("MANTRA-INPUT-REFERENCE", "Input ${input.id} declares :references but is not a table", input.location)
            }
            input.references.forEach { (columnName, target) ->
                val column = input.columns.firstOrNull { it.name == columnName }
                if (column == null) {
                    sink.error("MANTRA-INPUT-REFERENCE", "Input ${input.id} references unknown column :$columnName", input.location)
                } else if (column.type !in setOf(ValueType.KEYWORD, ValueType.TEXT, ValueType.INTEGER, ValueType.DECIMAL)) {
                    sink.error("MANTRA-INPUT-REFERENCE", "Input ${input.id} column :$columnName must contain keyword, text, or numeric keys", input.location)
                }
                if (target !in dimensions) {
                    sink.error("MANTRA-INPUT-REFERENCE", "Input ${input.id} column :$columnName references unknown dimension $target", input.location)
                }
            }
        }
        dimensions.values.forEach { decl ->
            val parent = decl.parentDimension
            val parentKey = decl.parentKeyColumn
            if (parent == null && parentKey == null) return@forEach
            if (parent == null || parentKey == null || decl.fromTable == null) {
                sink.error("MANTRA-DIMENSION-RELATION", "Dimension ${decl.id} needs :from, :parent and :parent-key together", decl.location)
                return@forEach
            }
            if (parent !in dimensions) {
                sink.error("MANTRA-DIMENSION-RELATION", "Dimension ${decl.id} has unknown parent dimension $parent", decl.location)
            }
            val source = inputDecls[decl.fromTable]
            if (source?.type != ValueType.TABLE || source.columns.none { it.name == parentKey }) {
                sink.error("MANTRA-DIMENSION-RELATION", "Dimension ${decl.id} parent key :$parentKey is not a column of ${decl.fromTable}", decl.location)
            }
        }
    }

    private fun validateDefinitions(functions: List<FunctionDecl>): Boolean {
        if (functions.isEmpty()) return true
        val scope = scopeFor(emptyList())
        return when (val result = compiler.compile(DslCompileRequest("nil", namedDefinitions = definitions), environment, scope)) {
            is DslCompileResult.Success -> true
            is DslCompileResult.Failure -> {
                result.diagnostics.forEach { diagnostic ->
                    val function = functions.firstOrNull { diagnostic.logicalLocation == "defn.${it.name}" } ?: functions.first()
                    report(diagnostic, Formula(function.source, function.location, dummyForm), function.name)
                }
                false
            }
        }
    }

    private val dummyForm by lazy {
        (com.xqiou.normein.dsl.form.DslFormReader().readDocument("nil") as com.xqiou.normein.dsl.form.DslFormReadResult.Success).document.root
    }

    // ── Types and scopes ───────────────────────────────────────────────────────────────────────

    private fun buildTypes(): PlanTypes {
        val definitions = mutableListOf<DslTypeDefinition>()
        val tableRow = linkedMapOf<String, DslTypeId>()
        val dimensionRecord = linkedMapOf<String, DslTypeId>()
        inputDecls.values.filter { it.type == ValueType.TABLE }.forEach { decl ->
            val id = Names.typeId("mantra.row", decl.id)
            tableRow[decl.id] = id
            definitions += DslTypeDefinition(id, DslTypes.objectType(columnFields(decl)))
        }
        dimensions.values.forEach { dim ->
            val id = Names.typeId("mantra.dim", dim.id)
            dimensionRecord[dim.id] = id
            val base = listOf(
                field("key", DslType.Keyword, false),
                field("label", DslType.Text, false),
                field("index", DslType.Integer, false),
            )
            val columns = dim.fromTable?.let { table -> inputDecls[table]?.let(::columnFields) }.orEmpty()
                .filter { column -> base.none { it.name == column.name } }
            definitions += DslTypeDefinition(id, DslTypes.objectType(base + columns))
        }
        val allId = Names.typeId("mantra", "all")
        types = PlanTypes(dimensionRecord, tableRow, allId, definitions.toList())
        val allFields = vertices.values.filterIsInstance<ValueVertex>().filter { it.dims.isNotEmpty() }.mapNotNull { vertex ->
            Names.field(vertex.id)?.let { DslObjectField(it, mapOver(vertex.dims, elementType(vertex)), DslFieldPresence.OPTIONAL) }
        }
        definitions += DslTypeDefinition(allId, DslTypes.objectType(allFields))
        return PlanTypes(dimensionRecord, tableRow, allId, definitions)
    }

    private fun columnFields(decl: InputDecl): List<DslObjectField> = decl.columns.mapNotNull { column ->
        val type = Types.element(column.type)
        Names.field(column.name)?.let {
            DslObjectField(it, if (column.optional) DslTypes.nullable(type) else type, if (column.optional) DslFieldPresence.OPTIONAL else DslFieldPresence.REQUIRED)
        }
    }

    private fun field(name: String, type: DslType, optional: Boolean): DslObjectField =
        DslObjectField(Names.field(name)!!, type, if (optional) DslFieldPresence.OPTIONAL else DslFieldPresence.REQUIRED)

    internal fun elementType(vertex: ValueVertex): DslType = when (vertex) {
        is ParamVertex -> Types.infer(vertex.value)
        is InputVertex -> when {
            vertex.type == ValueType.TABLE -> DslTypes.vector(DslTypes.ref(types.tableRow.getValue(vertex.id)))
            vertex.decl.optional || !vertex.type.isNumeric -> DslTypes.nullable(Types.element(vertex.type))
            else -> Types.element(vertex.type)
        }
        is LineVertex -> if (vertex.type.isNumeric) Types.element(vertex.type) else DslTypes.nullable(Types.element(vertex.type))
        is TotalVertex, is ChoiceVertex -> DslType.Decimal
    }

    private fun mapOver(dims: List<String>, element: DslType): DslType =
        dims.foldRight(element) { _, inner -> DslTypes.map(DslType.Keyword, inner) }

    private fun scopeFor(dims: List<String>): DslAnalysisScope = scopes.getOrPut(dims) {
        val builder = DslAnalysisScopeBuilder.create("mantra.scope.${dims.joinToString(".").ifEmpty { "scalar" }}", "1")
        types.definitions.forEach(builder::type)
        vertices.values.filterIsInstance<ValueVertex>().forEach { vertex ->
            val extra = vertex.dims.filter { it !in dims }
            val type = mapOver(extra, elementType(vertex))
            // Plain name unless it would hide a function; the qualified mantra/<id> always works.
            if (vertex.id !in MantraKernel.callableNames) builder.root(DslRootDeclaration(vertex.id, type, DslFieldPresence.OPTIONAL))
            builder.root(DslRootDeclaration(MantraKernel.qualifiedRoot(vertex.id), type, DslFieldPresence.OPTIONAL))
        }
        dims.forEach { dim ->
            builder.root(DslRootDeclaration(dim, DslTypes.ref(types.dimensionRecord.getValue(dim)), DslFieldPresence.OPTIONAL))
        }
        relationRoots.keys.forEach { name ->
            builder.root(DslRootDeclaration(name, DslTypes.map(DslType.Keyword, DslType.Keyword), DslFieldPresence.OPTIONAL))
        }
        builder.root(DslRootDeclaration("all", DslTypes.ref(types.all), DslFieldPresence.OPTIONAL))
        when (val result = builder.build()) {
            is DslAnalysisScopeBuildResult.Success -> result.scope
            is DslAnalysisScopeBuildResult.Failure -> error("Analysis scope for $dims is invalid: ${result.diagnostics}")
        }
    }

    // ── Compilation ────────────────────────────────────────────────────────────────────────────

    private fun compileAll() {
        vertices.values.toList().forEach { vertex ->
            when (vertex) {
                is DimensionVertex -> vertex.decl.members.forEach { member ->
                    member.condition?.let { formula ->
                        compile(formula, emptyList(), DslType.Any, "${vertex.id}.${member.key}.when")?.let { vertex.memberConditions[member.key] = it }
                    }
                }
                is ConditionVertex -> vertex.compiled = compile(vertex.formula, vertex.dims, DslType.Any, vertex.id)
                is LineVertex -> {
                    val item = vertex.item
                    val context = if (item.spread) vertex.dims.dropLast(1) else vertex.dims
                    val expected = if (item.spread) DslTypes.map(DslType.Keyword, Types.expected(item.type)) else Types.expected(item.type)
                    vertex.compiled = compile(item.formula, context, expected, item.id)
                    if (item.formulaSlot && item.userDefined && item.allowedRefs != null) {
                        val compiled = vertex.compiled
                        val used = compiled?.let { it.nodeRefs + it.allRefs + it.dimRefs + it.relationRefs.keys }.orEmpty()
                        (used - item.allowedRefs).forEach { root ->
                            sink.error("MANTRA-FORMULA-SLOT-REFERENCE", "Formula slot ${item.id} may not reference $root", item.formula.location, item.id)
                        }
                    }
                    vertex.ownCondition = item.condition?.let { compile(it, vertex.dims, DslType.Any, "${item.id}.when") }
                }
                is ChoiceVertex -> {
                    val item = vertex.item
                    item.options.forEach { option ->
                        val formula = compile(option.formula, vertex.dims, DslTypes.nullable(Types.number), "${item.id}.${option.key}")
                        val condition = option.condition?.let { compile(it, vertex.dims, DslType.Any, "${item.id}.${option.key}.when") }
                        if (formula != null) vertex.options += CompiledOption(option, formula, condition)
                    }
                    vertex.ownCondition = item.condition?.let { compile(it, vertex.dims, DslType.Any, "${item.id}.when") }
                }
                is TotalVertex -> vertex.ownCondition = vertex.item.condition?.let { compile(it, vertex.dims, DslType.Any, "${vertex.id}.when") }
                is ParamVertex, is InputVertex -> Unit
            }
        }
    }

    private fun compile(formula: Formula, dims: List<String>, expected: DslType, logical: String): CompiledFormula? {
        val request = DslCompileRequest(
            source = Qualified.rewrite(formula.source),
            namedDefinitions = definitions,
            expectedType = expected,
            logicalLocation = logical.take(200),
            hostPosition = hostPosition(formula.location, formula.source),
        )
        return when (val result = compiler.compile(request, environment, scopeFor(dims))) {
            is DslCompileResult.Failure -> {
                result.diagnostics.forEach { report(it, formula, logical) }
                null
            }
            is DslCompileResult.Success -> {
                val expression = result.expression
                val roots = expression.requiredRoots.keys
                val allRefs = expression.references
                    .filter { it.rootName == "all" && it.kind == DslReferenceKind.FIELD_PATH }
                    .mapNotNull { reference -> reference.staticPath?.let { path -> if (path.firstOrNull() == "all") path.getOrNull(1) else path.firstOrNull() } }
                    .toSet()
                if ("all" in roots && allRefs.isEmpty()) {
                    sink.error("MANTRA-ALL-DYNAMIC", "`all` must be used with a static field such as all.<line-id>", formula.location, logical)
                }
                allRefs.filter { it !in vertices }.forEach {
                    sink.error("MANTRA-ALL-UNKNOWN", "all.$it does not name a dimensioned line", formula.location, logical)
                }
                val rootNames = roots.mapNotNull { root ->
                    val node = if (root.startsWith("mantra_")) root.removePrefix("mantra_") else root
                    if (vertices[node] is ValueVertex) root to node else null
                }.toMap()
                CompiledFormula(
                    formula = formula,
                    expression = expression,
                    dims = dims,
                    nodeRefs = rootNames.values.toSet(),
                    rootNames = rootNames,
                    allRefs = allRefs,
                    dimRefs = roots.filter { it in dims }.toSet(),
                    relationRefs = roots.mapNotNull { root -> relationRoots[root]?.let { root to it } }.toMap(),
                )
            }
        }
    }

    private fun report(diagnostic: DslDiagnostic, formula: Formula, nodeId: String) {
        val span = diagnostic.span
        val location = span?.let {
            SourceLocation(formula.location.source, it.line, it.column, it.startOffset, it.endOffset)
        } ?: formula.location
        val detail = diagnostic.attributes.entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(300)
        sink.error(
            "MANTRA-FORMULA",
            "${diagnostic.code}: ${diagnostic.message}${if (detail.isNotEmpty()) " ($detail)" else ""}",
            location,
            nodeId.substringBefore('.'),
        )
    }

    // ── Dependencies and order ─────────────────────────────────────────────────────────────────

    private fun order(): List<Vertex>? {
        vertices.values.forEach { vertex ->
            val deps = vertex.dependencies
            fun addFormula(formula: CompiledFormula?) {
                if (formula == null) return
                deps += formula.nodeRefs
                deps += formula.allRefs
                deps += formula.dims
                deps += formula.relationRefs.values
            }
            when (vertex) {
                is DimensionVertex -> {
                    vertex.memberConditions.values.forEach(::addFormula)
                    vertex.decl.parentDimension?.let(deps::add)
                    vertex.decl.fromTable?.let { table ->
                        if (inputDecls[table]?.type != ValueType.TABLE) {
                            sink.error("MANTRA-DIMENSION-TABLE", "Dimension ${vertex.id} draws members from `$table`, which is not a table input", vertex.location)
                        } else {
                            deps += table
                        }
                    }
                }
                is ConditionVertex -> {
                    addFormula(vertex.compiled)
                    deps += vertex.dims
                }
                is ValueVertex -> {
                    deps += vertex.dims
                    deps += vertex.guards
                    addFormula(vertex.ownCondition)
                    when (vertex) {
                        is LineVertex -> addFormula(vertex.compiled)
                        is ChoiceVertex -> vertex.options.forEach { addFormula(it.formula); addFormula(it.condition) }
                        is TotalVertex -> deps += vertex.components.map { it.vertexId }
                        is ParamVertex, is InputVertex -> Unit
                    }
                }
            }
            deps.remove(vertex.id).let { selfReference ->
                if (selfReference) sink.error("MANTRA-CYCLE", "`${vertex.id}` refers to itself", vertex.location, vertex.id)
            }
        }
        if (sink.hasErrors) return null
        val result = mutableListOf<Vertex>()
        val state = hashMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        fun visit(id: String): Boolean {
            when (state[id]) {
                2 -> return true
                1 -> {
                    val cycle = stack.dropWhile { it != id } + id
                    sink.error("MANTRA-CYCLE", "Circular dependency: ${cycle.joinToString(" → ")}", vertices[id]?.location, id)
                    return false
                }
            }
            val vertex = vertices[id] ?: return true
            state[id] = 1
            stack.addLast(id)
            for (dependency in vertex.dependencies) if (!visit(dependency)) return false
            stack.removeLast()
            state[id] = 2
            result += vertex
            return true
        }
        for (id in vertices.keys) if (!visit(id)) return null
        return result
    }
}

private fun hostPosition(location: SourceLocation, source: String): DslSourcePosition {
    val start = requireNotNull(location.startOffset) { "Embedded DSL source needs a host start offset" }
    val end = requireNotNull(location.endOffset) { "Embedded DSL source needs a host end offset" }
    require(end - start == source.length) { "Embedded DSL source must match its host span" }
    return DslSourcePosition(location.line, location.column, start, end)
}

/** Operator of a node item as displayed in its section (sign of its contribution). */
internal fun Op.symbolSign(): Int = sign

/**
 * Rewrites fully qualified node references `mantra/<id>` into the equal-length root name
 * `mantra_<id>`, so kernel diagnostics keep pointing at the author's source positions.
 */
internal object Qualified {
    private val reader = DslFormReader()

    fun rewrite(source: String): String {
        val document = (reader.readDocument(source) as? DslFormReadResult.Success)?.document ?: return source
        val slashes = mutableListOf<Int>()
        fun visit(form: DslForm) {
            when (form) {
                is DslForm.Atom -> if (
                    form.kind == DslFormAtomKind.SYMBOL &&
                    form.sourceText.startsWith("mantra/") &&
                    form.sourceText.getOrNull(7)?.isLetter() == true
                ) slashes += form.span.startOffset + 6
                is DslForm.Sequence -> form.values.forEach(::visit)
                is DslForm.Postfix -> {
                    visit(form.target)
                    form.suffixes.forEach { if (it is DslFormPostfix.Bracket) visit(it.key) }
                }
            }
        }
        visit(document.root)
        if (slashes.isEmpty()) return source
        return source.toCharArray().also { chars -> slashes.forEach { chars[it] = '_' } }.concatToString()
    }
}
