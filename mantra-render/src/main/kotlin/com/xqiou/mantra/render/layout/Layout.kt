package com.xqiou.mantra.render.layout

import java.util.Locale

/** How a table places values: tiered lead/main columns or one column per member. */
enum class TableStyle { TIERED, MATRIX }

enum class NegativeStyle { MINUS, PARENTHESES }

enum class ExplainMode { NONE, APPENDIX }

enum class Align { LEFT, RIGHT, CENTER }

/** Small, output-neutral style vocabulary. Null means this rule leaves a property unchanged. */
enum class StyleWeight { NORMAL, BOLD }
enum class StyleTone { DEFAULT, MUTED, ACCENT }
enum class StyleFill { NONE, SUBTLE, ACCENT }

data class StyleSpec(
    val weight: StyleWeight? = null,
    val tone: StyleTone? = null,
    val fill: StyleFill? = null,
) {
    fun merge(override: StyleSpec) = StyleSpec(
        weight = override.weight ?: weight,
        tone = override.tone ?: tone,
        fill = override.fill ?: fill,
    )
}

enum class RowParity { ODD, EVEN }

/** Table row numbers either continue across the document or restart at each table. */
enum class RowNumberMode { GLOBAL, TABLE }

/** Stable facts available to every output backend when resolving a visible table cell. */
data class StyleContext(
    val tableId: String,
    val sectionPath: List<String>,
    /** D3-style structural depth relative to the table root: root 0, direct children 1. */
    val depth: Int,
    /** D3-style greatest distance to a visible descendant leaf. */
    val height: Int,
    /** Visual indentation, independent of the structural hierarchy. */
    val indent: Int,
    /** One-based position among rendered table-body rows. */
    val rowIndex: Int,
    val rowKind: String,
    val hasRowNumber: Boolean,
    val classes: List<String>,
    val columnId: String,
    val columnRole: String,
)

/** A bounded selector; new conditions can be added without changing calculation items. */
data class StyleSelector(
    /** Universal selector; written as `{:all true}` in the layout DSL. */
    val all: Boolean = false,
    val section: String? = null,
    val depth: Int? = null,
    val height: Int? = null,
    val indent: Int? = null,
    /** CSS `tr:nth-child(odd|even)` over the rendered table body. */
    val nthChild: RowParity? = null,
    val hasRowNumber: Boolean? = null,
    val column: String? = null,
    val klass: String? = null,
    val rowKind: String? = null,
) {
    fun matchesBase(classes: List<String>): Boolean =
        section == null && depth == null && height == null && indent == null && nthChild == null &&
            hasRowNumber == null && column == null && rowKind == null &&
            (all || klass?.let { it in classes } == true)

    fun matches(context: StyleContext): Boolean =
        (section == null || section in context.sectionPath) &&
            (depth == null || depth == context.depth) &&
            (height == null || height == context.height) &&
            (indent == null || indent == context.indent) &&
            (nthChild == null || (context.rowIndex % 2 == 1) == (nthChild == RowParity.ODD)) &&
            (hasRowNumber == null || hasRowNumber == context.hasRowNumber) &&
            (column == null || column == context.columnId || column == context.columnRole) &&
            (klass == null || klass in context.classes) &&
            (rowKind == null || rowKind == context.rowKind)
}

data class StyleRule(val selector: StyleSelector, val style: StyleSpec)

/**
 * Built-in column content functions. A column can be named directly (`:label`) or customized
 * with `(col :label {:header "..."})`; the engine fills each row's cell with that content.
 */
sealed interface ColumnContent {
    val numeric: Boolean get() = false

    data object Label : ColumnContent
    data object Operator : ColumnContent
    data object RowNumber : ColumnContent
    data object Reference : ColumnContent
    data object Note : ColumnContent
    data object Source : ColumnContent
    /** Application-defined metadata, such as a tax form code or form line. */
    data class Attribute(val name: String) : ColumnContent
    data object Status : ColumnContent
    data object Formula : ColumnContent
    data object Explain : ColumnContent

