package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Produces browser-ready, versioned read-only fixtures for a self-contained example directory. */
object Fixtures {
    data class Entry(val id: String, val title: String, val files: Map<String, String>)

    fun write(casePath: Path, out: Path, publicPrefix: String = "/fixtures", workspaceRoot: Path? = null): Entry {
        val absoluteCase = casePath.toAbsolutePath().normalize()
        val directory = absoluteCase.parent
        val schemaPath = directory.resolve("schema.mantra")
        val layoutPath = directory.resolve("layout.mantra")
        require(Files.isRegularFile(absoluteCase)) { "Case file does not exist: $absoluteCase" }
        require(Files.isRegularFile(schemaPath)) { "Expected schema.mantra next to case: $schemaPath" }
        val schema = Mantra.loadSchema(schemaPath)
        val case = Mantra.loadCase(absoluteCase)
        val result = Mantra.calculate(schema, case)
        val view = CalculationView.of(result)
        val layout = if (Files.isRegularFile(layoutPath)) Render.loadLayout(layoutPath) else Render.defaultLayout(result)
        val paper = Render.paper(result, layout)
        val documents = buildList {
            add(absoluteCase)
            schema.sources.forEach { name ->
                val sourcePath = directory.resolve(name).normalize()
                if (Files.isRegularFile(sourcePath)) add(sourcePath)
            }
            if (Files.isRegularFile(layoutPath)) add(layoutPath)
        }.distinct()
        val root = workspaceRoot?.toAbsolutePath()?.normalize() ?: directory.parent
        require(absoluteCase.startsWith(root)) { "Case must be inside workspace root: $root" }
        val revision = revision(documents, root)
        val normein = readNormeinCommit(directory)
        val id = root.relativize(absoluteCase).toString().replace('\\', '/')
        val slug = id.removeSuffix(".mantra").replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-') +
            "-" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .take(4).joinToString("") { "%02x".format(it) }
        val target = out.resolve(slug)
        Files.createDirectories(target)
        val files = linkedMapOf<String, String>()
        listOf(
            "structure" to WorkbenchDocuments.structure(view),
            "run" to WorkbenchDocuments.run(view, layout),
            "paper" to WorkbenchDocuments.paper(paper, view),
            "diagnostics" to WorkbenchDocuments.diagnostics(view.diagnostics),
        ).forEach { (name, data) ->
            val file = "$name.json"
            Files.writeString(
                target.resolve(file),
                WorkbenchJson.write(WorkbenchJson.envelope(revision, "0.1.0-SNAPSHOT", normein, data)) + "\n",
            )
            files[name] = "${publicPrefix.trimEnd('/')}/$slug/$file"
        }
        val entry = Entry(id, case.text("title") ?: schema.meta.title, files)
        val manifest = linkedMapOf("cases" to listOf(manifestEntry(entry)))
        Files.writeString(out.resolve("index.json"), WorkbenchJson.write(manifest) + "\n")
        return entry
    }

    fun writeMany(cases: List<Path>, out: Path, publicPrefix: String = "/fixtures", workspaceRoot: Path? = null): List<Entry> {
        require(cases.isNotEmpty()) { "At least one case is required" }
        val absolute = cases.map { it.toAbsolutePath().normalize() }
        val root = workspaceRoot?.toAbsolutePath()?.normalize() ?: absolute.map { it.parent.parent }.reduce { common, next ->
            var candidate = common
            while (!next.startsWith(candidate)) candidate = candidate.parent
            candidate
        }
        val entries = absolute.map { write(it, out, publicPrefix, root) }
        val manifest = linkedMapOf("cases" to entries.map(::manifestEntry))
        Files.writeString(out.resolve("index.json"), WorkbenchJson.write(manifest) + "\n")
        return entries
    }

    private fun manifestEntry(entry: Entry): Map<String, Any?> =
        linkedMapOf("id" to entry.id, "title" to entry.title, "files" to entry.files)

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
