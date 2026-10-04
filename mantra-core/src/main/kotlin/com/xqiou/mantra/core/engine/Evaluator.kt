package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CalculationResult
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
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.trace.DslTraceLimits
import com.xqiou.normein.dsl.trace.DslTracePolicy
import com.xqiou.normein.dsl.trace.DslTraceStatus
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValueConstructionException
import com.xqiou.normein.dsl.value.DslValueConstructionResult
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Executes a [CalculationPlan] vertex by vertex in dependency order. */
internal class Evaluator(
    private val plan: CalculationPlan,
    private val sink: DiagnosticSink,
    private val explainTarget: Pair<String, Coord>? = null,
    auditOptions: AuditOptions? = null,
) {
    private val engine = DslEvaluationEngine()
    private val environment = MantraKernel.environment
    private val inputIdentity = MantraKernel.inputIdentity(plan.case.id, plan.case.source + "|" + plan.schema.id)
    private val members = linkedMapOf<String, List<Member>>()
    private val values = hashMapOf<String, MutableMap<Coord, Value>>()
    private val active = hashMapOf<String, MutableMap<Coord, Boolean>>()
    private val ratios = RatioAggregation(sink, { values[it].orEmpty() }, { id, at -> active[id]?.get(at) == true })
    private val traces = hashMapOf<String, MutableMap<Coord, NodeTrace>>()
    private val guardValues = hashMapOf<String, MutableMap<Coord, Boolean>>()
    private var explainTrace: ExplainTrace? = null
    private val audit = AuditCapture(auditOptions, explainTarget, sink)
    private var lastFailedExplanation: ExplainTrace? = null

    private class Outcome(val value: Value, val references: List<TraceRef>, val explainTrace: ExplainTrace?)

    fun run(): CalculationResult {
        for (vertex in plan.order) {
            when (vertex) {
                is DimensionVertex -> members[vertex.id] = resolveMembers(vertex)
                is ConditionVertex -> evaluateGuard(vertex)
                is ParamVertex -> store(vertex, emptyList(), vertex.value, NodeTrace.Param(vertex.source))
                is InputVertex -> bindInput(vertex)
                is LineVertex -> evaluateLine(vertex)
                is TotalVertex -> evaluateTotal(vertex)
                is ChoiceVertex -> evaluateChoice(vertex)
                is CheckVertex -> evaluateCheck(vertex)
                is ReconcileVertex -> evaluateReconcile(vertex)
                is InputValidationVertex -> validateRequirements(vertex)
            }
        }
        validateTableReferences()
        val nodes = plan.valueVertices.values.associate { vertex ->
            val aggregateTrace = if ((vertex as? LineVertex)?.item?.aggregate == AggregateRule.RATIO) {
                ratios.aggregate(vertex, emptyList(), emptyList())
            } else {
                null
            }
            vertex.id to NodeResult(
                vertex,
                values[vertex.id].orEmpty(),
                active[vertex.id].orEmpty(),
                traces[vertex.id].orEmpty(),
                aggregateTrace?.result,
                aggregateTrace,
            )
        }
        return CalculationResult(plan, members, nodes, sink.all, explainTrace)
    }

    // ── Dimensions and guards ──────────────────────────────────────────────────────────────────

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
                        "Member $key of ${decl.id} refers to ${target ?: "no key"} in parent dimension $parent, which has no such member",
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

    private fun coords(dims: List<String>): List<Coord> = dims.fold(listOf(emptyList())) { acc, dim ->
        val keys = members[dim].orEmpty().map { it.key }
        acc.flatMap { prefix -> keys.map { prefix + it } }
    }

    private fun project(coord: Coord, from: List<String>, to: List<String>): Coord = to.map { coord[from.indexOf(it)] }

    private fun evaluateGuard(vertex: ConditionVertex) {
        val target = guardValues.getOrPut(vertex.id) { linkedMapOf() }
        val compiled = vertex.compiled ?: return
        coords(vertex.dims).forEach { coord ->
            target[coord] = evaluate(compiled, coord, vertex.sectionId)?.value?.truthy ?: false
        }
    }

    private fun guardsHold(vertex: ValueVertex, coord: Coord): Boolean {
        val aligned = SectionGuards.align(plan, vertex, coord) { dim -> members[dim].orEmpty().map { it.key } }
        val inherited = aligned.assignments.any { assignment ->
            vertex.guards.all { guardId ->
                val guard = plan.vertices.getValue(guardId) as ConditionVertex
                guardValues[guardId]?.get(guard.dims.map(assignment::getValue)) == true
            }
        }
        if (!inherited) return false
        val own = vertex.ownCondition ?: return true
        return evaluate(own, coord, vertex.id)?.value?.truthy ?: false
    }

    // ── Value vertices ─────────────────────────────────────────────────────────────────────────

    private fun store(vertex: ValueVertex, coord: Coord, value: Value, trace: NodeTrace, isActive: Boolean = true) {
        values.getOrPut(vertex.id) { linkedMapOf() }[coord] = value
        active.getOrPut(vertex.id) { linkedMapOf() }[coord] = isActive
        traces.getOrPut(vertex.id) { linkedMapOf() }[coord] = trace
    }

    private fun storeInactive(vertex: ValueVertex, coord: Coord, reason: String) =
        store(vertex, coord, neutral(vertex.type), NodeTrace.Inactive(reason), isActive = false)

    private fun neutral(type: ValueType): Value = if (type.isNumeric) Value.ZERO else Value.Nil

    private fun bindInput(vertex: InputVertex) {
        val decl = vertex.decl
        val supplied = plan.case.inputs[vertex.id]
        for (coord in coords(vertex.dims)) {
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

    private fun validateRequirements(vertex: InputValidationVertex) {
        val input = vertex.input
        for (coord in coords(input.dims)) {
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

    private fun evaluateCheck(vertex: CheckVertex) {
        val formula = vertex.compiled ?: return
        for (coord in coords(vertex.dims)) {
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

    private fun evaluateReconcile(vertex: ReconcileVertex) {
        for (coord in coords(vertex.dims)) {
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
                    "${vertex.label}: difference ${reconciliation!!.difference.toPlainString()} exceeds tolerance ${vertex.item.tolerance}",
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

    private fun evaluateLine(vertex: LineVertex) {
        val compiled = vertex.compiled ?: return
        val item = vertex.item
        if (item.spread) {
            val context = vertex.dims.dropLast(1)
            val spreadDim = vertex.dims.last()
            for (ctx in coords(context)) {
                val outcome = evaluate(compiled, ctx, vertex.id)
                val failureTrace = lastFailedExplanation
                val map = outcome?.value as? Value.MapV
                for (member in members[spreadDim].orEmpty()) {
                    val coord = ctx + member.key
                    if (!guardsHold(vertex, coord)) {
                        storeInactive(vertex, coord, "condition not met")
                        continue
                    }
                    if (outcome == null) {
                        store(
                            vertex,
                            coord,
                            neutral(vertex.type),
                            NodeTrace.Failed("formula failed", failureTrace),
                            isActive = false,
                        )
                        continue
                    }
                    val raw = map?.entries?.get(Value.Kw(member.key)) ?: Value.ZERO
                    store(
                        vertex,
                        coord,
                        round(coerce(raw, vertex), item.rounding),
                        NodeTrace.Computed(
                            outcome.references,
                            raw,
                            item.rounding,
                            spread = true,
                            explanation = outcome.explainTrace,
                        ),
                    )
                    if (explainTarget == (vertex.id to coord)) explainTrace = outcome.explainTrace
                }
            }
            return
        }
        for (coord in coords(vertex.dims)) {
            if (!guardsHold(vertex, coord)) {
                storeInactive(vertex, coord, "condition not met")
                continue
            }
            val outcome = evaluate(compiled, coord, vertex.id)
            if (outcome == null) {
                store(
                    vertex,
                    coord,
                    neutral(vertex.type),
                    NodeTrace.Failed("formula failed", lastFailedExplanation),
                    isActive = false,
                )
                continue
            }
            val value = round(coerce(outcome.value, vertex), item.rounding)
            if (explainTarget == (vertex.id to coord)) explainTrace = outcome.explainTrace
            store(
                vertex,
                coord,
                value,
                NodeTrace.Computed(
                    outcome.references,
                    outcome.value,
                    item.rounding,
                    spread = false,
                    explanation = outcome.explainTrace,
                ),
            )
        }
    }

    private fun evaluateTotal(vertex: TotalVertex) {
        for (coord in coords(vertex.dims)) {
            if (!guardsHold(vertex, coord)) {
                storeInactive(vertex, coord, "condition not met")
                continue
            }
            val parts = vertex.components.map { component ->
                val source = plan.valueVertices.getValue(component.vertexId)
                val aggregate = if (source is LineVertex && source.item.ratio != null &&
                    source.dims.size > vertex.dims.size
                ) {
                    ratios.aggregate(source, vertex.dims, coord)
                } else {
                    null
                }
                TracePart(
                    component.vertexId,
                    component.sign,
                    when {
                        aggregate?.undefinedReason == "no-active-members" -> BigDecimal.ZERO
                        aggregate != null -> aggregate.result
                        else -> contribution(source, vertex.dims, coord)
                    },
                    source.dims.size > vertex.dims.size,
                    aggregate,
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

    /** Value of [source] seen from context [dims] at [coord]; extra dimensions are cross-footed. */
    private fun contribution(source: ValueVertex, dims: List<String>, coord: Coord): BigDecimal? {
        if (source is LineVertex && source.item.ratio != null && source.dims.size > dims.size) {
            val fixed = dims.zip(coord).toMap()
            val anyApplicable = active[source.id].orEmpty().any { (at, applies) ->
                applies &&
                    source.dims.withIndex().all { (index, dim) -> fixed[dim]?.let { it == at[index] } ?: true }
            }
            if (!anyApplicable) return BigDecimal.ZERO
            return ratios.aggregate(source, dims, coord).result
        }
        val stored = values[source.id].orEmpty()
        if (source.dims.size == dims.size) {
            val at = project(coord, dims, source.dims)
            return if (undefined(source, at, stored[at])) null else stored[at].orZero()
        }
        val fixed = dims.mapIndexed { index, dim -> dim to coord[index] }.toMap()
        val selected = stored.entries.filter { (sourceCoord, _) ->
            source.dims.withIndex().all { (index, dim) ->
                fixed[dim]?.let { it == sourceCoord[index] }
                    ?: true
            }
        }
        if (selected.any { (at, value) -> undefined(source, at, value) }) return null
        return selected.fold(BigDecimal.ZERO) { acc, (_, value) -> acc + value.orZero() }
    }

    private fun undefined(source: ValueVertex, coord: Coord, value: Value?): Boolean = value == Value.Nil &&
        active[source.id]?.get(coord) == true &&
        (source is TotalVertex || source is LineVertex && source.item.ratio != null)

    private fun Value?.orZero(): BigDecimal = (this as? Value.Num)?.value ?: BigDecimal.ZERO

    private fun evaluateChoice(vertex: ChoiceVertex) {
        val item = vertex.item
        for (coord in coords(vertex.dims)) {
            if (!guardsHold(vertex, coord)) {
                storeInactive(vertex, coord, "condition not met")
                continue
            }
            val references = mutableListOf<TraceRef>()
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
            val raw = selected?.value ?: Value.ZERO
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
        if (value == Value.Nil && vertex is LineVertex && vertex.item.ratio != null) return Value.Nil
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

    // ── Normein evaluation ─────────────────────────────────────────────────────────────────────

    private fun rootValue(vertex: ValueVertex, contextDims: List<String>, coord: Coord): Value {
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

    private fun toDsl(vertex: ValueVertex, value: Value, depth: Int): DslValue {
        if (depth > 0 && value is Value.MapV) {
            return DslValues.map(value.entries.map { (k, v) -> Values.toDsl(k) to toDsl(vertex, v, depth - 1) })
        }
        return when {
            vertex.type == ValueType.TABLE && value is Value.Vec -> structured(
                value.items.map { row ->
                    hostRecord(
                        (row as Value.MapV).entries.entries.associate { (k, v) ->
                            (k as Value.Kw).name to
                                v
                        },
                        rowTypes(vertex),
                    )
                },
                DslTypes.vector(DslTypes.ref(plan.types.tableRow.getValue(vertex.id))),
                vertex.id,
            )
            vertex.type == ValueType.INTEGER && value is Value.Num -> Values.toIntegerDsl(value)
            else -> Values.toDsl(value)
        }
    }

    private fun hostRecord(fields: Map<String, Value>, types: Map<String, ValueType>): Map<String, Any?> =
        fields.filterKeys {
            it in
                types
        }.mapValues { (name, v) ->
            when {
                v == Value.Nil -> null
                types[name] == ValueType.INTEGER && v is Value.Num -> Values.toIntegerDsl(v)
                else -> Values.toDsl(v)
            }
        }

    private val recordTypes: Map<String, Map<String, ValueType>> by lazy {
        val base = mapOf("key" to ValueType.KEYWORD, "label" to ValueType.TEXT, "index" to ValueType.INTEGER)
        plan.dimensions.values.associate { dim ->
            val columns = dim.fromTable?.let { table ->
                (plan.valueVertices[table] as? InputVertex)?.decl?.columns
            }.orEmpty()
            dim.id to (columns.associate { it.name to it.type } + base)
        }
    }

    private fun rowTypes(vertex: ValueVertex): Map<String, ValueType> =
        (vertex as? InputVertex)?.decl?.columns.orEmpty().associate {
            it.name to
                it.type
        }

    private fun structured(host: Any?, type: DslType, what: String): DslValue =
        when (val result = DslValues.importStructuredHost(host, type, plan.typeSchema)) {
            is DslValueConstructionResult.Success -> result.value
            is DslValueConstructionResult.Failure -> {
                sink.error("MANTRA-RECORD", "Cannot pass $what to the kernel: ${result.violation}")
                DslValue.Nil
            }
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
        for ((root, ref) in formula.rootNames) {
            val vertex = plan.valueVertices.getValue(ref)
            val value = rootValue(vertex, formula.dims, coord)
            val extra = vertex.dims.count { it !in formula.dims }
            if (references.none { it.id == ref }) {
                references +=
                    TraceRef(ref, value, if (extra == 0) TraceRef.Kind.ALIGNED else TraceRef.Kind.MEMBER_MAP)
            }
            roots += DslInputRootCandidate(root, DslInputCandidate.ControlledValue(toDsl(vertex, value, extra)))
        }
        for (dim in formula.dimRefs) {
            val key = coord[formula.dims.indexOf(dim)]
            val member = members[dim].orEmpty().first { it.key == key }
            references += TraceRef(dim, Value.Text(member.label), TraceRef.Kind.MEMBER)
            roots +=
                DslInputRootCandidate(
                    dim,
                    DslInputCandidate.ControlledValue(
                        structured(
                            hostRecord(member.record, recordTypes.getValue(dim)),
                            DslTypes.ref(plan.types.dimensionRecord.getValue(dim)),
                            "member $key of $dim",
                        ),
                    ),
                )
        }
        formula.relationRefs.forEach { (root, dim) ->
            val column = plan.dimensions.getValue(dim).parentKeyColumn!!
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
        if (formula.allRefs.isNotEmpty()) {
            val host = linkedMapOf<String, Any?>()
            formula.allRefs.forEach { ref ->
                val vertex = plan.valueVertices.getValue(ref)
                val full = rootValue(vertex, emptyList(), emptyList())
                references += TraceRef("all.$ref", full, TraceRef.Kind.ALL)
                host[ref] = toDsl(vertex, full, vertex.dims.size)
            }
            roots +=
                DslInputRootCandidate(
                    "all",
                    DslInputCandidate.ControlledValue(structured(host, DslTypes.ref(plan.types.all), "all")),
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
                    structured(
                        hostRecord(fields, rowTypes(input)),
                        DslTypes.ref(plan.types.tableRow.getValue(table)),
                        "row of $table",
                    ),
                ),
            )
        }
        val capture = audit.request(nodeId, coord, captureTrace)
        val request = DslEvaluationRequest(
            expression = formula.expression,
            environment = environment,
            input = DslEvaluationInput(
                roots = roots,
                bindings = emptyList(),
                inputIdentity = inputIdentity,
                tracePolicy = if (capture?.enabled == true) {
                    DslTracePolicy.FULL
                } else {
                    DslTracePolicy.NONE
                },
            ),
            kernelArtifact = MantraKernel.kernelArtifact,
            traceLimits = capture?.kernelLimits ?: DslTraceLimits(),
        )
        return when (val outcome = engine.evaluate(request)) {
            is DslEvaluationOutcome.Success -> Outcome(
                Values.fromDsl(outcome.value),
                references,
                audit.finish(
                    capture,
                    formula,
                    outcome.trace,
                    outcome.receipt.traceStatus == DslTraceStatus.TRUNCATED,
                    nodeId,
                    coord,
                ),
            )
            is DslEvaluationOutcome.Failure -> {
                lastFailedExplanation = audit.finish(
                    capture,
                    formula,
                    outcome.partialTrace,
                    outcome.receipt.traceStatus == DslTraceStatus.TRUNCATED,
                    nodeId,
                    coord,
                )
                if (explainTarget == (nodeId to coord)) explainTrace = lastFailedExplanation
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
                null
            }
        }
    }

    private fun coordText(dims: List<String>, coord: Coord): String = if (coord.isEmpty()) {
        ""
    } else {
        " [" +
            coord.mapIndexed { i, key -> "${dims.getOrElse(i) { "dim" }}=$key" }.joinToString(", ") +
            "]"
    }
}
