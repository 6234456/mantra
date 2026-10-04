package com.xqiou.mantra.core.model

import com.xqiou.mantra.core.SourceLocation
import com.xqiou.normein.dsl.form.DslForm
import java.math.RoundingMode

/** Element type of a node or input. Dimensioned nodes carry one element per member tuple. */
enum class ValueType(val keyword: String) {
    DECIMAL("decimal"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    KEYWORD("keyword"),
    TEXT("text"),
    DATE("date"),
    TABLE("table"),
    ANY("any"),
    ;

    val isNumeric: Boolean get() = this == DECIMAL || this == INTEGER

    companion object {
        fun of(keyword: String): ValueType? = entries.firstOrNull { it.keyword == keyword }
    }
}

/**
 * An embedded Normein expression. [source] is the exact author text of [form]; [location] is the
 * position of its first character in the Mantra document, passed to the kernel as hostPosition.
 */
data class Formula(val source: String, val location: SourceLocation, val form: DslForm)

/** Contribution of an item to the running sum of its enclosing section. */
enum class Op(val sign: Int, val keyword: String) {
    PLUS(1, "plus"),
    MINUS(-1, "minus"),
    INFO(0, "info"),
    ;

    fun compose(outer: Op): Op = when {
        this == INFO || outer == INFO -> INFO
        sign * outer.sign > 0 -> PLUS
        else -> MINUS
    }

