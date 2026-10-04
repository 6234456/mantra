package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.PeriodEntry
import com.xqiou.mantra.core.model.PeriodSpec
import com.xqiou.mantra.core.model.PeriodUnit
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Reads the host period declaration; every end is exclusive. */
internal class PeriodReader(private val document: Document, private val sink: DiagnosticSink) {
    fun read(form: DslForm, id: String): PeriodSpec? {
        if (form.isSequence(DslFormSequenceKind.MAP)) {
            val opts = document.options(form, sink, "period dimension $id")
            if (opts.keys.any { it !in setOf("start", "unit", "count") }) {
                error(form, id, "Generated periods accept only :start, :unit and :count")
                return null
            }
            val start = date(opts["start"], form, id, ":start")
            val unit = when (opts["unit"]?.keyword) {
                "month" -> PeriodUnit.MONTH
                "quarter" -> PeriodUnit.QUARTER
                "year" -> PeriodUnit.YEAR
                else -> {
                    error(opts["unit"] ?: form, id, ":unit must be :month, :quarter or :year")
                    null
                }
            }
            val count = runCatching { opts["count"]?.number?.intValueExact() }.getOrNull()
            if (count == null || count !in 1..5_000) {
                error(opts["count"] ?: form, id, ":count must be an integer in 1..5000")
                return null
            }
            return if (start != null && unit != null) PeriodSpec.Generated(start, unit, count) else null
        }
        val sequence = form as? DslForm.Sequence
        if (sequence?.kind != DslFormSequenceKind.VECTOR || sequence.values.isEmpty()) {
            error(form, id, ":periods must be a generation map or a nonempty vector of period maps")
            return null
        }
        var valid = true
        val entries = sequence.values.mapNotNull { entry ->
            val opts = document.options(entry, sink, "period of $id")
            if (opts.keys.any { it !in setOf("key", "start", "end", "label") }) {
                error(entry, id, "Static periods accept only :key, :start, :end and :label; :when is forbidden")
                valid = false
            }
            val key = opts["key"]?.keyword
            if (key == null) {
                error(entry, id, "Each static period requires a keyword :key")
                valid = false
            }
            val start = date(opts["start"], entry, id, ":start")
            val end = date(opts["end"], entry, id, ":end")
            if (start == null || end == null) valid = false
            if (key != null && start != null && end != null) {
                PeriodEntry(key, start, end, opts["label"]?.string, document.location(entry))
            } else {
                null
            }
        }
        return if (valid) PeriodSpec.Listed(entries) else null
    }

    private fun date(form: DslForm?, fallback: DslForm, id: String, name: String): LocalDate? {
        val text = form?.string
        if (text == null) {
            error(form ?: fallback, id, "$name must be an ISO date string")
            return null
        }
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            error(form ?: fallback, id, "$name must be a valid ISO date string")
            null
        }
    }

    private fun error(form: DslForm, id: String, message: String) {
        sink.error("MANTRA-PERIOD-DECLARATION", "Period dimension $id: $message", document.location(form), id)
    }
}
