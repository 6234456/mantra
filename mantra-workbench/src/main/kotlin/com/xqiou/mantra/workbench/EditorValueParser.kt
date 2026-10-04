package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.layout.LayoutSpec
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Shared host primitive parsing from the declared engine type and actual layout locale. */
object EditorValueParser {
    fun parse(
        view: CalculationView,
        layout: LayoutSpec,
        id: String,
        parameter: Boolean,
        text: String,
        column: String? = null,
    ): Value {
        val type = if (parameter) {
            val declared = view.nodes[id]?.parameter?.value
                ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown parameter $id")
            when (declared) {
                is Value.Num -> ValueType.DECIMAL
                is Value.Bool -> ValueType.BOOLEAN
                is Value.Kw -> ValueType.KEYWORD
                is Value.Date -> ValueType.DATE
                else -> ValueType.TEXT
            }
        } else {
            val input = view.nodes[id]?.input ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown input $id")
            if (column == null) {
                input.type
            } else {
                input.columns.firstOrNull { it.name == column }?.type
                    ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Unknown table column $column")
            }
        }
        if (!parameter && column != null && text.isBlank()) return Value.Nil
        return try {
            when (type) {
                ValueType.DECIMAL, ValueType.INTEGER -> {
                    val raw = text.trim()
                    val symbols = DecimalFormatSymbols.getInstance(layout.number.locale)
                    val group = symbols.groupingSeparator
                    val decimal = symbols.decimalSeparator
                    val pattern =
                        Regex(
                            "-?(?:\\d{1,3}(?:${Regex.escape(
                                group.toString(),
                            )}\\d{3})+|\\d+)(?:${Regex.escape(decimal.toString())}\\d+)?",
                        )
                    require(pattern.matches(raw)) { "Invalid decimal text" }
                    val number = BigDecimal(raw.replace(group.toString(), "").replace(decimal, '.'))
                    require(type != ValueType.INTEGER || number.stripTrailingZeros().scale() <= 0) {
                        "Expected an integer"
                    }
                    Value.Num(number)
                }
                ValueType.BOOLEAN -> when (text.trim().lowercase()) {
                    "true", "ja" -> Value.Bool(true)
                    "false", "nein" -> Value.Bool(false)
                    else -> throw IllegalArgumentException("Expected a boolean")
                }
                ValueType.KEYWORD -> Value.Kw(text.trim().removePrefix(":"))
                ValueType.DATE -> Value.Date(
                    LocalDate.parse(
                        text.trim(),
                        DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT),
                    ),
                )
                ValueType.TEXT, ValueType.ANY -> Value.Text(text)
                ValueType.TABLE -> throw IllegalArgumentException("Table input requires encoded rows")
            }
        } catch (error: RuntimeException) {
            throw WorkspaceException(
                WorkspaceProblem.INVALID,
                "Input text was rejected: ${error.message}",
                listOf(Diagnostic(Severity.ERROR, "MANTRA-WORKBENCH-EDIT", error.message.orEmpty())),
            )
        }
    }
}
