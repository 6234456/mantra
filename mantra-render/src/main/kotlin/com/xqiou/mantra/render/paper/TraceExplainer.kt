package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.view.ExplainTrace

/** Formats the captured kernel trace without evaluating or substituting any source expression. */
internal class TraceExplainer(private val language: String) {
    private val de = language == "de"

    fun explain(trace: ExplainTrace?): String {
        if (trace == null) return if (de) "[Audit-Trace nicht angefordert]" else "[Audit trace was not requested]"
        val parts = buildList {
            trace.steps.forEach { step ->
                val result = step.rendered ?: if (de) "[Wert nicht verfügbar]" else "[value unavailable]"
                add("${compact(step.text)} = $result")
            }
            trace.branches.filter { it.selected }.forEach { branch ->
                add((if (de) "gewählter Zweig: " else "selected branch: ") + compact(branch.text))
            }
            if (trace.truncated) {
                add(if (de) "[Audit-Trace gekürzt: Budget erreicht]" else "[Audit trace truncated: budget reached]")
            }
        }
        return parts.joinToString(" → ").ifEmpty {
            if (de) "[Keine darstellbaren Trace-Werte]" else "[No displayable trace values]"
        }
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
