package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

/** Reads real public wire envelopes. This adapter has no access to the calculation injection port. */
internal class GenericAcceptanceWorkbench(private val paths: AcceptancePaths) : AcceptanceWorkbenchProbe {
    override fun document(casePath: Path, document: String, layoutId: String?): Map<String, Any?> =
        WorkspaceCatalog(paths.root).use { catalog ->
            val id = paths.relative(casePath)
            val result = if (document == "export-preview") {
                catalog.exportPreview(id, layoutId = layoutId)
            } else {
                catalog.document(id, document, layoutId = layoutId)
            }
            val envelope = wireMap(plainJson(Json.parse(catalog.envelope(result))))
            require(envelope["contract"] == WorkbenchJson.CONTRACT)
            wireMap(envelope["data"])
        }

    override fun inheritedDiagnostics(casePath: Path): List<AcceptanceInheritedDiagnostic> {
        val run = document(casePath, "run", null)
        val graph = wireMap(run["caseGraph"])
        val root = wireText(graph, "root")
        val cases = wireList(graph["cases"]).map(::wireMap).associateBy { wireText(it, "case") }
        return wireList(run["diagnostics"]).map(::wireMap).mapNotNull { finding ->
            if (finding["category"] != "business") return@mapNotNull null
            val address = finding["address"]?.let(::wireMap) ?: return@mapNotNull null
            val owner = address["case"] as? String ?: return@mapNotNull null
            if (owner == root) return@mapNotNull null
            val source = cases.getValue(owner)
            val identity = identity(source)
            require(finding["caseRevision"] == identity.revision) { "Workbench source revision is inconsistent" }
            // Routing fields are retained in the typed identity carrier, leaving the direct
            // source diagnostic payload comparable to that source's original public result.
            AcceptanceInheritedDiagnostic(identity, diagnostic(finding, address))
        }
    }

    override fun links(casePath: Path): List<AcceptanceLinkedInput> {
        val run = document(casePath, "run", null)
        val graph = wireMap(run["caseGraph"])
        val root = wireText(graph, "root")
        val cases = wireList(graph["cases"]).map(::wireMap).associateBy { wireText(it, "case") }
        val values = wireMap(run["values"])
        return wireList(graph["edges"]).map(::wireMap).filter { wireMap(it["to"])["case"] == root }.map { edge ->
            val from = wireMap(edge["from"])
            val to = wireMap(edge["to"])
            val source = cases.getValue(wireText(from, "case"))
            val identity = identity(source)
            require(edge["revision"] == identity.revision)
            val target = scalarAddress(to)
            val cell = wireMap(wireMap(values[target.node])[target.coord.joinToString("/")])
            val link = wireMap(cell["link"])
            require(link["case"] == wireText(source, "case") && link["revision"] == identity.revision)
            require(link["caseId"] == source["caseId"])
            require(wireMap(link["schema"]) == wireMap(source["schema"]))
            require(scalarAddress(wireMap(link["address"])) == scalarAddress(from))
            AcceptanceLinkedInput(identity, scalarAddress(from), target, scalarValue(cell["value"]))
        }
    }

    private fun identity(case: Map<String, Any?>): AcceptanceRunIdentity {
        val schema = wireMap(case["schema"])
        return AcceptanceRunIdentity(
            paths.confined(paths.root.resolve(wireText(case, "case"))),
            AcceptanceSchemaId(wireText(schema, "id"), schema["version"] as? String),
            wireText(case, "revision"),
        )
    }

    private fun diagnostic(finding: Map<String, Any?>, address: Map<String, Any?>): Diagnostic {
        val location = finding["location"]?.let(::wireMap)?.let { source ->
            SourceLocation(
                wireText(source, "document"),
                wireInt(source["line"]),
                wireInt(source["column"]),
                source["startOffset"]?.let(::wireInt),
                source["endOffset"]?.let(::wireInt),
            )
        }
        return Diagnostic(
            severity = Severity.valueOf(wireText(finding, "severity").uppercase(java.util.Locale.ROOT)),
            code = wireText(finding, "code"), message = wireText(finding, "message"), location = location,
            nodeId = address["node"] as? String, coord = coordinate(address["coord"]),
            category = DiagnosticCategory.valueOf(wireText(finding, "category").uppercase(java.util.Locale.ROOT)),
            rowIndex = finding["rowIndex"]?.let(::wireInt), column = finding["column"] as? String,
        )
    }
}

private fun scalarAddress(value: Map<String, Any?>) =
    AcceptanceScalarAddress(wireText(value, "node"), coordinate(value["coord"]))

private fun coordinate(value: Any?): List<String> = if (value == null) {
    emptyList()
} else {
    wireList(value).map { it as? String ?: error("Wire coordinate must contain text") }
}

private fun wireText(value: Map<String, Any?>, key: String): String =
    value[key] as? String ?: error("Wire field $key requires text")

private fun wireInt(value: Any?): Int = when (value) {
    is Int -> value
    is Long -> Math.toIntExact(value)
    else -> error("Wire field requires an integral JSON number")
}

private fun wireList(value: Any?): List<*> = value as? List<*> ?: error("Wire field requires a list")

private fun wireMap(value: Any?): Map<String, Any?> {
    val map = value as? Map<*, *> ?: error("Wire field requires an object")
    return map.entries.associate { (key, item) ->
        (key as? String ?: error("Wire object key requires text")) to item
    }
}

private fun plainJson(value: Value): Any? = when (value) {
    Value.Nil -> null
    is Value.Text -> value.value
    is Value.Bool -> value.value
    is Value.Num -> try {
        value.value.intValueExact()
    } catch (_: ArithmeticException) {
        value.value.longValueExact()
    }
    is Value.Vec -> value.items.map(::plainJson)
    is Value.MapV -> value.entries.entries.associate { (key, item) ->
        (key as? Value.Kw)?.name?.let { it to plainJson(item) } ?: error("JSON object key requires text")
    }
    else -> error("Unexpected JSON value $value")
}

/** Decode the public tagged scalar/collection value contract, preserving decimal text exactly. */
private fun scalarValue(value: Any?): Value = when (value) {
    null -> Value.Nil
    is Boolean -> Value.Bool(value)
    is String -> Value.Text(value)
    is List<*> -> Value.Vec(value.map(::scalarValue))
    is Map<*, *> -> when {
        "n" in value -> Value.Num(BigDecimal(value["n"] as? String ?: error("Decimal wire requires text")))
        "kw" in value -> Value.Kw(value["kw"] as? String ?: error("Keyword wire requires text"))
        "date" in value -> Value.Date(LocalDate.parse(value["date"] as? String ?: error("Date wire requires text")))
        "map" in value -> Value.MapV(
            wireList(value["map"]).associate { entry ->
                val pair = wireList(entry)
                require(pair.size == 2)
                scalarValue(pair[0]) to scalarValue(pair[1])
            },
        )
        else -> error("Unexpected tagged wire value")
    }
    else -> error("Wire values may not contain untagged numeric amounts")
}
