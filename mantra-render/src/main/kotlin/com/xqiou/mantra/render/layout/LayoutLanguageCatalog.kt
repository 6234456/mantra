package com.xqiou.mantra.render.layout

import com.xqiou.mantra.core.api.language.HostFormDescription
import java.util.Collections

/** Presentation forms for static host completion; reading still uses the real LayoutReader. */
object LayoutLanguageCatalog {
    val forms: List<HostFormDescription> = Collections.unmodifiableList(
        listOf(
            form(
                "layout",
                "document",
                "(layout id {options}? declarations...)",
                "Declares presentation; :style-preset selects :utilities or :working-paper class rules.",
            ),
            form(
                "operators",
                "layout",
                "(operators {:plus ... :minus ... :total ... :info ...})",
                "Sets operator labels.",
            ),
            form("columns", "layout", "(columns :tiered|:matrix columns...)", "Defines a column template."),
            form(
                "table",
                "layout",
                "(table section-id {options}? columns...)",
                "Selects a section and generic table axes.",
            ),
            form(
                "style",
                "layout",
                "(style {selector} {:use :name|[:name ...] ...declarations})",
                "Merges named declarations left to right, then explicit weight, tone and fill.",
            ),
            form(
                "style-class",
                "layout",
                "(style-class :name {:weight ... :tone ... :fill ...})",
                "Defines a reusable class and emits its style rule at this declaration position.",
            ),
            form("schedule", "layout", "(schedule section-id...)", "Renders selected sections as schedules."),
            form("inline", "layout", "(inline section-id...)", "Renders selected sections inline."),
            form("hide", "layout", "(hide item-id...)", "Hides selected presentation items."),
            form("col", "columns,table", "(col :name {options}?)", "Defines a named column."),
            form(
                "members",
                "columns,table",
                "(members dimension {options}?)",
                "Expands declared members into columns.",
            ),
            form("member", "columns,table", "(member dimension :key)", "Selects one declared member column."),
            form("attribute", "columns,table", "(attribute :name {options}?)", "Reads a presentation attribute."),
            form("node", "table", "(node node-id)", "Selects a node column for a transpose table."),
        ),
    )
    private fun form(head: String, owners: String, syntax: String, summary: String): HostFormDescription =
        HostFormDescription(head, Collections.unmodifiableSet(owners.split(',').toSet()), syntax, summary)
}
