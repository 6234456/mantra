package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.BoundaryAggregation
import com.xqiou.mantra.core.model.CheckItem
import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.ChoiceOption
import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.ColumnDecl
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.FieldItem
import com.xqiou.mantra.core.model.Formula
import com.xqiou.mantra.core.model.FunctionDecl
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Item
import com.xqiou.mantra.core.model.LineItem
import com.xqiou.mantra.core.model.MemberDecl
import com.xqiou.mantra.core.model.NodeItem
import com.xqiou.mantra.core.model.NoteItem
import com.xqiou.mantra.core.model.Op
import com.xqiou.mantra.core.model.ParamDecl
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.RatioAggregation
import com.xqiou.mantra.core.model.ReconcileItem
import com.xqiou.mantra.core.model.Rounding
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SchemaMeta
import com.xqiou.mantra.core.model.SectionDisplay
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.model.TotalItem
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads a calculation schema. Grammar (all forms are Normein reader forms):
 *
 * ```
 * (schema <id> {meta}? <decl>*)          ; root document
 * (fragment <decl>*)                     ; included document
 * decl := (include "path") | (param id literal {opts}?) | (input id :type {opts}?)
 *       | (dimension id {opts}) | (defn name [^Type arg ...] body) | item
 * item := (section id "Label" {opts}? item*) | (line id "Label" formula {opts}?)
 *       | (formula-slot id "Label" default-formula {opts}?)
 *       | (field id "Label" {opts}?) | (total id "Label" {opts}?)
 *       | (choice id "Label" {opts}? (option :key "Label" formula {opts}?)+)
 *       | (slot id "Label" {opts}?) | (note "Text" {opts}?)
 * ```
 */
class SchemaReader(private val resolver: SourceResolver) {

    fun read(source: SourceText, sink: DiagnosticSink): Schema? {
        val document = Document.read(source, sink) ?: return null
        val root = document.root as? DslForm.Sequence
        if (root == null || root.listHead != "schema") {
            sink.error(
                "MANTRA-SCHEMA-ROOT",
                "A schema document must start with (schema <id> ...)",
                document.location(document.root),
            )
            return null
        }
        val id = root.values.getOrNull(1)?.let { it.symbol ?: it.string }
        if (id == null) {
            sink.error("MANTRA-SCHEMA-ID", "Schema id is missing", document.location(root))
            return null
        }
        var index = 2
        val metaForm = root.values.getOrNull(2)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }
        val meta = linkedMapOf<String, Value>()
        if (metaForm != null) {
            index = 3
            document.options(metaForm, sink, "schema metadata").forEach { (key, form) ->
                if (key == "version" && form.string?.isNotBlank() != true) {
                    sink.error(
                        "MANTRA-SCHEMA-VERSION",
                        ":version must be nonblank literal text",
                        document.location(form),
                    )
                } else {
                    document.literal(form, sink, "schema metadata :$key", symbolsAsText = true)?.let { meta[key] = it }
                }
            }
        }
        val state = SchemaState(resolver, sink)
        state.sources += source.name
        val rootChildren = mutableListOf<Item>()
        root.values.drop(index).forEach { form -> state.readDeclaration(document, form, rootChildren, topLevel = true) }
        val headline = meta["headline"]
        if (headline != null) {
            val name = (headline as? Value.Text)?.value ?: (headline as? Value.Kw)?.name
            fun hasNode(items: List<Item>): Boolean = items.any { item ->
                when (item) {
                    is SectionItem -> hasNode(item.children)
                    is com.xqiou.mantra.core.model.NodeItem -> item.id == name
                    else -> false
                }
            }
            if (name == null || !hasNode(rootChildren)) {
                sink.error(
                    "MANTRA-SCHEMA-HEADLINE",
                    ":headline must name a declared calculation node",
                    document.location(metaForm ?: root),
                )
            }
        }
        val groupTitles = meta["group-titles"]
        if (groupTitles != null && (
                groupTitles !is Value.MapV || groupTitles.entries.any { (key, value) ->
                    key !is Value.Kw && key !is Value.Text || value !is Value.Text
                }
                )
        ) {
            sink.error(
                "MANTRA-SCHEMA-GROUP-TITLES",
                ":group-titles must map group keys to title strings",
                document.location(metaForm ?: root),
            )
        }
        fun checkPresentation(presentation: Presentation, location: SourceLocation) {
            val labels = presentation.attributes["sign-labels"] ?: return
            if (labels !is Value.MapV || labels.entries.any { (key, value) ->
                    key !is Value.Kw || key.name !in setOf("positive", "negative", "zero") || value !is Value.Text
                }
            ) {
                sink.error(
                    "MANTRA-SCHEMA-SIGN-LABELS",
                    ":sign-labels must map :positive, :negative and :zero to strings",
                    location,
                )
            }
        }
        fun checkItems(items: List<Item>) {
            items.forEach { item ->
                when (item) {
                    is SectionItem -> checkItems(item.children)
                    is com.xqiou.mantra.core.model.NodeItem -> checkPresentation(item.presentation, item.location)
                    else -> Unit
                }
            }
        }
        checkItems(rootChildren)
        state.inputs.forEach { input ->
            checkPresentation(input.presentation, input.location)
            val group = input.presentation.attributes["group"]
            if (group != null && group !is Value.Kw) {
                sink.error("MANTRA-SCHEMA-GROUP", ":group of input ${input.id} must be a keyword", input.location)
            }
        }
        val title = (meta["title"] as? Value.Text)?.value ?: id
        return Schema(
            meta = SchemaMeta(id, title, meta),
            params = state.params,
            inputs = state.inputs,
            dimensions = state.dimensions,
            functions = state.functions,
            root = SectionItem(
                id = ROOT_SECTION_ID,
                label = title,
                per = null,
                condition = null,
                op = Op.PLUS,
                display = SectionDisplay.INLINE,
                layout = null,
                title = null,
                children = rootChildren,
                presentation = Presentation(),
                location = document.location(root),
            ),
            sources = state.sources,
        )
    }

    companion object {
        const val ROOT_SECTION_ID: String = "schema"
    }
}

