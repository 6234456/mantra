package com.xqiou.mantra.core.api.language

/** Core host forms. CLI/doc extraction must consume this projection during coordinated integration. */
data class HostFormDescription(val head: String, val owners: Set<String>, val syntax: String, val summary: String)

object LanguageCatalog {
    val forms: List<HostFormDescription> = frozen(
        listOf(
            form("schema", "document", "(schema id {metadata}? declarations...)", "Declares a calculation schema."),
            form("fragment", "document", "(fragment declarations...)", "Contains explicitly included declarations."),
            form("case", "document", "(case id {metadata}? forms...)", "Supplies authored facts, overrides and links."),
            form("parameters", "document", "(parameters id {metadata}? forms...)", "Declares a named parameter layer."),
            form("include", "schema,fragment", "(include \"path\")", "Reads a confined schema fragment."),
            form("param", "schema,fragment", "(param id value {options}?)", "Declares a default parameter."),
            form("input", "schema,fragment", "(input id :type {options}?)", "Declares a typed fact input."),
            form(
                "dimension",
                "schema,fragment",
                "(dimension id {options})",
                "Declares members, a table domain or continuous periods.",
            ),
            form(
                "section",
                "schema,fragment,extend,section",
                "(section id \"Label\" {options}? items...)",
                "Groups calculation items.",
            ),
            form(
                "line",
                "schema,fragment,extend,section",
                "(line id \"Label\" expression {options}?)",
                "Computes one typed value per coordinate.",
            ),
            form(
                "subtract",
                "schema,fragment,extend,section",
                "(subtract id \"Label\" expression {options}?)",
                "Computes a line with :op :minus; the line value retains its sign.",
            ),
            form(
                "info",
                "schema,fragment,extend,section",
                "(info id \"Label\" expression {options}?)",
                "Computes a line with :op :info, contributing nothing to totals.",
            ),
            form(
                "formula-slot",
                "schema,fragment,section",
                "(formula-slot id \"Label\" expression {options}?)",
                "Declares a case-editable formula.",
            ),
            form(
                "field",
                "schema,fragment,section",
                "(field id \"Label\" {options}?)",
                "Declares or presents a fact input.",
            ),
            form(
                "total",
                "schema,fragment,extend,section",
                "(total id \"Label\" {options}?)",
                "Reduces signed section contributions.",
            ),
            form(
                "choice",
                "schema,fragment,extend,section",
                "(choice id \"Label\" {options}? options...)",
                "Selects among applicable numeric options.",
            ),
            form(
                "choose-min",
                "schema,fragment,extend,section",
                "(choose-min id \"Label\" {options}? options...)",
                "Selects the minimum applicable numeric option with :rule :min.",
            ),
            form(
                "choose-max",
                "schema,fragment,extend,section",
                "(choose-max id \"Label\" {options}? options...)",
                "Selects the maximum applicable numeric option with :rule :max.",
            ),
            form(
                "option",
                "choice,choose-min,choose-max",
                "(option :key \"Label\" expression {options}?)",
                "Declares an applicable choice option.",
            ),
            form(
                "check",
                "schema,fragment,extend,section",
                "(check id \"Label\" expression {options}?)",
                "Declares a business validation.",
            ),
            form(
                "reconcile",
                "schema,fragment,extend,section",
                "(reconcile id \"Label\" left right {options}?)",
                "Preserves both numeric sides and their difference.",
            ),
            form(
                "slot",
                "schema,fragment,section",
                "(slot id \"Label\" {options}?)",
                "Declares a case extension point.",
            ),
            form("note", "schema,fragment,extend,section", "(note \"Text\" {options}?)", "Adds presentation text."),
            form(
                "defn",
                "schema,fragment,case",
                "(defn name [arguments] body)",
                "Declares a statically compiled helper function.",
            ),
            form("inputs", "case", "(inputs {:id value ...})", "Supplies fact values."),
            form(
                "rows",
                "inputs",
                "(rows [:column ...] [literal ...]...)",
                "Supplies a case input table with ordered keyword columns and literal cells.",
            ),
            form("params", "case", "(params {:id value ...})", "Overrides schema and parameter-layer values."),
            form("bind", "case", "(bind formula-slot-id expression)", "Replaces a declared formula slot."),
            form("extend", "case", "(extend slot-id items...)", "Adds authored items to an extension point."),
            form(
                "links",
                "case",
                "(links {:path ... :schema ... :schema-version ... :mappings [...]})",
                "Pins typed source facts by exact schema identity.",
            ),
            form(
                "sources",
                "case",
                "(sources (csv|json|xlsx {options})...)",
                "Declares data sources; editor analysis does not import them.",
            ),
            form("values", "parameters", "(values {:id value ...})", "Sets named parameters."),
            form("value", "parameters", "(value id value {options}?)", "Sets a parameter with an optional reference."),
        ),
    )

    private fun form(head: String, owners: String, syntax: String, summary: String): HostFormDescription =
        HostFormDescription(head, java.util.Collections.unmodifiableSet(owners.split(',').toSet()), syntax, summary)
}
