package com.xqiou.mantra.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xqiou.mantra.workbench.ExportBudget
import com.xqiou.mantra.workbench.ImportFiles
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/** Loopback-only HTTP adapter. All calculation and document resolution are delegated to WorkspaceCatalog. */
class WorkbenchServer(
    workspace: Path,
    port: Int = 8080,
    private val uiDirectory: Path? = Path.of("workbench-ui/dist").takeIf(Files::isDirectory),
    exportBudget: ExportBudget = ExportBudget(),
) : AutoCloseable {
    private val catalog = WorkspaceCatalog(workspace, exportBudget = exportBudget)
    private val requests = WorkbenchRequests(catalog)
    private val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val staticFiles = WorkbenchStaticFiles(uiDirectory, token)
    private val executor = Executors.newFixedThreadPool(4)

    @Volatile private var running = false
    private val events = WorkspaceEvents(catalog) { running }
    private val servers: List<HttpServer> = run {
        val ipv4 = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
        val ipv6 = runCatching {
            HttpServer.create(InetSocketAddress(InetAddress.getByName("::1"), ipv4.address.port), 16)
        }.getOrNull()
        listOfNotNull(ipv4, ipv6).onEach { server ->
            server.createContext("/") { exchange -> handle(exchange) }
            server.executor = executor
        }
    }
    val localPort: Int get() = servers.first().address.port

    fun start(): WorkbenchServer {
        running = true
        servers.forEach(HttpServer::start)
        return this
    }

    override fun close() {
        running = false
        executor.shutdownNow()
        servers.forEach { it.stop(0) }
        catalog.close()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set(
                "Content-Security-Policy",
                "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'",
            )
            val hosts = exchange.requestHeaders["Host"]
            if (hosts?.size != 1 || !validHost(hosts.single(), localPort)) {
                return error(exchange, 403, "MANTRA-WORKBENCH-HOST", "Host must be a loopback address")
            }
            val path =
                exchange.requestURI.rawPath ?: return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Missing path")
            val method = exchange.requestMethod.uppercase()
            if (method !in
                setOf("GET", "HEAD", "POST")
            ) {
                return error(exchange, 405, "MANTRA-WORKBENCH-REQUEST", "Method is not allowed")
            }
            if (method == "POST" && exchange.requestHeaders.getFirst("X-Mantra-Token") != token) {
                return error(exchange, 403, "MANTRA-WORKBENCH-TOKEN", "Session token is required")
            }
            val limit = if ("/imports/" in path) 14 * 1024 * 1024 else 1024 * 1024
            if (exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()?.let { it > limit } == true) {
                return error(exchange, 413, "MANTRA-WORKBENCH-TOO-LARGE", "Request body exceeds limit")
            }
            val body = readBody(exchange, limit)
                ?: return error(exchange, 413, "MANTRA-WORKBENCH-TOO-LARGE", "Request body exceeds limit")
            if (path.startsWith("/api/")) {
                api(exchange, path, method, body)
            } else if (method == "GET" || method == "HEAD") {
                staticFiles.serve(exchange, path, method == "HEAD")
            } else {
                error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Route was not found")
            }
        } catch (problem: WorkspaceException) {
            val status = when (problem.problem) {
                WorkspaceProblem.REQUEST -> 400
                WorkspaceProblem.NOT_FOUND -> 404
                WorkspaceProblem.INVALID -> 422
                WorkspaceProblem.TOO_LARGE -> 413
                WorkspaceProblem.CONFLICT -> 409
            }
            val code = when (problem.problem) {
                WorkspaceProblem.REQUEST -> "MANTRA-WORKBENCH-REQUEST"
                WorkspaceProblem.NOT_FOUND -> "MANTRA-WORKBENCH-NOT-FOUND"
                WorkspaceProblem.INVALID -> "MANTRA-WORKBENCH-DOCUMENT"
                WorkspaceProblem.TOO_LARGE -> "MANTRA-WORKBENCH-TOO-LARGE"
                WorkspaceProblem.CONFLICT -> "MANTRA-WORKBENCH-CONFLICT"
            }
            error(
                exchange,
                status,
                code,
                problem.message ?: "Workspace error",
                problem.diagnostics,
                currentRevision = problem.currentRevision,
            )
        } catch (_: Exception) {
            error(
                exchange,
                500,
                "MANTRA-WORKBENCH-INTERNAL",
                "Internal error",
                correlationId = UUID.randomUUID().toString(),
            )
        } finally {
            exchange.close()
        }
    }

    private fun api(exchange: HttpExchange, rawPath: String, method: String, body: ByteArray) {
        if (rawPath == "/api/v1/workspace" &&
            method == "GET"
        ) {
            return json(exchange, 200, catalog.envelope(catalog.workspace()))
        }
        if (rawPath == "/api/v1/import-templates") {
            if (exchange.requestURI.rawQuery != null) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            if (method == "GET") return json(exchange, 200, catalog.envelope(catalog.importTemplates()))
            if (method == "POST") {
                val template = requests.parseTemplate(body)
                return json(
                    exchange,
                    200,
                    catalog.envelope(catalog.saveImportTemplate(template.first, template.second, template.third)),
                )
            }
        }
        if (rawPath == "/api/v1/events" && method == "GET") {
            if (exchange.requestURI.rawQuery != null) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            return events.stream(exchange)
        }
        val match = Regex("^/api/v1/cases/([^/]+)/(.+)$").matchEntire(rawPath)
            ?: return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Route was not found")
        val caseId = decode(match.groupValues[1])
        val document = match.groupValues[2]
        if (method == "POST" && document == "sources/remove") {
            if (exchange.requestURI.rawQuery != null) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            val root = try {
                requests.json.readTree(body)
            } catch (_: Exception) {
                null
            }
            if (root == null || !root.isObject ||
                root.fieldNames().asSequence().toSet() != setOf("baseRevision", "index") ||
                !root["baseRevision"].isTextual || !Regex("[0-9a-f]{16}").matches(root["baseRevision"].textValue()) ||
                !root["index"].isInt
            ) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Invalid source removal request")
            }
            return json(
                exchange,
                200,
                catalog.envelope(
                    catalog.removeSource(
                        caseId,
                        root["baseRevision"].textValue(),
                        root["index"].intValue(),
                    ),
                ),
            )
        }
        if (method == "GET" && document in setOf("structure", "run", "paper", "diagnostics", "parameters", "sources")) {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it !in setOf("panel", "layout") } || (document != "paper" && query.isNotEmpty())) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            val result = if (document == "sources") {
                catalog.sources(caseId)
            } else {
                catalog.document(caseId, document, query["panel"], query["layout"])
            }
            return json(exchange, 200, catalog.envelope(result))
        }
        if (method == "GET" && document == "explain") {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it !in setOf("address", "depth") } || "address" !in query) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Explain address is required")
            }
            val depth = query["depth"]?.toIntOrNull() ?: if ("depth" in query) -1 else 1
            if (depth !in
                1..5
            ) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Explain depth must be between 1 and 5")
            }
            val address = parseExplainAddress(query.getValue("address"))
            return json(exchange, 200, catalog.envelope(catalog.explain(caseId, address, depth)))
        }
        if (method == "GET" && document == "export-preview") {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it !in setOf("sheet", "layout") }) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            return json(exchange, 200, catalog.envelope(catalog.exportPreview(caseId, query["sheet"], query["layout"])))
        }
        if (method == "GET" && document in setOf("export.xlsx", "export.html", "export.txt")) {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it != "layout" }) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            val format = document.substringAfter('.')
            val contentType = when (format) {
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "html" -> "text/html; charset=utf-8"
                else -> "text/plain; charset=utf-8"
            }
            val payload = catalog.export(caseId, format, query["layout"])
            exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"mantra-export.$format\"")
            return send(exchange, 200, contentType, payload)
        }
        if (method == "POST" && document == "compare") {
            if (exchange.requestURI.rawQuery != null) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            val variant = requests.parseCompare(body)
            return json(exchange, 200, catalog.envelope(catalog.compare(caseId, variant.first, variant.second)))
        }
        if (method == "POST" && document in setOf("imports/inspect", "imports/apply")) {
            if (exchange.requestURI.rawQuery != null) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            val request = requests.parseImport(body, document == "imports/apply")
            val result = if (document == "imports/inspect") {
                WorkspaceCatalog.DocumentResult(
                    catalog.document(caseId, "structure").revision,
                    ImportFiles.inspect(request.name, request.format, request.content),
                )
            } else {
                catalog.importApply(
                    caseId,
                    request.revision!!,
                    request.name,
                    request.format,
                    request.content,
                    request.options,
                )
            }
            return json(exchange, 200, catalog.envelope(result))
        }
        if (method == "POST" && document in setOf("preview", "edits", "undo", "redo")) {
            if (exchange.requestURI.rawQuery != null) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            val (revision, operations) = requests.parseEdits(caseId, body, document in setOf("undo", "redo"))
            val result = when (document) {
                "preview" -> catalog.previewEdits(caseId, revision, operations)
                "edits" -> catalog.commitEdits(caseId, revision, operations)
                "undo" -> catalog.undo(caseId, revision)
                else -> catalog.redo(caseId, revision)
            }
            return json(exchange, 200, catalog.envelope(result))
        }
        if (method == "POST" && document in setOf("authoring/complete", "authoring/hover", "authoring/check")) {
            if (exchange.requestURI.rawQuery != null) {
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            }
            val (target, source, cursor) = requests.parseAuthoring(body, document.substringAfter('/'))
            return json(
                exchange,
                200,
                catalog.envelope(
                    catalog.authoring(
                        caseId,
                        target,
                        source,
                        cursor,
                        document.substringAfter('/'),
                    ),
                ),
            )
        }
        if (document in setOf(
                "explain", "compare", "preview", "edits", "undo", "redo",
                "authoring/complete", "authoring/hover", "authoring/check",
            )
        ) {
            return unavailable(exchange)
        }
        error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Route was not found")
    }

    private fun unavailable(exchange: HttpExchange) =
        error(exchange, 501, "MANTRA-WORKBENCH-UNAVAILABLE", "Endpoint is not implemented in the read-only phase")
}