// ── Shared item grammar (also used by case extensions) ─────────────────────────────────────────

internal class ItemReader(
    private val document: Document,
    private val sink: DiagnosticSink,
    private val userDefined: Boolean,
    private val inputs: MutableList<InputDecl>,
) {
    fun read(form: DslForm): Item? {
        val list = form as? DslForm.Sequence
        return when (list?.listHead) {
            "section" -> section(list, slot = false)
            "slot" -> section(list, slot = true)
            "line" -> line(list, formulaSlot = false)
            "formula-slot" -> if (userDefined) {
                sink.error(
                    "MANTRA-FORMULA-SLOT-OWNER",
                    "Only an application schema may declare formula-slot",
                    document.location(list),
                )
                null
            } else {
                line(list, formulaSlot = true)
            }
            "field" -> field(list)
            "total" -> total(list)
            "choice" -> choice(list)
            "check" -> validation(list, reconciliation = false)
            "reconcile" -> validation(list, reconciliation = true)
            "note" -> note(list)
            else -> {
                sink.error(
                    "MANTRA-SCHEMA-FORM",
                    "Unknown schema form `${document.slice(form).take(60)}`",
                    document.location(form),
                )
                null
            }
        }
    }

    private fun header(list: DslForm.Sequence, kind: String): Pair<String, String>? {
        val id = list.values.getOrNull(1)?.symbol
        if (id == null || !isIdentifier(id)) {
            sink.error(
                "MANTRA-SCHEMA-ID",
                "($kind ...) requires an identifier as first argument",
                document.location(list),
            )
            return null
        }
        val label = list.values.getOrNull(2)?.string
        if (label == null) {
            sink.error(
                "MANTRA-SCHEMA-LABEL",
                "($kind $id ...) requires a label string as second argument",
                document.location(list),
            )
            return null
        }
        return id to label
    }

    private fun section(list: DslForm.Sequence, slot: Boolean): SectionItem? {
        val kind = if (slot) "slot" else "section"
        val (id, label) = header(list, kind) ?: return null
        var index = 3
        val optsForm = list.values.getOrNull(3)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }
        if (optsForm != null) index = 4
        val opts = document.options(optsForm, sink, "$kind $id")
        val children = mutableListOf<Item>()
        if (slot && list.values.size > index) {
            sink.error(
                "MANTRA-SLOT-CONTENT",
                "(slot $id ...) cannot contain items; cases fill slots with (extend $id ...)",
                document.location(list),
            )
        } else {
            list.values.drop(index).forEach { child -> read(child)?.let(children::add) }
        }
        return SectionItem(
            id = id,
            label = label,
            per = per(opts["per"], "$kind $id"),
            condition = opts["when"]?.let(document::formula),
            op = op(opts["op"], Op.PLUS, "$kind $id"),
            display = when (val display = opts["display"]?.keyword) {
                null, "inline" -> SectionDisplay.INLINE
                "schedule" -> SectionDisplay.SCHEDULE
                "hidden" -> SectionDisplay.HIDDEN
                else -> {
                    sink.error(
                        "MANTRA-SCHEMA-DISPLAY",
                        "Unknown :display :$display",
                        document.location(opts.getValue("display")),
                    )
                    SectionDisplay.INLINE
                }
            },
            layout = opts["layout"]?.let { form ->
                form.keyword?.takeIf { it == "tiered" || it == "matrix" } ?: run {
                    sink.error(
                        "MANTRA-SCHEMA-LAYOUT",
                        ":layout of $kind $id must be :tiered or :matrix",
                        document.location(form),
                    )
                    null
                }
            },
            title = opts["title"]?.string,
            children = children,
            presentation = presentation(opts, "$kind $id"),
            location = document.location(list),
            slot = slot,
            userDefined = userDefined,
        )
    }

    private fun line(list: DslForm.Sequence, formulaSlot: Boolean): LineItem? {
        val kind = if (formulaSlot) "formula-slot" else "line"
        val (id, label) = header(list, kind) ?: return null
        val formulaForm = list.values.getOrNull(3)
        if (formulaForm == null) {
            sink.error(
                "MANTRA-LINE-FORMULA",
                "($kind $id ...) requires a formula; use (field ...) for inputs",
                document.location(list),
            )
            return null
        }
        val opts = document.options(list.values.getOrNull(4), sink, "$kind $id")
        if (!formulaSlot && "uses" in opts) {
            sink.error(
                "MANTRA-FORMULA-SLOT-USES",
                ":uses is only valid on formula-slot",
                document.location(opts.getValue("uses")),
            )
        }
        val allowedRefs = opts["uses"]?.let { form ->
            if (!form.isSequence(DslFormSequenceKind.VECTOR)) {
                sink.error(
                    "MANTRA-FORMULA-SLOT-USES",
                    ":uses of $id must be a vector of root names",
                    document.location(form),
                )
                emptySet()
            } else {
                (form as DslForm.Sequence).values.mapNotNull { root ->
                    root.symbol ?: run {
                        sink.error(
                            "MANTRA-FORMULA-SLOT-USES",
                            ":uses of $id must list root symbols",
                            document.location(root),
                        )
                        null
                    }
                }.toSet()
            }
        }
        if (list.values.size > 5) {
            sink.error(
                "MANTRA-LINE-ARITY",
                "($kind $id ...) has unexpected trailing forms",
                document.location(list.values[5]),
            )
        }
        return LineItem(
            id = id,
            label = label,
            formula = document.formula(formulaForm),
            op = op(opts["op"], Op.PLUS, "line $id"),
            per = per(opts["per"], "line $id"),
            condition = opts["when"]?.let(document::formula),
            rounding = rounding(opts["round"], "line $id"),
            type = type(opts["type"], ValueType.DECIMAL, "line $id"),
            spread = opts["spread"]?.symbol == "true",
            presentation = presentation(opts, "line $id"),
            location = document.location(list),
            userDefined = userDefined,
            formulaSlot = formulaSlot,
            allowedRefs = allowedRefs,
            aggregate = aggregate(opts["aggregate"], "line $id"),
            ratio = ratio(opts["aggregate"], "line $id"),
            boundary = boundary(opts["aggregate"], "line $id"),
        )
    }

    private fun aggregate(form: DslForm?, what: String): AggregateRule = when {
        form == null || form.symbol == "true" || form.keyword == "sum" -> AggregateRule.SUM
        form.symbol == "false" || form.keyword == "none" -> AggregateRule.NONE
        form.isSequence(DslFormSequenceKind.MAP) -> {
            val opts = document.options(form, sink, "$what :aggregate")
            if ("first" in opts || "last" in opts) AggregateRule.SUM else AggregateRule.RATIO
        }
        else -> {
            sink.error(
                "MANTRA-AGGREGATE",
                ":aggregate of $what must be :sum, :none, a ratio, {:first dimension}, or {:last dimension}",
                document.location(form),
            )
            AggregateRule.SUM
        }
    }

    private fun ratio(form: DslForm?, what: String): RatioAggregation? {
        if (form == null || !form.isSequence(DslFormSequenceKind.MAP)) return null
        val opts = document.options(form, sink, "$what :aggregate")
        if ("first" in opts || "last" in opts) return null
        val refs = (opts["ratio"] as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }?.values
        if (refs == null || refs.size != 2 || refs.any { it.symbol == null || !isIdentifier(it.symbol!!) } ||
            opts.keys.any { it !in setOf("ratio", "round") }
        ) {
            sink.error(
                "MANTRA-AGGREGATE",
                ":aggregate of $what requires {:ratio [numerator-node denominator-node] :round ...?}",
                document.location(form),
            )
            return null
        }
        return RatioAggregation(refs[0].symbol!!, refs[1].symbol!!, rounding(opts["round"], "$what :aggregate"))
    }

    private fun boundary(form: DslForm?, what: String): BoundaryAggregation? {
        if (form == null || !form.isSequence(DslFormSequenceKind.MAP)) return null
        val opts = document.options(form, sink, "$what :aggregate")
        if ("first" !in opts && "last" !in opts) return null
        val name = opts.keys.singleOrNull()
        val dimension = name?.let { opts[it]?.symbol }
        if (name !in setOf("first", "last") || dimension == null || !isIdentifier(dimension)) {
            sink.error(
                "MANTRA-AGGREGATE",
                ":aggregate of $what requires exactly {:first period-dimension} or {:last period-dimension}",
                document.location(form),
            )
            return null
        }
        return BoundaryAggregation(
            dimension,
            if (name == "first") BoundaryAggregation.Boundary.FIRST else BoundaryAggregation.Boundary.LAST,
        )
    }

    private fun validation(list: DslForm.Sequence, reconciliation: Boolean): NodeItem? {
        val kind = if (reconciliation) "reconcile" else "check"
        val (id, label) = header(list, kind) ?: return null
        val optionIndex = if (reconciliation) 5 else 4
        val left = list.values.getOrNull(3)
        val right = if (reconciliation) list.values.getOrNull(4) else null
        if (left == null || reconciliation && right == null || list.values.size > optionIndex + 1) {
            sink.error("MANTRA-CHECK-ARITY", "($kind $id ...) has invalid arguments", document.location(list))
            return null
        }
        val opts = document.options(list.values.getOrNull(optionIndex), sink, "$kind $id")
        val requestedSeverity = opts["severity"]
        val severity = when {
            requestedSeverity == null || requestedSeverity.keyword == "error" -> Severity.ERROR
            requestedSeverity.keyword == "warning" -> Severity.WARNING
            else -> {
                sink.error("MANTRA-CHECK-SEVERITY", ":severity must be :error or :warning", document.location(list))
                Severity.ERROR
            }
        }
        if ("op" in opts && opts["op"]?.keyword != "info") {
            sink.error("MANTRA-CHECK-OP", "$kind $id never contributes to totals", document.location(list))
        }
        val per = per(opts["per"], "$kind $id")
        val condition = opts["when"]?.let(document::formula)
        val presentation = presentation(opts, "$kind $id")
        if (!reconciliation) {
            return CheckItem(
                id, label, document.formula(left), severity, per, condition, presentation,
                document.location(list), userDefined,
            )
        }
        val tolerance = opts["tolerance"]?.number ?: BigDecimal.ZERO
        if (opts["tolerance"] != null && opts["tolerance"]?.number == null || tolerance.signum() < 0) {
            sink.error(
                "MANTRA-RECONCILE-TOLERANCE",
                ":tolerance must be a nonnegative numeric literal",
                document.location(list),
            )
        }
        return ReconcileItem(
            id, label, document.formula(left), document.formula(right!!), tolerance, severity,
            per, condition, presentation, document.location(list), userDefined,
        )
    }

    private fun field(list: DslForm.Sequence): FieldItem? {
        val (id, label) = header(list, "field") ?: return null
        val opts = document.options(list.values.getOrNull(3), sink, "field $id")
        inputs += inputDecl(id, label, opts, document.location(list), defaultPerInherit = true)
        return FieldItem(
            id = id,
            label = label,
            op = op(opts["op"], Op.PLUS, "field $id"),
            presentation = presentation(opts, "field $id"),
            location = document.location(list),
            userDefined = userDefined,
        )
    }

    private fun total(list: DslForm.Sequence): TotalItem? {
        val (id, label) = header(list, "total") ?: return null
        val opts = document.options(list.values.getOrNull(3), sink, "total $id")
        return TotalItem(
            id = id,
            label = label,
            condition = opts["when"]?.let(document::formula),
            presentation = presentation(opts, "total $id"),
            location = document.location(list),
            userDefined = userDefined,
            aggregate = aggregate(opts["aggregate"], "total $id"),
            ratio = ratio(opts["aggregate"], "total $id"),
            boundary = boundary(opts["aggregate"], "total $id"),
        )
    }

    private fun choice(list: DslForm.Sequence): ChoiceItem? {
        val (id, label) = header(list, "choice") ?: return null
        var index = 3
        val optsForm = list.values.getOrNull(3)?.takeIf { it.isSequence(DslFormSequenceKind.MAP) }
        if (optsForm != null) index = 4
        val opts = document.options(optsForm, sink, "choice $id")
        val rule = when (val rule = opts["rule"]?.keyword) {
            "min" -> ChoiceRule.MIN
            "max" -> ChoiceRule.MAX
            else -> {
                sink.error(
                    "MANTRA-CHOICE-RULE",
                    "(choice $id ...) requires :rule :min or :rule :max (found ${rule ?: "nothing"})",
                    document.location(list),
                )
                ChoiceRule.MAX
            }
        }
        val options = list.values.drop(index).mapNotNull { form ->
            val option = form as? DslForm.Sequence
            if (option?.listHead != "option") {
                sink.error(
                    "MANTRA-CHOICE-OPTION",
                    "(choice $id ...) may only contain (option :key \"Label\" formula)",
                    document.location(form),
                )
                return@mapNotNull null
            }
            val key = option.values.getOrNull(1)?.keyword
            val optionLabel = option.values.getOrNull(2)?.string
            val formula = option.values.getOrNull(3)
            if (key == null || optionLabel == null || formula == null) {
                sink.error(
                    "MANTRA-CHOICE-OPTION",
                    "(option :key \"Label\" formula {opts}?) is incomplete",
                    document.location(option),
                )
                return@mapNotNull null
            }
            val optionOpts = document.options(option.values.getOrNull(4), sink, "option :$key")
            ChoiceOption(
                key,
                optionLabel,
                document.formula(formula),
                optionOpts["when"]?.let(document::formula),
                document.location(option),
            )
        }
        if (options.size < 2) {
            sink.error("MANTRA-CHOICE-OPTION", "(choice $id ...) needs at least two options", document.location(list))
        }
        return ChoiceItem(
            id = id,
            label = label,
            rule = rule,
            options = options,
            op = op(opts["op"], Op.PLUS, "choice $id"),
            per = per(opts["per"], "choice $id"),
            condition = opts["when"]?.let(document::formula),
            rounding = rounding(opts["round"], "choice $id"),
            presentation = presentation(opts, "choice $id"),
            location = document.location(list),
            userDefined = userDefined,
            aggregate = aggregate(opts["aggregate"], "choice $id"),
            ratio = ratio(opts["aggregate"], "choice $id"),
            boundary = boundary(opts["aggregate"], "choice $id"),
        )
    }

    private fun note(list: DslForm.Sequence): NoteItem? {
        val text = list.values.getOrNull(1)?.string
        if (text == null) {
            sink.error("MANTRA-NOTE-TEXT", "(note \"Text\") requires a string", document.location(list))
            return null
        }
        val opts = document.options(list.values.getOrNull(2), sink, "note")
        return NoteItem(text, presentation(opts, "note"), document.location(list), userDefined)
    }

    fun inputDecl(
        id: String,
        label: String?,
        opts: Map<String, DslForm>,
        location: SourceLocation,
        defaultPerInherit: Boolean,
    ): InputDecl {
        val type = type(opts["type"], ValueType.DECIMAL, "input $id")
        val columns = opts["columns"]?.let { columns(it, id) } ?: emptyList()
        val references = opts["references"]?.let { form ->
            document.options(form, sink, "input $id :references").mapNotNull { (column, target) ->
                val dimension = target.symbol
                if (dimension == null) {
                    sink.error(
                        "MANTRA-INPUT-REFERENCE",
                        "Reference target of :$column in $id must be a dimension name",
                        document.location(target),
                    )
                    null
                } else {
                    column to dimension
                }
            }.toMap()
        }.orEmpty()
        if (type == ValueType.TABLE && columns.isEmpty()) {
            sink.error("MANTRA-INPUT-COLUMNS", "Table input $id requires :columns {:name :type ...}", location)
        }
        val minRows = opts["min-rows"]?.let { form ->
            val value = runCatching { form.number?.intValueExact() }.getOrNull()
            if (value == null || value < 0 || type != ValueType.TABLE) {
                sink.error(
                    "MANTRA-INPUT-MIN-ROWS",
                    ":min-rows requires a nonnegative integer on a table input",
                    document.location(form),
                )
                null
            } else {
                value
            }
        }
        val options = linkedMapOf<String, String>()
        opts["options"]?.let { form ->
            when (val literal = document.literal(form, sink, "input $id :options")) {
                is Value.Vec -> literal.items.forEach { (it as? Value.Kw)?.let { kw -> options[kw.name] = kw.name } }
                is Value.MapV -> literal.entries.forEach { (k, v) ->
                    val key = (k as? Value.Kw)?.name
                    if (key != null) options[key] = (v as? Value.Text)?.value ?: key
                }
                else -> sink.error(
                    "MANTRA-INPUT-OPTIONS",
                    ":options must be a vector or map of keywords",
                    document.location(form),
                )
            }
        }
        return InputDecl(
            id = id,
            label = label ?: opts["label"]?.string,
            type = type,
            per = opts["per"]?.let { per(it, "input $id") } ?: if (defaultPerInherit) null else emptyList(),
            default = opts["default"]?.let { document.literal(it, sink, "input $id :default") },
            optional = opts["optional"]?.symbol == "true",
            options = options,
            columns = columns,
            presentation = presentation(opts, "input $id"),
            location = location,
            references = references,
            requiredWhen = opts["required-when"]?.let(document::formula),
            minRows = minRows,
            aggregate = aggregate(opts["aggregate"], "input $id"),
            ratio = ratio(opts["aggregate"], "input $id"),
            boundary = boundary(opts["aggregate"], "input $id"),
        )
    }

    private fun columns(form: DslForm, id: String): List<ColumnDecl> {
        val map = document.options(form, sink, "input $id :columns")
        return map.mapNotNull { (name, specification) ->
            val opts = if (specification.isSequence(DslFormSequenceKind.MAP)) {
                document.options(specification, sink, "input $id column :$name")
            } else {
                emptyMap()
            }
            val typeForm = opts["type"] ?: specification
            val raw = typeForm.keyword
            val optional = raw?.endsWith("?") == true
            val type = raw?.removeSuffix("?")?.let(ValueType::of)
            if (type == null || type == ValueType.TABLE) {
                sink.error(
                    "MANTRA-INPUT-COLUMNS",
                    "Column :$name of $id needs a scalar type keyword such as :decimal",
                    document.location(typeForm),
                )
                null
            } else {
                ColumnDecl(name, type, optional, opts["required-when"]?.let(document::formula))
            }
        }
    }

    fun per(form: DslForm?, what: String): List<String>? {
        if (form == null) return null
        form.symbol?.let { return listOf(it) }
        if (form.isSequence(DslFormSequenceKind.VECTOR)) {
            return (form as DslForm.Sequence).values.mapNotNull { dim ->
                dim.symbol ?: run {
                    sink.error("MANTRA-SCHEMA-PER", ":per of $what must list dimension symbols", document.location(dim))
                    null
                }
            }
        }
        sink.error("MANTRA-SCHEMA-PER", ":per of $what must be a dimension symbol or vector", document.location(form))
        return null
    }

    fun op(form: DslForm?, default: Op, what: String): Op {
        if (form == null) return default
        val op = form.keyword?.let(Op::of)
        if (op ==
            null
        ) {
            sink.error("MANTRA-SCHEMA-OP", ":op of $what must be :plus, :minus or :info", document.location(form))
        }
        return op ?: default
    }

    fun type(form: DslForm?, default: ValueType, what: String): ValueType {
        if (form == null) return default
        val type = form.keyword?.let(ValueType::of)
        if (type == null) sink.error("MANTRA-SCHEMA-TYPE", "Unknown :type for $what", document.location(form))
        return type ?: default
    }

    fun rounding(form: DslForm?, what: String): Rounding? {
        if (form == null) return null
        form.number?.let { return Rounding(it.intValueExact(), RoundingMode.HALF_UP) }
        if (form.isSequence(DslFormSequenceKind.VECTOR)) {
            val values = (form as DslForm.Sequence).values
            val scale = values.getOrNull(0)?.number?.intValueExact()
            val mode = values.getOrNull(1)?.keyword?.let(::roundingMode)
            if (scale != null && mode != null) return Rounding(scale, mode)
        }
        sink.error("MANTRA-SCHEMA-ROUND", ":round of $what must be a scale or [scale :mode]", document.location(form))
        return null
    }

    fun presentation(opts: Map<String, DslForm>, what: String): Presentation {
        val attributes = linkedMapOf<String, Value>()
        opts.filterKeys { it !in STRUCTURAL_KEYS && it !in PRESENTATION_KEYS }.forEach { (key, form) ->
            document.literal(form, sink, "$what :$key")?.let { attributes[key] = it }
        }
        fun text(key: String): String? = opts[key]?.let { form ->
            form.string ?: form.number?.toPlainString() ?: form.keyword ?: run {
                sink.error("MANTRA-SCHEMA-PRESENTATION", ":$key of $what must be a string", document.location(form))
                null
            }
        }
        val classes = opts["class"]?.let { form ->
            val entries = if (form.isSequence(
                    DslFormSequenceKind.VECTOR,
                )
            ) {
                (form as DslForm.Sequence).values
            } else {
                listOf(form)
            }
            entries.mapNotNull { entry ->
                val name = entry.keyword
                if (name == null || !name.matches(Regex("[a-z][a-z0-9-]*"))) {
                    sink.error(
                        "MANTRA-SCHEMA-CLASS",
                        ":class of $what must be a keyword or vector of simple keywords",
                        document.location(entry),
                    )
                    null
                } else {
                    name
                }
            }.distinct()
        }.orEmpty()
        return Presentation(
            reference = text("reference"),
            note = text("note"),
            source = text("source"),
            format = opts["format"]?.keyword,
            precision = opts["precision"]?.number?.intValueExact(),
            hidden = opts["hidden"]?.symbol == "true",
            emphasis = opts["emphasis"]?.keyword,
            attributes = attributes,
            classes = classes,
        )
    }

    companion object {
        val STRUCTURAL_KEYS = setOf(
            "per", "when", "op", "round", "type", "spread", "display", "layout", "title", "rule",
            "default", "optional", "options", "columns", "references", "label", "uses", "aggregate",
            "required-when", "min-rows", "severity", "tolerance",
        )
        val PRESENTATION_KEYS =
            setOf("reference", "note", "source", "format", "precision", "hidden", "emphasis", "class")
    }
}

