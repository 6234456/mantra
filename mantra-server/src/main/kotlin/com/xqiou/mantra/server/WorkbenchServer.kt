package com.xqiou.mantra.server

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.ExplainAddress
import com.xqiou.mantra.workbench.ExportBudget
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.URLDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Loopback-only HTTP adapter. All calculation and document resolution are delegated to WorkspaceCatalog. */
class WorkbenchServer(
    workspace: Path,
    port: Int = 8080,
    private val uiDirectory: Path? = Path.of("workbench-ui/dist").takeIf(Files::isDirectory),
    exportBudget: ExportBudget = ExportBudget(),
) : AutoCloseable {
    private val catalog = WorkspaceCatalog(workspace, exportBudget = exportBudget)
    private val requestJson = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val executor = Executors.newFixedThreadPool(4)
    private val eventSlots = Semaphore(2)
    @Volatile private var running = false
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

    fun start(): WorkbenchServer { running = true; servers.forEach(HttpServer::start); return this }

    override fun close() {
        running = false
        executor.shutdownNow()
        servers.forEach { it.stop(0) }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Content-Security-Policy", "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'")
            val hosts = exchange.requestHeaders["Host"]
            if (hosts?.size != 1 || !validHost(hosts.single(), localPort))
                return error(exchange, 403, "MANTRA-WORKBENCH-HOST", "Host must be a loopback address")
            val path = exchange.requestURI.rawPath ?: return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Missing path")
            val method = exchange.requestMethod.uppercase()
            if (method !in setOf("GET", "HEAD", "POST")) return error(exchange, 405, "MANTRA-WORKBENCH-REQUEST", "Method is not allowed")
            if (method == "POST" && exchange.requestHeaders.getFirst("X-Mantra-Token") != token)
                return error(exchange, 403, "MANTRA-WORKBENCH-TOKEN", "Session token is required")
            val limit = if ("/imports/" in path) 10 * 1024 * 1024 else 1024 * 1024
            if (exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()?.let { it > limit } == true)
                return error(exchange, 413, "MANTRA-WORKBENCH-TOO-LARGE", "Request body exceeds limit")
            val body = readBody(exchange, limit)
                ?: return error(exchange, 413, "MANTRA-WORKBENCH-TOO-LARGE", "Request body exceeds limit")
            if (path.startsWith("/api/")) api(exchange, path, method, body)
            else if (method == "GET" || method == "HEAD") static(exchange, path, method == "HEAD")
            else error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Route was not found")
        } catch (problem: WorkspaceException) {
            val status = when (problem.problem) {
                WorkspaceProblem.REQUEST -> 400
                WorkspaceProblem.NOT_FOUND -> 404
                WorkspaceProblem.INVALID -> 422
                WorkspaceProblem.TOO_LARGE -> 413
            }
            val code = when (problem.problem) {
                WorkspaceProblem.REQUEST -> "MANTRA-WORKBENCH-REQUEST"
                WorkspaceProblem.NOT_FOUND -> "MANTRA-WORKBENCH-NOT-FOUND"
                WorkspaceProblem.INVALID -> "MANTRA-WORKBENCH-DOCUMENT"
                WorkspaceProblem.TOO_LARGE -> "MANTRA-WORKBENCH-TOO-LARGE"
            }
            error(exchange, status, code, problem.message ?: "Workspace error", problem.diagnostics)
        } catch (_: Exception) {
            error(exchange, 500, "MANTRA-WORKBENCH-INTERNAL", "Internal error", correlationId = UUID.randomUUID().toString())
        } finally {
            exchange.close()
        }
    }

    private fun api(exchange: HttpExchange, rawPath: String, method: String, body: ByteArray) {
        if (rawPath == "/api/v1/workspace" && method == "GET") return json(exchange, 200, catalog.envelope(catalog.workspace()))
        if (rawPath == "/api/v1/events" && method == "GET") {
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            return events(exchange)
        }
        val match = Regex("^/api/v1/cases/([^/]+)/(.+)$").matchEntire(rawPath)
            ?: return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Route was not found")
        val caseId = decode(match.groupValues[1])
        val document = match.groupValues[2]
        if (method == "GET" && document in setOf("structure", "run", "paper", "diagnostics", "parameters")) {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it !in setOf("panel", "layout") } || (document != "paper" && query.isNotEmpty()))
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            return json(exchange, 200, catalog.envelope(catalog.document(caseId, document, query["panel"], query["layout"])))
        }
        if (method == "GET" && document == "explain") {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it !in setOf("address", "depth") } || "address" !in query)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Explain address is required")
            val depth = query["depth"]?.toIntOrNull() ?: if ("depth" in query) -1 else 1
            if (depth !in 1..5) return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Explain depth must be between 1 and 5")
            val address = parseExplainAddress(query.getValue("address"))
            return json(exchange, 200, catalog.envelope(catalog.explain(caseId, address, depth)))
        }
        if (method == "GET" && document == "export-preview") {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it !in setOf("sheet", "layout") })
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            return json(exchange, 200, catalog.envelope(catalog.exportPreview(caseId, query["sheet"], query["layout"])))
        }
        if (method == "GET" && document in setOf("export.xlsx", "export.html", "export.txt")) {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it != "layout" })
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
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
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            val variant = parseCompare(body)
            return json(exchange, 200, catalog.envelope(catalog.compare(caseId, variant.first, variant.second)))
        }
        if (document in setOf("explain", "compare", "preview", "edits", "undo", "redo",
                "authoring/complete", "authoring/hover", "authoring/check", "imports/inspect", "imports/apply"))
            return unavailable(exchange)
        error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Route was not found")
    }

    private fun static(exchange: HttpExchange, rawPath: String, head: Boolean) {
        val root = uiDirectory?.toAbsolutePath()?.normalize()?.takeIf(Files::isDirectory)
            ?: return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Live UI build was not found")
        val decoded = decode(rawPath)
        val candidate = root.resolve(decoded.removePrefix("/")).normalize()
        if (!candidate.startsWith(root)) return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Static file was not found")
        val file = if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) candidate else root.resolve("index.html")
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root.toRealPath()))
            return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Static file was not found")
        val suffix = file.fileName.toString().substringAfterLast('.', "")
        val contentType = when (suffix) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
        val bytes = if (suffix == "html") {
            val html = Files.readString(file)
            val meta = "<meta name=\"mantra-session-token\" content=\"$token\">"
            if (!html.contains("</head>")) return error(exchange, 500, "MANTRA-WORKBENCH-INTERNAL", "Live UI index is invalid",
                correlationId = UUID.randomUUID().toString())
            html.replaceFirst("</head>", "$meta</head>").toByteArray(Charsets.UTF_8)
        } else Files.readAllBytes(file)
        send(exchange, 200, contentType, bytes, head)
    }

    private fun validHost(host: String, port: Int): Boolean {
        val authority = host.trim().lowercase()
        return authority == "127.0.0.1:$port" || authority == "[::1]:$port" ||
            (port == 80 && authority in setOf("127.0.0.1", "[::1]"))
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val pairs = raw.split('&').map { part ->
            val split = part.split('=', limit = 2)
            decode(split[0]) to decode(split.getOrElse(1) { "" })
        }
        if (pairs.map { it.first }.distinct().size != pairs.size)
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Duplicate query parameter")
        return pairs.toMap()
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed URL encoding")
    }

    private fun parseExplainAddress(value: String): ExplainAddress {
        fun invalid(): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed Explain address")
        val cellSplit = value.split('#', limit = 2)
        val nodeSplit = cellSplit[0].split('@', limit = 2)
        if (nodeSplit[0].isBlank()) invalid()
        val node = decode(nodeSplit[0]).takeIf(String::isNotBlank) ?: invalid()
        val coord = if (nodeSplit.size == 1) emptyList() else nodeSplit[1].split('/').map {
            if (it.isBlank()) invalid()
            decode(it).takeIf(String::isNotBlank) ?: invalid()
        }
        val cell = if (cellSplit.size == 1) null else {
            val parts = cellSplit[1].split('.', limit = 2)
            if (parts.size != 2 || parts.any(String::isBlank)) invalid()
            val row = decode(parts[0]).takeIf(String::isNotBlank) ?: invalid()
            val column = decode(parts[1]).takeIf(String::isNotBlank) ?: invalid()
            row to column
        }
        return ExplainAddress(node, coord, cell)
    }

    private fun readBody(exchange: HttpExchange, limit: Int): ByteArray? {
        var total = 0
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            val count = exchange.requestBody.read(buffer)
            if (count < 0) return output.toByteArray()
            total += count
            if (total > limit) return null
            output.write(buffer, 0, count)
        }
    }

    private fun parseCompare(body: ByteArray): Pair<String?, List<String>?> {
        fun invalid(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root: JsonNode = try { requestJson.readTree(body) ?: invalid("Comparison body is required") }
            catch (error: WorkspaceException) { throw error }
            catch (_: Exception) { invalid("Malformed comparison JSON") }
        if (!root.isObject || root.size() != 1 || !root.has("variant")) invalid("Expected a variant object")
        val variant = root["variant"]
        if (!variant.isObject || variant.size() !in 1..2 ||
            variant.fieldNames().asSequence().any { it !in setOf("case", "parameters") })
            invalid("Expected case or parameters in variant")
        val case = variant.get("case")?.let { node ->
            if (!node.isTextual || node.textValue().isBlank()) invalid("Variant case must be a nonempty path")
            val value = node.textValue()
            val path = try { Path.of(value) } catch (_: InvalidPathException) { invalid("Variant case path is invalid") }
            if (path.isAbsolute || path.normalize().toString() != value || '\\' in value)
                invalid("Variant case must be a canonical workspace-relative path")
            value
        }
        val parameters = variant.get("parameters")?.let { node ->
            if (!node.isArray || node.size() > 128) invalid("Variant parameters must be an array of at most 128 ids")
            node.map { id ->
                if (!id.isTextual || id.textValue().isBlank()) invalid("Parameter id must be nonempty text")
                id.textValue()
            }
        }
        return case to parameters
    }

    private fun unavailable(exchange: HttpExchange) =
        error(exchange, 501, "MANTRA-WORKBENCH-UNAVAILABLE", "Endpoint is not implemented in the read-only phase")

    private fun events(exchange: HttpExchange) {
        if (!eventSlots.tryAcquire())
            return error(exchange, 503, "MANTRA-WORKBENCH-BUSY", "Too many event streams")
        try {
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("X-Accel-Buffering", "no")
            exchange.sendResponseHeaders(200, 0)
            val output = exchange.responseBody
            fun sendEvent(name: String, data: Map<String, Any?>) {
                output.write("event: $name\ndata: ${WorkbenchJson.write(data)}\n\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
            var previous: WorkspaceCatalog.WorkspaceStamp? = null
            var scanError: String? = null
            while (running && !Thread.currentThread().isInterrupted) {
                val current = try {
                    catalog.workspaceStamp()
                } catch (problem: WorkspaceException) {
                    val code = when (problem.problem) {
                        WorkspaceProblem.TOO_LARGE -> "MANTRA-WORKBENCH-TOO-LARGE"
                        else -> "MANTRA-WORKBENCH-DOCUMENT"
                    }
                    val signature = "$code:${problem.message}"
                    if (scanError != signature) {
                        sendEvent("workspaceError", mapOf("code" to code, "message" to problem.message))
                        scanError = signature
                    }
                    Thread.sleep(5_000)
                    continue
                }
                if (previous == null || scanError != null) {
                    sendEvent("revision", mapOf("revision" to current.revision))
                } else if (current.revision != previous.revision) {
                    val changed = (previous.files.keys + current.files.keys).filter { previous.files[it] != current.files[it] }.sorted()
                    sendEvent("documentChanged", mapOf("revision" to current.revision, "paths" to changed))
                } else {
                    output.write(": keepalive\n\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                previous = current
                scanError = null
                Thread.sleep(1_000)
            }
        } catch (_: IOException) {
            // A closed browser tab is observed when the next event or heartbeat is written.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            eventSlots.release()
        }
    }

    private fun error(exchange: HttpExchange, status: Int, code: String, message: String,
                      diagnostics: List<com.xqiou.mantra.core.Diagnostic> = emptyList(), correlationId: String? = null) {
        val detail = linkedMapOf<String, Any?>("code" to code, "message" to message)
        if (diagnostics.isNotEmpty()) detail["diagnostics"] = diagnostics.map(WorkbenchDocuments::diagnostic)
        if (correlationId != null) detail["correlationId"] = correlationId
        json(exchange, status, WorkbenchJson.write(mapOf("error" to detail)))
    }

    private fun json(exchange: HttpExchange, status: Int, payload: String) =
        send(exchange, status, "application/json; charset=utf-8", payload.toByteArray(Charsets.UTF_8))

    private fun send(exchange: HttpExchange, status: Int, contentType: String, bytes: ByteArray, head: Boolean = false) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, if (head) -1 else bytes.size.toLong())
        if (!head) exchange.responseBody.use { it.write(bytes) }
    }
}
