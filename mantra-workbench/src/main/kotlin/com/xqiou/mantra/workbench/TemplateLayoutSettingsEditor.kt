package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.isSequence
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.literal
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.workbench.json.WorkbenchJson
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.util.Locale

/** A bounded source editor, not a second layout parser or a calculation evaluator. */
internal object TemplateLayoutSettingsEditor {
    data class Patch(val startOffset: Int, val endOffset: Int, val text: String, val removedText: String)
    data class Result(
        val layout: LayoutSpec,
        val text: String,
        val properties: List<Map<String, Any?>>,
        val patches: List<Patch>,
    )

    private data class Property(
        val id: String,
        val label: String,
        val group: String,
        val type: String,
        val options: List<String> = emptyList(),
        val value: (LayoutSpec) -> Value,
    ) {
        fun supported(value: Value?): Boolean = when (type) {
            "text" -> value is Value.Text && (id != "locale" || validLocale(value.value))
            "boolean" -> value is Value.Bool
            "enum" -> value is Value.Kw && value.name in options
            "integer" -> value is Value.Num && runCatching { value.value.intValueExact() in 0..18 }.getOrDefault(false)
            else -> false
        }

        fun input(text: String, document: Document): Value = when (type) {
            "text" -> Value.Text(text).also {
                if (id == "locale" &&
                    !validLocale(text)
                ) {
                    invalid("locale must be a BCP 47 tag with a language", document)
                }
            }
            "boolean" -> when (text) {
                "true" -> Value.Bool(true)
                "false" -> Value.Bool(false)
                else -> invalid("$id must be true or false", document)
            }
            "enum" -> {
                if (text !in options) invalid("$id must be one of ${options.joinToString()}", document)
                Value.Kw(text)
            }
            "integer" -> {
                if (!Regex("[0-9]+").matches(text)) invalid("$id must be an integer from 0 to 18", document)
                val number = text.toBigIntegerOrNull()
                if (number == null || number !in 0.toBigInteger()..18.toBigInteger()) {
                    invalid("$id must be an integer from 0 to 18", document)
                }
                Value.Num(number.toBigDecimal())
            }
            else -> error("Unknown setting type")
        }
    }

    private val properties = listOf(
        Property("title", "Title", "report", "text") { it.title.textValue() },
        Property("subtitle", "Subtitle", "report", "text") { it.subtitle.textValue() },
        Property("locale", "Number locale", "numbers", "text") { Value.Text(it.number.locale.toLanguageTag()) },
        Property("language", "Report language", "report", "enum", listOf("de", "en")) { Value.Kw(it.texts.language) },
        Property("precision", "Decimal places", "numbers", "integer") { Value.num(it.number.precision.toLong()) },
        Property("percent-precision", "Percent decimal places", "numbers", "integer") {
            Value.num(it.number.percentPrecision.toLong())
        },
        Property("negative", "Negative numbers", "numbers", "enum", listOf("minus", "parentheses")) {
            Value.Kw(it.number.negative.name.lowercase())
        },
        Property("zero", "Zero text", "numbers", "text") { it.number.zero.textValue() },
        Property("grouping", "Digit grouping", "numbers", "boolean") { Value.Bool(it.number.grouping) },
        Property("hide-zero", "Hide zero rows", "visibility", "boolean") { Value.Bool(it.hideZero) },
        Property("show-inactive", "Show inactive rows", "visibility", "boolean") { Value.Bool(it.showInactive) },
        Property("expand-members", "Expand members", "visibility", "boolean") { Value.Bool(it.expandMembers) },
        Property("signed", "Signed contributions", "numbers", "boolean") { Value.Bool(it.signedValues) },
        Property("row-numbers", "Row numbering", "visibility", "enum", listOf("global", "table")) {
            it.rowNumbers?.let { mode -> Value.Kw(mode.name.lowercase()) } ?: Value.Nil
        },
    )
    val propertyIds: Set<String> = properties.map(Property::id).toSet()