fun roundingMode(keyword: String): RoundingMode? = when (keyword) {
    "half-up" -> RoundingMode.HALF_UP
    "half-down" -> RoundingMode.HALF_DOWN
    "half-even" -> RoundingMode.HALF_EVEN
    "floor" -> RoundingMode.FLOOR
    "ceiling" -> RoundingMode.CEILING
    "down" -> RoundingMode.DOWN
    "up" -> RoundingMode.UP
    else -> null
}

private fun SchemaState.readParam(document: Document, list: DslForm.Sequence): ParamDecl? {
    val id = list.values.getOrNull(1)?.symbol
    val valueForm = list.values.getOrNull(2)
    if (id == null || !isIdentifier(id) || valueForm == null) {
        sink.error("MANTRA-PARAM", "(param <id> <literal> {opts}?) is malformed", document.location(list))
        return null
    }
    val value = document.literal(valueForm, sink, "param $id") ?: return null
    val opts = document.options(list.values.getOrNull(3), sink, "param $id")
    val items = ItemReader(document, sink, false, mutableListOf())
    return ParamDecl(id, opts["label"]?.string, value, items.presentation(opts, "param $id"), document.location(list))
}

private fun SchemaState.readInput(document: Document, list: DslForm.Sequence): InputDecl? {
    val id = list.values.getOrNull(1)?.symbol
    if (id == null || !isIdentifier(id)) {
        sink.error("MANTRA-INPUT", "(input <id> :type {opts}?) requires an identifier", document.location(list))
        return null
    }
    val second = list.values.getOrNull(2)
    val (typeForm, optsForm) = if (second?.keyword != null) second to list.values.getOrNull(3) else null to second
    val opts = document.options(optsForm, sink, "input $id").toMutableMap()
    if (typeForm != null) opts["type"] = typeForm
    return ItemReader(
        document,
        sink,
        false,
        mutableListOf(),
    ).inputDecl(id, null, opts, document.location(list), defaultPerInherit = false)
}

