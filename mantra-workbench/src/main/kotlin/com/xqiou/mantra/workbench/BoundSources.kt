package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.data.CsvSource
import com.xqiou.mantra.core.data.DataSource
import com.xqiou.mantra.core.data.DataSources
import com.xqiou.mantra.core.data.JsonSource
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SourceBinding
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.excel.XlsxSource
import java.nio.file.Files
import java.nio.file.Path

/** Replays a case's source declarations and keeps source files in its revision set. */
object BoundSources {
    data class Loaded(val case: CaseData, val files: List<Path>, val overridden: List<List<String>>)

    fun load(case: CaseData, schema: Schema, caseFile: Path, workspaceRoot: Path): Loaded {
        if (case.sources.isEmpty()) return Loaded(case, emptyList(), emptyList())
        val root = workspaceRoot.toRealPath()
        val base = caseFile.toRealPath().parent
        val files = case.sources.map { binding ->
            val name = (binding.options["path"] as? Value.Text)?.value
                ?: invalid(binding, "Source :path must be text")
            val file = base.resolve(name).toAbsolutePath().normalize()
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root))
                invalid(binding, "Source path is missing or outside the workspace")
            if (Files.size(file) > 10 * 1024 * 1024)
                throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Import file exceeds 10 MiB")
            file
        }
        if (files.distinct().size != files.size) invalid(case.sources.first(), "The same source file is bound more than once")
        val sink = DiagnosticSink()
        val readValues = linkedMapOf<Path, Map<String, Value>>()
        val sources = case.sources.zip(files).map { (binding, file) ->
            val source = create(binding, file)
            object : DataSource {
                override val description = source.description
                override fun read(schema: Schema, sink: DiagnosticSink): Map<String, Value> = try {
                    source.read(schema, sink).also { readValues[file] = it }
                } catch (error: Exception) {
                    sink.error("MANTRA-DATA-SOURCE", "${file.fileName}: ${error.message}", binding.location)
                    emptyMap()
                }
            }
        }
        val effective = DataSources.apply(case, schema, sources, sink)
        if (sink.hasErrors) throw WorkspaceException(WorkspaceProblem.INVALID, "Data source could not be read", sink.all)
        fun coordinates(value: Value, prefix: String = ""): Set<String> = when (value) {
            is Value.MapV -> value.entries.flatMap { (key, child) ->
                val member = (key as? Value.Kw)?.name ?: (key as? Value.Text)?.value ?: return@flatMap emptyList()
                coordinates(child, if (prefix.isEmpty()) member else "$prefix/$member")
            }.toSet()
            else -> setOf(prefix)
        }
        val overridden = files.map { file ->
            readValues[file].orEmpty().flatMap { (id, value) ->
                val manual = case.inputs[id] ?: return@flatMap emptyList()
                (coordinates(value) intersect coordinates(manual)).map { coord -> if (coord.isEmpty()) id else "$id@$coord" }
            }.sorted()
        }
        return Loaded(effective, files, overridden)
    }

    private fun invalid(binding: SourceBinding, message: String): Nothing = throw WorkspaceException(
        WorkspaceProblem.INVALID, message, listOf(Diagnostic(Severity.ERROR, "MANTRA-CASE-SOURCE", message, binding.location)))

    private fun create(binding: SourceBinding, file: Path): DataSource {
        fun string(key: String): String? = when (val value = binding.options[key]) {
            null -> null
            is Value.Text -> value.value
            is Value.Kw -> value.name
            else -> invalid(binding, "Source :$key must be text")
        }
        fun mapping(key: String): Map<String, String> = when (val value = binding.options[key]) {
            null -> emptyMap()
            is Value.MapV -> value.entries.map { (from, to) ->
                val left = when (from) { is Value.Text -> from.value; is Value.Kw -> from.name; else -> invalid(binding, "Source mapping key must be text") }
                val right = when (to) { is Value.Text -> to.value; is Value.Kw -> to.name; else -> invalid(binding, "Source mapping target must be text") }
                left to right
            }.toMap()
            else -> invalid(binding, "Source :$key must be a map")
        }
        fun character(key: String, default: Char): Char = string(key)?.singleOrNull() ?: if (binding.options.containsKey(key))
            invalid(binding, "Source :$key must be one character") else default
        return when (binding.kind) {
            "csv" -> CsvSource(file, input = string("input"), delimiter = character("delimiter", ';'),
                decimal = character("decimal", ','), grouping = when (val selected = string("grouping")) {
                    null -> '.'
                    "" -> null
                    else -> selected.singleOrNull() ?: invalid(binding, "Source :grouping must be one character or empty")
                }.also { if (it == character("decimal", ',')) invalid(binding, "Decimal and grouping separators must differ") },
                columns = mapping("columns"), mode = string("mode") ?: "pairs", memberColumn = string("member-column"))
            "json" -> JsonSource(file, root = string("root"), mapping = mapping("mapping"))
            "xlsx" -> XlsxSource(file)
            else -> invalid(binding, "Unsupported source ${binding.kind}")
        }
    }
}
