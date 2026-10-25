package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.isSequence
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** Edits declaration tokens using public source spans; final validation remains the layout reader's. */
internal object TemplateStyleClassesEditor {
    data class Patch(val startOffset: Int, val endOffset: Int, val text: String, val removedText: String)
    data class Result(
        val layoutId: String,
        val stylePresets: List<String>,
        val text: String,
        val classes: List<Map<String, Any?>>,
        val patches: List<Patch>,
    )

    private data class Local(
        val form: DslForm.Sequence,
        val declarations: DslForm.Sequence,
        val pairs: Map<String, Pair<DslForm, DslForm>>,
    ) {
        fun style() = TemplateStyleSpec(
            pairs["weight"]?.second?.keyword,
            pairs["tone"]?.second?.keyword,
            pairs["fill"]?.second?.keyword,
        )
    }

    private data class Reference(val name: String, val kind: String, val form: DslForm) {
        fun data(): Map<String, Any?> = linkedMapOf(
            "kind" to kind,
            "startOffset" to form.span.startOffset,
            "endOffset" to form.span.endOffset,
        )
    }

    private data class Parsed(
        val document: Document,
        val root: DslForm.Sequence,
        val presets: List<String>,
        val locals: Map<String, Local>,
        val references: List<Reference>,
    )

    private val empty = TemplateStyleSpec(null, null, null)
    private val names = Regex("[a-z][a-z0-9-]*")
    private val choices = linkedMapOf(
        "weight" to setOf("normal", "bold"),
        "tone" to setOf("default", "muted", "accent"),
        "fill" to setOf("none", "subtle", "accent"),
    )