private fun SchemaState.readDimension(document: Document, list: DslForm.Sequence): DimensionDecl? {
    val id = list.values.getOrNull(1)?.symbol
    if (id == null || !isIdentifier(id)) {
        sink.error("MANTRA-DIMENSION", "(dimension <id> {opts}) requires an identifier", document.location(list))
        return null
    }
    val opts = document.options(list.values.getOrNull(2), sink, "dimension $id")
    val periods = opts["periods"]?.let { PeriodReader(document, sink).read(it, id) }
    if (opts["periods"] != null && periods == null) return null
    if (periods != null && opts.keys.any { it in setOf("members", "from", "when", "parent-key") }) {
        sink.error(
            "MANTRA-PERIOD-DECLARATION",
            "Period dimension $id cannot mix :periods with :members, :from, :when, or :parent-key",
            document.location(list),
        )
    }
    val members = mutableListOf<MemberDecl>()
    opts["members"]?.let { form ->
        if (!form.isSequence(DslFormSequenceKind.VECTOR)) {
            sink.error("MANTRA-DIMENSION", ":members of dimension $id must be a vector", document.location(form))
        } else {
            (form as DslForm.Sequence).values.forEach { member ->
                member.keyword?.let {
                    members += MemberDecl(it, it, null)
                    return@forEach
                }
                val memberOpts = document.options(member, sink, "member of $id")
                val key = memberOpts["key"]?.keyword
                if (key == null) {
                    sink.error("MANTRA-DIMENSION", "Member of dimension $id requires :key", document.location(member))
                } else {
                    members +=
                        MemberDecl(key, memberOpts["label"]?.string ?: key, memberOpts["when"]?.let(document::formula))
                }
            }
        }
    }
    val from = opts["from"]?.symbol
    if (from == null && members.isEmpty() && periods == null) {
        sink.error("MANTRA-DIMENSION", "Dimension $id needs :members or :from <table-input>", document.location(list))
    }
    if (from != null && members.isNotEmpty()) {
        sink.error("MANTRA-DIMENSION", "Dimension $id cannot declare both :members and :from", document.location(list))
    }
    return DimensionDecl(
        id = id,
        label = opts["label"]?.string ?: id,
        members = members,
        fromTable = from,
        keyColumn = opts["key"]?.keyword ?: "id",
        titleColumn = opts["title"]?.keyword,
        parentDimension = opts["parent"]?.symbol,
        parentKeyColumn = opts["parent-key"]?.keyword,
        totalLabel = opts["total-label"]?.string ?: "Total",
        location = document.location(list),
        periods = periods,
    )
}

