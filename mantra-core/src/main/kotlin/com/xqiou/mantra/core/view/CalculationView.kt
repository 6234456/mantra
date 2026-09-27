package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.engine.CalculationResult
import com.xqiou.mantra.core.engine.ChoiceVertex
import com.xqiou.mantra.core.engine.ConditionVertex
import com.xqiou.mantra.core.engine.Coord
import com.xqiou.mantra.core.engine.GuardAlignment
import com.xqiou.mantra.core.engine.InputVertex
import com.xqiou.mantra.core.engine.LineVertex
import com.xqiou.mantra.core.engine.Member
import com.xqiou.mantra.core.engine.NodeTrace
import com.xqiou.mantra.core.engine.NodeResult
import com.xqiou.mantra.core.engine.ParamVertex
import com.xqiou.mantra.core.engine.ResolvedItem
import com.xqiou.mantra.core.engine.ResolvedNode
import com.xqiou.mantra.core.engine.ResolvedNote
import com.xqiou.mantra.core.engine.ResolvedSection
import com.xqiou.mantra.core.engine.TotalVertex
import com.xqiou.mantra.core.engine.ValueVertex
import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.Formula
import com.xqiou.mantra.core.model.FunctionDecl
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Item
import com.xqiou.mantra.core.model.LineItem
import com.xqiou.mantra.core.model.NodeItem
import com.xqiou.mantra.core.model.NoteItem
import com.xqiou.mantra.core.model.ParamDecl
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.SchemaMeta
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.model.TotalItem
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.model.decimalOrZero
import com.xqiou.mantra.core.structure.SchemaMap
import com.xqiou.mantra.core.structure.SchemaMaps
import java.math.BigDecimal

/** Stable, presentation-facing classification. No planner vertex types cross this boundary. */
enum class NodeKind { INPUT, PARAM, LINE, TOTAL, CHOICE, FORMULA_SLOT, EXTENSION }

sealed interface ViewItem

class ViewSection(
    val item: SectionItem,
    val dims: List<String>,
    val children: List<ViewItem>,
    val resultId: String?,
) : ViewItem {
    val id: String get() = item.id
    val label: String get() = item.label
}

class ViewTreeNode(val item: NodeItem, val dims: List<String>, val op: Int) : ViewItem {
    val id: String get() = item.id
}

class ViewNote(val item: NoteItem) : ViewItem

data class ViewCondition(val id: String, val sectionId: String, val dims: List<String>, val formula: Formula)
data class ViewComponent(val vertexId: String, val sign: Int)

/** One calculated node with the metadata needed by paper, workbook and workbench readers. */
class ViewNode(
    val id: String,
    val kind: NodeKind,
    val label: String,
    val type: ValueType,
    val dims: List<String>,
    /** Signed contribution in the resolved section tree: +1, -1 or 0. */
    val op: Int,
    val userDefined: Boolean,
    val slotId: String?,
    val location: SourceLocation,
    val presentation: Presentation,
    val input: InputDecl? = null,
    val parameter: ParamDecl? = null,
    val parameterValue: Value? = null,
    val parameterSource: String? = null,
    val line: LineItem? = null,
    val total: TotalItem? = null,
    val choice: ChoiceItem? = null,
    val components: List<ViewComponent> = emptyList(),
    val guards: List<String> = emptyList(),
    val ownCondition: Formula? = null,
    val values: Map<Coord, Value>,
    val active: Map<Coord, Boolean>,
    val traces: Map<Coord, NodeTrace>,
) {
    fun value(coord: Coord = emptyList()): Value = values[coord] ?: Value.Nil
    fun isActive(coord: Coord = emptyList()): Boolean = active[coord] == true
    fun trace(coord: Coord = emptyList()): NodeTrace? = traces[coord]
    val anyActive: Boolean get() = active.values.any { it }

    /** Null for a declared nonadditive measure. */
    fun crossTotal(): BigDecimal? {
        if (line?.aggregate == AggregateRule.NONE && dims.isNotEmpty()) return null
        return values.values.fold(BigDecimal.ZERO) { sum, value -> sum + value.decimalOrZero() }
    }
}

