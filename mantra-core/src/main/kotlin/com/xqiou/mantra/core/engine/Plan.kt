package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.CheckItem
import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.ChoiceOption
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.FieldItem
import com.xqiou.mantra.core.model.Formula
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.LineItem
import com.xqiou.mantra.core.model.NodeItem
import com.xqiou.mantra.core.model.NoteItem
import com.xqiou.mantra.core.model.ParamDecl
import com.xqiou.mantra.core.model.ReconcileItem
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.model.TotalItem
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.ParameterLayer
import com.xqiou.normein.dsl.compiler.DslCompiledExpression
import com.xqiou.normein.dsl.compiler.DslNamedDefinition
import com.xqiou.normein.dsl.compiler.DslSourceIndexEntry
import com.xqiou.normein.dsl.identity.DslCanonicalNodeId
import com.xqiou.normein.dsl.type.DslTypeSchema

internal data class NamedSource(val source: String, val location: SourceLocation)

/** A formula compiled by the Normein kernel for one evaluation context. */
internal class CompiledFormula(
    val formula: Formula,
    val expression: DslCompiledExpression,
    val namedSources: Map<String, NamedSource>,
    /** Context dimensions the formula is evaluated in. */
    val dims: List<String>,
    /** Value vertices referenced directly as roots. */
    val nodeRefs: Set<String>,
    /** Root name used in the compiled expression → value vertex (`x` or `mantra_x` for `mantra/x`). */
    val rootNames: Map<String, String>,
    /** Value vertices referenced as full member maps through `all.<id>`. */
    val allRefs: Set<String>,
    /** Dimension roots (current member records) referenced by the formula. */
    val dimRefs: Set<String>,
    /** Generated source-member → parent-member maps referenced by the formula. */
    val relationRefs: Map<String, String>,
    /** A typed table record supplied as the local `row` root, only for column validation. */
    val rowTable: String? = null,
    /** Host-lowered prior-period inputs; their target references are never current-period roots. */
    val previousBindings: List<PreviousBinding> = emptyList(),
    /** Dependency occurrences retain first-only activation, including named-function calls. */
    val dependencies: List<FormulaDependency> = emptyList(),
    /** Original author ownership mapped onto the executable kernel's final canonical paths. */
    val authorSourceIndex: Map<DslCanonicalNodeId, DslSourceIndexEntry> = expression.sourceIndex,
    /** Continuous period domains read through periods.<dimension>.keys. */
    val periodRefs: Set<String> = emptySet(),
)

internal sealed class Vertex(val id: String, val location: SourceLocation) {
    val dependencies: MutableSet<String> = linkedSetOf()
}

/** Resolves the active members of a dimension. */
internal class DimensionVertex(val decl: DimensionDecl) : Vertex(decl.id, decl.location) {
    val memberConditions: MutableMap<String, CompiledFormula> = linkedMapOf()
}

/** The `:when` guard of a section, evaluated in the section's own dimension context. */
internal class ConditionVertex(id: String, val sectionId: String, val dims: List<String>, val formula: Formula) :
    Vertex(id, formula.location) {
    var compiled: CompiledFormula? = null
}

internal sealed class ValueVertex(id: String, location: SourceLocation) : Vertex(id, location) {
    abstract val dims: List<String>
    abstract val type: ValueType
    abstract val label: String

    /** Section guards (ConditionVertex ids) that must all hold for this vertex to be active. */
    val guards: MutableList<String> = mutableListOf()
    var ownCondition: CompiledFormula? = null

    val aggregate: com.xqiou.mantra.core.model.AggregateRule get() = when (this) {
        is InputVertex -> decl.aggregate
        is LineVertex -> item.aggregate
        is TotalVertex -> item.aggregate
        is ChoiceVertex -> item.aggregate
        else -> com.xqiou.mantra.core.model.AggregateRule.NONE
    }
    val ratio: com.xqiou.mantra.core.model.RatioAggregation? get() = when (this) {
        is InputVertex -> decl.ratio
        is LineVertex -> item.ratio
        is TotalVertex -> item.ratio
        is ChoiceVertex -> item.ratio
        else -> null
    }
    val boundary: com.xqiou.mantra.core.model.BoundaryAggregation? get() = when (this) {
        is InputVertex -> decl.boundary
        is LineVertex -> item.boundary
        is TotalVertex -> item.boundary
        is ChoiceVertex -> item.boundary
        else -> null
    }
}

