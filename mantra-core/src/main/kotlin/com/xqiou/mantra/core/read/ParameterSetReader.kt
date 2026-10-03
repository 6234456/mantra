package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.Value
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/**
 * A named, versionable set of parameter values (e.g. the amounts of one Veranlagungszeitraum or
 * a what-if variant). Precedence when calculating: schema default < parameter sets (in the given
 * order) < `(params …)` of the case.
 */
data class ParameterSet(
    val id: String,
    val meta: Map<String, Value>,
    val values: Map<String, Value>,
    val references: Map<String, String>,
    val location: SourceLocation,
) {
    val label: String get() = (meta["label"] as? Value.Text)?.value ?: id
    val forSchema: String? get() = (meta["for"] as? Value.Text)?.value
}

/**
 * ```
 * (parameters test/params-2026 {:for "test/example" :label "…" :valid-from "2026-01-01"}
 *   (values {:grundfreibetrag 12348 …})
 *   (value allowance 100 {:reference "Example policy"}))
 * ```
 */
object ParameterSetReader {
    fun read(source: SourceText, sink: DiagnosticSink): ParameterSet? {
        val document = Document.read(source, sink) ?: return null
        val root = document.root as? DslForm.Sequence
        if (root == null || root.listHead != "parameters") {
            sink.error("MANTRA-PARAMETERS-ROOT", "A parameter set must start with (parameters <id> ...)", document.location(document.root))
            return null
        }
        val id = root.values.getOrNull(1)?.let { it.symbol ?: it.string } ?: run {
            sink.error("MANTRA-PARAMETERS-ID", "Parameter set id is missing", document.location(root))
            return null
        }
        var index = 2
        val meta = linkedMapOf<String, Value>()
        root.values.getOrNull(2)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }?.let { metaForm ->
            index = 3
            document.options(metaForm, sink, "parameter set metadata").forEach { (key, form) ->
                document.literal(form, sink, "parameter set :$key", symbolsAsText = true)?.let { meta[key] = it }
            }
        }
        val values = linkedMapOf<String, Value>()
        val references = linkedMapOf<String, String>()
        fun put(key: String, value: Value, at: DslForm) {
            if (values.put(key, value) != null) sink.error("MANTRA-PARAMETERS-DUPLICATE", "Parameter $key is set twice", document.location(at))
        }
        root.values.drop(index).forEach { form ->
            val list = form as? DslForm.Sequence
            when (list?.listHead) {
                "values" -> list.values.drop(1).forEach { map ->
                    document.options(map, sink, "values").forEach { (key, valueForm) ->
                        document.literal(valueForm, sink, "parameter $key")?.let { put(key, it, valueForm) }
                    }
                }
                "value" -> {
                    val key = list.values.getOrNull(1)?.symbol
                    val valueForm = list.values.getOrNull(2)
                    if (key == null || valueForm == null) {
                        sink.error("MANTRA-PARAMETERS-VALUE", "(value <param-id> <literal> {:reference …}?) is malformed", document.location(list))
                    } else {
                        document.literal(valueForm, sink, "parameter $key")?.let { put(key, it, valueForm) }
                        document.options(list.values.getOrNull(3), sink, "value $key")["reference"]?.string?.let { references[key] = it }
                    }
                }
                else -> sink.error("MANTRA-PARAMETERS-FORM", "Unknown form in parameter set: `${document.slice(form).take(60)}`", document.location(form))
            }
        }
        return ParameterSet(id, meta, values, references, document.location(root))
    }
}
