package com.xqiou.mantra.render.layout

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.isSequence
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.literal
import com.xqiou.mantra.core.read.number
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.util.Locale

/**
 * Reads the presentation DSL. A layout never changes values; it selects built-in table primitives:
 *
 * ```
 * (layout <id> {:preset :de-staffel-4 :title "..." :locale "de-DE" :precision 2 :negative :minus
 *               :zero "–" :hide-zero true :show-inactive false :expand-members false
 *               :explain :appendix :header [:subject :period ...] :theme :classic
 *               :row-numbers :global|:table}
 *   (operators {:plus "" :minus "./." :total "=" :info ""})
 *   (columns :tiered :label (col :pre {:header "Detail" :width 30}) :main)
 *   (columns :matrix  :label (members person) :cross-total)
 *   (style {:all true} {:tone :default :fill :none})
 *   (style {:class :variance} {:weight :bold :tone :accent})
 *   (style {:section costs :nth-child :even :kind :value} {:fill :subtle})
 *   (table <section-id> {:title "..." :style :matrix :expand-members true} (col ...) ...)
 *   (schedule <section-id> ...) (inline <section-id> ...) (hide <item-id> ...))
 * ```
 */
object LayoutReader {
    fun read(source: SourceText): LayoutSpec {
        val sink = DiagnosticSink()
        val layout = read(source, sink)
        sink.throwIfErrors()
        return layout ?: throw MantraException(sink.all)
    }

