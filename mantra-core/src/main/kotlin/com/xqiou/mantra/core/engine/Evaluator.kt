package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Rounding
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.model.truthy
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValueConstructionResult
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Executes a [CalculationPlan] vertex by vertex in dependency order. */
internal class Evaluator(private val plan: CalculationPlan, private val sink: DiagnosticSink) {
    private val engine = DslEvaluationEngine()
    private val environment = MantraKernel.environment
    private val inputIdentity = MantraKernel.inputIdentity(plan.case.id, plan.case.source + "|" + plan.schema.id)
    private val members = linkedMapOf<String, List<Member>>()
    private val values = hashMapOf<String, MutableMap<Coord, Value>>()
    private val active = hashMapOf<String, MutableMap<Coord, Boolean>>()
    private val traces = hashMapOf<String, MutableMap<Coord, NodeTrace>>()
    private val guardValues = hashMapOf<String, MutableMap<Coord, Boolean>>()

    private class Outcome(val value: Value, val references: List<TraceRef>)

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
            }
        }
        validateTableReferences()
        val nodes = plan.valueVertices.values.associate { vertex ->
            vertex.id to NodeResult(
                vertex,
                values[vertex.id].orEmpty(),
                active[vertex.id].orEmpty(),
                traces[vertex.id].orEmpty(),
            )
        }
        return CalculationResult(plan, members, nodes, sink.all)
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
                Member(member.key, member.label, index, mapOf("key" to Value.Kw(member.key), "label" to Value.Text(member.label), "index" to Value.num(index.toLong())))
            }
        }
        val rows = (values[table]?.get(emptyList()) as? Value.Vec)?.items.orEmpty()
        val seen = mutableSetOf<String>()
        return rows.mapIndexedNotNull { index, row ->
            val fields = (row as Value.MapV).entries.entries.associate { (k, v) -> (k as Value.Kw).name to v }
            val key = memberKey(fields[decl.keyColumn]) ?: run {
                sink.error("MANTRA-DIMENSION-KEY", "Row ${index + 1} of $table has no :${decl.keyColumn} for dimension ${decl.id}", decl.location)
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
            Member(key, label, index, fields + mapOf("key" to Value.Kw(key), "label" to Value.Text(label), "index" to Value.num(index.toLong())))
        }
    }

    /** Validate declared foreign keys against the active members, after all dimensions are resolved. */
    private fun validateTableReferences() {
        plan.valueVertices.values.filterIsInstance<InputVertex>().filter { it.decl.references.isNotEmpty() }.forEach { input ->
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
                                "Row ${index + 1} of ${input.id}${coordText(input.dims, coord)} :$column refers to `$key`, which is not a member of $target",
                                inputLocation(input.id, input.location),
                                input.id,
                                coord,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun coords(dims: List<String>): List<Coord> =
        dims.fold(listOf(emptyList())) { acc, dim ->
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
            val (value, origin) = when {
                raw != null && raw != Value.Nil -> convertInput(raw, decl, coord) to InputOrigin.CASE
                decl.default != null && decl.default != Value.Nil -> convertInput(decl.default, decl, coord) to InputOrigin.DEFAULT
                decl.optional -> Value.Nil to InputOrigin.DEFAULT
                decl.type.isNumeric -> Value.ZERO to InputOrigin.IMPLICIT
                decl.type == ValueType.TABLE -> Value.Vec(emptyList()) to InputOrigin.IMPLICIT
                decl.type == ValueType.BOOLEAN -> Value.Bool(false) to InputOrigin.IMPLICIT
                else -> {
                    // Inputs marked :required are reported by validateInput with a more specific code.
                    if (decl.presentation.attributes["required"] != Value.Bool(true)) {
                        sink.error("MANTRA-INPUT-MISSING", "Required input ${decl.id}${coordText(vertex.dims, coord)} is missing", decl.location, decl.id)
                    }
                    Value.Nil to InputOrigin.IMPLICIT
                }
            }
            validateInput(vertex, coord, value, origin)
            store(vertex, coord, value, NodeTrace.Input(origin))
        }
    }

    /** Variable metadata of the schema (`:required`, `:min`, `:max`) checked against case data. */
    private fun validateInput(vertex: InputVertex, coord: Coord, value: Value, origin: InputOrigin) {
        val attributes = vertex.decl.presentation.attributes
        val where = "${vertex.id}${coordText(vertex.dims, coord)}"
        if (attributes["required"] == Value.Bool(true) && origin != InputOrigin.CASE) {
            sink.error("MANTRA-INPUT-REQUIRED", "Input $where is required but was not supplied", vertex.location, vertex.id, coord)
        }
        val number = (value as? Value.Num)?.value ?: return
        (attributes["min"] as? Value.Num)?.value?.let { min ->
            if (number < min) sink.error("MANTRA-INPUT-RANGE", "Input $where = ${number.toPlainString()} is below the minimum ${min.toPlainString()}", inputLocation(vertex.id, vertex.location), vertex.id, coord)
        }
        (attributes["max"] as? Value.Num)?.value?.let { max ->
            if (number > max) sink.error("MANTRA-INPUT-RANGE", "Input $where = ${number.toPlainString()} is above the maximum ${max.toPlainString()}", inputLocation(vertex.id, vertex.location), vertex.id, coord)
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

    private fun inputLocation(id: String, declaration: SourceLocation): SourceLocation =
        plan.case.inputLocations[id.substringBefore('[')] ?: declaration

    private fun convertInput(raw: Value, decl: InputDecl, coord: Coord): Value {
        fun fail(message: String): Value {
            sink.error("MANTRA-INPUT-TYPE", "Input ${decl.id}${coordText(emptyList(), coord)}: $message", inputLocation(decl.id, decl.location), decl.id, coord)
            return Value.Nil
        }
        return when (decl.type) {
            ValueType.DECIMAL -> raw as? Value.Num ?: fail("expected a number, got $raw")
            ValueType.INTEGER -> (raw as? Value.Num)?.takeIf { it.value.stripTrailingZeros().scale() <= 0 } ?: fail("expected an integer, got $raw")
            ValueType.BOOLEAN -> raw as? Value.Bool ?: fail("expected true or false, got $raw")
            ValueType.TEXT -> raw as? Value.Text ?: fail("expected a string, got $raw")
            ValueType.KEYWORD -> {
                // Text from JSON/CSV sources is accepted as keyword name.
                val keyword = raw as? Value.Kw ?: (raw as? Value.Text)?.let { Value.Kw(it.value.removePrefix(":")) } ?: return fail("expected a keyword, got $raw")
                if (decl.options.isNotEmpty() && keyword.name !in decl.options) fail("`$keyword` is not one of ${decl.options.keys.joinToString { ":$it" }}") else keyword
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
                Value.Vec(rows.items.mapIndexed { index, row -> convertRow(row, decl, index) })
            }
            ValueType.ANY -> raw
        }
    }

    private fun convertRow(row: Value, decl: InputDecl, index: Int): Value {
        val map = row as? Value.MapV
        if (map == null) {
            sink.error("MANTRA-INPUT-TYPE", "Row ${index + 1} of ${decl.id} must be a map", inputLocation(decl.id, decl.location), decl.id)
            return Value.MapV(emptyMap())
        }
        val byName = map.entries.entries.associate { (k, v) -> ((k as? Value.Kw)?.name ?: k.toString()) to v }
        byName.keys.filter { key -> decl.columns.none { it.name == key } }.forEach {
            sink.error("MANTRA-INPUT-COLUMN", "Row ${index + 1} of ${decl.id} has unknown column :$it", inputLocation(decl.id, decl.location), decl.id)
        }
        val converted = linkedMapOf<Value, Value>()
        decl.columns.forEach { column ->
            val value = byName[column.name]
            val columnDecl = decl.copy(id = "${decl.id}[${index + 1}].${column.name}", type = column.type, options = emptyMap(), optional = column.optional)
            converted[Value.Kw(column.name)] = when {
                value != null && value != Value.Nil -> convertInput(value, columnDecl, emptyList())
                column.optional -> Value.Nil
                column.type.isNumeric -> Value.ZERO
                else -> {
                    sink.error("MANTRA-INPUT-COLUMN", "Row ${index + 1} of ${decl.id} is missing column :${column.name}", inputLocation(decl.id, decl.location), decl.id)
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
                val map = outcome?.value as? Value.MapV
                for (member in members[spreadDim].orEmpty()) {
                    val coord = ctx + member.key
                    if (!guardsHold(vertex, coord)) {
                        storeInactive(vertex, coord, "condition not met")
                        continue
                    }
                    if (outcome == null) {
                        store(vertex, coord, neutral(vertex.type), NodeTrace.Failed("formula failed"), isActive = false)
                        continue
                    }
                    val raw = map?.entries?.get(Value.Kw(member.key)) ?: Value.ZERO
                    store(vertex, coord, round(coerce(raw, vertex), item.rounding), NodeTrace.Computed(outcome.references, raw, item.rounding, spread = true))
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
                store(vertex, coord, neutral(vertex.type), NodeTrace.Failed("formula failed"), isActive = false)
                continue
            }
            val value = round(coerce(outcome.value, vertex), item.rounding)
            store(vertex, coord, value, NodeTrace.Computed(outcome.references, outcome.value, item.rounding, spread = false))
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
                TracePart(component.vertexId, component.sign, contribution(source, vertex.dims, coord), source.dims.size > vertex.dims.size)
            }
            val sum = parts.fold(BigDecimal.ZERO) { acc, part -> acc + part.value.multiply(BigDecimal(part.sign)) }
            store(vertex, coord, Value.Num(sum), NodeTrace.Sum(parts))
        }
    }

    /** Value of [source] seen from context [dims] at [coord]; extra dimensions are cross-footed. */
    private fun contribution(source: ValueVertex, dims: List<String>, coord: Coord): BigDecimal {
        val stored = values[source.id].orEmpty()
        if (source.dims.size == dims.size) return stored[project(coord, dims, source.dims)].orZero()
        val fixed = dims.mapIndexed { index, dim -> dim to coord[index] }.toMap()
        return stored.entries.fold(BigDecimal.ZERO) { acc, (sourceCoord, value) ->
            val matches = source.dims.withIndex().all { (index, dim) -> fixed[dim]?.let { it == sourceCoord[index] } ?: true }
            if (matches) acc + value.orZero() else acc
        }
    }

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
                val available = option.condition?.let { evaluate(it, coord, vertex.id)?.value?.truthy ?: false } ?: true
                val result = if (available) evaluate(option.formula, coord, vertex.id) else null
                result?.let { references += it.references }
                TraceOption(option.option.key, option.option.label, result?.value ?: Value.Nil, available && result?.value is Value.Num)
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
            store(vertex, coord, round(raw, item.rounding), NodeTrace.Choice(outcomes, selected?.key, raw, item.rounding))
        }
    }

    private fun coerce(value: Value, vertex: ValueVertex): Value {
        if (!vertex.type.isNumeric) return value
        return when (value) {
            is Value.Num -> value
            Value.Nil -> Value.ZERO
            else -> {
                sink.error("MANTRA-RESULT-TYPE", "Formula of ${vertex.id} returned $value where a number is required", vertex.location, vertex.id)
                Value.ZERO
            }
        }
    }

    private fun round(value: Value, rounding: Rounding?): Value =
        if (rounding != null && value is Value.Num) Value.Num(value.value.setScale(rounding.scale, rounding.mode)) else value

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
                    members[dim].orEmpty().forEach { member -> put(Value.Kw(member.key), build(remaining.drop(1), assignment + (dim to member.key))) }
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
                value.items.map { row -> hostRecord((row as Value.MapV).entries.entries.associate { (k, v) -> (k as Value.Kw).name to v }, rowTypes(vertex)) },
                DslTypes.vector(DslTypes.ref(plan.types.tableRow.getValue(vertex.id))),
                vertex.id,
            )
            vertex.type == ValueType.INTEGER && value is Value.Num -> Values.toIntegerDsl(value)
            else -> Values.toDsl(value)
        }
    }

    private fun hostRecord(fields: Map<String, Value>, types: Map<String, ValueType>): Map<String, Any?> =
        fields.filterKeys { it in types }.mapValues { (name, v) ->
            when {
                v == Value.Nil -> null
                types[name] == ValueType.INTEGER && v is Value.Num -> Values.toIntegerDsl(v)
                else -> Values.toDsl(v)
            }
        }

    private val recordTypes: Map<String, Map<String, ValueType>> by lazy {
        val base = mapOf("key" to ValueType.KEYWORD, "label" to ValueType.TEXT, "index" to ValueType.INTEGER)
        plan.dimensions.values.associate { dim ->
            val columns = dim.fromTable?.let { table -> (plan.valueVertices[table] as? InputVertex)?.decl?.columns }.orEmpty()
            dim.id to (columns.associate { it.name to it.type } + base)
        }
    }

    private fun rowTypes(vertex: ValueVertex): Map<String, ValueType> =
        (vertex as? InputVertex)?.decl?.columns.orEmpty().associate { it.name to it.type }

    private fun structured(host: Any?, type: DslType, what: String): DslValue =
        when (val result = DslValues.importStructuredHost(host, type, plan.typeSchema)) {
            is DslValueConstructionResult.Success -> result.value
            is DslValueConstructionResult.Failure -> {
                sink.error("MANTRA-RECORD", "Cannot pass $what to the kernel: ${result.violation}")
                DslValue.Nil
            }
        }

    private fun evaluate(formula: CompiledFormula, coord: Coord, nodeId: String): Outcome? {
        val roots = mutableListOf<DslInputRootCandidate>()
        val references = mutableListOf<TraceRef>()
        for ((root, ref) in formula.rootNames) {
            val vertex = plan.valueVertices.getValue(ref)
            val value = rootValue(vertex, formula.dims, coord)
            val extra = vertex.dims.count { it !in formula.dims }
            if (references.none { it.id == ref }) references += TraceRef(ref, value, if (extra == 0) TraceRef.Kind.ALIGNED else TraceRef.Kind.MEMBER_MAP)
            roots += DslInputRootCandidate(root, DslInputCandidate.ControlledValue(toDsl(vertex, value, extra)))
        }
        for (dim in formula.dimRefs) {
            val key = coord[formula.dims.indexOf(dim)]
            val member = members[dim].orEmpty().first { it.key == key }
            references += TraceRef(dim, Value.Text(member.label), TraceRef.Kind.MEMBER)
            roots += DslInputRootCandidate(dim, DslInputCandidate.ControlledValue(structured(hostRecord(member.record, recordTypes.getValue(dim)), DslTypes.ref(plan.types.dimensionRecord.getValue(dim)), "member $key of $dim")))
        }
        formula.relationRefs.forEach { (root, dim) ->
            val column = plan.dimensions.getValue(dim).parentKeyColumn!!
            val relation = Value.MapV(linkedMapOf<Value, Value>().apply {
                members[dim].orEmpty().forEach { member ->
                    val parent = when (val raw = member.record[column]) {
                        is Value.Kw -> raw.name
                        is Value.Text -> raw.value
                        is Value.Num -> raw.value.toPlainString()
                        else -> null
                    }
                    if (parent != null) put(Value.Kw(member.key), Value.Kw(parent))
                }
            })
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
            roots += DslInputRootCandidate("all", DslInputCandidate.ControlledValue(structured(host, DslTypes.ref(plan.types.all), "all")))
        }
        val request = DslEvaluationRequest(
            expression = formula.expression,
            environment = environment,
            input = DslEvaluationInput(roots = roots, bindings = emptyList(), inputIdentity = inputIdentity),
            kernelArtifact = MantraKernel.kernelArtifact,
        )
        return when (val outcome = engine.evaluate(request)) {
            is DslEvaluationOutcome.Success -> Outcome(Values.fromDsl(outcome.value), references)
            is DslEvaluationOutcome.Failure -> {
                outcome.diagnostics.forEach { diagnostic ->
                    val span = diagnostic.span
                    val base = formula.formula.location
                    val location = when {
                        span == null -> base
                        span.line == 1 -> base.copy(column = base.column + span.column - 1)
                        else -> base.copy(line = base.line + span.line - 1, column = span.column)
                    }
                    sink.error(
                        "MANTRA-EVALUATION",
                        "${diagnostic.code}: ${diagnostic.message}${coordText(formula.dims, coord)}",
                        location,
                        nodeId,
                    )
                }
                null
            }
        }
    }

    private fun coordText(dims: List<String>, coord: Coord): String =
        if (coord.isEmpty()) "" else " [" + coord.mapIndexed { i, key -> "${dims.getOrElse(i) { "dim" }}=$key" }.joinToString(", ") + "]"
}