    /** The row value in its own context (scalar value, or cross-total of a dimensioned row). */
    data object Value : ColumnContent { override val numeric = true }

    /** Tiered lead column: components of nested computations. */
    data object Pre : ColumnContent { override val numeric = true }

    /** Tiered main column: items and checkpoints of the table's own running sum. */
    data object Main : ColumnContent { override val numeric = true }

    /** Sum over the members of the table's member dimension (cross-footing column). */
    data object CrossTotal : ColumnContent { override val numeric = true }

    /** Value for a single member of a dimension. */
    data class Member(val dimension: String, val key: String) : ColumnContent { override val numeric = true }

    /** Expands into one [Member] column per active member of the dimension. */
    data class Members(val dimension: String) : ColumnContent { override val numeric = true }

    companion object {
        fun of(keyword: String): ColumnContent? = when (keyword) {
            "label" -> Label
            "operator" -> Operator
            "row-number" -> RowNumber
            "reference" -> Reference
            "note" -> Note
            "source" -> Source
            "status" -> Status
            "formula" -> Formula
            "explain" -> Explain
            "value" -> Value
            "pre" -> Pre
            "main" -> Main
            "cross-total" -> CrossTotal
            else -> null
        }

        val catalog: List<String> = listOf(
            "label", "operator", "row-number", "reference", "note", "source", "status",
            "formula", "explain", "value", "pre", "main", "cross-total", "(attribute :name)",
            "(member <dim> :key)", "(members <dim>)",
        )
    }
}

fun ColumnContent.styleRole(): String = when (this) {
    ColumnContent.Label -> "label"
    ColumnContent.Operator -> "operator"
    ColumnContent.RowNumber -> "row-number"
    ColumnContent.Reference -> "reference"
    ColumnContent.Note -> "note"
    ColumnContent.Source -> "source"
    is ColumnContent.Attribute -> "attribute"
    ColumnContent.Status -> "status"
    ColumnContent.Formula -> "formula"
    ColumnContent.Explain -> "explain"
    ColumnContent.Value -> "value"
    ColumnContent.Pre -> "pre"
    ColumnContent.Main -> "main"
    ColumnContent.CrossTotal -> "cross-total"
    is ColumnContent.Member -> "member"
    is ColumnContent.Members -> "members"
}

data class ColumnSpec(
    val id: String,
    val header: String?,
    val content: ColumnContent,
    val width: Int? = null,
    val align: Align? = null,
)

data class NumberStyle(
    val locale: Locale,
    val precision: Int,
    val negative: NegativeStyle,
    /** Text shown for exact zero amounts; `null` prints the formatted zero. */
    val zero: String?,
    val grouping: Boolean = true,
    val percentPrecision: Int = 2,
)

data class Operators(val plus: String, val minus: String, val total: String, val info: String)