/** Detached read-only projection of one calculation; planners and compiled expressions stay internal. */
class CalculationView private constructor(
    val schema: SchemaMeta,
    val case: CaseData,
    val structure: SchemaMap,
    val tree: ViewSection,
    val dimensions: Map<String, DimensionDecl>,
    val members: Map<String, List<Member>>,
    val nodes: Map<String, ViewNode>,
    val conditions: Map<String, ViewCondition>,
    val functions: List<FunctionDecl>,
    val diagnostics: List<Diagnostic>,
) {
    val succeeded: Boolean get() = diagnostics.none { it.severity == com.xqiou.mantra.core.Severity.ERROR }
    fun node(id: String): ViewNode = nodes[id] ?: throw NoSuchElementException("No calculated node `$id`")
    fun value(id: String, vararg coord: String): Value = node(id).value(coord.toList())
    fun decimal(id: String, vararg coord: String): BigDecimal = value(id, *coord).decimalOrZero()
    fun dimensionOrder(dims: Collection<String>): List<String> = dimensions.keys.filter { it in dims }

    /** Coordinates where all inherited section guards must be evaluated together. */
    fun alignGuards(node: ViewNode, coord: Coord, memberKeys: (String) -> List<String>): GuardAlignment {
        val fixed = node.dims.zip(coord).toMap()
        val extra = dimensionOrder(node.guards.flatMap { conditions.getValue(it).dims }.filter { it !in fixed }.toSet())
        val assignments = extra.fold(listOf(fixed)) { partial, dim ->
            partial.flatMap { assignment -> memberKeys(dim).map { assignment + (dim to it) } }
        }
        return GuardAlignment(extra, assignments)
    }

    companion object {
        fun of(plan: com.xqiou.mantra.core.engine.CalculationPlan): CalculationView = of(
            CalculationResult(
                plan, emptyMap(),
                plan.valueVertices.mapValues { (_, vertex) -> NodeResult(vertex, emptyMap(), emptyMap(), emptyMap()) },
                emptyList(),
            )
        )

        fun of(result: CalculationResult): CalculationView {
            val plan = result.plan
            val opByNode = linkedMapOf<String, Int>()
            fun collectOps(item: ResolvedItem) {
                when (item) {
                    is ResolvedSection -> item.children.forEach(::collectOps)
                    is ResolvedNode -> opByNode[item.id] = item.op
                    is ResolvedNote -> Unit
                }
            }
            collectOps(plan.tree)
            val slotByNode = linkedMapOf<String, String>()
            fun collectExtensions(item: Item, slotId: String) {
                when (item) {
                    is SectionItem -> item.children.forEach { collectExtensions(it, slotId) }
                    is NodeItem -> slotByNode[item.id] = slotId
                    else -> Unit
                }
            }
            plan.case.extensions.forEach { (slotId, items) -> items.forEach { collectExtensions(it, slotId) } }
            fun tree(item: ResolvedItem): ViewItem = when (item) {
                is ResolvedSection -> ViewSection(item.item, item.dims.toList(), item.children.map(::tree), item.resultId)
                is ResolvedNode -> ViewTreeNode(item.item, item.dims.toList(), item.op)
                is ResolvedNote -> ViewNote(item.item)
            }
            val nodes = result.nodes.mapValues { (_, calculated) ->
                val vertex: ValueVertex = calculated.vertex
                val line = vertex as? LineVertex
                val choice = vertex as? ChoiceVertex
                val total = vertex as? TotalVertex
                val input = vertex as? InputVertex
                val param = vertex as? ParamVertex
                val item = line?.item ?: choice?.item ?: total?.item
                val kind = when {
                    input != null -> NodeKind.INPUT
                    param != null -> NodeKind.PARAM
                    line?.item?.userDefined == true || choice?.item?.userDefined == true || total?.item?.userDefined == true -> NodeKind.EXTENSION
                    line?.item?.formulaSlot == true -> NodeKind.FORMULA_SLOT
                    line != null -> NodeKind.LINE
                    total != null -> NodeKind.TOTAL
                    choice != null -> NodeKind.CHOICE
                    else -> error("Unknown value vertex ${vertex.id}")
                }
                ViewNode(
                    id = vertex.id, kind = kind, label = vertex.label, type = vertex.type,
                    dims = vertex.dims.toList(), op = opByNode[vertex.id] ?: 0,
                    userDefined = item?.userDefined == true, slotId = slotByNode[vertex.id],
                    location = vertex.location,
                    presentation = item?.presentation ?: input?.decl?.presentation ?: param!!.decl.presentation,
                    input = input?.decl, parameter = param?.decl, parameterValue = param?.value,
                    parameterSource = param?.source, line = line?.item, total = total?.item,
                    choice = choice?.item, components = total?.components?.map { ViewComponent(it.vertexId, it.sign) }.orEmpty(),
                    guards = vertex.guards.toList(), ownCondition = vertex.ownCondition?.formula,
                    values = calculated.values.toMap(), active = calculated.active.toMap(), traces = calculated.traces.toMap(),
                )
            }
            return CalculationView(
                schema = plan.schema.meta, case = plan.case, structure = SchemaMaps.of(plan),
                tree = tree(plan.tree) as ViewSection, dimensions = plan.dimensions.toMap(),
                members = result.members.mapValues { it.value.toList() }, nodes = nodes,
                conditions = plan.vertices.values.filterIsInstance<ConditionVertex>().associate { it.id to ViewCondition(it.id, it.sectionId, it.dims.toList(), it.formula) },
                functions = (plan.schema.functions + plan.case.functions).toList(), diagnostics = result.diagnostics.toList(),
            )
        }
    }
}
