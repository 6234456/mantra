package com.xqiou.mantra.core.structure

import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.ConditionVertex
import com.xqiou.mantra.core.engine.InputVertex
import com.xqiou.mantra.core.engine.ParamVertex
import com.xqiou.mantra.core.engine.ResolvedItem
import com.xqiou.mantra.core.engine.ResolvedNode
import com.xqiou.mantra.core.engine.ResolvedNote
import com.xqiou.mantra.core.engine.ResolvedSection
import com.xqiou.mantra.core.engine.ValueVertex
import com.xqiou.mantra.core.model.SectionDisplay
import com.xqiou.mantra.core.model.Value

/**
 * Role of a panel (Bearbeitungsbereich) in the calculation:
 * - [MAINLINE]: a step of the main computation chain (Hauptlinie),
 * - [BRANCH]: a sub-computation whose results flow into the mainline (Nebenrechnung),
 * - [AUXILIARY]: a side computation that does not feed the mainline (Nebeninformation).
 */
enum class PanelRole { MAINLINE, BRANCH, AUXILIARY }

/** A value produced in one panel and consumed in another. */
data class Flow(val fromPanel: String, val fromNode: String, val toPanel: String, val toNode: String)

/** Where a panel's results enter the mainline. */
data class MainlineEntry(
    val step: Int,
    val stepPanel: String,
    /** Node of the mainline panel that consumes the value (e.g. the carry-over line). */
    val viaNode: String,
    val viaLabel: String,
    /** Panels from this panel to the mainline step, both inclusive. */
    val path: List<String>,
)

data class Crumb(val panelId: String?, val label: String, val nodeId: String? = null)

/**
 * An independently presentable and editable unit of a schema. A UI renders one panel at a time:
 * its [fields] are editable, [imports] are read-only values from other panels, [exports] show where
 * its results are used, and [breadcrumb] places it on the mainline.
 */
class Panel(
    val id: String,
    val title: String,
    val order: Int,
    val role: PanelRole,
    /** 1-based step for mainline panels. */
    val step: Int?,
    val dims: List<String>,
    val parentId: String?,
    val resultId: String?,
    /** Inputs edited in this panel: its fields plus inputs used only here. */
    val fields: List<String>,
    /** Calculated nodes presented in this panel (excluding nested panels). */
    val nodes: List<String>,
    val imports: List<Flow>,
    val exports: List<Flow>,
    val entries: List<MainlineEntry>,
    val breadcrumb: List<Crumb>,
) {
    val position: MainlineEntry? get() = entries.minByOrNull { it.step }
}

class SchemaMap(
    val schemaId: String,
    val title: String,
    val panels: List<Panel>,
    val mainline: List<String>,
    /** Inputs used by several panels (Stammdaten); edited outside any single panel. */
    val generalInputs: List<String>,
    val params: List<String>,
    val flows: List<Flow>,
) {
    fun panel(id: String): Panel = panels.first { it.id == id }

    /** Panel presenting a node (the innermost panel containing it), or `null` for general nodes. */
    fun panelOf(nodeId: String): Panel? = panels.firstOrNull { nodeId in it.nodes || nodeId in it.fields }
}

/**
 * Derives the [SchemaMap] from a plan. Panels are sections marked `:panel true` or
 * `:display :schedule`; the mainline is the schema's `:mainline [ids]` metadata or, when absent,
 * every top-level panel in declaration order.
 */
object SchemaMaps {
    const val GENERAL: String = "general"