    fun read(source: SourceText, sink: DiagnosticSink): LayoutSpec? {
        val document = Document.read(source, sink) ?: return null
        val root = document.root as? DslForm.Sequence
        if (root == null || root.listHead != "layout") {
            sink.error("MANTRA-LAYOUT-ROOT", "A layout document must start with (layout <id> ...)", document.location(document.root))
            return null
        }
        val id = root.values.getOrNull(1)?.let { it.symbol ?: it.string } ?: "layout"
        var index = 2
        val opts = root.values.getOrNull(2)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }?.let {
            index = 3
            document.options(it, sink, "layout $id")
        }.orEmpty()
        val presetName = opts["preset"]?.keyword ?: "de-staffel-4"
        var spec = Presets.of(presetName)?.copy(id = id) ?: run {
            sink.error("MANTRA-LAYOUT-PRESET", "Unknown preset :$presetName (available: ${Presets.ALL.keys.joinToString { ":$it" }})", document.location(root))
            Presets.DE_STAFFEL_4.copy(id = id)
        }
        spec = applyOptions(document, spec, opts, sink)
        val tables = mutableListOf<TableSpec>()
        val styleRules = spec.styleRules.toMutableList()
        root.values.drop(index).forEach { form ->
            val list = form as? DslForm.Sequence
            when (list?.listHead) {
                "operators" -> {
                    val o = document.options(list.values.getOrNull(1), sink, "operators")
                    fun text(key: String, default: String) = o[key]?.string ?: default
                    spec = spec.copy(
                        operators = Operators(
                            plus = text("plus", spec.operators.plus),
                            minus = text("minus", spec.operators.minus),
                            total = text("total", spec.operators.total),
                            info = text("info", spec.operators.info),
                        ),
                    )
                }
                "columns" -> {
                    val target = list.values.getOrNull(1)?.keyword
                    val columns = list.values.drop(2).mapNotNull { column(document, it, sink) }
                    spec = when (target) {
                        "tiered" -> spec.copy(tieredColumns = columns)
                        "matrix" -> spec.copy(matrixColumns = columns)
                        else -> {
                            sink.error("MANTRA-LAYOUT-COLUMNS", "(columns :tiered|:matrix (col ...) ...) expected", document.location(list))
                            spec
                        }
                    }
                }
                "table" -> {
                    val section = list.values.getOrNull(1)?.symbol
                    if (section == null) {
                        sink.error("MANTRA-LAYOUT-TABLE", "(table <section-id> {opts}? (col ...)*) requires a section id", document.location(list))
                    } else {
                        var start = 2
                        val tableOpts = list.values.getOrNull(2)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }?.let {
                            start = 3
                            document.options(it, sink, "table $section")
                        }.orEmpty()
                        val columns = list.values.drop(start).mapNotNull { column(document, it, sink) }
                        tables += TableSpec(
                            sectionId = section,
                            title = tableOpts["title"]?.string,
                            style = tableOpts["style"]?.keyword?.let { style(document, it, list, sink) },
                            columns = columns.ifEmpty { null },
                            expandMembers = tableOpts["expand-members"]?.symbol?.let { it == "true" },
                        )
                    }
                }
                "style" -> {
                    val target = list.values.getOrNull(1)
                    if (target?.isSequence(DslFormSequenceKind.MAP) == true) {
                        val selector = styleSelector(document, target, sink)
                        if (selector != null) styleRules += StyleRule(selector, styleRule(document, list, sink))
                    } else {
                        sink.error("MANTRA-LAYOUT-STYLE", "(style {selector} {declarations}) expected", document.location(list))
                    }
                }
                "schedule" -> spec = spec.copy(schedules = spec.schedules + symbols(document, list, sink))
                "inline" -> spec = spec.copy(inline = spec.inline + symbols(document, list, sink))
                "hide" -> spec = spec.copy(hidden = spec.hidden + symbols(document, list, sink))
                else -> sink.error("MANTRA-LAYOUT-FORM", "Unknown layout form `${document.slice(form).take(60)}`", document.location(form))
            }
        }
        return spec.copy(tables = tables, styleRules = styleRules)
    }

    private fun styleSelector(document: Document, form: DslForm, sink: DiagnosticSink): StyleSelector? {
        val opts = document.options(form, sink, "style selector")
        if (opts.isEmpty()) {
            sink.error("MANTRA-LAYOUT-SELECTOR", "Style selector must contain at least one condition", document.location(form))
            return null
        }
        val valid = setOf("all", "section", "depth", "height", "indent", "nth-child", "has-row-number", "column", "class", "kind")
        opts.keys.filter { it !in valid }.forEach { sink.error("MANTRA-LAYOUT-SELECTOR", "Unknown style selector :$it", document.location(form)) }
        if ("all" in opts && (opts.size != 1 || opts["all"]?.symbol != "true")) {
            sink.error("MANTRA-LAYOUT-SELECTOR", ":all must be true and used alone", document.location(form))
        }
        fun name(key: String): String? = opts[key]?.let { value ->
            value.symbol ?: value.keyword ?: run {
                sink.error("MANTRA-LAYOUT-SELECTOR", ":$key must be a name", document.location(value))
                null
            }
        }
        fun level(key: String): Int? = opts[key]?.let { value ->
            value.number?.let { runCatching { it.intValueExact() }.getOrNull() }?.takeIf { it >= 0 } ?: run {
                sink.error("MANTRA-LAYOUT-SELECTOR", ":$key must be a nonnegative integer", document.location(value))
                null
            }
        }
        val nthChild = opts["nth-child"]?.let { value -> when (value.keyword) {
            "odd" -> RowParity.ODD
            "even" -> RowParity.EVEN
            else -> {
                sink.error("MANTRA-LAYOUT-SELECTOR", ":nth-child must be :odd or :even", document.location(value))
                null
            }
        } }
        val numbered = opts["has-row-number"]?.let { value -> when (value.symbol) {
            "true" -> true
            "false" -> false
            else -> {
                sink.error("MANTRA-LAYOUT-SELECTOR", ":has-row-number must be true or false", document.location(value))
                null
            }
        } }
        return StyleSelector(
            all = opts["all"]?.symbol == "true",
            section = name("section"), depth = level("depth"), height = level("height"), indent = level("indent"),
            nthChild = nthChild, hasRowNumber = numbered, column = name("column"), klass = name("class"), rowKind = name("kind"),
        )
    }

    private fun styleRule(document: Document, list: DslForm.Sequence, sink: DiagnosticSink): StyleSpec {
        if (list.values.size > 3) sink.error("MANTRA-LAYOUT-STYLE", "(style {selector} {declarations}) has unexpected trailing forms", document.location(list.values[3]))
        val opts = document.options(list.values.getOrNull(2), sink, "style declarations")
        opts.keys.filter { it !in setOf("weight", "tone", "fill") }.forEach { key ->
            sink.error("MANTRA-LAYOUT-STYLE", "Unknown style property :$key", document.location(list))
        }
        fun <T> choice(key: String, values: Map<String, T>): T? = opts[key]?.let { form ->
            values[form.keyword] ?: run {
                sink.error("MANTRA-LAYOUT-STYLE", ":$key must be one of ${values.keys.joinToString { ":$it" }}", document.location(form))
                null
            }
        }
        return StyleSpec(
            weight = choice("weight", mapOf("normal" to StyleWeight.NORMAL, "bold" to StyleWeight.BOLD)),
            tone = choice("tone", mapOf("default" to StyleTone.DEFAULT, "muted" to StyleTone.MUTED, "accent" to StyleTone.ACCENT)),
            fill = choice("fill", mapOf("none" to StyleFill.NONE, "subtle" to StyleFill.SUBTLE, "accent" to StyleFill.ACCENT)),
        )
    }

    private fun applyOptions(document: Document, base: LayoutSpec, opts: Map<String, DslForm>, sink: DiagnosticSink): LayoutSpec {
        var spec = base
        opts["title"]?.string?.let { spec = spec.copy(title = it) }
        opts["subtitle"]?.string?.let { spec = spec.copy(subtitle = it) }
        opts["locale"]?.string?.let { spec = spec.copy(number = spec.number.copy(locale = Locale.forLanguageTag(it))) }
        opts["language"]?.keyword?.let { spec = spec.copy(texts = if (it == "en") Texts.EN else Texts.DE) }
        opts["precision"]?.number?.let { spec = spec.copy(number = spec.number.copy(precision = it.intValueExact())) }
        opts["percent-precision"]?.number?.let { spec = spec.copy(number = spec.number.copy(percentPrecision = it.intValueExact())) }
        opts["negative"]?.keyword?.let {
            spec = spec.copy(number = spec.number.copy(negative = if (it == "parentheses") NegativeStyle.PARENTHESES else NegativeStyle.MINUS))
        }
        opts["zero"]?.let { form -> spec = spec.copy(number = spec.number.copy(zero = form.string)) }
        opts["grouping"]?.symbol?.let { spec = spec.copy(number = spec.number.copy(grouping = it == "true")) }
        opts["hide-zero"]?.symbol?.let { spec = spec.copy(hideZero = it == "true") }
        opts["show-inactive"]?.symbol?.let { spec = spec.copy(showInactive = it == "true") }
        opts["expand-members"]?.symbol?.let { spec = spec.copy(expandMembers = it == "true") }
        opts["signed"]?.symbol?.let { spec = spec.copy(signedValues = it == "true") }
        opts["explain"]?.keyword?.let { spec = spec.copy(explain = if (it == "none") ExplainMode.NONE else ExplainMode.APPENDIX) }
        opts["theme"]?.keyword?.let { spec = spec.copy(theme = it) }
        opts["row-numbers"]?.let { form ->
            spec = when (form.keyword) {
                "global" -> spec.copy(rowNumbers = RowNumberMode.GLOBAL)
                "table" -> spec.copy(rowNumbers = RowNumberMode.TABLE)
                else -> {
                    sink.error("MANTRA-LAYOUT-ROW-NUMBERS", ":row-numbers must be :global or :table", document.location(form))
                    spec
                }
            }
        }
        opts["header"]?.let { form ->
            val header = document.literal(form, sink, "layout :header")
            if (header is Value.Vec) spec = spec.copy(header = header.items.mapNotNull { (it as? Value.Kw)?.name })
        }
        return spec
    }

    private fun style(document: Document, name: String, form: DslForm, sink: DiagnosticSink): TableStyle? = when (name) {
        "tiered" -> TableStyle.TIERED
        "matrix" -> TableStyle.MATRIX
        else -> {
            sink.error("MANTRA-LAYOUT-STYLE", "Table :style must be :tiered or :matrix", document.location(form))
            null
        }
    }

    private fun symbols(document: Document, list: DslForm.Sequence, sink: DiagnosticSink): Set<String> =
        list.values.drop(1).mapNotNull { form ->
            form.symbol ?: run {
                sink.error("MANTRA-LAYOUT-ID", "Expected an item or section identifier", document.location(form))
                null
            }
        }.toSet()

    private fun column(document: Document, form: DslForm, sink: DiagnosticSink): ColumnSpec? {
        form.keyword?.let { name ->
            val content = ColumnContent.of(name)
            if (content == null) {
                sink.error("MANTRA-LAYOUT-CONTENT", "Unknown column :$name; built-ins: ${ColumnContent.catalog.joinToString()}", document.location(form))
                return null
            }
            return ColumnSpec(id = name, header = null, content = content)
        }
        val list = form as? DslForm.Sequence
        when (list?.listHead) {
            "col" -> {
                val id = list.values.getOrNull(1)?.let { it.keyword ?: it.symbol }
                if (id == null) {
                    sink.error("MANTRA-LAYOUT-COL", "(col :name {opts}?) requires a column name", document.location(list))
                    return null
                }
                val opts = document.options(list.values.getOrNull(2), sink, "col $id")
                val contentForm = opts["content"]
                val content = when {
                    contentForm == null -> ColumnContent.of(id)
                    contentForm.keyword != null -> ColumnContent.of(contentForm.keyword!!)
                    else -> memberContent(contentForm) ?: attributeContent(contentForm)
                }
                if (content == null) {
                    sink.error(
                        "MANTRA-LAYOUT-CONTENT",
                        "Unknown column content for $id; built-ins: ${ColumnContent.catalog.joinToString()}",
                        document.location(list),
                    )
                    return null
                }
                return ColumnSpec(
                    id = id,
                    header = opts["header"]?.string,
                    content = content,
                    width = opts["width"]?.number?.intValueExact(),
                    align = when (opts["align"]?.keyword) {
                        "left" -> Align.LEFT
                        "right" -> Align.RIGHT
                        "center" -> Align.CENTER
                        else -> null
                    },
                )
            }
            "members", "member", "attribute" -> {
                val content = memberContent(list) ?: attributeContent(list) ?: run {
                    sink.error("MANTRA-LAYOUT-CONTENT", "(members <dimension> {opts}?), (member <dimension> :key), or (attribute :name {opts}?) expected", document.location(list))
                    return null
                }
                val opts = document.options(list.values.lastOrNull()?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }, sink, "column")
                return ColumnSpec(
                    id = if (content is ColumnContent.Attribute) content.name else document.slice(list).filter { it.isLetterOrDigit() || it == '-' }.take(40),
                    header = opts["header"]?.string,
                    content = content,
                    width = opts["width"]?.number?.intValueExact(),
                )
            }
            else -> {
                sink.error("MANTRA-LAYOUT-COL", "Columns are declared with :name, (col :name {opts}?), (attribute :name), (members <dim>) or (member <dim> :key)", document.location(form))
                return null
            }
        }
    }

    private fun memberContent(form: DslForm): ColumnContent? {
        val list = form as? DslForm.Sequence ?: return null
        val dimension = list.values.getOrNull(1)?.symbol ?: return null
        return when (list.listHead) {
            "members" -> ColumnContent.Members(dimension)
            "member" -> list.values.getOrNull(2)?.keyword?.let { ColumnContent.Member(dimension, it) }
            else -> null
        }
    }

    private fun attributeContent(form: DslForm): ColumnContent.Attribute? {
        val list = form as? DslForm.Sequence ?: return null
        if (list.listHead != "attribute") return null
        val name = list.values.getOrNull(1)?.keyword ?: return null
        return ColumnContent.Attribute(name)
    }
}