    fun apply(original: SourceText, source: String, operations: List<TemplateLayoutOperation>): Result {
        var current = source
        val patches = mutableListOf<Patch>()
        operations.forEach { operation ->
            val parsed = parse(original, current)
            val property = properties.single { it.id == operation.property }
            val existing = parsed.pairs[property.id]
            if (operation.op == "unset") {
                if (existing != null) {
                    // Removing the two tokens independently retains comments between key and value.
                    current = change(current, existing.second, "", patches)
                    current = change(current, existing.first, "", patches)
                }
            } else {
                val before = existing?.second?.let { parsed.document.literal(it, DiagnosticSink(), "layout setting") }
                if (existing != null && !property.supported(before)) {
                    invalid(
                        "${property.id} has an unsupported declaration; reset it before setting a value",
                        parsed.document,
                    )
                }
                val value = property.input(checkNotNull(operation.text), parsed.document)
                if (existing == null) {
                    val literal = literal(value, parsed.document)
                    val map = parsed.metadata
                    val at = map?.span?.endOffset?.minus(1) ?: parsed.root.values[1].span.endOffset
                    val insertion = when {
                        map == null -> " {:${property.id} $literal}"
                        map.values.isEmpty() -> ":${property.id} $literal"
                        else -> " :${property.id} $literal"
                    }
                    current = change(current, at, at, insertion, patches)
                } else if (!sameValue(before, value)) {
                    current = change(current, existing.second, literal(value, parsed.document), patches)
                }
            }
            if (current.length > 65_536) {
                throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Edited layout exceeds the source budget")
            }
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
            throw WorkspaceException(WorkspaceProblem.INVALID, "Layout settings cannot be read", sink.all)
        }
        val inherited = checkNotNull(Presets.of(layout.preset))
        return Result(
            layout,
            current,
            properties.map { property ->
                val form = parsed.pairs[property.id]?.second
                val explicit = form?.let { parsed.document.literal(it, DiagnosticSink(), "layout setting") }
                val editable = form == null || property.supported(explicit)
                linkedMapOf(
                    "id" to property.id,
                    "label" to property.label,
                    "group" to property.group,
                    "type" to property.type,
                    "options" to property.options,
                    "minimum" to if (property.type == "integer") 0 else null,
                    "maximum" to if (property.type == "integer") 18 else null,
                    "declared" to (form != null),
                    "explicitValue" to explicit?.let(WorkbenchJson::value),
                    "inheritedValue" to WorkbenchJson.value(property.value(inherited)),
                    "effectiveValue" to WorkbenchJson.value(property.value(layout)),
                    "editable" to editable,
                    "reason" to if (editable) {
                        null
                    } else if (explicit == null) {
                        "This declaration is not a supported literal; its value is not evaluated. Reset it to inherit."
                    } else {
                        "This declaration is outside the supported editing range or type. Reset it to inherit."
                    },
                    "canUnset" to (form != null),
                )
            },
            patches,
        )
    }

    private data class Parsed(
        val document: Document,
        val root: DslForm.Sequence,
        val metadata: DslForm.Sequence?,
        val pairs: Map<String, Pair<DslForm, DslForm>>,
    )

    private fun parse(original: SourceText, text: String): Parsed {
        val sink = DiagnosticSink()
        val source = SourceText(original.name, text, original.base)
        val document = Document.read(source, sink)
            ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Layout source cannot be read", sink.all)
        val root = document.root as? DslForm.Sequence
        if (root == null || root.listHead != "layout" || root.values.size < 2) {
            invalid("Settings require one root layout declaration", document)
        }
        val metadata = root.values.getOrNull(2)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) } as? DslForm.Sequence
        document.options(metadata, sink, "layout settings")
        if (sink.hasErrors) {
            throw WorkspaceException(
                WorkspaceProblem.INVALID,
                "Layout settings cannot be located unambiguously",
                sink.all,
            )
        }
        return Parsed(
            document,
            root,
            metadata,
            metadata?.values?.chunked(2)?.associate { (key, value) ->
                checkNotNull(key.keyword) to (key to value)
            }.orEmpty(),
        )
    }

    private fun literal(value: Value, document: Document): String = when (value) {
        is Value.Text -> {
            val encoded = WorkbenchJson.write(value.value)
            val sink = DiagnosticSink()
            val probe = Document.read(SourceText(document.source.name, "(layout settings {:title $encoded})"), sink)
                ?: invalid("This text cannot be represented exactly by the current layout reader", document)
            val root = probe.root as? DslForm.Sequence
            val metadata = root?.values?.getOrNull(2) as? DslForm.Sequence
            val actual = metadata?.values?.getOrNull(1)?.let { probe.literal(it, sink, "layout text") }
            if (sink.hasErrors || actual != value) {
                invalid("This text cannot be represented exactly by the current layout reader", document)
            }
            encoded
        }
        is Value.Num -> value.value.toPlainString()
        is Value.Kw -> ":${value.name}"
        is Value.Bool -> value.value.toString()
        else -> error("Unsupported setting literal")
    }

    private fun sameValue(left: Value?, right: Value): Boolean =
        if (left is Value.Num && right is Value.Num) left.value.compareTo(right.value) == 0 else left == right

    private fun change(text: String, form: DslForm, replacement: String, patches: MutableList<Patch>): String =
        change(text, form.span.startOffset, form.span.endOffset, replacement, patches)

    private fun change(text: String, start: Int, end: Int, replacement: String, patches: MutableList<Patch>): String {
        val removed = text.substring(start, end)
        if (removed == replacement) return text
        patches += Patch(start, end, replacement, removed)
        return text.substring(0, start) + replacement + text.substring(end)
    }

    private fun String?.textValue(): Value = this?.let(Value::Text) ?: Value.Nil

    private fun validLocale(text: String): Boolean = text.isNotBlank() && runCatching {
        Locale.Builder().setLanguageTag(text).build().language.isNotEmpty()
    }.getOrDefault(false)

    private fun invalid(message: String, document: Document): Nothing = throw WorkspaceException(
        WorkspaceProblem.INVALID,
        message,
        listOf(Diagnostic(Severity.ERROR, "MANTRA-TEMPLATE-SETTING", message, document.location(document.root))),
    )
}