    fun apply(original: SourceText, source: String, operations: List<TemplateStyleClassOperation>): Result {
        var current = source
        val patches = mutableListOf<Patch>()
        operations.forEach { operation ->
            val parsed = parse(original, current)
            val local = parsed.locals[operation.name]
            if (operation.op == "remove") {
                if (local == null) invalid("Only a local style class definition can be removed", parsed.document)
                val hasFallback = palettes(parsed).any { (_, rules) -> operation.name in rules }
                if (!hasFallback && parsed.references.any { it.name == operation.name && it.kind == "use" }) {
                    invalid(
                        "This local style class is still referenced by :use and has no selected preset fallback",
                        parsed.document,
                    )
                }
                tokenRanges(local.form).sortedByDescending { it.first }.forEach { (start, end) ->
                    current = change(current, start, end, "", patches)
                }
            } else {
                val style = checkNotNull(operation.style)
                if (local == null) {
                    val newline = if ("\r\n" in current) "\r\n" else "\n"
                    val declaration = style.pairs().filterValues { it != null }.entries
                        .joinToString(" ") { (key, value) -> ":$key :$value" }
                    val at = parsed.root.span.endOffset - 1
                    current = change(
                        current,
                        at,
                        at,
                        "$newline  (style-class :${operation.name} {$declaration})$newline",
                        patches,
                    )
                } else {
                    style.pairs().forEach { (key, value) ->
                        // Reparse after each property so every patch is relative to the preceding result.
                        val now = parse(original, current).locals.getValue(operation.name)
                        val pair = now.pairs[key]
                        if (value == null) {
                            if (pair != null) {
                                current = change(current, pair.second, "", patches)
                                current = change(current, pair.first, "", patches)
                            }
                        } else if (pair == null) {
                            val at = now.declarations.span.endOffset - 1
                            val separator = if (now.declarations.values.isEmpty()) "" else " "
                            current = change(current, at, at, "$separator:$key :$value", patches)
                        } else if (pair.second.keyword != value) {
                            current = change(current, pair.second, ":$value", patches)
                        }
                    }
                }
            }
            budget(current)
        }
        val parsed = parse(original, current)
        val sink = DiagnosticSink()
        val layout = try {
            LayoutReader.read(parsed.document.source, sink)
        } catch (_: ArithmeticException) {
            invalid("Layout integer options must be exact supported integers", parsed.document)
        } catch (_: IllegalArgumentException) {
            invalid("Layout options could not be read", parsed.document)
        }
        if (layout == null || sink.hasErrors) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Layout style classes cannot be read", sink.all)
        }
        val palettes = palettes(parsed)
        val classNames = linkedSetOf<String>()
        palettes.forEach { (_, rules) -> classNames += rules.keys }
        classNames += parsed.locals.keys
        classNames += parsed.references.map(Reference::name)
        return Result(
            layout.id,
            parsed.presets,
            current,
            classNames.map { name ->
                val inherited = palettes.fold(empty) { style, (_, rules) -> style.merge(rules[name] ?: empty) }
                val local = parsed.locals[name]?.style()
                val references = parsed.references.filter { it.name == name }
                val providedBy = palettes.filter { (_, rules) -> name in rules }.map { it.first }
                val blocked = local != null && providedBy.isEmpty() && references.any { it.kind == "use" }
                linkedMapOf(
                    "name" to name,
                    "presets" to providedBy,
                    "local" to local?.data(),
                    "inherited" to inherited.data(),
                    "effective" to inherited.merge(local ?: empty).data(),
                    "references" to references.map(Reference::data),
                    "editable" to names.matches(name),
                    "reason" to if (names.matches(name)) {
                        null
                    } else {
                        "This selector name cannot be defined as a simple local style class. Edit its source directly."
                    },
                    "canRemove" to (local != null && !blocked),
                    "removeReason" to when {
                        local == null -> "There is no local definition to remove."
                        blocked -> "This class is used by :use and has no selected preset fallback."
                        else -> null
                    },
                )
            },
            patches,
        )
    }

    private fun parse(original: SourceText, text: String): Parsed {
        val sink = DiagnosticSink()
        val document = Document.read(SourceText(original.name, text, original.base), sink)
            ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Layout source cannot be read", sink.all)
        val root = document.root as? DslForm.Sequence
        if (root == null || root.listHead != "layout" || root.values.size < 2) {
            invalid("Style classes require one root layout declaration", document)
        }
        val metadata = root.values.getOrNull(2)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }
        val options = document.options(metadata, sink, "layout style classes")
        if (sink.hasErrors) {
            throw WorkspaceException(
                WorkspaceProblem.INVALID,
                "Layout metadata cannot be located unambiguously",
                sink.all,
            )
        }
        val presets = options["style-preset"]?.let { form ->
            when {
                form.keyword != null -> listOf(checkNotNull(form.keyword))
                form.isSequence(DslFormSequenceKind.VECTOR) -> (form as DslForm.Sequence).values.map {
                    it.keyword ?: invalid("Style presets must be keyword names", document)
                }
                else -> invalid("Style presets must be a keyword or a vector of keywords", document)
            }
        }.orEmpty()
        val declarations = root.values.drop(if (metadata == null) 2 else 3)
        val locals = linkedMapOf<String, Local>()
        declarations.filterIsInstance<DslForm.Sequence>().filter { it.listHead == "style-class" }.forEach { form ->
            val name = form.values.getOrNull(1)?.keyword
            val map = form.values.getOrNull(2) as? DslForm.Sequence
            if (form.values.size != 3 || name == null || !names.matches(name) ||
                map == null || !map.isSequence(DslFormSequenceKind.MAP)
            ) {
                invalid("Style class definitions must have one simple keyword name and declaration map", document)
            }
            val properties = document.options(map, sink, "style class :$name")
            if (sink.hasErrors) {
                throw WorkspaceException(
                    WorkspaceProblem.INVALID,
                    "Style definitions cannot be located unambiguously",
                    sink.all,
                )
            }
            if (properties.any { (key, value) -> key !in choices || value.keyword !in choices.getValue(key) }) {
                invalid("Style classes only accept finite weight, tone and fill keywords", document)
            }
            val local = Local(
                form,
                map,
                map.values.chunked(2).filter { it.size == 2 }.associate { (key, value) ->
                    checkNotNull(key.keyword) to (key to value)
                },
            )
            if (locals.put(name, local) != null) invalid("Duplicate local style class :$name", document)
        }
        val references = mutableListOf<Reference>()
        declarations.filterIsInstance<DslForm.Sequence>().filter { it.listHead == "style" }.forEach { style ->
            val selector = document.options(style.values.getOrNull(1), sink, "style selector")
            val properties = document.options(style.values.getOrNull(2), sink, "style declarations")
            selector["class"]?.let { form ->
                (form.keyword ?: form.symbol)?.let { references += Reference(it, "selector", form) }
            }
            properties["use"]?.let { form ->
                val values = if (form.isSequence(
                        DslFormSequenceKind.VECTOR,
                    )
                ) {
                    (form as DslForm.Sequence).values
                } else {
                    listOf(form)
                }
                values.forEach { value -> value.keyword?.let { references += Reference(it, "use", value) } }
            }
        }
        if (sink.hasErrors) {
            throw WorkspaceException(
                WorkspaceProblem.INVALID,
                "Style definitions cannot be located unambiguously",
                sink.all,
            )
        }
        return Parsed(document, root, presets, locals, references.sortedBy { it.form.span.startOffset })
    }

    /** Read palette contents from the public reader rather than mirroring its internal registry. */
    private fun palettes(parsed: Parsed): List<Pair<String, Map<String, TemplateStyleSpec>>> =
        parsed.presets.map { preset ->
            val sink = DiagnosticSink()
            val layout = LayoutReader.read(
                SourceText("style-preset/$preset", "(layout catalog {:style-preset :$preset})"),
                sink,
            )
            if (layout == null ||
                sink.hasErrors
            ) {
                invalid("The selected style preset :$preset cannot be read", parsed.document)
            }
            preset to layout.styleRules.mapNotNull { rule ->
                rule.selector.klass?.let { it to rule.style.template() }
            }.toMap(linkedMapOf())
        }

    private fun TemplateStyleSpec.pairs(): Map<String, String?> = linkedMapOf(
        "weight" to weight,
        "tone" to tone,
        "fill" to fill,
    )
    private fun TemplateStyleSpec.data(): Map<String, Any?> = linkedMapOf(
        "weight" to weight,
        "tone" to tone,
        "fill" to fill,
    )
    private fun TemplateStyleSpec.merge(other: TemplateStyleSpec) = TemplateStyleSpec(
        other.weight ?: weight,
        other.tone ?: tone,
        other.fill ?: fill,
    )
    private fun StyleSpec.template() =
        TemplateStyleSpec(weight?.name?.lowercase(), tone?.name?.lowercase(), fill?.name?.lowercase())

    /** Retain all whitespace and comments, including those inside a removed definition. */
    private fun tokenRanges(form: DslForm): List<Pair<Int, Int>> = if (form is DslForm.Sequence) {
        listOf(form.span.startOffset to form.span.startOffset + 1, form.span.endOffset - 1 to form.span.endOffset) +
            form.values.flatMap(::tokenRanges)
    } else {
        listOf(form.span.startOffset to form.span.endOffset)
    }

    private fun change(text: String, form: DslForm, replacement: String, patches: MutableList<Patch>): String =
        change(text, form.span.startOffset, form.span.endOffset, replacement, patches)

    private fun change(text: String, start: Int, end: Int, replacement: String, patches: MutableList<Patch>): String {
        val removed = text.substring(start, end)
        if (removed == replacement) return text
        patches += Patch(start, end, replacement, removed)
        val edited = text.substring(0, start) + replacement + text.substring(end)
        budget(edited)
        return edited
    }

    private fun budget(source: String) {
        if (source.length >
            65_536
        ) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Edited layout exceeds the source budget")
        }
    }

    private fun invalid(message: String, document: Document): Nothing = throw WorkspaceException(
        WorkspaceProblem.INVALID,
        message,
        listOf(Diagnostic(Severity.ERROR, "MANTRA-TEMPLATE-STYLE", message, document.location(document.root))),
    )
}
