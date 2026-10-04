package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.BoundaryAggregation
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewNode

/** Mirrors engine reduction policy using live cells and explicit declared period order. */
internal fun ExcelWorkbookBuilder.reductionFormula(node: ViewNode, fixed: Map<String, String>): X.Scalar? =
    reductionExpressions.getOrPut(node.id to fixed.toMap()) { buildReductionFormula(node, fixed) }

private fun ExcelWorkbookBuilder.buildReductionFormula(node: ViewNode, fixed: Map<String, String>): X.Scalar? {
    if (!node.type.isNumeric || node.check != null || node.reconcile != null) return null
    val contextDims = node.dims.filter { it in fixed }
    val coord = contextDims.map(fixed::getValue)
    if (contextDims.size == node.dims.size) {
        val value = reference(node.id, contextDims, coord) as? X.Scalar ?: return null
        val scope = conjunction(node.dims.mapIndexed { index, axis -> reductionScope(axis, coord[index], fixed) })
        return if (scope == Ex.TRUE) value else Ex.iff(scope, value, Ex.ZERO)
    }
    if (node.aggregate == AggregateRule.NONE) return null
    val included = reductionMembers(node, fixed)
    fun component(id: String): X.Scalar {
        // Only a complete, statically unconditional member scope may use its contiguous range.
        // Editable guards, parent relations and period boundary selections retain live terms.
        val range = rangeNames[id]
        if (range != null && view.nodes[id]?.dims == node.dims && included.isNotEmpty() &&
            included.all { it.second == Ex.TRUE } && included.map { it.first } == nodeSlots[id]?.keys?.toList()
        ) {
            return Ex.fn("SUM", Ex.atom(range))
        }
        return boundedReductionSum(
            included.map { (at, condition) ->
                val value = reference(id, node.dims, at) as? X.Scalar
                    ?: throw Untranslatable("$id lacks a cell for $at")
                if (condition == Ex.TRUE) value else Ex.iff(condition, value, Ex.ZERO)
            },
        )
    }
    node.line?.ratio?.let { ratio ->
        val numerator = compactReduction(component(ratio.numerator))
        val denominator = compactReduction(component(ratio.denominator))
        val quotient = Ex.div(numerator, denominator)
        val value = ratio.rounding?.let { Ex.round(quotient, Ex.num(it.scale.toLong()), it.mode) } ?: quotient
        return Ex.iff(Ex.cmp("=", denominator, Ex.ZERO), Ex.EMPTY, value)
    }
    val sum = component(node.id)
    if (node.boundary == null && node.kind != NodeKind.TOTAL && !node.undefinedValues) return sum
    val defined = included.map { (at, condition) ->
        val value = reference(node.id, node.dims, at) as X.Scalar
        Ex.iff(condition, Ex.fn("ISNUMBER", value, kind = XKind.BOOL), Ex.TRUE)
    }
    return if (defined.isEmpty()) {
        sum
    } else {
        Ex.iff(
            compactReduction(boundedReductionBoolean("AND", defined)),
            compactReduction(sum),
            Ex.EMPTY,
        )
    }
}

/** Selected period is decided by declared scope membership before applying value activity. */
private fun ExcelWorkbookBuilder.reductionMembers(
    node: ViewNode,
    fixed: Map<String, String>,
): List<Pair<Coord, X.Scalar>> {
    val related = node.dims.flatMap { axis ->
        generateSequence(axis) { view.dimensions[it]?.parentDimension }.toList()
    }.toSet()
    val aligned = fixed.filterKeys { it in related }
    aligned.forEach { (axis, key) ->
        if (members[axis].orEmpty().none { it.key == key }) throw Untranslatable("unknown member $key of $axis")
    }
    val boundary = node.boundary?.takeIf { it.dimension !in aligned }
    val selection = boundary?.let { rule ->
        var prior: X.Scalar = Ex.FALSE
        val keys = members[rule.dimension].orEmpty().map { it.key }
            .let { if (rule.boundary == BoundaryAggregation.Boundary.FIRST) it else it.reversed() }
        keys.associateWith { key ->
            val inScope = reductionScope(rule.dimension, key, aligned)
            val withoutPrior = when (prior) {
                Ex.FALSE -> Ex.TRUE
                Ex.TRUE -> Ex.FALSE
                else -> Ex.fn("NOT", prior, kind = XKind.BOOL)
            }
            val selected = conjunction(listOf(inScope, withoutPrior))
            prior = disjunction(listOf(prior, inScope))
                .let { if (it.text.length > 1000) materializeExpression(it) else it }
            selected
        }
    }.orEmpty()
    return nodeSlots[node.id]?.keys.orEmpty().mapNotNull { at ->
        val scope = conjunction(node.dims.mapIndexed { index, axis -> reductionScope(axis, at[index], aligned) })
        val selected = boundary?.let { selection.getValue(at[node.dims.indexOf(it.dimension)]) } ?: Ex.TRUE
        if (scope == Ex.FALSE ||
            selected == Ex.FALSE
        ) {
            null
        } else {
            at to conjunction(listOf(scope, selected, applicable(node, at)))
        }
    }
}

