package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.workbench.json.WorkbenchDocuments

/** One captured graph and one cumulative read epoch for the entire bounded Explain tree. */
internal fun WorkspaceCatalog.explainGraph(
    caseId: String,
    address: ExplainAddress,
    depth: Int,
): WorkspaceCatalog.DocumentResult {
    if (depth !in 1..5) throw WorkspaceException(WorkspaceProblem.REQUEST, "Explain depth must be between 1 and 5")
    val projected = address.node.startsWith("all.") || address.node.startsWith("aggregate.")
    val resolved = resolve(caseId, scan(), audit = true, explain = address.takeUnless { projected })
    val graph = checkNotNull(resolved.graph)
    return resolved.view.openReader().use { reader ->
        var remaining = 64
        fun project(target: ExplainAddress, level: Int): Map<String, Any?> {
            if (--remaining < 0) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Explain exceeds 64 nodes")
            val memberMap = target.node.startsWith("all.")
            val aggregate = target.node.startsWith("aggregate.")
            val projected = memberMap || aggregate
            if (projected && target.cell != null) {
                throw WorkspaceException(WorkspaceProblem.REQUEST, "Member-map or aggregate address cannot have a cell")
            }
            val nodeId = if (memberMap) target.node.removePrefix("all.") else target.node.removePrefix("aggregate.")
            val key = target.case?.let(::CanonicalCaseKey) ?: checkNotNull(graph.root)
            val source = graph.cases[key] ?: throw WorkspaceException(
                WorkspaceProblem.NOT_FOUND,
                "Explain case does not participate in this graph",
            )
            if (target.expectedRevision != null && target.expectedRevision != source.revision) {
                throw WorkspaceException(
                    WorkspaceProblem.CONFLICT,
                    "Explain source revision has changed",
                    currentRevision = source.revision,
                )
            }
            val view = source.view
            val layout = resolved.caseLayouts.getValue(key)
            val node = view.nodes[nodeId]
                ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain node was not found")
            if (!projected && (node.dims.size != target.coord.size || target.coord !in node.values)) {
                throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain coordinate was not found")
            }
            if (memberMap && node.dims.isEmpty()) {
                throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain member map was not found")
            }
            if (aggregate && (node.dims.isEmpty() || reader.reduce(view, nodeId, emptyMap()).trace == null)) {
                throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain aggregate was not found")
            }
            val fixed = if (projected) {
                val bindings = linkedMapOf<String, String>()
                target.coord.forEach { part ->
                    val split = part.split('=', limit = 2)
                    if (split.size != 2 || split.any(String::isBlank) ||
                        split[0] !in (if (aggregate) view.dimensions.keys else node.dims) ||
                        bindings.put(split[0], split[1]) != null
                    ) {
                        throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed member-map coordinate")
                    }
                    if (view.members[split[0]].orEmpty().none { it.key == split[1] }) {
                        throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain member was not found")
                    }
                }
                val dimensions = if (aggregate) {
                    view.dimensionOrder(bindings.keys)
                } else {
                    node.dims.filter {
                        it in
                            bindings
                    }
                }
                if (target.coord != dimensions.map { "$it=${bindings.getValue(it)}" } ||
                    node.dims.all { it in bindings }
                ) {
                    throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed member-map coordinate")
                }
                bindings
            } else {
                emptyMap()
            }
            val cellValue = target.cell?.let { (row, column) ->
                val rows = node.value(target.coord) as? Value.Vec
                val item = row.toIntOrNull()?.let { rows?.items?.getOrNull(it) } as? Value.MapV
                item?.entries?.entries?.firstOrNull { (key, _) ->
                    key == Value.Kw(column) || key == Value.Text(column)
                }?.value ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain cell was not found")
            }
            val data = if (aggregate) {
                try {
                    WorkbenchDocuments.aggregate(view, layout, nodeId, fixed, reader).toMutableMap()
                } catch (_: IllegalArgumentException) {
                    throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain aggregate evidence was not found")
                }
            } else if (memberMap) {
                WorkbenchDocuments.memberMap(view, layout, nodeId, fixed, reader).toMutableMap()
            } else {
                val captured = if (target == address && !projected) {
                    resolved.explainTrace
                } else {
                    when (val trace = node.trace(target.coord)) {
                        is NodeTrace.Computed -> trace.explanation
                        is NodeTrace.Validation -> trace.explanation
                        is NodeTrace.Failed -> trace.explanation
                        is NodeTrace.Choice -> trace.options.firstOrNull { it.key == trace.selected }?.explanation
                        else -> null
                    }
                }
                WorkbenchDocuments.explain(
                    view,
                    layout,
                    nodeId,
                    target.coord,
                    captured,
                    target.cell,
                    cellValue,
                    reader,
                ).toMutableMap()
            }
            data["revision"] = source.revision
            val qualified = qualifyAddresses(data, key.value.takeUnless { key == graph.root }, reader) as Map<*, *>
            data.clear()
            qualified.forEach { (k, v) -> data[k as String] = v }
            reader.chargeScans()
            if (level > 1) {
                @Suppress("UNCHECKED_CAST")
                val refs = data["references"] as List<Map<String, Any?>>
                data["references"] = refs.map { ref ->
                    @Suppress("UNCHECKED_CAST")
                    val linked = ref["address"] as Map<String, Any?>
                    val child = ExplainAddress(
                        linked.getValue("node") as String,
                        (linked["coord"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                        case = linked["case"] as? String,
                        expectedRevision = ref["revision"] as? String,
                    )
                    ref + ("explanation" to project(child, level - 1))
                }
            }
            return data
        }
        WorkspaceCatalog.DocumentResult(resolved.revision, project(address, depth))
    }
}

/** Relative addresses in a source projection belong to that exact source case. */
private fun qualifyAddresses(value: Any?, case: String?, reader: CalculationReader): Any? {
    reader.chargeScans()
    return when (value) {
        is Map<*, *> -> {
            val copy = value.entries.associate { (key, item) -> key to qualifyAddresses(item, case, reader) }
            if ("node" in copy && "case" in copy && copy["case"] == null) copy + ("case" to case) else copy
        }
        is List<*> -> value.map { qualifyAddresses(it, case, reader) }
        else -> value
    }
}