/** A parameter; [source] is `schema`, the id of the parameter set that supplied it, or `case`. */
internal class ParamVertex(
    val decl: ParamDecl,
    val value: Value,
    val source: String,
    val layers: List<ParameterLayer>,
) : ValueVertex(decl.id, decl.location) {
    val overridden: Boolean get() = source != "schema"
    override val dims: List<String> = emptyList()
    override val type: ValueType = when (value) {
        is Value.Num -> ValueType.DECIMAL
        is Value.Bool -> ValueType.BOOLEAN
        is Value.Kw -> ValueType.KEYWORD
        is Value.Text -> ValueType.TEXT
        is Value.Date -> ValueType.DATE
        else -> ValueType.ANY
    }
    override val label: String = decl.label ?: decl.id
}

internal class InputVertex(val decl: InputDecl, override val dims: List<String>) : ValueVertex(decl.id, decl.location) {
    override val type: ValueType = decl.type
    override val label: String = decl.label ?: decl.id
}

/** Input requirements run separately, so a condition may safely reference derived values. */
internal class InputValidationVertex(val input: InputVertex) : Vertex("${input.id}?validation", input.location) {
    var required: CompiledFormula? = null
    val columns: MutableMap<String, CompiledFormula> = linkedMapOf()
}

internal class CheckVertex(val item: CheckItem, override val dims: List<String>) : ValueVertex(item.id, item.location) {
    override val type: ValueType = ValueType.BOOLEAN
    override val label: String = item.label
    var compiled: CompiledFormula? = null
}

internal class ReconcileVertex(val item: ReconcileItem, override val dims: List<String>) :
    ValueVertex(item.id, item.location) {
    override val type: ValueType = ValueType.DECIMAL
    override val label: String = item.label
    var left: CompiledFormula? = null
    var right: CompiledFormula? = null
}

internal val ValueVertex.isValidation: Boolean get() = this is CheckVertex || this is ReconcileVertex
internal val ValueVertex.undefinedValues: Boolean get() = this is LineVertex &&
    compiled?.previousBindings?.isNotEmpty() == true

internal class LineVertex(val item: LineItem, override val dims: List<String>) : ValueVertex(item.id, item.location) {
    override val type: ValueType = item.type
    override val label: String = item.label
    var compiled: CompiledFormula? = null
}

internal data class Component(val vertexId: String, val sign: Int)

internal class TotalVertex(val item: TotalItem, override val dims: List<String>, val components: List<Component>) :
    ValueVertex(item.id, item.location) {
    override val type: ValueType = ValueType.DECIMAL
    override val label: String = item.label
}

internal class ChoiceVertex(val item: ChoiceItem, override val dims: List<String>) :
    ValueVertex(item.id, item.location) {
    override val type: ValueType = ValueType.DECIMAL
    override val label: String = item.label
    val options: MutableList<CompiledOption> = mutableListOf()
}

internal class CompiledOption(val option: ChoiceOption, val formula: CompiledFormula, val condition: CompiledFormula?)

// ── Resolved presentation tree ─────────────────────────────────────────────────────────────────

internal sealed interface ResolvedItem

internal class ResolvedSection(
    val item: SectionItem,
    val dims: List<String>,
    val children: List<ResolvedItem>,
    /** Last `total` of an opaque section; `null` for transparent (grouping) sections. */
    val resultId: String?,
    val guardId: String?,
) : ResolvedItem {
    val id: String get() = item.id
    val label: String get() = item.label
}

internal class ResolvedNode(val item: NodeItem, val dims: List<String>, val op: Int) : ResolvedItem {
    val id: String get() = item.id
}

internal class ResolvedNote(val item: NoteItem) : ResolvedItem

/** Everything needed to evaluate a schema for one case; produced by [Planner]. */
internal class CalculationPlan(
    val schema: Schema,
    val case: CaseData,
    val parameterSets: List<com.xqiou.mantra.core.read.ParameterSet>,
    val dimensions: Map<String, DimensionDecl>,
    val vertices: Map<String, Vertex>,
    val order: List<Vertex>,
    val tree: ResolvedSection,
    val definitions: List<DslNamedDefinition>,
    val typeSchema: DslTypeSchema,
    val types: PlanTypes,
) {
    val valueVertices: Map<String, ValueVertex> = vertices.values.filterIsInstance<ValueVertex>().associateBy { it.id }

    fun dimensionOrder(dims: Collection<String>): List<String> = dimensions.keys.filter { it in dims }
}