/** Ancestor filters retain editable parent relations, including multi-level hierarchies. */
private fun ExcelWorkbookBuilder.reductionScope(axis: String, member: String, fixed: Map<String, String>): X.Scalar {
    var dimension = axis
    var key: X.Scalar = Ex.text(member)
    val tests = mutableListOf<X.Scalar>()
    while (true) {
        fixed[dimension]?.let { wanted ->
            tests += if (key.kind == XKind.TEXT && key.text.startsWith('"')) {
                if (key == Ex.text(wanted)) Ex.TRUE else Ex.FALSE
            } else {
                Ex.cmp("=", key, Ex.text(wanted))
            }
        }
        val declaration = view.dimensions[dimension] ?: break
        val parent = declaration.parentDimension ?: break
        val column = declaration.parentKeyColumn ?: "parent-key"
        val literalKey = key.text.takeIf { key.kind == XKind.TEXT && it.startsWith('"') && it.endsWith('"') }
            ?.removeSurrounding("\"")?.replace("\"\"", "\"")
        key = if (literalKey != null) {
            translator.toScalar(
                record(dimension, literalKey, column)
                    ?: throw Untranslatable("missing parent relation $dimension.$column"),
            )
        } else {
            members[dimension].orEmpty().foldRight(Ex.EMPTY) { candidate, otherwise ->
                val parentKey = translator.toScalar(
                    record(dimension, candidate.key, column)
                        ?: throw Untranslatable("missing parent relation $dimension.$column"),
                )
                Ex.iff(Ex.cmp("=", key, Ex.text(candidate.key)), parentKey, otherwise)
            }
        }
        dimension = parent
    }
    return conjunction(tests)
}

private fun conjunction(values: List<X.Scalar>): X.Scalar {
    if (Ex.FALSE in values) return Ex.FALSE
    val terms = values.filter { it != Ex.TRUE }.distinct()
    return when (terms.size) {
        0 -> Ex.TRUE
        1 -> terms.single()
        else -> Ex.fn("AND", terms, XKind.BOOL)
    }
}

private fun disjunction(values: List<X.Scalar>): X.Scalar {
    if (Ex.TRUE in values) return Ex.TRUE
    val terms = values.filter { it != Ex.FALSE }.distinct()
    return when (terms.size) {
        0 -> Ex.FALSE
        1 -> terms.single()
        else -> Ex.fn("OR", terms, XKind.BOOL)
    }
}

internal fun ExcelWorkbookBuilder.reductionHasActiveMembers(node: ViewNode, fixed: Map<String, String>): X.Scalar =
    boundedReductionBoolean("OR", reductionMembers(node, fixed).map { it.second })

private fun ExcelWorkbookBuilder.compactReduction(value: X.Scalar): X.Scalar =
    if (value.text.length > 2500) materializeExpression(value) else value

/** Scratch cells bound length, argument count and nesting independently of the member count. */
internal fun ExcelWorkbookBuilder.boundedReductionBoolean(operation: String, terms: List<X.Scalar>): X.Scalar {
    require(operation == "AND" || operation == "OR")
    if (terms.isEmpty()) return if (operation == "AND") Ex.TRUE else Ex.FALSE
    if (terms.size == 1) return terms.single()
    val expression = Ex.fn(operation, terms, XKind.BOOL)
    if (terms.size <= 200 && expression.text.length < 6000) return expression
    return boundedReductionBoolean(operation, reductionChunks(terms, operation, XKind.BOOL))
}

private fun ExcelWorkbookBuilder.boundedReductionSum(terms: List<X.Scalar>): X.Scalar {
    val result = Ex.fn("SUM", terms.ifEmpty { listOf(Ex.ZERO) })
    if (result.text.length < 6000) return result
    return boundedReductionSum(reductionChunks(terms, "SUM", XKind.NUM))
}

private fun ExcelWorkbookBuilder.reductionChunks(
    terms: List<X.Scalar>,
    operation: String,
    kind: XKind,
): List<X.Scalar> {
    val cells = mutableListOf<X.Scalar>()
    var chunk = mutableListOf<X.Scalar>()
    var length = 0
    for (original in terms) {
        val term = if (original.text.length > 1000) materializeExpression(original) else original
        if (length + term.text.length > 5000 || chunk.size >= 200) {
            cells += materializeExpression(Ex.fn(operation, chunk, kind))
            chunk = mutableListOf()
            length = 0
        }
        chunk += term
        length += term.text.length + 1
    }
    if (chunk.isNotEmpty()) cells += materializeExpression(Ex.fn(operation, chunk, kind))
    return cells
}
