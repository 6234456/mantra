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
                "Declares presentation without changing calculation values.",
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
            form("style", "layout", "(style {selector} {declarations})", "Adds a presentation style rule."),
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
