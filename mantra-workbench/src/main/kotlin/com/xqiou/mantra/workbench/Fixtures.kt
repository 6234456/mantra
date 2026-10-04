package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunCase
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.CaseRunResult
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Browser fixtures retain actual case bindings, graph revisions and one FULL capture per entry. */
object Fixtures {
    data class Entry(val id: String, val title: String, val files: Map<String, Any?>)

    fun write(
        casePath: Path,
        out: Path,
        publicPrefix: String = "/fixtures",
        workspaceRoot: Path? = null,
        explainAddresses: List<ExplainAddress> = emptyList(),
    ): Entry {
        val absolute = casePath.toRealPath()
        val root = workspaceRoot?.toRealPath() ?: absolute.parent.parent
        val id = relative(root, absolute)
        return generate(listOf(absolute), out, publicPrefix, root, mapOf(id to explainAddresses))
            .first { it.id == id }
    }

    fun writeMany(
        cases: List<Path>,
        out: Path,
        publicPrefix: String = "/fixtures",
        workspaceRoot: Path? = null,
        explainAddresses: Map<String, List<ExplainAddress>> = emptyMap(),
    ): List<Entry> {
        require(cases.isNotEmpty()) { "At least one case is required" }
        val absolute = cases.map(Path::toRealPath)
        val root = workspaceRoot?.toRealPath() ?: absolute.map { it.parent.parent }.reduce { common, next ->
            var candidate = common
            while (!next.startsWith(candidate)) candidate = candidate.parent
            candidate
        }
        return generate(absolute, out, publicPrefix, root, explainAddresses)
    }