/** Localizable captions used by renderers. */
data class Texts(
    val language: String,
    val pre: String,
    val main: String,
    val total: String,
    val label: String,
    val reference: String,
    val row: String,
    val status: String,
    val formula: String,
    val explain: String,
    val audit: String,
    val auditIntro: String,
    val result: String,
    val notApplicable: String,
    val selected: String,
    val userDefined: String,
    val footed: String,
    val legend: String,
    val diagnostics: String,
    val schedule: String,
    val table: String,
    val carriedFrom: String,
    val subject: String,
    val period: String,
    val schema: String,
    val preparedBy: String,
    val reviewedBy: String,
    val date: String,
    val index: String,
    val page: String,
    val mainline: String,
    val branch: String,
    val auxiliary: String,
    val structure: String,
    val feeds: String,
) {
    companion object {
        val DE = Texts(
            language = "de",
            pre = "Vorspalte", main = "Hauptspalte", total = "Gesamt", label = "Bezeichnung", reference = "Rechtsgrundlage",
            row = "Zeile", status = "", formula = "Formel", explain = "Rechenweg", audit = "Berechnungsnachweis",
            auditIntro = "Nachweis jeder berechneten Zeile: Formel des Berechnungsschemas, eingesetzte Werte und Ergebnis.",
            result = "Ergebnis", notApplicable = "entfällt", selected = "gewählte Alternative (Günstigerprüfung)",
            userDefined = "benutzerdefinierte Zeile", footed = "Summe geprüft (Fußung)", legend = "Prüfzeichen",
            diagnostics = "Hinweise", schedule = "Nebenrechnung", table = "Tabelle", carriedFrom = "Übertrag aus", subject = "Mandant",
            period = "Zeitraum", schema = "Berechnungsschema", preparedBy = "Erstellt", reviewedBy = "Geprüft", date = "Datum",
            index = "Index", page = "Seite", mainline = "Hauptlinie", branch = "Nebenrechnung",
            auxiliary = "Nebeninformation", structure = "Aufbau der Berechnung", feeds = "fließt ein in",
        )
        val EN = Texts(
            language = "en",
            pre = "Detail", main = "Amount", total = "Total", label = "Description", reference = "Reference",
            row = "Line", status = "", formula = "Formula", explain = "Working", audit = "Audit trail",
            auditIntro = "Evidence for every calculated line: schema formula, substituted values and result.",
            result = "Result", notApplicable = "n/a", selected = "selected alternative", userDefined = "user-defined line",
            footed = "footed / cross-footed", legend = "Tick marks", diagnostics = "Findings", schedule = "Schedule", table = "Table",
            carriedFrom = "carried from", subject = "Entity", period = "Period", schema = "Calculation schema",
            preparedBy = "Prepared by", reviewedBy = "Reviewed by", date = "Date", index = "WP ref.", page = "Page",
            mainline = "Mainline", branch = "Supporting schedule", auxiliary = "Supplementary", structure = "Structure of the calculation",
            feeds = "feeds",
        )
    }
}

data class TableSpec(
    val sectionId: String,
    val title: String? = null,
    val style: TableStyle? = null,
    val columns: List<ColumnSpec>? = null,
    val expandMembers: Boolean? = null,
)

/** Complete presentation settings; produced from a preset and refined by a layout document. */
data class LayoutSpec(
    val id: String,
    val preset: String,
    val title: String? = null,
    val subtitle: String? = null,
    val number: NumberStyle,
    val operators: Operators,
    val texts: Texts,
    val tieredColumns: List<ColumnSpec>,
    val matrixColumns: List<ColumnSpec>,
    /** Explicit tables; empty means one table for the schema root plus one per schedule section. */
    val tables: List<TableSpec> = emptyList(),
    val schedules: Set<String> = emptySet(),
    val inline: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val hideZero: Boolean = false,
    val showInactive: Boolean = false,
    val expandMembers: Boolean = false,
    /** Show deductions as negative figures (IFRS style) instead of positive figures with an operator. */
    val signedValues: Boolean = false,
    val explain: ExplainMode = ExplainMode.APPENDIX,
    val header: List<String> = listOf("subject", "period", "schema", "prepared-by", "reviewed-by", "date", "reference"),
    val theme: String = "classic",
    /** Null keeps preset/column visibility; an explicit mode also enables the row-number column. */
    val rowNumbers: RowNumberMode? = null,
    /** Every style uses the same selector + declaration form; rules apply in layout order. */
    val styleRules: List<StyleRule> = emptyList(),
) {
    fun styleFor(classes: List<String>): StyleSpec = styleRules.fold(StyleSpec()) { resolved, rule ->
        if (rule.selector.matchesBase(classes)) resolved.merge(rule.style) else resolved
    }

    fun styleFor(context: StyleContext): StyleSpec = styleRules.fold(StyleSpec()) { resolved, rule ->
        if (rule.selector.matches(context)) resolved.merge(rule.style) else resolved
    }
}

/** Built-in presets: common presentation settings, free of any domain logic. */
object Presets {
    private fun col(id: String, content: ColumnContent, header: String? = null, width: Int? = null) = ColumnSpec(id, header, content, width)

