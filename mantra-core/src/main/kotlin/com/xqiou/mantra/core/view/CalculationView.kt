package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.engine.CheckVertex
import com.xqiou.mantra.core.engine.ChoiceVertex
import com.xqiou.mantra.core.engine.ConditionVertex
import com.xqiou.mantra.core.engine.InputVertex
import com.xqiou.mantra.core.engine.LineVertex
import com.xqiou.mantra.core.engine.NodeResult
import com.xqiou.mantra.core.engine.ParamVertex
import com.xqiou.mantra.core.engine.ReconcileVertex
import com.xqiou.mantra.core.engine.ReductionNode
import com.xqiou.mantra.core.engine.ReductionService
import com.xqiou.mantra.core.engine.ResolvedItem
import com.xqiou.mantra.core.engine.ResolvedNode
import com.xqiou.mantra.core.engine.ResolvedNote
import com.xqiou.mantra.core.engine.ResolvedSection
import com.xqiou.mantra.core.engine.TotalVertex
import com.xqiou.mantra.core.engine.ValueVertex
import com.xqiou.mantra.core.engine.undefinedValues
import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.CheckItem
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
import com.xqiou.mantra.core.model.ReconcileItem
import com.xqiou.mantra.core.model.SchemaMeta
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.model.TotalItem
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.model.decimalOrZero
import com.xqiou.mantra.core.structure.SchemaMap
import com.xqiou.mantra.core.structure.SchemaMaps
import java.math.BigDecimal
import java.util.IdentityHashMap

/** Stable, presentation-facing classification. No planner vertex types cross this boundary. */
enum class NodeKind { INPUT, PARAM, LINE, TOTAL, CHOICE, FORMULA_SLOT, EXTENSION, CHECK, RECONCILE }

/** One section, calculation row or note in the resolved presentation tree. */
sealed interface ViewItem

/** A resolved section whose children preserve schema order and case extensions. */
class ViewSection(val item: SectionItem, val dims: List<String>, val children: List<ViewItem>, val resultId: String?) :
    ViewItem {
    val id: String get() = item.id
    val label: String get() = item.label
}

/** Placement of a node in the presentation tree, with its signed contribution. */
class ViewTreeNode(val item: NodeItem, val dims: List<String>, val op: Int) : ViewItem {
    val id: String get() = item.id
}

/** A descriptive note placed in the presentation tree. */
class ViewNote(val item: NoteItem) : ViewItem

/** Source formula and dimension context of an inherited section condition. */
data class ViewCondition(val id: String, val sectionId: String, val dims: List<String>, val formula: Formula)

/** A referenced node and its sign in a running total. */
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
    val parameterLayers: List<ParameterLayer> = emptyList(),
    val line: LineItem? = null,
    val total: TotalItem? = null,
    val choice: ChoiceItem? = null,
    val components: List<ViewComponent> = emptyList(),
    val guards: List<String> = emptyList(),
    val ownCondition: Formula? = null,
    val values: Map<Coord, Value>,
    val active: Map<Coord, Boolean>,
    val traces: Map<Coord, NodeTrace>,
    val check: CheckItem? = null,
    val reconcile: ReconcileItem? = null,
    val validations: Map<Coord, ValidationResult> = emptyMap(),
    val aggregateValue: BigDecimal? = null,
    /** Engine-owned weighted aggregation evidence; renderers never recompute its totals. */
    val aggregateTrace: RatioAggregateTrace? = null,
    val reduction: AggregationResult? = null,
    /** True when active nil is a real undefined calculation, rather than an optional zero input. */
    val undefinedValues: Boolean = false,
) {
    val aggregate: AggregateRule get() = input?.aggregate ?: line?.aggregate ?: total?.aggregate
        ?: choice?.aggregate ?: AggregateRule.NONE
    val ratio get() = input?.ratio ?: line?.ratio ?: total?.ratio ?: choice?.ratio
    val boundary get() = input?.boundary ?: line?.boundary ?: total?.boundary ?: choice?.boundary
    fun value(coord: Coord = emptyList()): Value = values[coord] ?: Value.Nil
    fun isActive(coord: Coord = emptyList()): Boolean = active[coord] == true
    fun trace(coord: Coord = emptyList()): NodeTrace? = traces[coord]
    val anyActive: Boolean get() = active.values.any { it }

    /** Null for a declared nonadditive measure. */
    fun crossTotal(): BigDecimal? {
        if (reduction != null) return (reduction.value as? Value.Num)?.value
        if (check != null || reconcile != null) return null
        if (line?.aggregate == AggregateRule.RATIO) return aggregateValue
        if (total != null && values.any { (coord, value) -> value == Value.Nil && isActive(coord) }) return null
        if (line?.aggregate == AggregateRule.NONE && dims.isNotEmpty()) return null
        return values.values.fold(BigDecimal.ZERO) { sum, value -> sum + value.decimalOrZero() }
    }
}

