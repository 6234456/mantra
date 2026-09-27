package com.xqiou.mantra.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/** Loopback-only HTTP adapter. All calculation and document resolution are delegated to WorkspaceCatalog. */
class WorkbenchServer(
    workspace: Path,
    port: Int = 8080,
    private val uiDirectory: Path? = Path.of("workbench-ui/dist").takeIf(Files::isDirectory),
) : AutoCloseable {
    private val catalog = WorkspaceCatalog(workspace)
    private val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val executor = Executors.newFixedThreadPool(4)
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

    fun start(): WorkbenchServer { servers.forEach(HttpServer::start); return this }

    override fun close() {
        servers.forEach { it.stop(0) }
        executor.shutdownNow()
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
            if (exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()?.let { it > limit } == true || !withinBodyLimit(exchange, limit))
                return error(exchange, 413, "MANTRA-WORKBENCH-TOO-LARGE", "Request body exceeds limit")
            if (path.startsWith("/api/")) api(exchange, path, method)
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

    private fun api(exchange: HttpExchange, rawPath: String, method: String) {
        if (rawPath == "/api/v1/workspace" && method == "GET") return json(exchange, 200, catalog.envelope(catalog.workspace()))
        if (rawPath == "/api/v1/events" && method == "GET") return unavailable(exchange)
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
        if (document in setOf("explain", "compare", "preview", "edits", "undo", "redo", "export.xlsx", "export.html", "export.txt",
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

    private fun withinBodyLimit(exchange: HttpExchange, limit: Int): Boolean {
        var total = 0
        val buffer = ByteArray(8192)
        while (true) {
            val count = exchange.requestBody.read(buffer)
            if (count < 0) return true
            total += count
            if (total > limit) return false
        }
    }

    private fun unavailable(exchange: HttpExchange) =
        error(exchange, 501, "MANTRA-WORKBENCH-UNAVAILABLE", "Endpoint is not implemented in the read-only phase")

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
