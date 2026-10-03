package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.number
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormPostfix
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/**
 * Turns a formula into a human-readable working ("Rechenweg"): arithmetic becomes infix, calls
 * become `name(a; b)`, and every referenced line is replaced by the value it had in this
 * evaluation, e.g. `(max wk pausch)` → `max(800; 1.230)`.
 */
class FormulaExplainer(private val numbers: NumberFormatter) {
    private val infix = mapOf(
        "+" to " + ", "-" to " − ", "*" to " × ", "/" to " ÷ ",
        "<" to " < ", ">" to " > ", "<=" to " ≤ ", ">=" to " ≥ ", "=" to " = ", "not=" to " ≠ ",
    )

    fun explain(
        form: DslForm,
        values: Map<String, Value>,
        records: Map<String, Map<String, Value>>,
        maxLength: Int = 600,
    ): String {
        val text = render(form, values, records, nested = false)
        return if (text.length > maxLength) text.take(maxLength - 1) + "…" else text
    }

    private fun symbolText(
        qualified: String,
        values: Map<String, Value>,
        records: Map<String, Map<String, Value>>,
    ): String {
        val symbol = qualified.removePrefix("mantra/")
        values[symbol]?.let { return numbers.explain(it) }
        val path = symbol.split('.')
        if (path.size == 2) {
            if (path[0] == "all") values["all.${path[1]}"]?.let { return numbers.explain(it) }
            records[path[0]]?.get(path[1])?.let { return numbers.explain(it) }
        }
        return symbol
    }

    private fun render(
        form: DslForm,
        values: Map<String, Value>,
        records: Map<String, Map<String, Value>>,
        nested: Boolean,
    ): String {
        form.number?.let { return numbers.plain(it) }
        form.string?.let { return "\"$it\"" }
        form.keyword?.let { return ":$it" }
        form.symbol?.let { symbol -> return symbolText(symbol, values, records) }
        when (form) {
            is DslForm.Postfix -> {
                val target = form.target.symbol
                val path = form.suffixes.filterIsInstance<DslFormPostfix.Member>().map { it.value }
                if (target == "all" && path.size == 1) {
                    return values["all.${path[0]}"]?.let(numbers::explain) ?: "all.${path[0]}"
                }
                if (target != null && path.size == 1) {
                    records[target]?.get(path[0])?.let { return numbers.explain(it) }
                }
                return (listOfNotNull(target) + path).joinToString(".")
            }
            is DslForm.Sequence -> {
                val parts = form.values
                return when (form.kind) {
                    DslFormSequenceKind.VECTOR -> parts.joinToString("; ", "[", "]") {
                        render(it, values, records, false)
                    }
                    DslFormSequenceKind.MAP -> parts.chunked(2).joinToString("; ", "{", "}") { pair ->
                        pair.joinToString(" ") { render(it, values, records, false) }
                    }
                    DslFormSequenceKind.SET -> parts.joinToString("; ", "#{", "}") {
                        render(it, values, records, false)
                    }
                    DslFormSequenceKind.LAMBDA -> "#(…)"
                    DslFormSequenceKind.LIST -> {
                        val head =
                            form.listHead
                                ?: return "(" + parts.joinToString(" ") { render(it, values, records, true) } + ")"
                        val args = parts.drop(1)
                        val operator = infix[head]
                        when {
                            head == "-" && args.size == 1 -> "−" + render(args[0], values, records, true)
                            operator != null && args.size >= 2 -> {
                                val body = args.joinToString(operator) { render(it, values, records, true) }
                                if (nested) "($body)" else body
                            }
                            else -> head + "(" + args.joinToString("; ") { render(it, values, records, false) } + ")"
                        }
                    }
                }
            }
            is DslForm.Atom -> return form.sourceText
        }
    }
}
