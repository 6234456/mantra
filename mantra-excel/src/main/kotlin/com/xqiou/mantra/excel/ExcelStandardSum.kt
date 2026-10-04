package com.xqiou.mantra.excel

import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.string
import com.xqiou.normein.dsl.DslAmountParser
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** Stdlib sum scans one sequence level, parses amount text, and returns nil without numeric items. */
internal fun standardSum(source: X, form: DslForm, materialize: (X.Scalar) -> X.Scalar): X.Scalar {
    if (source is X.Branches) {
        val default = source.cases.lastOrNull { it.first == null }?.second
            ?.let { standardSum(it, form, materialize) } ?: Ex.EMPTY
        return source.cases.filter { it.first != null }.foldRight(default) { (condition, value), rest ->
            Ex.iff(condition!!, standardSum(value, form, materialize), rest)
        }
    }
    val literalItems = (form as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }?.values
    val items = when (source) {
        is X.Vec -> source.items
        X.Nil, is X.MapX, is X.Range -> emptyList()
        is X.Scalar -> if (source.kind == XKind.TEXT && form.string != null) {
            emptyList()
        } else {
            throw Untranslatable("sum source is not a sequence")
        }
        else -> throw Untranslatable("sum source shape is unavailable")
    }
    fun compact(value: X.Scalar): X.Scalar =
        if (value.text.length > 500 && safeAggregateExpression(value.text)) materialize(value) else value
    fun contribution(item: X, literal: DslForm? = null): SumContribution {
        if (item is X.Branches) {
            val default = item.cases.lastOrNull { it.first == null }?.second?.let { contribution(it) }
                ?: SumContribution(Ex.ZERO, Ex.ZERO)
            return item.cases.filter { it.first != null }.foldRight(default) { (condition, value), rest ->
                val selected = contribution(value)
                SumContribution(
                    Ex.iff(condition!!, selected.value, rest.value),
                    Ex.iff(condition, selected.count, rest.count),
                )
            }
        }
        val numeric = when (item) {
            is X.Scalar -> when {
                item.kind == XKind.BOOL || item.kind == XKind.DATE -> null
                item.kind == XKind.TEXT -> {
                    when {
                        literal?.keyword != null -> null
                        literal?.string != null -> DslAmountParser.parseAmountLiteral(literal.string)?.let(Ex::num)
                        else -> throw Untranslatable(
                            "sum of dynamic amount text has no locale-neutral Excel translation",
                        )
                    }
                }
                item.numericOrNil -> item.takeUnless { it == Ex.EMPTY }
                else -> throw Untranslatable("sum item may contain dynamically parsed amount text")
            }
            // Nested collections are items, not additional sequences to flatten.
            else -> null
        }
        if (numeric == null) {
            val checked = ignoredSumErrors(item)
            return SumContribution(checked, checked)
        }
        val value = compact(numeric)
        return SumContribution(
            compact(Ex.iff(Ex.cmp("=", value, Ex.EMPTY), Ex.ZERO, value)),
            compact(Ex.iff(Ex.cmp("=", value, Ex.EMPTY), Ex.ZERO, Ex.num(1))),
        )
    }
    val contributions = when (source) {
        // A map scans entry vectors, so its values do not contribute numbers, but their
        // eager evaluation errors still belong to the selected sum expression.
        is X.MapX, is X.Range -> ignoredSumErrors(source).let { listOf(SumContribution(it, it)) }
        else -> items.mapIndexed { index, item -> contribution(item, literalItems?.getOrNull(index)) }
    }
    val terms = contributions.map { it.value }.filter { it != Ex.ZERO }
    val counts = contributions.map { it.count }.filter { it != Ex.ZERO }
    if (counts.isEmpty()) return Ex.EMPTY
    fun chunks(arguments: List<X.Scalar>, operation: String): List<X.Scalar> {
        val result = mutableListOf<X.Scalar>()
        var chunk = mutableListOf<X.Scalar>()
        var length = 0
        for (value in arguments) {
            if (chunk.isNotEmpty() && (length + value.text.length > 4000 || chunk.size >= 200)) {
                result += compact(Ex.fn(operation, chunk))
                chunk = mutableListOf()
                length = 0
            }
            chunk += value
            length += value.text.length + 1
        }
        if (chunk.isNotEmpty()) result += compact(Ex.fn(operation, chunk))
        return result
    }
    val total = if (terms.isEmpty()) Ex.ZERO else compact(Ex.fn("SUM", chunks(terms, "SUM")))
    val count = compact(Ex.fn("SUM", chunks(counts, "SUM")))
    return compact(Ex.iff(Ex.cmp("=", count, Ex.ZERO), Ex.EMPTY, total))
}

private data class SumContribution(val value: X.Scalar, val count: X.Scalar)

/** Excluded values still evaluate eagerly. Only literal constants can be omitted entirely. */
private fun ignoredSumErrors(value: X): X.Scalar = when (value) {
    is X.Scalar -> if (value.text.toBigDecimalOrNull() != null || value in listOf(Ex.TRUE, Ex.FALSE, Ex.EMPTY) ||
        Ex.isTextLiteral(value)
    ) {
        Ex.ZERO
    } else {
        Ex.iff(Ex.fn("ISERROR", value, kind = XKind.BOOL), value, Ex.ZERO)
            .copy(kind = XKind.NUM, numericOrNil = true, booleanOrNil = false)
    }
    is X.Vec -> ignoredSumCollectionErrors(value.items)
    is X.MapX -> ignoredSumCollectionErrors(value.values)
    is X.Range -> ignoredSumCollectionErrors(value.cells)
    is X.Branches -> {
        val default = value.cases.lastOrNull { it.first == null }?.second?.let(::ignoredSumErrors) ?: Ex.ZERO
        value.cases.filter { it.first != null }.foldRight(default) { (condition, selected), rest ->
            Ex.iff(condition!!, ignoredSumErrors(selected), rest)
        }
    }
    // Constructing a lexical function does not execute its body.
    X.Nil, is X.Callable -> Ex.ZERO
}

private fun ignoredSumCollectionErrors(values: List<X>): X.Scalar {
    val checks = values.map(::ignoredSumErrors).filter { it != Ex.ZERO }
    return if (checks.isEmpty()) Ex.ZERO else Ex.fn("SUM", checks)
}

/** Hoist only total aggregate/selection expressions; arithmetic or arbitrary functions remain lazy. */
private fun safeAggregateExpression(formula: String): Boolean {
    val visible = StringBuilder()
    var index = 0
    while (index < formula.length) {
        val char = formula[index]
        if (char == '\'' || char == '"') {
            val quote = char
            index++
            while (index < formula.length) {
                if (formula[index] != quote) {
                    index++
                } else if (index + 1 < formula.length && formula[index + 1] == quote) {
                    index += 2
                } else {
                    index++
                    break
                }
            }
        } else {
            visible.append(char)
            index++
        }
    }
    val text = visible.toString()
    if (text.any { it in "+-*/^" }) return false
    val totalFunctions = setOf("SUM", "COUNT", "IF", "ISNUMBER", "NOT", "AND", "OR")
    return Regex("([A-Za-z_][A-Za-z_0-9.]*)\\s*\\(").findAll(text).all {
        it.groupValues[1] in totalFunctions
    }
}