/** Read-only snapshot of one calculation; planners and compiled expressions stay internal. */
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
    /** Original formulas of application-declared hooks, before case bindings are applied. */
    val formulaSlotDefaults: Map<String, Formula>,
    val diagnostics: List<Diagnostic>,
    /** Total number of directed dependencies in the compiled calculation graph. */
    val dependencyCount: Int,
) {
    private val memberKeys by lazy { members.mapValues { (_, domain) -> domain.map { it.key }.toSet() } }
    private val reductions by lazy {
        ReductionService(
            { id ->
                val source = node(id)
                ReductionNode(
                    source.id, source.dims, source.type, source.aggregate, source.ratio, source.boundary,
                    source.values, source.active, source.location,
                    source.check != null || source.reconcile != null, source.total != null, source.undefinedValues,
                )
            },
            { id -> members[id].orEmpty() },
            parents = { id ->
                dimensions[id]?.parentDimension?.let { parent ->
                    val column = dimensions.getValue(id).parentKeyColumn ?: "parent-key"
                    parent to members[id].orEmpty().mapNotNull { member ->
                        when (val value = member.record[column]) {
                            is Value.Kw -> member.key to value.name
                            is Value.Text -> member.key to value.value
                            is Value.Num -> member.key to value.value.stripTrailingZeros().toPlainString()
                            else -> null
                        }
                    }.toMap()
                }
            },
        )
    }

    /** Exact configured reduction in a fixed coordinate scope. Unrelated axes broadcast the node. */
    fun reduce(nodeId: String, fixed: Map<String, String> = emptyMap()): AggregationResult {
        validateFixed(fixed)
        return reductions.reduce(nodeId, fixed)
    }

    /** Complete canonical coordinate, if the fixed child and ancestor assignments are consistent. */
    fun coordinate(nodeId: String, fixed: Map<String, String>): Coord? {
        validateFixed(fixed)
        return reductions.coordinate(nodeId, fixed)?.let(::frozenList)
    }

    /** All declared coordinates compatible with one fixed child/ancestor scope, in canonical order. */
    fun coordinates(nodeId: String, fixed: Map<String, String> = emptyMap()): List<Coord> {
        validateFixed(fixed)
        return frozenList(reductions.coordinates(nodeId, fixed).map(::frozenList))
    }

    private fun validateFixed(fixed: Map<String, String>) {
        fixed.forEach { (dimension, member) ->
            require(dimension in dimensions) { "Unknown dimension $dimension" }
            require(member in memberKeys[dimension].orEmpty()) { "Unknown member $member of $dimension" }
        }
    }
    val succeeded: Boolean get() = diagnostics.none {
        it.severity == com.xqiou.mantra.core.Severity.ERROR && it.category != DiagnosticCategory.BUSINESS
    }
    val validationPassed: Boolean get() = diagnostics.none {
        it.severity == com.xqiou.mantra.core.Severity.ERROR && it.category == DiagnosticCategory.BUSINESS
    }
    fun node(id: String): ViewNode = nodes[id] ?: throw NoSuchElementException("No calculated node `$id`")
    fun value(id: String, vararg coord: String): Value = node(id).value(coord.toList())
    fun decimal(id: String, vararg coord: String): BigDecimal = value(id, *coord).decimalOrZero()
    fun dimensionOrder(dims: Collection<String>): List<String> = dimensions.keys.filter { it in dims }

    /** True for an explicitly supplied case/source fact, including false and zero; nil/blank are absent. */
    fun inputProvided(id: String, coord: Coord = emptyList(), rowIndex: Int? = null, column: String? = null): Boolean {
        require(node(id).input != null) { "Node $id is not an input" }
        var current = case.inputs[id]
        coord.forEach { key ->
            val entries = (current as? Value.MapV)?.entries
            current = entries?.get(Value.Kw(key)) ?: entries?.get(Value.Text(key))
        }
        if (rowIndex != null) current = (current as? Value.Vec)?.items?.getOrNull(rowIndex)
        if (column != null) {
            val entries = (current as? Value.MapV)?.entries
            current = entries?.get(Value.Kw(column)) ?: entries?.get(Value.Text(column))
        }
        return current != null && current != Value.Nil &&
            (current !is Value.Text || !(current as Value.Text).value.isBlank())
    }

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
        internal fun of(plan: com.xqiou.mantra.core.engine.CalculationPlan): CalculationView = of(
            CalculationResult(
                plan,
                emptyMap(),
                plan.valueVertices.mapValues { (_, vertex) -> NodeResult(vertex, emptyMap(), emptyMap(), emptyMap()) },
                emptyList(),
            ),
        )

        /** Returns the read-only snapshot owned by [result]. */
        fun of(result: CalculationResult): CalculationView = result.view

        internal fun fromResult(result: CalculationResult): CalculationView {
            val plan = result.plan
            val traceSnapshots = IdentityHashMap<ExplainTrace, ExplainTrace>()
            fun traceSnapshot(trace: ExplainTrace): ExplainTrace = traceSnapshots.getOrPut(trace) { trace.snapshot() }
            val aggregateSnapshots = IdentityHashMap<RatioAggregateTrace, RatioAggregateTrace>()
            fun aggregateSnapshot(trace: RatioAggregateTrace): RatioAggregateTrace =
                aggregateSnapshots.getOrPut(trace) { trace.snapshot() }
            val formulaSlotDefaults = linkedMapOf<String, Formula>()
            fun collectFormulaSlots(item: com.xqiou.mantra.core.model.Item) {
                when (item) {
                    is SectionItem -> item.children.forEach(::collectFormulaSlots)
                    is LineItem -> if (item.formulaSlot) formulaSlotDefaults[item.id] = item.formula
                    else -> Unit
                }
            }
            collectFormulaSlots(plan.schema.root)
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
                is ResolvedSection -> ViewSection(
                    item.item.snapshot() as SectionItem,
                    frozenList(item.dims),
                    frozenList(item.children.map(::tree)),
                    item.resultId,
                )
                is ResolvedNode -> ViewTreeNode(item.item.snapshot() as NodeItem, frozenList(item.dims), item.op)
                is ResolvedNote -> ViewNote(item.item.snapshot() as NoteItem)
            }
            val nodes = result.rawNodes.mapValues { (_, calculated) ->
                val vertex: ValueVertex = calculated.vertex
                val line = vertex as? LineVertex
                val choice = vertex as? ChoiceVertex
                val total = vertex as? TotalVertex
                val input = vertex as? InputVertex
                val param = vertex as? ParamVertex
                val check = vertex as? CheckVertex
                val reconcile = vertex as? ReconcileVertex
                val item = line?.item ?: choice?.item ?: total?.item ?: check?.item ?: reconcile?.item
                val kind = when {
                    input != null -> NodeKind.INPUT
                    param != null -> NodeKind.PARAM
                    line?.item?.formulaSlot == true -> NodeKind.FORMULA_SLOT
                    line?.item?.userDefined == true || choice?.item?.userDefined == true ||
                        total?.item?.userDefined == true -> NodeKind.EXTENSION
                    line != null -> NodeKind.LINE
                    total != null -> NodeKind.TOTAL
                    choice != null -> NodeKind.CHOICE
                    check != null -> NodeKind.CHECK
                    reconcile != null -> NodeKind.RECONCILE
                    else -> error("Unknown value vertex ${vertex.id}")
                }
                val presentation = item?.presentation ?: input?.decl?.presentation ?: param!!.decl.presentation
                ViewNode(
                    id = vertex.id, kind = kind, label = vertex.label, type = vertex.type,
                    dims = frozenList(vertex.dims), op = opByNode[vertex.id] ?: 0,
                    userDefined = item?.userDefined == true, slotId = slotByNode[vertex.id],
                    location = vertex.location,
                    presentation = presentation.snapshot(),
                    input = input?.decl?.snapshot(),
                    parameter = param?.decl?.snapshot(),
                    parameterValue = param?.value?.snapshot(),
                    parameterSource = param?.source,
                    parameterLayers = frozenList(
                        param?.layers?.map {
                            it.copy(value = it.value?.snapshot())
                        }.orEmpty(),
                    ),
                    line = line?.item?.snapshot() as? LineItem,
                    total = total?.item?.snapshot() as? TotalItem, choice = choice?.item?.snapshot() as? ChoiceItem,
                    components = frozenList(total?.components?.map { ViewComponent(it.vertexId, it.sign) }.orEmpty()),
                    guards = frozenList(vertex.guards), ownCondition = vertex.ownCondition?.formula,
                    values = calculated.values.snapshotCoords { it.snapshot() },
                    active = calculated.active.snapshotCoords { it },
                    traces = calculated.traces.snapshotCoords { it.snapshot(::traceSnapshot, ::aggregateSnapshot) },
                    check = check?.item?.snapshot() as? CheckItem,
                    reconcile = reconcile?.item?.snapshot() as? ReconcileItem,
                    validations = calculated.traces.mapNotNull { (coord, trace) ->
                        (trace as? NodeTrace.Validation)?.let { coord to it.result }
                    }.toMap().snapshotCoords { it },
                    aggregateValue = calculated.aggregateValue,
                    aggregateTrace = calculated.aggregateTrace?.let(::aggregateSnapshot),
                    reduction = calculated.reduction?.let { reduction ->
                        reduction.copy(
                            value = reduction.value?.snapshot(),
                            trace = when (val generic = reduction.trace) {
                                is RatioAggregateTrace -> aggregateSnapshot(generic)
                                else -> generic?.snapshot()
                            },
                        )
                    },
                    undefinedValues = vertex.undefinedValues,
                )
            }
            return CalculationView(
                schema = plan.schema.meta.snapshot(), case = plan.case.snapshot(),
                structure = SchemaMaps.of(
                    plan,
                ).snapshot(),
                tree = tree(plan.tree) as ViewSection,
                dimensions = frozenMap(plan.dimensions.mapValues { (_, decl) -> decl.snapshot() }),
                members = frozenMap(
                    result.rawMembers.mapValues { (_, members) ->
                        frozenList(members.map { it.snapshot() })
                    },
                ),
                nodes = frozenMap(nodes),
                conditions = frozenMap(
                    plan.vertices.values.filterIsInstance<ConditionVertex>().associate {
                        it.id to
                            ViewCondition(it.id, it.sectionId, frozenList(it.dims), it.formula)
                    },
                ),
                functions = frozenList(plan.schema.functions + plan.case.functions),
                formulaSlotDefaults = frozenMap(formulaSlotDefaults),
                diagnostics = frozenList(result.diagnostics),
                dependencyCount = plan.vertices.values.sumOf { it.dependencies.size },
            )
        }
    }
}
