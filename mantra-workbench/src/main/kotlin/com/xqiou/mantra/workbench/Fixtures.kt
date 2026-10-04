package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Produces browser-ready, versioned read-only fixtures for a self-contained example directory. */
object Fixtures {
    data class Entry(val id: String, val title: String, val files: Map<String, Any?>)

    fun write(
        casePath: Path,
        out: Path,
        publicPrefix: String = "/fixtures",
        workspaceRoot: Path? = null,
        explainAddresses: List<ExplainAddress> = emptyList(),
    ): Entry {
        val absoluteCase = casePath.toRealPath()
        val directory = absoluteCase.parent
        val schemaPath = directory.resolve("schema.mantra")
        val layoutPath = directory.resolve("layout.mantra")
        require(Files.isRegularFile(absoluteCase)) { "Case file does not exist: $absoluteCase" }
        require(Files.isRegularFile(schemaPath)) { "Expected schema.mantra next to case: $schemaPath" }
        val schema = Mantra.loadSchema(schemaPath)
        val case = Mantra.loadCase(absoluteCase)
        val root = workspaceRoot?.toRealPath() ?: directory.parent
        require(absoluteCase.startsWith(root)) { "Case must be inside workspace root: $root" }
        val bound = BoundSources.load(case, schema, absoluteCase, root)
        val result = Mantra.calculateForAudit(schema, bound.case)
        val view = CalculationView.of(result)
        val layoutBinding = case.meta["layout"]
        require(layoutBinding == null || layoutBinding is Value.Text) { "Case :layout must be text: $absoluteCase" }
        val selectedLayout = if (layoutBinding != null) {
            require(Files.isRegularFile(layoutPath)) { "Expected layout.mantra next to bound case: $layoutPath" }
            Render.loadLayout(layoutPath).also { layout ->
                require(layout.id == (layoutBinding as Value.Text).value) {
                    "Case binds layout ${(layoutBinding).value}, but $layoutPath declares ${layout.id}"
                }
            }
        } else {
            null
        }
        val layout = selectedLayout ?: Render.defaultLayout(view)
        val paper = Render.paper(result, layout)
        val documents = buildList {
            add(absoluteCase)
            schema.sources.forEach { name ->
                val sourcePath = directory.resolve(name).normalize()
                if (Files.isRegularFile(sourcePath)) add(sourcePath)
            }
            addAll(bound.files)
            if (selectedLayout != null) add(layoutPath)
        }.distinct()
        val revision = revision(documents, root)
        val normein = readNormeinCommit(directory)
        val id = root.relativize(absoluteCase).toString().replace('\\', '/')
        val slug = id.removeSuffix(".mantra").replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-') +
            "-" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .take(4).joinToString("") { "%02x".format(it) }
        val target = out.resolve(slug)
        Files.createDirectories(target)
        val files = linkedMapOf<String, Any?>()
        val exportPreviews = ExcelExport.workbook(view, layout).use { export ->
            export.report.sheets.map { name -> name to ExportDocuments.preview(requireNotNull(export.describe(name))) }
        }
        listOf(
            "structure" to WorkbenchDocuments.structure(view),
            "run" to WorkbenchDocuments.run(view, layout),
            "paper" to WorkbenchDocuments.paper(paper, view),
            "diagnostics" to WorkbenchDocuments.diagnostics(view.diagnostics),
            "export-preview" to exportPreviews.first().second,
            "parameters" to WorkbenchDocuments.parameters(view),
        ).forEach { (name, data) ->
            val file = "$name.json"
            Files.writeString(
                target.resolve(file),
                WorkbenchJson.write(WorkbenchJson.envelope(revision, "0.2.0-SNAPSHOT", normein, data)) + "\n",
            )
            files[name] = "${publicPrefix.trimEnd('/')}/$slug/$file"
        }
        if (explainAddresses.isNotEmpty()) {
            val explains = linkedMapOf<String, String>()
            explainAddresses.distinct().forEach { address ->
                val memberMap = address.node.startsWith("all.")
                val aggregate = address.node.startsWith("aggregate.")
                val projected = memberMap || aggregate
                val nodeId = if (memberMap) {
                    address.node.removePrefix(
                        "all.",
                    )
                } else {
                    address.node.removePrefix("aggregate.")
                }
                val explained = if (projected) {
                    result
                } else {
                    Mantra.calculateForExplain(schema, bound.case, emptyList(), nodeId, address.coord)
                }
                val explainedView = CalculationView.of(explained)
                val node = explainedView.nodes[nodeId]
                val fixed = if (projected) {
                    address.coord.associate { part ->
                        val split = part.split('=', limit = 2)
                        require(split.size == 2 && split.none(String::isBlank)) {
                            "Malformed member-map coordinate: $part"
                        }
                        split[0] to split[1]
                    }
                } else {
                    emptyMap()
                }
                require(
                    node != null && (
                        if (projected) {
                            node.dims.isNotEmpty() && fixed.size < node.dims.size &&
                                (!aggregate || node.line?.ratio != null) &&
                                address.coord == node.dims.mapNotNull { dim -> fixed[dim]?.let { "$dim=$it" } } &&
                                fixed.all { (dim, member) ->
                                    explainedView.members[dim].orEmpty().any { it.key == member }
                                }
                        } else {
                            node.dims.size == address.coord.size && address.coord in node.values
                        }
                        ),
                ) {
                    "Explain address is not a calculated value: $address"
                }
                require(address.cell == null) { "Fixture Explain cell addresses are not supported" }
                val key = addressPath(address)
                val file = "explain-" + MessageDigest.getInstance("SHA-256")
                    .digest(key.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) } + ".json"
                val data = if (aggregate) {
                    WorkbenchDocuments.aggregate(explainedView, layout, nodeId, fixed)
                } else if (memberMap) {
                    WorkbenchDocuments.memberMap(explainedView, layout, nodeId, fixed)
                } else {
                    WorkbenchDocuments.explain(
                        explainedView,
                        layout,
                        nodeId,
                        address.coord,
                        explained.explainTrace,
                    )
                }
                Files.writeString(
                    target.resolve(file),
                    WorkbenchJson.write(WorkbenchJson.envelope(revision, "0.2.0-SNAPSHOT", normein, data)) + "\n",
                )
                explains[key] = "${publicPrefix.trimEnd('/')}/$slug/$file"
            }
            files["explains"] = explains
        }
        exportPreviews.forEachIndexed { index, (name, data) ->
            val file = "export-preview-$index.json"
            Files.writeString(
                target.resolve(file),
                WorkbenchJson.write(WorkbenchJson.envelope(revision, "0.2.0-SNAPSHOT", normein, data)) + "\n",
            )
            files["export-preview:$name"] = "${publicPrefix.trimEnd('/')}/$slug/$file"
        }
        val entry = Entry(id, case.text("title") ?: schema.meta.title, files)
        val manifest = linkedMapOf("cases" to listOf(manifestEntry(entry)))
        Files.writeString(out.resolve("index.json"), WorkbenchJson.write(manifest) + "\n")
        return entry
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
        val entries = absolute.map { path ->
            val id = root.relativize(path).toString().replace('\\', '/')
            write(path, out, publicPrefix, root, explainAddresses[id].orEmpty())
        }
        val manifest = linkedMapOf("cases" to entries.map(::manifestEntry))
        Files.writeString(out.resolve("index.json"), WorkbenchJson.write(manifest) + "\n")
        return entries
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
        return part(address.node) + members
    }

    private fun revision(paths: List<Path>, workspaceRoot: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sortedBy { workspaceRoot.relativize(it).toString() }.forEach { path ->
            val name = workspaceRoot.relativize(path).toString().replace('\\', '/').toByteArray(Charsets.UTF_8)
            val bytes = Files.readAllBytes(path)
            digest.update(name.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(name)
            digest.update(0.toByte())
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
            digest.update(0.toByte())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private fun readNormeinCommit(exampleDirectory: Path): String {
        var cursor: Path? = exampleDirectory
        while (cursor != null) {
            val lock = cursor.resolve("normein-build.lock")
            if (Files.isRegularFile(lock)) {
                return Files.readAllLines(lock)
                    .firstOrNull { it.startsWith("normeinCommit=") }
                    ?.substringAfter('=')?.take(8) ?: "unknown"
            }
            cursor = cursor.parent
        }
        return "unknown"
    }
}