    private val deOperators = Operators(plus = "", minus = "./.", total = "=", info = "")
    private val enOperators = Operators(plus = "", minus = "less", total = "=", info = "")

    private val deNumbers = NumberStyle(Locale.GERMANY, 2, NegativeStyle.MINUS, zero = null)
    private val enNumbers = NumberStyle(Locale.UK, 0, NegativeStyle.PARENTHESES, zero = "–")

    /** German Staffel with four columns: Zeile | Bezeichnung | Vorspalte | Hauptspalte (+ Rechtsgrundlage). */
    val DE_STAFFEL_4 = LayoutSpec(
        id = "preset/de-staffel-4",
        preset = "de-staffel-4",
        number = deNumbers,
        operators = deOperators,
        texts = Texts.DE,
        tieredColumns = listOf(
            col("row-number", ColumnContent.RowNumber, "Zeile", 5),
            col("operator", ColumnContent.Operator, "", 4),
            col("label", ColumnContent.Label, "Bezeichnung"),
            col("pre", ColumnContent.Pre, "EUR", 14),
            col("main", ColumnContent.Main, "EUR", 14),
            col("status", ColumnContent.Status, "", 3),
            col("reference", ColumnContent.Reference, "Rechtsgrundlage", 22),
        ),
        matrixColumns = listOf(
            col("row-number", ColumnContent.RowNumber, "Zeile", 5),
            col("operator", ColumnContent.Operator, "", 4),
            col("label", ColumnContent.Label, "Bezeichnung"),
            col("members", ColumnContent.Members("*"), null, 14),
            col("cross-total", ColumnContent.CrossTotal, "Gesamt", 14),
            col("status", ColumnContent.Status, "", 3),
            col("reference", ColumnContent.Reference, "Rechtsgrundlage", 22),
        ),
        hideZero = true,
    )

    /** German Staffel with three columns: Bezeichnung | Vorspalte | Hauptspalte. */
    val DE_STAFFEL_3 = DE_STAFFEL_4.copy(
        id = "preset/de-staffel-3",
        preset = "de-staffel-3",
        tieredColumns = listOf(
            col("operator", ColumnContent.Operator, "", 4),
            col("label", ColumnContent.Label, "Bezeichnung"),
            col("pre", ColumnContent.Pre, "EUR", 14),
            col("main", ColumnContent.Main, "EUR", 14),
        ),
        matrixColumns = listOf(
            col("operator", ColumnContent.Operator, "", 4),
            col("label", ColumnContent.Label, "Bezeichnung"),
            col("members", ColumnContent.Members("*"), null, 14),
            col("cross-total", ColumnContent.CrossTotal, "Gesamt", 14),
        ),
    )

    /** IFRS-style schedule: amounts in currency units, negatives in parentheses, members as columns. */
    val IFRS_SCHEDULE = LayoutSpec(
        id = "preset/ifrs-schedule",
        preset = "ifrs-schedule",
        number = enNumbers,
        operators = enOperators,
        texts = Texts.EN,
        tieredColumns = listOf(
            col("label", ColumnContent.Label, ""),
            col("pre", ColumnContent.Pre, "CU", 12),
            col("main", ColumnContent.Main, "CU", 12),
            col("status", ColumnContent.Status, "", 3),
            col("reference", ColumnContent.Reference, "Reference", 16),
        ),
        matrixColumns = listOf(
            col("label", ColumnContent.Label, ""),
            col("members", ColumnContent.Members("*"), null, 11),
            col("cross-total", ColumnContent.CrossTotal, "Total", 11),
            col("status", ColumnContent.Status, "", 3),
            col("reference", ColumnContent.Reference, "Reference", 16),
        ),
        signedValues = true,
    )

    val ALL: Map<String, LayoutSpec> = listOf(DE_STAFFEL_4, DE_STAFFEL_3, IFRS_SCHEDULE).associateBy { it.preset }

    fun of(name: String): LayoutSpec? = ALL[name]
}