    companion object {
        fun of(keyword: String): Op? = entries.firstOrNull { it.keyword == keyword }
    }
}

data class Rounding(val scale: Int, val mode: RoundingMode) {
    override fun toString(): String = "$scale ${mode.name.lowercase()}"
}

/** Presentation metadata attached to calculation items. It never influences values. */
data class Presentation(
    val reference: String? = null,
    val note: String? = null,
    val source: String? = null,
    val format: String? = null,
    val precision: Int? = null,
    val hidden: Boolean = false,
    val emphasis: String? = null,
    val attributes: Map<String, Value> = emptyMap(),
    /** Reusable presentation tags resolved by a layout; they never affect calculation. */
    val classes: List<String> = emptyList(),
)

data class SchemaMeta(val id: String, val title: String, val attributes: Map<String, Value>) {
    fun text(key: String): String? = (attributes[key] as? Value.Text)?.value
}

data class ParamDecl(
    val id: String,
    val label: String?,
    val value: Value,
    val presentation: Presentation,
    val location: SourceLocation,
)

data class ColumnDecl(val name: String, val type: ValueType, val optional: Boolean, val requiredWhen: Formula? = null)

data class InputDecl(
    val id: String,
    val label: String?,
    val type: ValueType,
    /** Explicit dimensions; `null` means "inherit from the enclosing section" (fields only). */
    val per: List<String>?,
    val default: Value?,
    val optional: Boolean,
    val options: Map<String, String>,
    val columns: List<ColumnDecl>,
    val presentation: Presentation,
    val location: SourceLocation,
    /** Table column -> target dimension. Non-null row values must name an active member. */
    val references: Map<String, String> = emptyMap(),
    val requiredWhen: Formula? = null,
    val minRows: Int? = null,
    val aggregate: AggregateRule = AggregateRule.SUM,
    val ratio: RatioAggregation? = null,
    val boundary: BoundaryAggregation? = null,
)

data class MemberDecl(val key: String, val label: String, val condition: Formula?)

data class DimensionDecl(
    val id: String,
    val label: String,
    val members: List<MemberDecl>,
    val fromTable: String?,
    val keyColumn: String,
    val titleColumn: String?,
    /** Optional relation from this table-backed dimension to a parent dimension. */
    val parentDimension: String?,
    val parentKeyColumn: String?,
    val totalLabel: String,
    val location: SourceLocation,
    val periods: PeriodSpec? = null,
)

/** `(defn name [^Type arg ...] body)` helper made available to every formula of the schema. */
data class FunctionDecl(val name: String, val source: String, val location: SourceLocation)

enum class ChoiceRule { MIN, MAX }

/** Whether values across dimension members have a meaningful cross total. */
enum class AggregateRule { SUM, NONE, RATIO }

/** A ratio is cross-footed by summing its numeric components before dividing. */
data class RatioAggregation(val numerator: String, val denominator: String, val rounding: Rounding? = null)

data class ChoiceOption(
    val key: String,
    val label: String,
    val formula: Formula,
    val condition: Formula?,
    val location: SourceLocation,
)

enum class SectionDisplay { INLINE, SCHEDULE, HIDDEN }

/** Node of the presentation/computation tree. */
sealed interface Item {
    val location: SourceLocation
    val userDefined: Boolean
}

sealed interface NodeItem : Item {
    val id: String
    val label: String
    val presentation: Presentation
}

data class LineItem(
    override val id: String,
    override val label: String,
    val formula: Formula,
    val op: Op,
    val per: List<String>?,
    val condition: Formula?,
    val rounding: Rounding?,
    val type: ValueType,
    /** Evaluate once without [per] dimensions and distribute the resulting map over the members. */
    val spread: Boolean,
    override val presentation: Presentation,
    override val location: SourceLocation,
    override val userDefined: Boolean = false,
    /** Application-declared formula hook; cases may replace the formula but not its type or context. */
    val formulaSlot: Boolean = false,
    /** Roots the application permits a case-bound formula to reference. Null means unrestricted. */
    val allowedRefs: Set<String>? = null,
    /** A unit rate is usually NONE; its cross total must be expressed by a separate formula. */
    val aggregate: AggregateRule = AggregateRule.SUM,
    val ratio: RatioAggregation? = null,
    val boundary: BoundaryAggregation? = null,
) : NodeItem

/** An input shown at its place in the computation (`field` form). */
data class FieldItem(
    override val id: String,
    override val label: String,
    val op: Op,
    override val presentation: Presentation,
    override val location: SourceLocation,
    override val userDefined: Boolean = false,
) : NodeItem

/** Checkpoint of the running sum of the enclosing section (the `=` line of a tiered table). */
data class TotalItem(
    override val id: String,
    override val label: String,
    val condition: Formula?,
    override val presentation: Presentation,
    override val location: SourceLocation,
    override val userDefined: Boolean = false,
    val aggregate: AggregateRule = AggregateRule.SUM,
    val ratio: RatioAggregation? = null,
    val boundary: BoundaryAggregation? = null,
) : NodeItem

/** Alternatives with a selection rule (Günstigerprüfung, higher-of tests). */
data class ChoiceItem(
    override val id: String,
    override val label: String,
    val rule: ChoiceRule,
    val options: List<ChoiceOption>,
    val op: Op,
    val per: List<String>?,
    val condition: Formula?,
    val rounding: Rounding?,
    override val presentation: Presentation,
    override val location: SourceLocation,
    override val userDefined: Boolean = false,
    val aggregate: AggregateRule = AggregateRule.SUM,
    val ratio: RatioAggregation? = null,
    val boundary: BoundaryAggregation? = null,
) : NodeItem

data class SectionItem(
    val id: String,
    val label: String,
    val per: List<String>?,
    val condition: Formula?,
    val op: Op,
    val display: SectionDisplay,
    val layout: String?,
    val title: String?,
    val children: List<Item>,
    val presentation: Presentation,
    override val location: SourceLocation,
    val slot: Boolean = false,
    override val userDefined: Boolean = false,
) : Item

data class NoteItem(
    val text: String,
    val presentation: Presentation,
    override val location: SourceLocation,
    override val userDefined: Boolean = false,
) : Item

data class Schema(
    val meta: SchemaMeta,
    val params: List<ParamDecl>,
    val inputs: List<InputDecl>,
    val dimensions: List<DimensionDecl>,
    val functions: List<FunctionDecl>,
    val root: SectionItem,
    val sources: List<String>,
) {
    val id: String get() = meta.id
}

/** User data for one calculation run ("Fall", engagement file). */
data class SourceBinding(val kind: String, val options: Map<String, Value>, val location: SourceLocation)

data class CaseData(
    val id: String,
    val schemaId: String?,
    val meta: Map<String, Value>,
    val inputs: Map<String, Value>,
    val params: Map<String, Value>,
    /** Items added by the user to declared `slot`s of the schema. */
    val extensions: Map<String, List<Item>>,
    /** User formulas bound only to application-declared `formula-slot` lines. */
    val formulaBindings: Map<String, Formula>,
    val functions: List<FunctionDecl>,
    val source: String,
    /** Exact locations of supplied values, separate from schema input declarations. */
    val inputLocations: Map<String, SourceLocation> = emptyMap(),
    val paramLocations: Map<String, SourceLocation> = emptyMap(),
    /** Ordered, replayable data source declarations from `(sources ...)`. */
    val sources: List<SourceBinding> = emptyList(),
    /** Provenance for values supplied by sources, keyed by input id and coordinate path. */
    val inputOrigins: Map<String, Map<String, String>> = emptyMap(),
    /** Exact scalar, row and column positions available in the case source. */
    val inputCells: Map<String, List<InputCellLocation>> = emptyMap(),
) {
    fun text(key: String): String? = (meta[key] as? Value.Text)?.value

    companion object {
        fun empty(id: String = "empty"): CaseData =
            CaseData(id, null, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), "<none>")
    }
}

data class InputCellLocation(
    val coord: List<String>,
    val rowIndex: Int?,
    val column: String?,
    val location: SourceLocation,
)