private fun SchemaState.readFunction(document: Document, list: DslForm.Sequence): FunctionDecl? {
    val name = list.values.getOrNull(1)?.symbol
    if (name == null || !isIdentifier(name)) {
        sink.error("MANTRA-DEFN", "(defn <name> [args] body) requires a name", document.location(list))
        return null
    }
    return FunctionDecl(name, document.slice(list), document.location(list))
}

private class SchemaState(private val resolver: SourceResolver, val sink: DiagnosticSink) {
    val params = mutableListOf<ParamDecl>()
    val inputs = mutableListOf<InputDecl>()
    val dimensions = mutableListOf<DimensionDecl>()
    val functions = mutableListOf<FunctionDecl>()
    val sources = mutableListOf<String>()
    private val includeStack = ArrayDeque<String>()

    fun readDeclaration(document: Document, form: DslForm, items: MutableList<Item>, topLevel: Boolean) {
        when (form.listHead) {
            "include" -> if (topLevel) include(document, form, items) else notAllowed(document, form, "include")
            "param" -> if (topLevel) {
                readParam(
                    document,
                    form as DslForm.Sequence,
                )?.let(params::add)
            } else {
                notAllowed(document, form, "param")
            }
            "input" -> if (topLevel) {
                readInput(
                    document,
                    form as DslForm.Sequence,
                )?.let(inputs::add)
            } else {
                notAllowed(document, form, "input")
            }
            "dimension" -> if (topLevel) {
                readDimension(
                    document,
                    form as DslForm.Sequence,
                )?.let(dimensions::add)
            } else {
                notAllowed(document, form, "dimension")
            }
            "defn" -> if (topLevel) {
                readFunction(
                    document,
                    form as DslForm.Sequence,
                )?.let(functions::add)
            } else {
                notAllowed(document, form, "defn")
            }
            else -> ItemReader(document, sink, userDefined = false, inputs).read(form)?.let(items::add)
        }
    }