    fun of(plan: CalculationPlan): SchemaMap {
        val schema = plan.schema
        data class RawPanel(val section: ResolvedSection, val parent: String?, val order: Int, val topLevel: Boolean)

        val raw = mutableListOf<RawPanel>()
        val nodePanel = hashMapOf<String, String>()
        val sectionPanel = hashMapOf<String, String>()

        fun isPanel(section: ResolvedSection): Boolean =
            section.item.display == SectionDisplay.SCHEDULE || section.item.presentation.attributes["panel"] == Value.Bool(true)

        fun walk(item: ResolvedItem, current: String?, topLevel: Boolean) {
            when (item) {
                is ResolvedSection -> {
                    val panelHere = item !== plan.tree && isPanel(item)
                    val owner = if (panelHere) item.id else current
                    if (panelHere) raw += RawPanel(item, current, raw.size, topLevel && current == null)
                    owner?.let { sectionPanel[item.id] = it }
                    item.children.forEach { walk(it, owner, topLevel && !panelHere) }
                }
                is ResolvedNode -> nodePanel[item.id] = current ?: GENERAL
                is ResolvedNote -> Unit
            }
        }
        walk(plan.tree, null, true)

        // Guards belong to the panel of their section; inputs declared at top level get a home panel
        // when exactly one panel uses them.
        fun panelOfVertex(id: String): String? = when (val vertex = plan.vertices[id]) {
            is ConditionVertex -> sectionPanel[vertex.sectionId] ?: GENERAL
            is ValueVertex -> nodePanel[id]
            else -> null
        }
        val users = hashMapOf<String, MutableSet<String>>()
        plan.vertices.values.forEach { vertex ->
            val owner = panelOfVertex(vertex.id) ?: return@forEach
            vertex.dependencies.forEach { dependency -> users.getOrPut(dependency) { linkedSetOf() } += owner }
        }
        val homeInputs = hashMapOf<String, String>()
        val general = mutableListOf<String>()
        plan.valueVertices.values.filterIsInstance<InputVertex>().filter { it.id !in nodePanel }.forEach { input ->
            val explicit = (input.decl.presentation.attributes["panel"] as? Value.Kw)?.name
            val using = users[input.id].orEmpty() - GENERAL
            when {
                explicit != null -> homeInputs[input.id] = explicit
                using.size == 1 -> homeInputs[input.id] = using.single()
                else -> general += input.id
            }
        }

        // Flows between panels (parameters and general inputs are shared context, not flows).
        val flows = linkedSetOf<Flow>()
        plan.vertices.values.forEach { vertex ->
            val to = panelOfVertex(vertex.id) ?: return@forEach
            if (to == GENERAL) return@forEach
            val toNode = if (vertex is ConditionVertex) vertex.sectionId else vertex.id
            vertex.dependencies.forEach { dependency ->
                val source = plan.valueVertices[dependency] ?: return@forEach
                if (source is ParamVertex) return@forEach
                val from = nodePanel[dependency] ?: homeInputs[dependency] ?: return@forEach
                if (from != to && from != GENERAL) flows += Flow(from, dependency, to, toNode)
            }
        }

        val declaredMainline = (schema.meta.attributes["mainline"] as? Value.Vec)?.items
            ?.mapNotNull { (it as? Value.Kw)?.name ?: (it as? Value.Text)?.value }
        val mainline = declaredMainline?.filter { id -> raw.any { it.section.id == id } }
            ?: raw.filter { it.topLevel }.map { it.section.id }

        fun label(nodeId: String): String = plan.valueVertices[nodeId]?.label ?: nodeId

        // Shortest path (by panels) from each panel to every mainline step it feeds.
        fun entriesOf(panelId: String): List<MainlineEntry> {
            if (panelId in mainline) {
                return listOf(MainlineEntry(mainline.indexOf(panelId) + 1, panelId, "", "", listOf(panelId)))
            }
            val result = mutableListOf<MainlineEntry>()
            val seen = mutableSetOf(panelId)
            var frontier = listOf(listOf(panelId))
            while (frontier.isNotEmpty()) {
                val next = mutableListOf<List<String>>()
                for (path in frontier) {
                    val last = path.last()
                    flows.filter { it.fromPanel == last }.groupBy { it.toPanel }.forEach { (target, via) ->
                        if (target in mainline) {
                            if (result.none { it.stepPanel == target }) {
                                val consumer = via.first().toNode
                                result += MainlineEntry(mainline.indexOf(target) + 1, target, consumer, label(consumer), path + target)
                            }
                        } else if (seen.add(target)) {
                            next += path + target
                        }
                    }
                }
                frontier = next
            }
            return result.sortedBy { it.step }
        }

        val panels = raw.map { rp ->
            val id = rp.section.id
            val entries = entriesOf(id)
            val role = when {
                id in mainline -> PanelRole.MAINLINE
                entries.isNotEmpty() -> PanelRole.BRANCH
                else -> PanelRole.AUXILIARY
            }
            val step = if (role == PanelRole.MAINLINE) mainline.indexOf(id) + 1 else null
            val title = rp.section.item.title ?: rp.section.label
            val crumbs = buildList {
                add(Crumb(null, "mainline"))
                val entry = entries.firstOrNull()
                if (entry != null) {
                    val stepPanel = raw.first { it.section.id == entry.stepPanel }.section
                    add(Crumb(entry.stepPanel, "${entry.step} ${stepPanel.item.title ?: stepPanel.label}"))
                    if (role != PanelRole.MAINLINE) {
                        add(Crumb(entry.stepPanel, entry.viaLabel, entry.viaNode))
                        entry.path.dropLast(1).drop(1).reversed().forEach { intermediate ->
                            val section = raw.first { it.section.id == intermediate }.section
                            add(Crumb(intermediate, section.item.title ?: section.label))
                        }
                        add(Crumb(id, title))
                    }
                } else {
                    add(Crumb(id, title))
                }
            }
            Panel(
                id = id,
                title = title,
                order = rp.order,
                role = role,
                step = step,
                dims = rp.section.dims,
                parentId = rp.parent,
                resultId = rp.section.resultId,
                fields = nodePanel.filter { (node, panel) -> panel == id && plan.valueVertices[node] is InputVertex }.keys.toList() +
                    homeInputs.filterValues { it == id }.keys,
                nodes = nodePanel.filter { (node, panel) -> panel == id && plan.valueVertices[node] !is InputVertex }.keys.toList(),
                imports = flows.filter { it.toPanel == id },
                exports = flows.filter { it.fromPanel == id },
                entries = entries,
                breadcrumb = crumbs,
            )
        }
        return SchemaMap(
            schemaId = schema.id,
            title = schema.meta.title,
            panels = panels,
            mainline = mainline,
            generalInputs = general,
            params = plan.valueVertices.values.filterIsInstance<ParamVertex>().map { it.id },
            flows = flows.toList(),
        )
    }
}
