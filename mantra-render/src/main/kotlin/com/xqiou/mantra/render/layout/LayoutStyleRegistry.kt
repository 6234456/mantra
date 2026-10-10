package com.xqiou.mantra.render.layout

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.isSequence
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** Bounded, nonrecursive named declarations lowered into the existing style model. */
internal class LayoutStyleRegistry(
    private val document: Document,
    private val sink: DiagnosticSink,
    preset: DslForm?,
    declarations: List<DslForm>,
) {
    private val classes = linkedMapOf<String, StyleSpec>()
    private val localRules = linkedMapOf<DslForm.Sequence, StyleRule>()
    private val localNames = mutableSetOf<String>()

    val presetRules: List<StyleRule> = buildList {
        val selected = mutableSetOf<String>()
        names(preset, "MANTRA-LAYOUT-STYLE-PRESET", 2, "style presets").forEach { (name, form) ->
            val palette = PALETTES[name]
            when {
                !selected.add(name) -> error("MANTRA-LAYOUT-STYLE-PRESET", "Repeated style preset :$name", form)
                palette == null -> error(
                    "MANTRA-LAYOUT-STYLE-PRESET",
                    "Unknown style preset :$name; available: :utilities, :working-paper",
                    form,
                )
                else -> palette.forEach { (klass, style) ->
                    classes[klass] = classes[klass]?.merge(style) ?: style
                    add(StyleRule(StyleSelector(klass = klass), style))
                }
            }
        }
    }

    init {
        declarations.filterIsInstance<DslForm.Sequence>().filter { it.listHead == "style-class" }
            .forEach(::declare)
    }

    fun classRule(list: DslForm.Sequence): StyleRule? = localRules[list]

    fun style(list: DslForm.Sequence): StyleSpec {
        if (list.values.size != 3) {
            error(
                "MANTRA-LAYOUT-STYLE",
                "(style {selector} {declarations}) requires exactly two maps",
                list.values.getOrNull(3) ?: list,
            )
        }
        val properties = properties(list.values.getOrNull(2), "MANTRA-LAYOUT-STYLE", list, allowUse = true)
        var inherited = StyleSpec()
        names(properties["use"], "MANTRA-LAYOUT-STYLE-USE", MAX_USE, "style references").forEach { (name, form) ->
            val style = classes[name]
            if (style == null) {
                error("MANTRA-LAYOUT-STYLE-USE", "Unknown style class :$name", form)
            } else {
                inherited = inherited.merge(style)
            }
        }
        return inherited.merge(explicitStyle(properties))
    }

    private fun declare(list: DslForm.Sequence) {
        if (list.values.size != 3) {
            error(
                "MANTRA-LAYOUT-STYLE-CLASS",
                "(style-class :name {declarations}) requires a keyword name and a declaration map",
                list.values.getOrNull(3) ?: list,
            )
            return
        }
        val nameForm = list.values[1]
        val name = name(nameForm, "MANTRA-LAYOUT-STYLE-CLASS") ?: return
        if (name in localNames) {
            error("MANTRA-LAYOUT-STYLE-CLASS", "Duplicate local style class :$name", nameForm)
            return
        }
        if (localNames.size >= MAX_LOCAL) {
            error("MANTRA-LAYOUT-STYLE-CLASS", "At most $MAX_LOCAL local style classes are allowed", nameForm)
            return
        }
        localNames += name
        val properties = properties(list.values[2], "MANTRA-LAYOUT-STYLE-CLASS", list, allowUse = false)
        val style = explicitStyle(properties)
        classes[name] = classes[name]?.merge(style) ?: style
        localRules[list] = StyleRule(StyleSelector(klass = name), style)
    }

    private fun properties(form: DslForm?, code: String, owner: DslForm, allowUse: Boolean): Map<String, DslForm> {
        if (allowUse) {
            // Ordinary style maps retain their existing reader codes and duplicate-key behavior.
            val properties = document.options(form, sink, "style declarations")
            properties.keys.filter { it !in setOf("weight", "tone", "fill", "use") }.forEach { key ->
                val token = (form as? DslForm.Sequence)?.values?.chunked(2)
                    ?.lastOrNull { it.size == 2 && it[0].keyword == key }?.firstOrNull() ?: owner
                error("MANTRA-LAYOUT-STYLE", "Unknown style property :$key", token)
            }
            return properties
        }
        val map = form as? DslForm.Sequence
        if (map?.kind != DslFormSequenceKind.MAP || map.values.size % 2 != 0) {
            error(code, "Style declarations must be a map with keyword property names", form ?: owner)
            return emptyMap()
        }
        val result = linkedMapOf<String, DslForm>()
        map.values.chunked(2).forEach { (keyForm, value) ->
            val key = keyForm.keyword
            when {
                key == "use" && !allowUse -> error(
                    "MANTRA-LAYOUT-STYLE-USE",
                    ":use is only valid in style rules; style-class definitions cannot inherit",
                    keyForm,
                )
                key !in setOf("weight", "tone", "fill", "use") -> error(
                    "MANTRA-LAYOUT-STYLE",
                    "Unknown style property ${document.slice(keyForm)}",
                    keyForm,
                )
                result.putIfAbsent(key!!, value) != null -> error(
                    "MANTRA-LAYOUT-STYLE",
                    "Duplicate style property :$key",
                    keyForm,
                )
            }
        }
        return result
    }

    private fun explicitStyle(properties: Map<String, DslForm>): StyleSpec {
        fun <T> choice(key: String, values: Map<String, T>): T? = properties[key]?.let { form ->
            values[form.keyword] ?: run {
                error(
                    "MANTRA-LAYOUT-STYLE",
                    ":$key must be one of ${values.keys.joinToString { ":$it" }}",
                    form,
                )
                null
            }
        }
        return StyleSpec(
            weight = choice("weight", mapOf("normal" to StyleWeight.NORMAL, "bold" to StyleWeight.BOLD)),
            tone = choice(
                "tone",
                mapOf("default" to StyleTone.DEFAULT, "muted" to StyleTone.MUTED, "accent" to StyleTone.ACCENT),
            ),
            fill = choice(
                "fill",
                mapOf("none" to StyleFill.NONE, "subtle" to StyleFill.SUBTLE, "accent" to StyleFill.ACCENT),
            ),
        )
    }

    private fun names(form: DslForm?, code: String, maximum: Int, what: String): List<Pair<String, DslForm>> {
        if (form == null) return emptyList()
        val forms = when {
            form.keyword != null -> listOf(form)
            form.isSequence(DslFormSequenceKind.VECTOR) -> (form as DslForm.Sequence).values
            else -> {
                error(code, "$what must be a keyword name or a vector of keyword names", form)
                return emptyList()
            }
        }
        if (forms.size > maximum) {
            error(code, "At most $maximum $what are allowed", forms[maximum])
        }
        return forms.take(maximum).mapNotNull { value -> name(value, code)?.let { it to value } }
    }

    private fun name(form: DslForm, code: String): String? = form.keyword?.takeIf(CLASS_NAME::matches) ?: run {
        error(code, "Style names must be simple keywords matching [a-z][a-z0-9-]*", form)
        null
    }

    private fun error(code: String, message: String, form: DslForm) {
        sink.error(code, message, document.location(form))
    }

    private companion object {
        const val MAX_LOCAL = 256
        const val MAX_USE = 64
        val CLASS_NAME = Regex("[a-z][a-z0-9-]*")

        val PALETTES = linkedMapOf(
            "utilities" to linkedMapOf(
                "normal" to StyleSpec(weight = StyleWeight.NORMAL),
                "strong" to StyleSpec(weight = StyleWeight.BOLD),
                "muted" to StyleSpec(tone = StyleTone.MUTED),
                "accent" to StyleSpec(tone = StyleTone.ACCENT),
                "subtle" to StyleSpec(fill = StyleFill.SUBTLE),
                "highlight" to StyleSpec(fill = StyleFill.ACCENT),
            ),
            "working-paper" to linkedMapOf(
                "source" to StyleSpec(tone = StyleTone.MUTED, fill = StyleFill.NONE),
                "assumption" to StyleSpec(tone = StyleTone.MUTED, fill = StyleFill.SUBTLE),
                "detail" to StyleSpec(weight = StyleWeight.NORMAL, tone = StyleTone.DEFAULT),
                "subtotal" to StyleSpec(weight = StyleWeight.BOLD, fill = StyleFill.SUBTLE),
                "result" to StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.ACCENT, fill = StyleFill.ACCENT),
                "note" to StyleSpec(tone = StyleTone.MUTED),
                "variance" to StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.ACCENT, fill = StyleFill.SUBTLE),
                "control" to StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.ACCENT),
            ),
        )
    }
}
