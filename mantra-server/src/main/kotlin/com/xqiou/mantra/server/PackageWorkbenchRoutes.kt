package com.xqiou.mantra.server

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.xqiou.mantra.packages.PackageException
import com.xqiou.mantra.workbench.ExportBudget
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import com.xqiou.mantra.workbench.json.WorkbenchJson
import com.xqiou.mantra.workbench.packages.PackageWorkspaceCatalog

/** Called only after the existing loopback, CSRF token and bounded-body middleware. */
internal class PackageWorkbenchRoutes(
    private val catalog: PackageWorkspaceCatalog,
    private val exportBudget: ExportBudget,
    private val requests: WorkbenchRequests = WorkbenchRequests(catalog),
) {
    private val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).also {
            it.factory.setStreamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(1_048_576).build(),
            )
        }

    fun handle(exchange: HttpExchange, path: String, method: String, body: ByteArray): Boolean {
        if (path != "/api/v1/packages" && !path.startsWith("/api/v1/package-cases/")) return false
        try {
            val payload = dispatch(exchange, path, method, body)
            com.xqiou.mantra.server.json(exchange, 200, WorkbenchJson.write(payload))
        } catch (_: BinaryResponseSent) {
            return true
        } catch (error: PackageException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, error.message.orEmpty(), error.diagnostics)
        } catch (error: IllegalArgumentException) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, error.message ?: "Invalid package request")
        }
        return true
    }

    private fun dispatch(exchange: HttpExchange, path: String, method: String, body: ByteArray): Map<String, Any?> {
        val query = query(exchange.requestURI.rawQuery)
        if (path == "/api/v1/packages") {
            require(method == "GET" && query.isEmpty())
            return catalog.workspace()
        }
        val match = Regex("^/api/v1/package-cases/([^/]+)/([^/]+)$").matchEntire(path)
            ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Package route was not found")
        val case = decode(match.groupValues[1])
        val action = match.groupValues[2]
        if (method == "GET" && action in setOf("run", "structure", "paper", "parameters", "diagnostics", "sources")) {
            require(query.keys.all { it in setOf("panel", "includeZero") } && (action == "paper" || query.isEmpty()))
            require("includeZero" !in query || query["includeZero"] in setOf("true", "false")) {
                "includeZero must be true or false"
            }
            return catalog.document(case, action, query["panel"], query["includeZero"] == "true")
        }
        if (method == "GET" && action == "diagnostic-source") {
            val request = diagnosticSourceRequest(query)
            return catalog.sourceContext(case, request.first, request.second)
        }
        if (method == "GET" && action == "export-preview") {
            require(query.keys.all { it == "sheet" })
            return catalog.exportPreview(case, query["sheet"], exportBudget)
        }
        if (method == "GET" && action == "explain") {
            require(query.keys.all { it in setOf("address", "case", "expectedRevision") } && "address" in query)
            val address = parseExplainAddress(query.getValue("address"))
            require(address.cell == null) { "Package Explain currently accepts a node coordinate" }
            return catalog.explain(case, address.node, address.coord, query["case"], query["expectedRevision"])
        }
        if (method == "GET" && action in setOf("export.xlsx", "export.html", "export.txt", "export.pdf")) {
            require(query.isEmpty())
            val format = when (action) {
                "export.txt" -> "text"
                else -> action.substringAfter('.')
            }
            val bytes = catalog.export(case, format, exportBudget)
            send(
                exchange,
                200,
                when (format) {
                    "pdf" -> "application/pdf"
                    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    "html" -> "text/html; charset=utf-8"
                    else -> "text/plain; charset=utf-8"
                },
                bytes,
            )
            // The caller must not send a second JSON response for binary exports.
            throw BinaryResponseSent
        }
        require(method == "POST" && query.isEmpty())
        val root = try {
            requireNotNull(json.readTree(body))
        } catch (error: Exception) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed package request JSON")
        }
        fun fields(vararg expected: String) {
            require(root.isObject && root.fieldNames().asSequence().toSet() == expected.toSet()) {
                "Unexpected package request fields"
            }
        }
        fun text(name: String): String {
            require(root[name]?.isTextual == true && root[name].textValue().isNotBlank())
            return root[name].textValue()
        }
        return when (action) {
            "compare" -> {
                fields("variantParameters", "effectiveDate", "expectedRevision")
                val parameters = root["variantParameters"]
                require(parameters.isArray && parameters.size() in 1..8 && parameters.all { it.isTextual }) {
                    "Captured parameter resource IDs are required"
                }
                val date = text("effectiveDate")
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date)) { "An explicit ISO date is required" }
                val effectiveDate = try {
                    java.time.LocalDate.parse(date)
                } catch (_: java.time.DateTimeException) {
                    throw WorkspaceException(WorkspaceProblem.REQUEST, "An explicit valid ISO date is required")
                }
                catalog.compare(
                    case,
                    parameters.map { it.textValue() },
                    effectiveDate,
                    text("expectedRevision"),
                )
            }
            "migration-preview" -> {
                fields("baseRevision", "targetCase")
                catalog.previewMigration(case, text("baseRevision"), text("targetCase"))
            }
            "migration-apply" -> {
                fields("reviewToken")
                catalog.applyMigration(case, text("reviewToken"))
            }
            "undo", "redo" -> {
                fields("baseRevision")
                catalog.restore(case, text("baseRevision"), action == "undo")
            }
            "edit", "preview" -> {
                val edits = requests.parseEdits(case, body, false)
                catalog.edit(case, edits.first, edits.second, action == "preview")
            }
            else -> throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Package action was not found")
        }
    }

    private object BinaryResponseSent : RuntimeException(null, null, false, false)
}