    private fun generate(
        cases: List<Path>,
        out: Path,
        publicPrefix: String,
        root: Path,
        explains: Map<String, List<ExplainAddress>>,
    ): List<Entry> {
        require(Files.isDirectory(root)) { "Workspace must be a directory" }
        Files.createDirectories(out)
        val requested = cases.map { CanonicalCaseKey(relative(root, it)) }.distinct()
        val pending = ArrayDeque(requested)
        val snapshots = linkedMapOf<CanonicalCaseKey, CaseRunCase>()
        val graphs = linkedMapOf<CanonicalCaseKey, CaseRunResult>()
        val layouts = linkedMapOf<CanonicalCaseKey, LayoutSpec>()
        val sources = linkedSetOf<CanonicalCaseKey>()
        val expected = linkedMapOf<CanonicalCaseKey, CaseRunCase>()
        val normein = readNormeinCommit(root)
        fun consistent(first: CaseRunCase, next: CaseRunCase) {
            require(first.revision == next.revision && first.schema == next.schema && first.caseId == next.caseId) {
                "Case identity or revision changed during fixture generation: ${first.key.value}"
            }
            require(first.view.nodes.mapValues { it.value.values } == next.view.nodes.mapValues { it.value.values }) {
                "Case values changed between consuming and source fixture captures: ${first.key.value}"
            }
        }
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            if (key in graphs) continue
            val loader = CasePackageLoader(root)
            val graph = CaseGraphRunner(loader).use { runner ->
                runner.run(CaseRunRequest(CaseReference(key.value), audit = AuditOptions()))
            }
            requireNotNull(graph.result) { "No current root calculation for ${key.value}: ${graph.diagnostics}" }
            val current = graph.cases.getValue(key)
            expected[key]?.let { consistent(it, current) }
            graph.cases.forEach { (source, snapshot) ->
                snapshots[source]?.let { consistent(it, snapshot) }
                expected.putIfAbsent(source, snapshot)?.let { consistent(it, snapshot) }
                if (source != key) {
                    sources.add(source)
                    if (source !in graphs && source !in pending) pending.addLast(source)
                }
            }
            snapshots[key] = current
            val binding = loader.binding(key)
            layouts[key] = binding.layout ?: Render.defaultLayout(current.view)
            graphs[key] = graph
        }
        // Discover the complete source set before writing: an explicitly requested case
        // can also be a source of a later requested consumer, and needs navigable evidence.
        val result = graphs.map { (key, graph) ->
            val current = snapshots.getValue(key)
            val sourceEntry = key in sources
            val addresses = buildList {
                addAll(explains[key.value].orEmpty())
                if (sourceEntry) {
                    current.view.nodes.values.forEach { node ->
                        node.values.keys.forEach { coord -> add(ExplainAddress(node.id, coord)) }
                    }
                }
            }
            writeSnapshot(current, graph, layouts.getValue(key), addresses, sourceEntry, out, publicPrefix, normein)
        }
        val manifest = linkedMapOf("cases" to result.map(::manifestEntry))
        Files.writeString(out.resolve("index.json"), WorkbenchJson.write(manifest) + "\n")
        return result
    }

    private fun writeSnapshot(
        snapshot: CaseRunCase,
        graph: CaseRunResult,
        layout: LayoutSpec,
        addresses: List<ExplainAddress>,
        completeSource: Boolean,
        out: Path,
        prefix: String,
        normein: String,
    ): Entry {
        val id = snapshot.key.value
        val stem = id.removeSuffix(".mantra").replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-')
        val slug = "$stem-${hash(id, 4)}"
        val target = out.resolve(slug)
        Files.createDirectories(target)
        val files = linkedMapOf<String, Any?>()
        fun write(file: String, data: Any?): String {
            val envelope = WorkbenchJson.envelope(snapshot.revision, "0.4.0-SNAPSHOT", normein, data)
            Files.writeString(target.resolve(file), WorkbenchJson.write(envelope) + "\n")
            return "${prefix.trimEnd('/')}/$slug/$file"
        }
        val view = snapshot.view
        val paper = Render.paper(view, layout)
        val exportPreviews = if (snapshot.result.succeeded) {
            ExcelExport.workbook(view, layout).use { export ->
                export.report.sheets.map { name ->
                    name to ExportDocuments.preview(requireNotNull(export.describe(name)))
                }
            }
        } else {
            emptyList()
        }
        val documents = linkedMapOf(
            "structure" to WorkbenchDocuments.structure(view),
            "run" to WorkbenchDocuments.run(view, layout, graph),
            "paper" to WorkbenchDocuments.paper(paper, view),
            "diagnostics" to WorkbenchDocuments.diagnostics(view.diagnostics),
            "parameters" to WorkbenchDocuments.parameters(view),
        )
        exportPreviews.firstOrNull()?.let { documents["export-preview"] = it.second }
        documents.forEach { (name, data) -> files[name] = write("$name.json", data) }
        if (addresses.isNotEmpty()) {
            view.openReader().use { reader ->
                val output = linkedMapOf<String, String>()
                val pending = ArrayDeque(addresses.distinct())
                while (pending.isNotEmpty()) {
                    reader.chargeScans()
                    val address = pending.removeFirst()
                    require(address.case == null || address.case == id) {
                        "Explain case does not match this fixture entry"
                    }
                    val path = addressPath(address)
                    if (path in output) continue
                    require(address.expectedRevision == null || address.expectedRevision == snapshot.revision) {
                        "Explain source revision has changed"
                    }
                    val data = explain(snapshot, layout, address, reader).toMutableMap()
                    data["revision"] = snapshot.revision
                    // Source resources can be opened while the UI still shows their consumer case.
                    val qualified = qualify(data, id, reader)
                    output[path] = write("explain-${hash(path, 8)}.json", qualified)
                    if (completeSource) {
                        linkedAddresses(qualified, reader).forEach { child ->
                            if (child.case == id && addressPath(child) !in output) pending.addLast(child)
                        }
                    }
                }
                files["explains"] = output
            }
        }
        exportPreviews.forEachIndexed { index, (name, data) ->
            files["export-preview:$name"] = write("export-preview-$index.json", data)
        }
        return Entry(id, view.case.text("title") ?: view.schema.title, files)
    }

    private fun explain(
        snapshot: CaseRunCase,
        layout: LayoutSpec,
        address: ExplainAddress,
        reader: CalculationReader,
    ): Map<String, Any?> {
        val view = snapshot.view
        val memberMap = address.node.startsWith("all.")
        val aggregate = address.node.startsWith("aggregate.")
        val projected = memberMap || aggregate
        val nodeId = if (memberMap) address.node.removePrefix("all.") else address.node.removePrefix("aggregate.")
        val node = requireNotNull(view.nodes[nodeId]) { "Unknown Explain node: $address" }
        if (projected) {
            require(address.cell == null && node.dims.isNotEmpty()) { "Invalid projected Explain address" }
            val fixed = linkedMapOf<String, String>()
            address.coord.forEach { part ->
                val pair = part.split('=', limit = 2)
                require(pair.size == 2 && pair.none(String::isBlank)) { "Malformed member-map coordinate" }
                require(pair[0] in (if (aggregate) view.dimensions.keys else node.dims))
                require(fixed.put(pair[0], pair[1]) == null) { "Repeated fixed dimension" }
                require(view.members[pair[0]].orEmpty().any { it.key == pair[1] }) { "Unknown fixed member" }
            }
            val order = if (aggregate) view.dimensionOrder(fixed.keys) else node.dims.filter { it in fixed }
            val canonical = order.map { "$it=${fixed.getValue(it)}" }
            require(address.coord == canonical && !node.dims.all { it in fixed })
            return if (aggregate) {
                WorkbenchDocuments.aggregate(view, layout, nodeId, fixed, reader)
            } else {
                WorkbenchDocuments.memberMap(view, layout, nodeId, fixed, reader)
            }
        }
        require(node.dims.size == address.coord.size && address.coord in node.values) { "Unknown Explain coordinate" }
        val cellValue = address.cell?.let { (row, column) ->
            val rows = node.value(address.coord) as? Value.Vec
            val record = row.toIntOrNull()?.let { rows?.items?.getOrNull(it) } as? Value.MapV
            requireNotNull(record?.entries?.get(Value.Kw(column)) ?: record?.entries?.get(Value.Text(column))) {
                "Unknown Explain input cell"
            }
        }
        val captured = when (val trace = node.trace(address.coord)) {
            is NodeTrace.Computed -> trace.explanation
            is NodeTrace.Validation -> trace.explanation
            is NodeTrace.Failed -> trace.explanation
            is NodeTrace.Choice -> trace.options.firstOrNull { it.key == trace.selected }?.explanation
            else -> null
        }
        return WorkbenchDocuments.explain(
            view,
            layout,
            nodeId,
            address.coord,
            captured,
            address.cell,
            cellValue,
            reader,
        )
    }

    private fun qualify(value: Any?, case: String, reader: CalculationReader): Any? {
        reader.chargeScans()
        return when (value) {
            is Map<*, *> -> value.entries.associate { (key, item) -> key to qualify(item, case, reader) }.let { copy ->
                if ("node" in copy && "case" in copy && copy["case"] == null) copy + ("case" to case) else copy
            }
            is List<*> -> value.map { qualify(it, case, reader) }
            else -> value
        }
    }

    private fun linkedAddresses(value: Any?, reader: CalculationReader): List<ExplainAddress> {
        reader.chargeScans()
        return when (value) {
            is Map<*, *> -> {
                val own = if (value["node"] is String && "case" in value) {
                    listOf(
                        ExplainAddress(
                            value["node"] as String,
                            (value["coord"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                            cell = (value["cell"] as? Map<*, *>)?.let { cell ->
                                (cell["row"] as? String)?.let { row -> (cell["column"] as? String)?.let { row to it } }
                            },
                            case = value["case"] as? String,
                        ),
                    )
                } else {
                    emptyList()
                }
                own + value.values.flatMap { linkedAddresses(it, reader) }
            }
            is List<*> -> value.flatMap { linkedAddresses(it, reader) }
            else -> emptyList()
        }
    }

    private fun relative(root: Path, file: Path): String {
        val actual = file.toRealPath()
        require(actual.startsWith(root) && Files.isRegularFile(actual)) { "Case must be inside workspace root: $root" }
        return root.relativize(actual).toString().replace('\\', '/')
    }

    private fun manifestEntry(entry: Entry): Map<String, Any?> = linkedMapOf(
        "id" to entry.id,
        "title" to entry.title,
        "files" to entry.files,
    )

    /** Canonical components match workbench-ui addressToPath, including reserved punctuation. */
    internal fun addressPath(address: ExplainAddress): String {
        fun part(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
            .replace(".", "%2E").replace("*", "%2A")
        val members = if (address.coord.isEmpty()) "" else "@" + address.coord.joinToString("/") { part(it) }
        val cell = address.cell?.let { "#${part(it.first)}.${part(it.second)}" }.orEmpty()
        return part(address.node) + members + cell
    }

    private fun hash(value: String, bytes: Int): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(bytes).joinToString("") { "%02x".format(it) }

    private fun readNormeinCommit(directory: Path): String {
        var cursor: Path? = directory
        while (cursor != null) {
            val lock = cursor.resolve("normein-build.lock")
            if (Files.isRegularFile(lock)) {
                return Files.readAllLines(lock)
                    .firstOrNull { it.startsWith("normeinCommit=") }?.substringAfter('=')?.take(8) ?: "unknown"
            }
            cursor = cursor.parent
        }
        return "unknown"
    }
}
