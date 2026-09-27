package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.render.layout.NegativeStyle
import com.xqiou.mantra.render.layout.NumberStyle
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/** Locale-aware formatting of amounts, percentages and plain numbers. */
class NumberFormatter(private val style: NumberStyle) {
    private val symbols = DecimalFormatSymbols.getInstance(style.locale)

    private fun pattern(decimals: Int): DecimalFormat {
        val grouping = if (style.grouping) "#,##0" else "0"
        val fraction = if (decimals > 0) "." + "0".repeat(decimals) else ""
        return DecimalFormat(grouping + fraction, symbols).apply { roundingMode = RoundingMode.HALF_UP }
    }

    private fun signed(magnitude: String, negative: Boolean): String = when {
        !negative -> magnitude
        style.negative == NegativeStyle.PARENTHESES -> "($magnitude)"
        else -> "-$magnitude"
    }

    fun amount(value: BigDecimal, precision: Int = style.precision): String {
        if (value.signum() == 0 && style.zero != null) return style.zero
        val scaled = value.setScale(precision, RoundingMode.HALF_UP)
        if (scaled.signum() == 0 && style.zero != null) return style.zero
        return signed(pattern(precision).format(scaled.abs()), scaled.signum() < 0)
    }

    fun percent(value: BigDecimal, precision: Int = style.percentPrecision): String {
        val scaled = value.movePointRight(2).setScale(precision, RoundingMode.HALF_UP)
        return signed(pattern(precision).format(scaled.abs()), scaled.signum() < 0) + " %"
    }

    /** Minimal representation used in formula workings, e.g. `1.230` or `0,125`. */
    fun plain(value: BigDecimal): String {
        val stripped = value.stripTrailingZeros()
        val decimals = stripped.scale().coerceIn(0, 6)
        val text = pattern(decimals).format(stripped.abs().setScale(decimals, RoundingMode.HALF_UP))
        return if (stripped.signum() < 0) "-$text" else text
    }

    /** Formats a node value according to its presentation `:format` and `:precision`. */
    fun value(value: Value, format: String?, precision: Int?): String = when (value) {
        is Value.Num -> when (format) {
            "percent" -> percent(value.value, precision ?: style.percentPrecision)
            "integer", "years" -> amount(value.value, 0)
            "number", "factor" -> if (precision != null) amount(value.value, precision) else plain(value.value)
            else -> amount(value.value, precision ?: style.precision)
        }
        Value.Nil -> ""
        is Value.Bool -> if (value.value) "✓" else "–"
        is Value.Kw -> value.name
        is Value.Text -> value.value
        is Value.Date -> value.value.toString()
        is Value.Vec -> value.items.joinToString(", ") { this.value(it, format, precision) }
        is Value.MapV -> value.entries.entries.joinToString(", ") { (k, v) -> "${this.value(k, null, null)}: ${this.value(v, format, precision)}" }
    }

    fun explain(value: Value): String = when (value) {
        is Value.Num -> plain(value.value)
        is Value.MapV -> value.entries.entries.joinToString("; ", "{", "}") { (k, v) -> "${explain(k)} ${explain(v)}" }
        is Value.Vec -> value.items.joinToString("; ", "[", "]") { explain(it) }
        is Value.Kw -> ":${value.name}"
        is Value.Text -> "\"${value.value}\""
        Value.Nil -> "nil"
        else -> value.toString()
    }
}
