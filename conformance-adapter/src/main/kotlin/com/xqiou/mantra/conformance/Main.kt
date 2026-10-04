package com.xqiou.mantra.conformance

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.Path

/** Nonpublished test adapter. Expected answers belong exclusively to the independent corpus. */
fun main(arguments: Array<String>) {
    require(arguments.size % 2 == 0) { "Arguments are --schema/--case/--parameter/--query/--max-formulas VALUE" }
    val options = arguments.toList().chunked(2)
    require(options.all { it.first() in setOf("--schema", "--case", "--parameter", "--query", "--max-formulas") })
    fun one(name: String): String = options.filter { it.first() == name }.single()[1]
    fun source(path: String): SourceText = SourceText(path, Files.readString(Path.of(path)))
    val queries = options.filter { it.first() == "--query" }.map { it[1] }
    require(queries.distinct().size == queries.size) { "Duplicate query" }
    val ceiling = options.filter { it.first() == "--max-formulas" }
    require(ceiling.size <= 1) { "Duplicate formula ceiling" }
    val limits = ceiling.singleOrNull()?.let {
        RunLimits(maxFormulaExecutions = it[1].toLong())
    } ?: RunLimits()
    val response: Map<String, Any?> = try {
        val schema = Mantra.loadSchema(source(one("--schema")), SourceResolver { _, _ -> null })
        val case = Mantra.loadCase(source(one("--case")))
        val parameters = options.filter { it.first() == "--parameter" }.map { Mantra.loadParameters(source(it[1])) }
        val result = Mantra.calculate(schema, case, parameters, CalculationOptions(limits = limits))
        val values = result.view.openReader().use { reader ->
            queries.associateWith { query ->
                val id = query.substringBefore('@')
                val node = result.node(id)
                val suffix = query.substringAfter('@', "")
                val value = when {
                    suffix == "*" -> reader.reduce(id).value
                    '*' in suffix -> {
                        val parts = suffix.split('/')
                        require(parts.size == node.dims.size) { "Reduction query requires every dimension" }
                        reader.reduce(id, node.dims.zip(parts).filter { it.second != "*" }.toMap()).value
                    }
                    suffix.isEmpty() -> {
                        require(node.dims.isEmpty()) { "A scalar query cannot omit dimensions" }
                        node.value(emptyList())
                    }
                    else -> {
                        val coordinate = suffix.split('/')
                        require(coordinate.size == node.dims.size) { "Query requires a complete coordinate" }
                        node.value(coordinate)
                    }
                }
                checkNotNull(value) { "Unsupported reduction: $query" }
                WorkbenchJson.value(value, reader)
            }
        }
        linkedMapOf(
            "succeeded" to result.succeeded,
            "validationPassed" to result.validationPassed,
            "values" to values,
            "diagnostics" to diagnostics(result.diagnostics),
        )
    } catch (error: MantraException) {
        linkedMapOf(
            "succeeded" to false,
            "validationPassed" to
                error.diagnostics.none { it.category == DiagnosticCategory.BUSINESS && it.severity == Severity.ERROR },
            "values" to emptyMap<String, Any?>(),
            "diagnostics" to diagnostics(error.diagnostics),
        )
    }
    println(WorkbenchJson.write(response))
}

private fun diagnostics(findings: List<Diagnostic>): List<Map<String, String>> = findings.map { finding ->
    val effect = when {
        finding.severity == Severity.ERROR && finding.category == DiagnosticCategory.BUSINESS -> "validation-failure"
        finding.severity == Severity.ERROR -> "technical-failure"
        finding.severity == Severity.WARNING -> "warning"
        else -> "information"
    }
    mapOf(
        "code" to finding.code,
        "category" to finding.category.name.lowercase(),
        "severity" to finding.severity.name.lowercase(),
        "effect" to effect,
    )
}.sortedWith(
    compareBy({
        it.getValue("code")
    }, { it.getValue("category") }, { it.getValue("severity") }, { it.getValue("effect") }),
)
