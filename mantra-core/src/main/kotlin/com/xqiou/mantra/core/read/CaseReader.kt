package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Formula
import com.xqiou.mantra.core.model.FunctionDecl
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Item
import com.xqiou.mantra.core.model.SourceBinding
import com.xqiou.mantra.core.model.Value
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/**
 * Reads a case document — the user-controlled part of a calculation:
 *
 * ```
 * (case <id> {:schema test/example :title "..." ...}?
 *   (inputs {<input-id> <literal> ...})
 *   (params {<param-id> <literal> ...})     ; overrides of template parameters
 *   (extend <slot-id> <item>*)              ; user-defined lines in declared slots
 *   (bind <formula-slot-id> <formula>)        ; replace an application-declared formula
 *   (defn name [^Type arg] body))           ; user helper functions
 * ```
 */
object CaseReader {
    fun read(source: SourceText, sink: DiagnosticSink): CaseData? {
        val document = Document.read(source, sink) ?: return null
        val root = document.root as? DslForm.Sequence
        if (root == null || root.listHead != "case") {
            sink.error(
                "MANTRA-CASE-ROOT",
                "A case document must start with (case <id> ...)",
                document.location(document.root),
            )
            return null
        }
        val id = root.values.getOrNull(1)?.let { it.symbol ?: it.string } ?: run {
            sink.error("MANTRA-CASE-ID", "Case id is missing", document.location(root))
            return null
        }
        var index = 2
        val meta = linkedMapOf<String, Value>()
        root.values.getOrNull(2)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }?.let { metaForm ->
            index = 3
            document.options(metaForm, sink, "case metadata").forEach { (key, form) ->
                val value = form.symbol?.let { Value.Text(it) } ?: document.literal(form, sink, "case metadata :$key")
                value?.let { meta[key] = it }
            }
        }
        val inputs = linkedMapOf<String, Value>()
        val params = linkedMapOf<String, Value>()
        val inputLocations = linkedMapOf<String, SourceLocation>()
        val paramLocations = linkedMapOf<String, SourceLocation>()
        val extensions = linkedMapOf<String, MutableList<Item>>()
        val formulaBindings = linkedMapOf<String, Formula>()
        val functions = mutableListOf<FunctionDecl>()
        val sources = mutableListOf<SourceBinding>()
        root.values.drop(index).forEach { form ->
            val list = form as? DslForm.Sequence
            when (list?.listHead) {
                "inputs" -> readValues(document, list, sink, "inputs", inputs, inputLocations)
                "params" -> readValues(document, list, sink, "params", params, paramLocations)
                "sources" -> list.values.drop(1).forEach { declaration ->
                    val source = declaration as? DslForm.Sequence
                    val kind = source?.listHead
                    val options = source?.values?.getOrNull(1)
                    if (source == null || kind == null || kind !in setOf("csv", "json", "xlsx") ||
                        source.values.size != 2 ||
                        options == null ||
                        !options.isSequence(DslFormSequenceKind.MAP)
                    ) {
                        sink.error(
                            "MANTRA-CASE-SOURCE",
                            "Expected (csv|json|xlsx {options})",
                            document.location(declaration),
                        )
                    } else {
                        val values = document.options(options, sink, "$kind source").mapNotNull { (key, value) ->
                            document.literal(value, sink, "$kind source :$key")?.let { key to it }
                        }.toMap()
                        sources += SourceBinding(kind, values, document.location(source))
                    }
                }
                "extend" -> {
                    val slot = list.values.getOrNull(1)?.symbol
                    if (slot == null) {
                        sink.error(
                            "MANTRA-CASE-EXTEND",
                            "(extend <slot-id> item...) requires a slot id",
                            document.location(list),
                        )
                    } else {
                        val declaredInputs = mutableListOf<InputDecl>()
                        val items = list.values.drop(2).mapNotNull {
                            ItemReader(document, sink, userDefined = true, declaredInputs).read(it)
                        }
                        if (declaredInputs.isNotEmpty()) {
                            sink.error(
                                "MANTRA-CASE-EXTEND",
                                "User extensions cannot declare (field ...) inputs; use (line ...) with a value",
                                document.location(list),
                            )
                        }
                        extensions.getOrPut(slot) { mutableListOf() } += items
                    }
                }
                "bind" -> {
                    val slot = list.values.getOrNull(1)?.symbol
                    val formula = list.values.getOrNull(2)
                    if (slot == null || formula == null || list.values.size != 3) {
                        sink.error(
                            "MANTRA-CASE-BIND",
                            "(bind <formula-slot-id> <formula>) requires exactly two arguments",
                            document.location(list),
                        )
                    } else if (formulaBindings.putIfAbsent(slot, document.formula(formula)) != null) {
                        sink.error(
                            "MANTRA-CASE-BIND-DUPLICATE",
                            "Formula slot $slot is bound twice",
                            document.location(list),
                        )
                    }
                }
                "defn" -> {
                    val name = list.values.getOrNull(1)?.symbol
                    if (name == null) {
                        sink.error("MANTRA-DEFN", "(defn <name> [args] body) requires a name", document.location(list))
                    } else {
                        functions += FunctionDecl(name, document.slice(list), document.location(list))
                    }
                }
                else -> sink.error(
                    "MANTRA-CASE-FORM",
                    "Unknown case form `${document.slice(form).take(60)}`",
                    document.location(form),
                )
            }
        }
        val schemaId = (meta["schema"] as? Value.Text)?.value
        return CaseData(
            id, schemaId, meta, inputs, params, extensions, formulaBindings, functions, source.name,
            inputLocations, paramLocations, sources = sources,
        )
    }

    private fun readValues(
        document: Document,
        list: DslForm.Sequence,
        sink: DiagnosticSink,
        what: String,
        target: MutableMap<String, Value>,
        locations: MutableMap<String, SourceLocation>,
    ) {
        list.values.drop(1).forEach { mapForm ->
            document.options(mapForm, sink, what).forEach { (key, form) ->
                document.literal(form, sink, "$what :$key")?.let { value ->
                    locations[key] = document.location(form)
                    if (target.put(key, value) != null) {
                        sink.error("MANTRA-CASE-DUPLICATE", "Duplicate $what entry :$key", document.location(form))
                    }
                }
            }
        }
    }
}
