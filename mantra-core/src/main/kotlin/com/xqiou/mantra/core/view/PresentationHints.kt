package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal

/** Optional wording selected from a result's sign. This is display metadata, never arithmetic. */
data class SignLabels(val positive: String?, val negative: String?, val zero: String?) {
    fun forValue(value: BigDecimal, fallback: String): String = when (value.signum()) {
        1 -> positive
        -1 -> negative
        else -> zero
    } ?: fallback
}

private fun Value.keyText(): String? = when (this) {
    is Value.Kw -> name
    is Value.Text -> value
    else -> null
}

private fun Value.MapV.textAt(key: String): String? = entries.entries.firstOrNull { it.key.keyText() == key }
    ?.value.let { it as? Value.Text }?.value

val ViewNode.signLabels: SignLabels?
    get() = (presentation.attributes["sign-labels"] as? Value.MapV)?.let {
        SignLabels(it.textAt("positive"), it.textAt("negative"), it.textAt("zero"))
    }

fun ViewNode.displayLabel(value: BigDecimal? = crossTotal()): String =
    value?.let { signLabels?.forValue(it, label) } ?: label

/** The declared headline, or the result of the last mainline panel. */
val CalculationView.headlineId: String?
    get() {
        val declared = schema.attributes["headline"]?.keyText()
        if (declared != null && declared in nodes) return declared
        return structure.mainline.lastOrNull()?.let { structure.panel(it).resultId }
    }

val ViewNode.groupKey: String?
    get() = presentation.attributes["group"]?.keyText()

fun CalculationView.groupTitle(key: String): String =
    (schema.attributes["group-titles"] as? Value.MapV)?.textAt(key) ?: key