    private fun notAllowed(document: Document, form: DslForm, what: String) {
        sink.error(
            "MANTRA-SCHEMA-PLACEMENT",
            "($what ...) is only allowed at the top level of a schema or fragment",
            document.location(form),
        )
    }

    private fun include(document: Document, form: DslForm, items: MutableList<Item>) {
        val path = (form as DslForm.Sequence).values.getOrNull(1)?.string
        if (path == null) {
            sink.error("MANTRA-INCLUDE-PATH", "(include \"path\") requires a string path", document.location(form))
            return
        }
        val source = resolver.resolve(path, document.source)
        if (source == null) {
            sink.error("MANTRA-INCLUDE-MISSING", "Included document `$path` was not found", document.location(form))
            return
        }
        if (source.name in includeStack) {
            sink.error("MANTRA-INCLUDE-CYCLE", "Include cycle through `${source.name}`", document.location(form))
            return
        }
        val included = Document.read(source, sink) ?: return
        val root = included.root as? DslForm.Sequence
        if (root == null || root.listHead != "fragment") {
            sink.error(
                "MANTRA-INCLUDE-ROOT",
                "Included documents must start with (fragment ...)",
                included.location(included.root),
            )
            return
        }
        sources += source.name
        includeStack.addLast(source.name)
        root.values.drop(1).forEach { readDeclaration(included, it, items, topLevel = true) }
        includeStack.removeLast()
    }
}
