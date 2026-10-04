package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.api.RecalculationStats
import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Rounding
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.model.truthy
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.mantra.core.view.InputOrigin
import com.xqiou.mantra.core.view.Member
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.Reconciliation
import com.xqiou.mantra.core.view.TraceOption
import com.xqiou.mantra.core.view.TracePart
import com.xqiou.mantra.core.view.TraceRef
import com.xqiou.mantra.core.view.ValidationResult
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValueConstructionException
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Executes a [CalculationPlan] vertex by vertex in dependency order. */
internal class Evaluator(
    private var plan: CalculationPlan,
    private val sink: DiagnosticSink,
    private val explainTarget: Pair<String, Coord>? = null,
    auditOptions: AuditOptions? = null,
) : AutoCloseable {
    private val kernel = KernelExecution()
    private val inputs = KernelInputs({ plan }, sink)
    private val inputIdentity = MantraKernel.inputIdentity(plan.case.id, plan.case.source + "|" + plan.schema.id)
    private val members = linkedMapOf<String, List<Member>>()
    private data class MemberKeys(val domain: List<Member>, val keys: Set<String>)
    private val memberKeyCache = hashMapOf<String, MemberKeys>()
    private val values = hashMapOf<String, MutableMap<Coord, Value>>()
    private val active = hashMapOf<String, MutableMap<Coord, Boolean>>()
    private val periodDomains = linkedMapOf<String, ResolvedPeriodDimension>()
    private val reductions = ReductionService(
        { id -> reductionNode(plan.valueVertices.getValue(id)) },
        { members[it].orEmpty() },
        sink,
        { id ->
            plan.dimensions[id]?.parentDimension?.let { parent ->
                val column = plan.dimensions.getValue(id).parentKeyColumn ?: "parent-key"
                parent to members[id].orEmpty().mapNotNull { member ->
                    memberKey(member.record[column])?.let { member.key to it }
                }.toMap()
            }
        },
    )
    private val graph = MemberGraph(sink, { MemberPlanner.describe(plan, it) }, { MemberPlanner.location(plan, it) })
    private val spreadOutcomes = hashMapOf<Pair<String, Coord>, Outcome?>()
    private val spreadFailures = hashMapOf<Pair<String, Coord>, ExplainTrace?>()
    private val traces = hashMapOf<String, MutableMap<Coord, NodeTrace>>()
    private val guardValues = hashMapOf<String, MutableMap<Coord, Boolean>>()
    private var explainTrace: ExplainTrace? = null
    private val audit = AuditCapture(auditOptions, explainTarget, sink)
    private var lastFailedExplanation: ExplainTrace? = null
    private val runOwner = Any()
    private var invalidated = 0
    private var fullRebuild = true
    var lastRun = RecalculationStats(0, 0, 0, true, 0, 0)
        private set

    private class Outcome(val value: Value, val references: List<TraceRef>, val explainTrace: ExplainTrace?)

    fun run(): CalculationResult {
        val evaluatedBefore = graph.evaluations
        val formulasBefore = kernel.valueOnlyEvaluations + kernel.auditedEvaluations
        val retained = graph.completedTasks.size
        sink.removeOwned(setOf(runOwner))
        plan.dimensions.keys.forEach(::ensureDomain)
        for (vertex in plan.valueVertices.values) {
            coords(vertex.dims).forEach { ensureValue(vertex, it) }
        }
        plan.vertices.values.filterIsInstance<InputValidationVertex>().forEach { vertex ->
            coords(vertex.input.dims).forEach { coord ->
                graph.ensure(MemberTask.Validation(vertex.input.id, coord)) {
                    ensureValue(vertex.input, coord)
                    validateRequirements(vertex, coord)
                }
            }
        }
        sink.scoped(runOwner) { validateTableReferences() }
        val nodes = sink.scoped(runOwner) {
            plan.valueVertices.values.associate { vertex ->
                val reduction = reductions.reduce(vertex.id)
                val aggregateTrace = reduction.trace as? com.xqiou.mantra.core.view.RatioAggregateTrace
                vertex.id to NodeResult(
                    vertex,
                    values[vertex.id].orEmpty(),
                    active[vertex.id].orEmpty(),
                    traces[vertex.id].orEmpty(),
                    aggregateTrace?.result,
                    aggregateTrace,
                    reduction,
                )
            }
        }
        lastRun = RecalculationStats(
            graph.evaluations - evaluatedBefore,
            retained,
            invalidated,
            fullRebuild,
            kernel.valueOnlyEvaluations + kernel.auditedEvaluations - formulasBefore,
            kernel.sessionCount,
        )
        invalidated = 0
        fullRebuild = false
        return CalculationResult(plan, members, nodes, sink.all, explainTrace)
    }

    fun recalculate(next: CalculationPlan, planningDiagnostics: List<Diagnostic>): CalculationResult {
        check(explainTarget == null) { "Explain evaluators cannot be reused for editing" }
        val changed = IncrementalInvalidation.changed(plan, next, graph.completedTasks)
        val dirty = graph.invalidate(changed)
        invalidated = dirty.size
        sink.removeOwned(dirty)
        sink.replaceUnowned(planningDiagnostics)
        dirty.forEach { task ->
            when (task) {
                is MemberTask.Value -> {
                    values[task.id]?.remove(task.coord)
                    active[task.id]?.remove(task.coord)
                    traces[task.id]?.remove(task.coord)
                }
                is MemberTask.Domain -> {
                    members.remove(task.id)
                    periodDomains.remove(task.id)
                }
                is MemberTask.Guard -> guardValues[task.id]?.remove(task.coord)
                is MemberTask.Spread -> {
                    spreadOutcomes.remove(task.id to task.coord)
                    spreadFailures.remove(task.id to task.coord)
                }
                else -> Unit
            }
        }
        reductions.clear()
        plan = next
        return run()
    }

    // Dimensions and guards

    private fun ensureDomain(id: String) {
        graph.ensure(MemberTask.Domain(id)) {
            val vertex = plan.vertices.getValue(id) as DimensionVertex
            val decl = vertex.decl
            decl.parentDimension?.let(::ensureDomain)
            val periodSpec = decl.periods
            if (periodSpec != null) {
                val resolved = PeriodMemberResolver(sink).resolve(
                    id,
                    periodSpec,
                    decl.location,
                    decl.parentDimension?.let(periodDomains::get),
                )
                sink.throwIfStructuralErrors()
                periodDomains[id] = checkNotNull(resolved)
                members[id] = resolved.members
            } else {
                decl.fromTable?.let { ensureSlice(plan.valueVertices.getValue(it), emptyMap()) }
                members[id] = resolveMembers(vertex)
            }
        }
    }

    private fun ensureValue(vertex: ValueVertex, coord: Coord) {
        graph.ensure(MemberTask.Value(vertex.id, coord)) {
            vertex.dims.forEach(::ensureDomain)
            vertex.ratio?.let { ratio ->
                listOf(ratio.numerator, ratio.denominator).forEach { id ->
                    ensureValue(plan.valueVertices.getValue(id), coord)
                }
            }
            when (vertex) {
                is ParamVertex -> store(vertex, coord, vertex.value, NodeTrace.Param(vertex.source))
                is InputVertex -> bindInput(vertex, coord)
                is LineVertex -> evaluateLine(vertex, coord)
                is TotalVertex -> evaluateTotal(vertex, coord)
                is ChoiceVertex -> evaluateChoice(vertex, coord)
                is CheckVertex -> evaluateCheck(vertex, coord)
                is ReconcileVertex -> evaluateReconcile(vertex, coord)
            }
        }
    }

    private fun ensureSlice(vertex: ValueVertex, fixed: Map<String, String>) {
        val aligned = fixed.filterKeys { it in vertex.dims }.toMap()
        graph.ensure(MemberTask.Slice(vertex.id, aligned)) {
            coords(vertex.dims, aligned).forEach { ensureValue(vertex, it) }
        }
    }

    private fun reductionNode(vertex: ValueVertex): ReductionNode = ReductionNode(
        vertex.id, vertex.dims, vertex.type, vertex.aggregate, vertex.ratio, vertex.boundary,
        values[vertex.id].orEmpty(), active[vertex.id].orEmpty(), vertex.location,
        vertex.isValidation, vertex is TotalVertex, vertex.undefinedValues,
    )

    private fun memberKey(raw: Value?): String? = when (raw) {
        is Value.Kw -> raw.name
        is Value.Text -> raw.value
        is Value.Num -> raw.value.stripTrailingZeros().toPlainString()
        else -> null
    }

    private fun resolveMembers(vertex: DimensionVertex): List<Member> {
        val decl = vertex.decl
        val table = decl.fromTable
        if (table == null) {
            return decl.members.filter { member ->
                val condition = vertex.memberConditions[member.key] ?: return@filter true
                evaluate(condition, emptyList(), "${decl.id}.${member.key}")?.value?.truthy ?: false
            }.mapIndexed { index, member ->
                Member(
                    member.key,
                    member.label,
                    index,
                    mapOf(
                        "key" to Value.Kw(member.key),
                        "label" to Value.Text(member.label),
                        "index" to Value.num(index.toLong()),
                    ),
                )
            }
        }
        val rows = (values[table]?.get(emptyList()) as? Value.Vec)?.items.orEmpty()
        val seen = mutableSetOf<String>()
        return rows.mapIndexedNotNull { index, row ->
            val fields = (row as Value.MapV).entries.entries.associate { (k, v) -> (k as Value.Kw).name to v }
            val key = memberKey(fields[decl.keyColumn]) ?: run {
                sink.error(
                    "MANTRA-DIMENSION-KEY",
                    "Row ${index + 1} of $table has no :${decl.keyColumn} for dimension ${decl.id}",
                    decl.location,
                )
                return@mapIndexedNotNull null
            }
            if (!seen.add(key)) {
                sink.error("MANTRA-DIMENSION-KEY", "Duplicate member key `$key` in $table", decl.location)
                return@mapIndexedNotNull null
            }
            decl.parentDimension?.let { parent ->
                val parentKey = decl.parentKeyColumn?.let(fields::get)
                val target = memberKey(parentKey)
                if (target == null || members[parent].orEmpty().none { it.key == target }) {
                    sink.error(
                        "MANTRA-DIMENSION-PARENT",
                        "Member $key of ${decl.id} refers to ${target ?: "no key"} in parent dimension $parent, " +
                            "which has no such member",
                        decl.location,
                    )
                }
            }
            val label = decl.titleColumn?.let { (fields[it] as? Value.Text)?.value } ?: key
            Member(
                key,
                label,
                index,
                fields +
                    mapOf("key" to Value.Kw(key), "label" to Value.Text(label), "index" to Value.num(index.toLong())),
            )
        }
    }

    /** Validate declared foreign keys against the active members, after all dimensions are resolved. */
    private fun validateTableReferences() {
        plan.valueVertices.values.filterIsInstance<InputVertex>().filter {
            it.decl.references.isNotEmpty()
        }.forEach { input ->
            values[input.id].orEmpty().forEach { (coord, value) ->
                val rows = (value as? Value.Vec)?.items.orEmpty()
                rows.forEachIndexed { index, row ->
                    val fields = (row as? Value.MapV)?.entries.orEmpty()
                    input.decl.references.forEach reference@{ (column, target) ->
                        val raw = fields[Value.Kw(column)]
                        if (raw == null || raw == Value.Nil) return@reference
                        val key = memberKey(raw) ?: return@reference
                        if (members[target].orEmpty().none { it.key == key }) {
                            sink.error(
                                "MANTRA-INPUT-REFERENCE",
                                "Row ${index + 1} of ${input.id}${coordText(
                                    input.dims,
                                    coord,
                                )} :$column refers to `$key`, which is not a member of $target",
                                inputLocation(input.id, input.location, coord, index, column),
                                input.id,
                                coord,
                                rowIndex = index,
                                column = column,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun memberKeys(id: String): Set<String> {
        val domain = members[id].orEmpty()
        val cached = memberKeyCache[id]
        if (cached?.domain === domain) return cached.keys
        return domain.map { it.key }.toSet().also { memberKeyCache[id] = MemberKeys(domain, it) }
    }

    private fun coords(dims: List<String>, fixed: Map<String, String> = emptyMap()): List<Coord> {
        dims.forEach(::ensureDomain)
        return dims.fold(listOf(emptyList())) { acc, dim ->
            val selected = fixed[dim]
            val keys = if (selected == null) {
                members[dim].orEmpty().map { it.key }
            } else {
                if (selected in memberKeys(dim)) listOf(selected) else emptyList()
            }
            acc.flatMap { prefix -> keys.map { prefix + it } }
        }
    }

    private fun project(coord: Coord, from: List<String>, to: List<String>): Coord = to.map { coord[from.indexOf(it)] }

    private fun evaluateGuard(vertex: ConditionVertex, coord: Coord) {
        graph.ensure(MemberTask.Guard(vertex.id, coord)) {
            vertex.dims.forEach(::ensureDomain)
            val compiled = vertex.compiled ?: return@ensure
            guardValues.getOrPut(vertex.id) { linkedMapOf() }[coord] =
                evaluate(compiled, coord, vertex.sectionId)?.value?.truthy ?: false
        }
    }

    private fun guardsHold(vertex: ValueVertex, coord: Coord): Boolean {
        vertex.guards.forEach { id ->
            (plan.vertices.getValue(id) as ConditionVertex).dims.forEach(::ensureDomain)
        }
        val aligned = SectionGuards.align(plan, vertex, coord) { dim -> members[dim].orEmpty().map { it.key } }
        val inherited = aligned.assignments.any { assignment ->
            vertex.guards.all { guardId ->
                val guard = plan.vertices.getValue(guardId) as ConditionVertex
                val guardCoord = guard.dims.map(assignment::getValue)
                evaluateGuard(guard, guardCoord)
                guardValues[guardId]?.get(guardCoord) == true
            }
        }
        if (!inherited) return false
        val own = vertex.ownCondition ?: return true
        return evaluate(own, coord, vertex.id)?.value?.truthy ?: false
    }

    // Value vertices

    private fun store(vertex: ValueVertex, coord: Coord, value: Value, trace: NodeTrace, isActive: Boolean = true) {
        values.getOrPut(vertex.id) { linkedMapOf() }[coord] = value
        active.getOrPut(vertex.id) { linkedMapOf() }[coord] = isActive
        traces.getOrPut(vertex.id) { linkedMapOf() }[coord] = trace
    }

    private fun storeInactive(vertex: ValueVertex, coord: Coord, reason: String) =
        store(vertex, coord, neutral(vertex.type), NodeTrace.Inactive(reason), isActive = false)

    private fun neutral(type: ValueType): Value = if (type.isNumeric) Value.ZERO else Value.Nil

    private fun bindInput(vertex: InputVertex, at: Coord) {
        val decl = vertex.decl
        val supplied = plan.case.inputs[vertex.id]
        for (coord in listOf(at)) {
            if (!guardsHold(vertex, coord)) {
                storeInactive(vertex, coord, "condition not met")
                continue
            }
            val raw = if (vertex.dims.isEmpty()) supplied else lookup(supplied, coord)
            val source = plan.case.inputOrigins[vertex.id]?.get(coord.joinToString("/"))
            val (value, origin) = when {
                raw != null && raw != Value.Nil -> convertInput(raw, decl, coord) to
                    if (source == null) InputOrigin.CASE else InputOrigin.SOURCE
                decl.default != null && decl.default != Value.Nil -> convertInput(decl.default, decl, coord) to
                    InputOrigin.DEFAULT
                decl.optional -> Value.Nil to InputOrigin.DEFAULT
                decl.type.isNumeric -> Value.ZERO to InputOrigin.IMPLICIT
                decl.type == ValueType.TABLE -> Value.Vec(emptyList()) to InputOrigin.IMPLICIT
                decl.type == ValueType.BOOLEAN -> Value.Bool(false) to InputOrigin.IMPLICIT
                else -> {
                    // Inputs marked :required are reported by validateInput with a more specific code.
                    if (decl.presentation.attributes["required"] != Value.Bool(true) && decl.requiredWhen == null) {
                        sink.error(
                            "MANTRA-INPUT-MISSING",
                            "Required input ${decl.id}${coordText(vertex.dims, coord)} is missing",
                            decl.location,
                            decl.id,
                        )
                    }
                    Value.Nil to InputOrigin.IMPLICIT
                }
            }
            validateInput(vertex, coord, value, origin)
            store(vertex, coord, value, NodeTrace.Input(origin, source))
        }
    }

    /** Variable metadata of the schema (`:required`, `:min`, `:max`) checked against case data. */
    private fun validateInput(vertex: InputVertex, coord: Coord, value: Value, origin: InputOrigin) {
        val attributes = vertex.decl.presentation.attributes
        val where = "${vertex.id}${coordText(vertex.dims, coord)}"
        if (attributes["required"] == Value.Bool(true) &&
            (origin !in setOf(InputOrigin.CASE, InputOrigin.SOURCE) || isMissing(value))
        ) {
            sink.error(
                "MANTRA-INPUT-REQUIRED",
                "Input $where is required but was not supplied",
                inputLocation(vertex.id, vertex.location, coord),
                vertex.id,
                coord,
                category = DiagnosticCategory.BUSINESS,
            )
        }
        vertex.decl.minRows?.let { minimum ->
            val count = (value as? Value.Vec)?.items?.size ?: 0
            if (count < minimum) {
                sink.error(
                    "MANTRA-INPUT-MIN-ROWS",
                    "Input $where needs at least $minimum rows; found $count",
                    inputLocation(vertex.id, vertex.location, coord),
                    vertex.id,
                    coord,
                    category = DiagnosticCategory.BUSINESS,
                )
            }
        }
        val number = (value as? Value.Num)?.value ?: return
        (attributes["min"] as? Value.Num)?.value?.let { min ->
            if (number <
                min
            ) {
                sink.error(
                    "MANTRA-INPUT-RANGE",
                    "Input $where = ${number.toPlainString()} is below the minimum ${min.toPlainString()}",
                    inputLocation(vertex.id, vertex.location, coord),
                    vertex.id,
                    coord,
                    category = DiagnosticCategory.BUSINESS,
                )
            }
        }
        (attributes["max"] as? Value.Num)?.value?.let { max ->
            if (number >
                max
            ) {
                sink.error(
                    "MANTRA-INPUT-RANGE",
                    "Input $where = ${number.toPlainString()} is above the maximum ${max.toPlainString()}",
                    inputLocation(vertex.id, vertex.location, coord),
                    vertex.id,
                    coord,
                    category = DiagnosticCategory.BUSINESS,
                )
            }
        }
    }

    private fun lookup(supplied: Value?, coord: Coord): Value? {
        var current: Value = supplied ?: return null
        for (key in coord) {
            val map = current as? Value.MapV ?: return null
            current = map.entries[Value.Kw(key)] ?: map.entries[Value.Text(key)] ?: return null
        }
        return current
    }

    private fun isMissing(value: Value?): Boolean = value == null || value == Value.Nil ||
        value is Value.Text && value.value.isBlank()

    private fun validateRequirements(vertex: InputValidationVertex, at: Coord) {
        val input = vertex.input
        for (coord in listOf(at)) {
            if (active[input.id]?.get(coord) != true) continue
            val supplied = if (input.dims.isEmpty()) {
                plan.case.inputs[input.id]
            } else {
                lookup(
                    plan.case.inputs[input.id],
                    coord,
                )
            }
            val required = vertex.required?.let { evaluate(it, coord, input.id)?.value == Value.Bool(true) } ?: false
            if (required && isMissing(supplied) && input.decl.presentation.attributes["required"] != Value.Bool(true)) {
                sink.error(
                    "MANTRA-INPUT-REQUIRED",
                    "Input ${input.id}${coordText(input.dims, coord)} is required",
                    inputLocation(input.id, input.location, coord),
                    input.id,
                    coord,
                    category = DiagnosticCategory.BUSINESS,
                )
            }
            val rawRows = (supplied as? Value.Vec)?.items.orEmpty()
            val rows = (values[input.id]?.get(coord) as? Value.Vec)?.items.orEmpty()
            rows.forEachIndexed { index, row ->
                if (row !is Value.MapV) return@forEachIndexed
                vertex.columns.forEach { (column, formula) ->
                    val requiredColumn = evaluate(formula, coord, input.id, row = row)?.value == Value.Bool(true)
                    val raw = (rawRows.getOrNull(index) as? Value.MapV)?.entries
                    val value = raw?.get(Value.Kw(column)) ?: raw?.get(Value.Text(column))
                    if (requiredColumn && isMissing(value)) {
                        sink.error(
                            "MANTRA-INPUT-REQUIRED",
                            "Row ${index + 1} of ${input.id} requires :$column",
                            inputLocation(input.id, input.location, coord, index, column),
                            input.id,
                            coord,
                            category = DiagnosticCategory.BUSINESS,
                            rowIndex = index,
                            column = column,
                        )
                    }
                }
            }
        }
    }

    private fun evaluateCheck(vertex: CheckVertex, at: Coord) {
        val formula = vertex.compiled ?: return
        for (coord in listOf(at)) {
            if (!guardsHold(vertex, coord)) {
                store(
                    vertex,
                    coord,
                    Value.Nil,
                    NodeTrace.Validation(ValidationResult(null, vertex.item.severity)),
                    false,
                )
                continue
            }
            val outcome = evaluate(formula, coord, vertex.id)
            val passed = outcome?.let { (it.value as? Value.Bool)?.value ?: false }
            val trace = outcome?.explainTrace ?: lastFailedExplanation
            if (explainTarget == (vertex.id to coord)) explainTrace = trace
            store(
                vertex,
                coord,
                outcome?.value ?: Value.Nil,
                NodeTrace.Validation(ValidationResult(passed, vertex.item.severity), trace),
                outcome != null,
            )
            if (passed == false) {
                businessFinding(
                    vertex.item.severity,
                    "MANTRA-CHECK-FAILED",
                    vertex.label,
                    vertex.location,
                    vertex.id,
                    coord,
                )
            }
        }
    }

    private fun evaluateReconcile(vertex: ReconcileVertex, at: Coord) {
        for (coord in listOf(at)) {
            if (!guardsHold(vertex, coord)) {
                store(
                    vertex,
                    coord,
                    Value.Nil,
                    NodeTrace.Validation(ValidationResult(null, vertex.item.severity)),
                    false,
                )
                continue
            }
            val left = vertex.left?.let { evaluate(it, coord, vertex.id) }
            val leftTrace = left?.explainTrace ?: lastFailedExplanation
            val right = vertex.right?.let { evaluate(it, coord, vertex.id) }
            val rightTrace = right?.explainTrace ?: lastFailedExplanation
            val a = (left?.value as? Value.Num)?.value
            val b = (right?.value as? Value.Num)?.value
            val reconciliation = if (a != null && b != null) {
                Reconciliation(a, b, a - b, vertex.item.tolerance)
            } else {
                null
            }
            val passed = reconciliation?.let { it.difference.abs() <= it.tolerance }
            store(
                vertex,
                coord,
                reconciliation?.let { Value.Num(it.difference) } ?: Value.Nil,
                NodeTrace.Validation(
                    ValidationResult(passed, vertex.item.severity, reconciliation),
                    leftTrace,
                    rightTrace,
                ),
                reconciliation != null,
            )
            if (explainTarget == (vertex.id to coord)) {
                explainTrace = ExplainTrace(
                    leftTrace?.steps.orEmpty() + rightTrace?.steps.orEmpty(),
                    leftTrace?.branches.orEmpty() + rightTrace?.branches.orEmpty(),
                    leftTrace?.truncated == true || rightTrace?.truncated == true,
                )
            }
            if (passed == false) {
                businessFinding(
                    vertex.item.severity,
                    "MANTRA-RECONCILE-FAILED",
                    "${vertex.label}: difference ${reconciliation!!.difference.toPlainString()} " +
                        "exceeds tolerance ${vertex.item.tolerance}",
                    vertex.location,
                    vertex.id,
                    coord,
                )
            }
        }
    }

    private fun businessFinding(
        severity: Severity,
        code: String,
        message: String,
        location: SourceLocation,
        nodeId: String,
        coord: Coord,
    ) {
        sink.addAll(listOf(Diagnostic(severity, code, message, location, nodeId, coord, DiagnosticCategory.BUSINESS)))
    }

    private fun inputLocation(
        id: String,
        declaration: SourceLocation,
        coord: Coord = emptyList(),
        rowIndex: Int? = null,
        column: String? = null,
    ): SourceLocation {
        val cells = plan.case.inputCells[id].orEmpty()
        return cells.firstOrNull { it.coord == coord && it.rowIndex == rowIndex && it.column == column }?.location
            ?: cells.firstOrNull { it.coord == coord && it.rowIndex == rowIndex && it.column == null }?.location
            ?: plan.case.inputLocations[id] ?: declaration
    }

    private fun convertInput(
        raw: Value,
        decl: InputDecl,
        coord: Coord,
        rowIndex: Int? = null,
        column: String? = null,
    ): Value {
        fun fail(message: String): Value {
            sink.error(
                "MANTRA-INPUT-TYPE",
                "Input ${decl.id}${coordText(emptyList(), coord)}: $message",
                inputLocation(decl.id, decl.location, coord, rowIndex, column),
                decl.id,
                coord,
                rowIndex = rowIndex,
                column = column,
            )
            return Value.Nil
        }
        return when (decl.type) {
            ValueType.DECIMAL -> raw as? Value.Num ?: fail("expected a number, got $raw")
            ValueType.INTEGER -> (raw as? Value.Num)?.takeIf { it.value.stripTrailingZeros().scale() <= 0 }
                ?: fail("expected an integer, got $raw")
            ValueType.BOOLEAN -> raw as? Value.Bool ?: fail("expected true or false, got $raw")
            ValueType.TEXT -> raw as? Value.Text ?: fail("expected a string, got $raw")
            ValueType.KEYWORD -> {
                // Text from JSON/CSV sources is accepted as keyword name.
                val keyword =
                    raw as? Value.Kw ?: (raw as? Value.Text)?.let { Value.Kw(it.value.removePrefix(":")) }
                        ?: return fail("expected a keyword, got $raw")
                if (decl.options.isNotEmpty() &&
                    keyword.name !in decl.options
                ) {
                    fail("`$keyword` is not one of ${decl.options.keys.joinToString { ":$it" }}")
                } else {
                    keyword
                }
            }
            ValueType.DATE -> when (raw) {
                is Value.Date -> raw
                is Value.Text -> try {
                    Value.Date(LocalDate.parse(raw.value))
                } catch (_: DateTimeParseException) {
                    fail("expected an ISO date yyyy-mm-dd")
                }
                else -> fail("expected a date string")
            }
            ValueType.TABLE -> {
                val rows = raw as? Value.Vec ?: return fail("expected a vector of row maps")
                Value.Vec(rows.items.mapIndexed { index, row -> convertRow(row, decl, coord, index) })
            }
            ValueType.ANY -> raw
        }
    }

    private fun convertRow(row: Value, decl: InputDecl, coord: Coord, index: Int): Value {
        val map = row as? Value.MapV
        if (map == null) {
            sink.error(
                "MANTRA-INPUT-TYPE",
                "Row ${index + 1} of ${decl.id} must be a map",
                inputLocation(decl.id, decl.location, coord, index),
                decl.id,
                coord,
                rowIndex = index,
            )
            return Value.MapV(emptyMap())
        }
        val byName = map.entries.entries.associate { (k, v) ->
            (
                (k as? Value.Kw)?.name ?: (k as? Value.Text)?.value
                    ?: k.toString()
                ) to
                v
        }
        byName.keys.filter { key -> decl.columns.none { it.name == key } }.forEach {
            sink.error(
                "MANTRA-INPUT-COLUMN",
                "Row ${index + 1} of ${decl.id} has unknown column :$it",
                inputLocation(decl.id, decl.location, coord, index, it),
                decl.id,
                coord,
                rowIndex = index,
                column = it,
            )
        }
        val converted = linkedMapOf<Value, Value>()
        decl.columns.forEach { column ->
            val value = byName[column.name]
            val columnDecl = decl.copy(
                type = column.type,
                options = emptyMap(),
                optional = column.optional,
            )
            converted[Value.Kw(column.name)] = when {
                value != null && value != Value.Nil -> convertInput(value, columnDecl, coord, index, column.name)
                column.optional -> Value.Nil
                column.type.isNumeric -> Value.ZERO
                else -> {
                    sink.error(
                        "MANTRA-INPUT-COLUMN",
                        "Row ${index + 1} of ${decl.id} is missing column :${column.name}",
                        inputLocation(decl.id, decl.location, coord, index, column.name),
                        decl.id,
                        coord,
                        rowIndex = index,
                        column = column.name,
                    )
                    Value.Nil
                }
            }
        }
        return Value.MapV(converted)
    }

    private fun evaluateLine(vertex: LineVertex, coord: Coord) {
        val compiled = vertex.compiled ?: return
        val item = vertex.item
        if (!guardsHold(vertex, coord)) {
            storeInactive(vertex, coord, "condition not met")
            return
        }
        val outcome = if (item.spread) {
            val context = coord.dropLast(1)
            val key = vertex.id to context
            graph.ensure(MemberTask.Spread(vertex.id, context)) {
                spreadOutcomes[key] = evaluate(compiled, context, vertex.id)
                spreadFailures[key] = lastFailedExplanation
            }
            lastFailedExplanation = spreadFailures[key]
            spreadOutcomes[key]
        } else {
            evaluate(compiled, coord, vertex.id)
        }
        if (outcome == null) {
            store(vertex, coord, neutral(vertex.type), NodeTrace.Failed("formula failed", lastFailedExplanation), false)
            return
        }
        val raw = if (item.spread) {
            (outcome.value as? Value.MapV)?.entries?.get(Value.Kw(coord.last())) ?: Value.ZERO
        } else {
            outcome.value
        }
        store(
            vertex,
            coord,
            round(coerce(raw, vertex), item.rounding),
            NodeTrace.Computed(outcome.references, raw, item.rounding, item.spread, outcome.explainTrace),
        )
        if (explainTarget == (vertex.id to coord)) explainTrace = outcome.explainTrace
    }

    private fun evaluateTotal(vertex: TotalVertex, at: Coord) {
        for (coord in listOf(at)) {
            if (!guardsHold(vertex, coord)) {
                storeInactive(vertex, coord, "condition not met")
                continue
            }
            val parts = vertex.components.map { component ->
                val source = plan.valueVertices.getValue(component.vertexId)
                val fixed = vertex.dims.zip(coord).toMap()
                ensureSlice(source, fixed)
                val reduced = reductions.reduce(source.id, fixed)
                val aggregate = reduced.trace as? com.xqiou.mantra.core.view.RatioAggregateTrace
                val contribution = when {
                    aggregate?.undefinedReason == "no-active-members" -> BigDecimal.ZERO
                    reduced.value == null -> BigDecimal.ZERO
                    reduced.value == Value.Nil -> null
                    else -> (reduced.value as? Value.Num)?.value
                }
                TracePart(
                    component.vertexId,
                    component.sign,
                    contribution,
                    source.dims.size > vertex.dims.size,
                    aggregate,
                    reduced.trace,
                )
            }
            val sum = if (parts.any { it.value == null }) {
                null
            } else {
                parts.fold(BigDecimal.ZERO) { acc, part -> acc + part.value!!.multiply(BigDecimal(part.sign)) }
            }
            store(vertex, coord, sum?.let(Value::Num) ?: Value.Nil, NodeTrace.Sum(parts))
        }
    }

    private fun evaluateChoice(vertex: ChoiceVertex, at: Coord) {
        val item = vertex.item
        for (coord in listOf(at)) {
            if (!guardsHold(vertex, coord)) {
                storeInactive(vertex, coord, "condition not met")
                continue
            }
            val references = mutableListOf<TraceRef>()
            var undefinedCandidate = false
            val outcomes = vertex.options.map { option ->
                val condition = option.condition?.let { evaluate(it, coord, vertex.id) }
                val conditionTrace = if (option.condition !=
                    null
                ) {
                    condition?.explainTrace ?: lastFailedExplanation
                } else {
                    null
                }
                val available = option.condition == null || condition?.value?.truthy == true
                val result = if (available) evaluate(option.formula, coord, vertex.id) else null
                if (available && result?.value == Value.Nil) undefinedCandidate = true
                val optionTrace = if (available) result?.explainTrace ?: lastFailedExplanation else null
                result?.let { references += it.references }
                TraceOption(
                    option.option.key,
                    option.option.label,
                    result?.value ?: Value.Nil,
                    available && result?.value is Value.Num,
                    explanation = optionTrace,
                    conditionExplanation = conditionTrace,
                )
            }
            var selected: TraceOption? = null
            for (candidate in outcomes.filter { it.available }) {
                val current = selected
                val value = (candidate.value as Value.Num).value
                val better = current == null || when (item.rule) {
                    ChoiceRule.MIN -> value < (current.value as Value.Num).value
                    ChoiceRule.MAX -> value > (current.value as Value.Num).value
                }
                if (better) selected = candidate
            }
            val raw = selected?.value ?: if (undefinedCandidate && (vertex.boundary != null || vertex.ratio != null)) {
                Value.Nil
            } else {
                Value.ZERO
            }
            if (explainTarget == (vertex.id to coord)) {
                explainTrace = selected?.explanation
            }
            store(
                vertex,
                coord,
                round(raw, item.rounding),
                NodeTrace.Choice(outcomes, selected?.key, raw, item.rounding),
            )
        }
    }

    private fun coerce(value: Value, vertex: ValueVertex): Value {
        if (!vertex.type.isNumeric) return value
        if (value == Value.Nil && (
                vertex.ratio != null || vertex.boundary != null ||
                    vertex is LineVertex && vertex.compiled?.previousBindings?.isNotEmpty() == true
                )
        ) {
            return Value.Nil
        }
        return when (value) {
            is Value.Num -> value
            Value.Nil -> Value.ZERO
            else -> {
                sink.error(
                    "MANTRA-RESULT-TYPE",
                    "Formula of ${vertex.id} returned $value where a number is required",
                    vertex.location,
                    vertex.id,
                    category = DiagnosticCategory.EVALUATION,
                )
                Value.ZERO
            }
        }
    }

    private fun round(value: Value, rounding: Rounding?): Value = if (rounding != null &&
        value is Value.Num
    ) {
        Value.Num(value.value.setScale(rounding.scale, rounding.mode))
    } else {
        value
    }

    // Normein evaluation

    private fun rootValue(vertex: ValueVertex, contextDims: List<String>, coord: Coord): Value {
        ensureSlice(vertex, contextDims.zip(coord).toMap())
        val stored = values[vertex.id].orEmpty()
        val extra = vertex.dims.filter { it !in contextDims }
        if (extra.isEmpty()) return stored[project(coord, contextDims, vertex.dims)] ?: neutral(vertex.type)
        val fixed = contextDims.mapIndexed { index, dim -> dim to coord[index] }.toMap()
        fun build(remaining: List<String>, assignment: Map<String, String>): Value {
            if (remaining.isEmpty()) {
                return stored[vertex.dims.map { assignment.getValue(it) }] ?: neutral(vertex.type)
            }
            val dim = remaining.first()
            return Value.MapV(
                LinkedHashMap<Value, Value>().apply {
                    members[dim].orEmpty().forEach { member ->
                        put(
                            Value.Kw(member.key),
                            build(
                                remaining.drop(1),
                                assignment + (dim to member.key),
                            ),
                        )
                    }
                },
            )
        }
        return build(extra, fixed.filterKeys { it in vertex.dims })
    }

    private fun evaluate(
        formula: CompiledFormula,
        coord: Coord,
        nodeId: String,
        captureTrace: Boolean = true,
        row: Value.MapV? = null,
    ): Outcome? = try {
        lastFailedExplanation = null
        evaluateUnchecked(formula, coord, nodeId, captureTrace, row)
    } catch (failure: DslValueConstructionException) {
        sink.error(
            if (failure.violation.code.contains("LIMIT")) "MANTRA-VALUE-LIMIT" else "MANTRA-VALUE",
            "${failure.violation.code}: ${failure.violation}${coordText(formula.dims, coord)}",
            formula.formula.location,
            nodeId,
            coord,
            category = DiagnosticCategory.EVALUATION,
        )
        null
    }

    private fun evaluateUnchecked(
        formula: CompiledFormula,
        coord: Coord,
        nodeId: String,
        captureTrace: Boolean,
        row: Value.MapV?,
    ): Outcome? {
        val roots = mutableListOf<DslInputRootCandidate>()
        val references = mutableListOf<TraceRef>()
        val bindings = formula.previousBindings.associateBy { it.id }
        fun isFirst(id: Int): Boolean {
            val binding = bindings.getValue(id)
            ensureDomain(binding.periodDimension)
            val key = coord[formula.dims.indexOf(binding.periodDimension)]
            return periodDomains.getValue(binding.periodDimension).periods.first().key == key
        }
        val dependencies = formula.dependencies.filter { dependency ->
            dependency.firstFallbackGuards.all(::isFirst)
        }
        val currentRoots = dependencies.filter { it.kind == FormulaDependencyKind.CURRENT }.map { it.rootName }.toSet()
        val allRefs = dependencies.filter { it.kind == FormulaDependencyKind.ALL }.map { it.targetNodeId }.toSet()
        val dimensions = dependencies.filter { it.kind == FormulaDependencyKind.DIMENSION }
            .map { it.targetNodeId }.toSet()
        val relations = dependencies.filter { it.kind == FormulaDependencyKind.RELATION }.map { it.rootName }.toSet()
        for ((root, ref) in formula.rootNames) {
            if (root !in currentRoots) continue
            val vertex = plan.valueVertices.getValue(ref)
            val value = rootValue(vertex, formula.dims, coord)
            val extra = vertex.dims.count { it !in formula.dims }
            if (references.none { it.id == ref }) {
                references +=
                    TraceRef(ref, value, if (extra == 0) TraceRef.Kind.ALIGNED else TraceRef.Kind.MEMBER_MAP)
            }
            roots += DslInputRootCandidate(root, DslInputCandidate.ControlledValue(inputs.toDsl(vertex, value, extra)))
        }
        for (dim in formula.dimRefs.filter { it in dimensions }) {
            ensureDomain(dim)
            val key = coord[formula.dims.indexOf(dim)]
            val member = members[dim].orEmpty().first { it.key == key }
            references += TraceRef(dim, Value.Text(member.label), TraceRef.Kind.MEMBER)
            roots +=
                DslInputRootCandidate(
                    dim,
                    DslInputCandidate.ControlledValue(
                        inputs.structured(
                            inputs.hostRecord(member.record, inputs.recordTypes.getValue(dim)),
                            DslTypes.ref(plan.types.dimensionRecord.getValue(dim)),
                            "member $key of $dim",
                        ),
                    ),
                )
        }
        formula.relationRefs.forEach { (root, dim) ->
            if (root !in relations) return@forEach
            ensureDomain(dim)
            val column = plan.dimensions.getValue(dim).parentKeyColumn ?: "parent-key"
            val relation = Value.MapV(
                linkedMapOf<Value, Value>().apply {
                    members[dim].orEmpty().forEach { member ->
                        val parent = when (val raw = member.record[column]) {
                            is Value.Kw -> raw.name
                            is Value.Text -> raw.value
                            is Value.Num -> raw.value.toPlainString()
                            else -> null
                        }
                        if (parent != null) put(Value.Kw(member.key), Value.Kw(parent))
                    }
                },
            )
            references += TraceRef(root, relation, TraceRef.Kind.MEMBER_MAP)
            roots += DslInputRootCandidate(root, DslInputCandidate.ControlledValue(Values.toDsl(relation)))
        }
        if (allRefs.isNotEmpty()) {
            val host = linkedMapOf<String, Any?>()
            allRefs.forEach { ref ->
                val vertex = plan.valueVertices.getValue(ref)
                val full = rootValue(vertex, emptyList(), emptyList())
                references += TraceRef("all.$ref", full, TraceRef.Kind.ALL)
                host[ref] = inputs.toDsl(vertex, full, vertex.dims.size)
            }
            roots +=
                DslInputRootCandidate(
                    "all",
                    DslInputCandidate.ControlledValue(inputs.structured(host, DslTypes.ref(plan.types.all), "all")),
                )
        }
        dependencies.mapNotNull { it.previousBindingId }.distinct().forEach { id ->
            val binding = bindings.getValue(id)
            val first = isFirst(id)
            val prior = coord.toMutableList()
            val at = formula.dims.indexOf(binding.periodDimension)
            val domain = periodDomains.getValue(binding.periodDimension)
            val previous = if (first) null else domain.previous(coord[at])
            if (previous != null) prior[at] = previous.key
            val vertex = plan.valueVertices.getValue(binding.targetNodeId)
            val value = if (first) Value.Nil else rootValue(vertex, formula.dims, prior)
            val extra = vertex.dims.count { it !in formula.dims }
            roots += DslInputRootCandidate(
                binding.syntheticRoot,
                DslInputCandidate.ControlledValue(inputs.toDsl(vertex, value, extra)),
            )
            roots += DslInputRootCandidate(
                binding.firstPeriodRoot,
                DslInputCandidate.ControlledValue(Values.toDsl(Value.Bool(first))),
            )
            if (!first) {
                references += TraceRef(
                    binding.targetNodeId,
                    value,
                    TraceRef.Kind.PREVIOUS,
                    if (extra == 0) project(prior, formula.dims, vertex.dims) else null,
                    if (extra == 0) null else formula.dims.zip(prior).filter { it.first in vertex.dims }.toMap(),
                )
            }
        }
        if (formula.periodRefs.isNotEmpty()) {
            val host = formula.periodRefs.associateWith { id ->
                ensureDomain(id)
                mapOf("keys" to DslValues.sequential(members[id].orEmpty().map { Values.toDsl(Value.Kw(it.key)) }))
            }
            roots += DslInputRootCandidate(
                "periods",
                DslInputCandidate.ControlledValue(
                    inputs.structured(host, periodsRootType(plan.dimensions.values), "periods"),
                ),
            )
        }
        formula.rowTable?.let { table ->
            val input = plan.valueVertices.getValue(table)
            val fields = row?.entries.orEmpty().entries.associate { (key, value) ->
                ((key as? Value.Kw)?.name ?: (key as? Value.Text)?.value ?: key.toString()) to value
            }
            roots += DslInputRootCandidate(
                "row",
                DslInputCandidate.ControlledValue(
                    inputs.structured(
                        inputs.hostRecord(fields, inputs.rowTypes(input)),
                        DslTypes.ref(plan.types.tableRow.getValue(table)),
                        "row of $table",
                    ),
                ),
            )
        }
        val capture = audit.request(nodeId, coord, captureTrace)
        val outcome = kernel.evaluate(formula, roots, inputIdentity, capture)
        val explanation = audit.finish(capture, formula, outcome.trace, outcome.truncated, nodeId, coord)
        if (outcome.value != null) return Outcome(Values.fromDsl(outcome.value), references, explanation)
        lastFailedExplanation = explanation
        if (explainTarget == (nodeId to coord)) explainTrace = explanation
        outcome.diagnostics.forEach { diagnostic ->
            val span = diagnostic.span
            val base = formula.formula.location
            val location = span?.let {
                SourceLocation(base.source, it.line, it.column, it.startOffset, it.endOffset)
            } ?: base
            sink.error(
                "MANTRA-EVALUATION",
                "${diagnostic.code}: ${diagnostic.message}${coordText(formula.dims, coord)}",
                location,
                nodeId,
                coord,
                category = DiagnosticCategory.EVALUATION,
            )
        }
        return null
    }

    override fun close() = kernel.close()

    private fun coordText(dims: List<String>, coord: Coord): String = if (coord.isEmpty()) {
        ""
    } else {
        " [" +
            coord.mapIndexed { i, key -> "${dims.getOrElse(i) { "dim" }}=$key" }.joinToString(", ") +
            "]"
    }
}
