package com.xqiou.mantra.server

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.CaseTextEditor
import com.xqiou.mantra.workbench.AuthoringTarget
import com.xqiou.mantra.workbench.ExplainAddress
import com.xqiou.mantra.workbench.ExportBudget
import com.xqiou.mantra.workbench.ImportFiles
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
import java.util.Base64
import java.util.concurrent.Executors
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.Semaphore

/** Loopback-only HTTP adapter. All calculation and document resolution are delegated to WorkspaceCatalog. */
class WorkbenchServer(
    workspace: Path,
    port: Int = 8080,
    private val uiDirectory: Path? = Path.of("workbench-ui/dist").takeIf(Files::isDirectory),
    exportBudget: ExportBudget = ExportBudget(),
) : AutoCloseable {
    private data class EditAddress(val node: String, val coord: List<String>, val cell: Pair<String, String>?)
    private val catalog = WorkspaceCatalog(workspace, exportBudget = exportBudget)
    private val requestJson = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val executor = Executors.newFixedThreadPool(4)
    private val eventSlots = Semaphore(2)
    private val stampLock = Any()
    private var sharedStamp: WorkspaceCatalog.WorkspaceStamp? = null
    private var stampError: WorkspaceException? = null
    private var stampCheckedAt = 0L
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
            val limit = if ("/imports/" in path) 14 * 1024 * 1024 else 1024 * 1024
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
                WorkspaceProblem.CONFLICT -> 409
            }
            val code = when (problem.problem) {
                WorkspaceProblem.REQUEST -> "MANTRA-WORKBENCH-REQUEST"
                WorkspaceProblem.NOT_FOUND -> "MANTRA-WORKBENCH-NOT-FOUND"
                WorkspaceProblem.INVALID -> "MANTRA-WORKBENCH-DOCUMENT"
                WorkspaceProblem.TOO_LARGE -> "MANTRA-WORKBENCH-TOO-LARGE"
                WorkspaceProblem.CONFLICT -> "MANTRA-WORKBENCH-CONFLICT"
            }
            error(exchange, status, code, problem.message ?: "Workspace error", problem.diagnostics,
                currentRevision = problem.currentRevision)
        } catch (_: Exception) {
            error(exchange, 500, "MANTRA-WORKBENCH-INTERNAL", "Internal error", correlationId = UUID.randomUUID().toString())
        } finally {
            exchange.close()
        }
    }

    private fun api(exchange: HttpExchange, rawPath: String, method: String, body: ByteArray) {
        if (rawPath == "/api/v1/workspace" && method == "GET") return json(exchange, 200, catalog.envelope(catalog.workspace()))
        if (rawPath == "/api/v1/import-templates") {
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            if (method == "GET") return json(exchange, 200, catalog.envelope(catalog.importTemplates()))
            if (method == "POST") {
                val template = parseTemplate(body)
                return json(exchange, 200, catalog.envelope(catalog.saveImportTemplate(template.first, template.second, template.third)))
            }
        }
        if (rawPath == "/api/v1/events" && method == "GET") {
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            return events(exchange)
        }
        val match = Regex("^/api/v1/cases/([^/]+)/(.+)$").matchEntire(rawPath)
            ?: return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Route was not found")
        val caseId = decode(match.groupValues[1])
        val document = match.groupValues[2]
        if (method == "POST" && document == "sources/remove") {
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            val root = try { requestJson.readTree(body) } catch (_: Exception) { null }
            if (root == null || !root.isObject || root.fieldNames().asSequence().toSet() != setOf("baseRevision", "index") ||
                !root["baseRevision"].isTextual || !Regex("[0-9a-f]{16}").matches(root["baseRevision"].textValue()) ||
                !root["index"].isInt)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Invalid source removal request")
            return json(exchange, 200, catalog.envelope(catalog.removeSource(caseId,
                root["baseRevision"].textValue(), root["index"].intValue())))
        }
        if (method == "GET" && document in setOf("structure", "run", "paper", "diagnostics", "parameters", "sources")) {
            val query = query(exchange.requestURI.rawQuery)
            if (query.keys.any { it !in setOf("panel", "layout") } || (document != "paper" && query.isNotEmpty()))
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            val result = if (document == "sources") catalog.sources(caseId)
                else catalog.document(caseId, document, query["panel"], query["layout"])
            return json(exchange, 200, catalog.envelope(result))
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
        if (method == "POST" && document in setOf("imports/inspect", "imports/apply")) {
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            val request = parseImport(body, document == "imports/apply")
            val result = if (document == "imports/inspect")
                WorkspaceCatalog.DocumentResult(catalog.document(caseId, "structure").revision,
                    ImportFiles.inspect(request.name, request.format, request.content))
            else catalog.importApply(caseId, request.revision!!, request.name, request.format,
                request.content, request.options)
            return json(exchange, 200, catalog.envelope(result))
        }
        if (method == "POST" && document in setOf("preview", "edits", "undo", "redo")) {
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            val (revision, operations) = parseEdits(caseId, body, document in setOf("undo", "redo"))
            val result = when (document) {
                "preview" -> catalog.previewEdits(caseId, revision, operations)
                "edits" -> catalog.commitEdits(caseId, revision, operations)
                "undo" -> catalog.undo(caseId, revision)
                else -> catalog.redo(caseId, revision)
            }
            return json(exchange, 200, catalog.envelope(result))
        }
        if (method == "POST" && document in setOf("authoring/complete", "authoring/hover", "authoring/check")) {
            if (exchange.requestURI.rawQuery != null)
                return error(exchange, 400, "MANTRA-WORKBENCH-REQUEST", "Unexpected query parameter")
            val (target, source, cursor) = parseAuthoring(body, document.substringAfter('/'))
            return json(exchange, 200, catalog.envelope(catalog.authoring(caseId, target, source, cursor,
                document.substringAfter('/'))))
        }
        if (document in setOf("explain", "compare", "preview", "edits", "undo", "redo",
                "authoring/complete", "authoring/hover", "authoring/check"))
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
            val styleNonce = ByteArray(18).also(SecureRandom()::nextBytes)
                .joinToString("") { "%02x".format(it) }
            exchange.responseHeaders.set("Content-Security-Policy", "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self' 'nonce-$styleNonce'; img-src 'self' data:; object-src 'none'; base-uri 'none'")
            val meta = "<meta name=\"mantra-session-token\" content=\"$token\"><meta name=\"mantra-style-nonce\" content=\"$styleNonce\">"
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

    private data class ImportRequest(val name: String, val format: String, val content: ByteArray,
                                     val revision: String?, val options: Map<String, Value>)

    private fun sourceOptions(encoded: JsonNode?): Map<String, Value> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val options = encoded?.takeIf(JsonNode::isObject) ?: bad("options must be an object")
        if (options.size() > 64) bad("Too many source options")
        return options.fields().asSequence().associate { (key, value) ->
            if (!Regex("[A-Za-z][A-Za-z0-9_-]*").matches(key)) bad("Invalid source option")
            key to when {
                value.isTextual -> Value.Text(value.textValue())
                value.isObject && value.size() <= 256 -> Value.MapV(LinkedHashMap<Value, Value>().apply {
                    value.fields().forEach { (from, to) ->
                        if (!to.isTextual) bad("Source mapping values must be text")
                        put(Value.Text(from), Value.Text(to.textValue()))
                    }
                })
                else -> bad("Source options must be text or text mappings")
            }
        }
    }

    private fun parseTemplate(body: ByteArray): Triple<String, String, Map<String, Value>> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try { requestJson.readTree(body) ?: bad("Template body is required") }
            catch (error: WorkspaceException) { throw error }
            catch (_: Exception) { bad("Malformed template JSON") }
        if (!root.isObject || root.fieldNames().asSequence().toSet() != setOf("name", "format", "options"))
            bad("Unexpected template fields")
        val name = root["name"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("Template name must be text")
        val format = root["format"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("Template format must be text")
        return Triple(name, format, sourceOptions(root["options"]))
    }

    private fun parseImport(body: ByteArray, apply: Boolean): ImportRequest {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try { requestJson.readTree(body) ?: bad("Import body is required") }
            catch (error: WorkspaceException) { throw error }
            catch (_: Exception) { bad("Malformed import JSON") }
        val fields = if (apply) setOf("name", "format", "contentBase64", "baseRevision", "options")
            else setOf("name", "format", "contentBase64")
        if (!root.isObject || root.fieldNames().asSequence().toSet() != fields) bad("Unexpected import request fields")
        fun string(key: String): String = root[key]?.takeIf(JsonNode::isTextual)?.textValue()
            ?: bad("$key must be text")
        val name = string("name")
        val format = string("format")
        if (format !in setOf("csv", "json", "xlsx")) bad("Unsupported import format")
        val content = try { Base64.getDecoder().decode(string("contentBase64")) }
            catch (_: IllegalArgumentException) { bad("Invalid base64 content") }
        if (content.size > ImportFiles.MAX_BYTES) throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Import file exceeds 10 MiB")
        val revision = if (apply) string("baseRevision").takeIf { Regex("[0-9a-f]{16}").matches(it) }
            ?: bad("baseRevision must be 16 hexadecimal characters") else null
        val options = if (apply) sourceOptions(root["options"]) else emptyMap()
        return ImportRequest(name, format, content, revision, options)
    }

    private fun parseEdits(caseId: String, body: ByteArray, history: Boolean): Pair<String, List<CaseTextEditor.Operation>> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try { requestJson.readTree(body) ?: bad("Edit body is required") }
            catch (error: WorkspaceException) { throw error }
            catch (_: Exception) { bad("Malformed edit JSON") }
        val expected = if (history) setOf("baseRevision") else setOf("baseRevision", "operations")
        if (!root.isObject || root.fieldNames().asSequence().toSet() != expected) bad("Unexpected edit request fields")
        val revision = root["baseRevision"]?.takeIf(JsonNode::isTextual)?.textValue()
            ?.takeIf { Regex("[0-9a-f]{16}").matches(it) } ?: bad("baseRevision must be 16 hexadecimal characters")
        if (history) return revision to emptyList()
        val values = root["operations"]?.takeIf(JsonNode::isArray) ?: bad("operations must be an array")
        if (values.size() !in 1..100) bad("Expected 1–100 operations")
        fun requireFields(node: JsonNode, vararg fields: String) {
            if (!node.isObject || node.fieldNames().asSequence().toSet() != fields.toSet()) bad("Unexpected operation fields")
        }
        fun string(node: JsonNode, name: String): String = node[name]?.takeIf(JsonNode::isTextual)?.textValue()
            ?: bad("$name must be text")
        fun integer(node: JsonNode, name: String): Int = node[name]?.takeIf(JsonNode::isInt)?.intValue()
            ?: bad("$name must be an integer")
        fun address(node: JsonNode): EditAddress {
            val address = node["address"] ?: bad("address is required")
            if (!address.isObject || address.fieldNames().asSequence().any { it !in setOf("node", "coord", "cell") } ||
                (address.has("coord") && address.has("cell"))) bad("Invalid address")
            val id = string(address, "node")
            val coord = address["coord"]?.let { array ->
                if (!array.isArray || array.size() > 8 || array.any { !it.isTextual }) bad("Invalid coordinate")
                array.map(JsonNode::textValue)
            }.orEmpty()
            val cell = address["cell"]?.let { entry ->
                if (!entry.isObject || entry.fieldNames().asSequence().toSet() != setOf("row", "column")) bad("Invalid cell address")
                string(entry, "row") to string(entry, "column")
            }
            return EditAddress(id, coord, cell)
        }
        fun valueOrText(node: JsonNode, id: String, parameter: Boolean, column: String? = null): Value {
            if (node.has("value") == node.has("text")) bad("Provide exactly one of value and text")
            return if (node.has("text")) catalog.parseEditText(caseId, id, parameter, string(node, "text"), column)
            else parseValue(node["value"])
        }
        val operations = values.map { op ->
            val kind = string(op, "op")
            when (kind) {
                "setInput" -> {
                    val target = address(op)
                    if (op.fieldNames().asSequence().toSet() != setOf("op", "address", if (op.has("text")) "text" else "value")) bad("Unexpected setInput fields")
                    val value = valueOrText(op, target.node, false, target.cell?.second)
                    if (target.cell == null) CaseTextEditor.Operation.SetInput(target.node, value, target.coord)
                    else CaseTextEditor.Operation.SetCell(target.node, target.cell.first, target.cell.second, value,
                        catalog.tableKeyColumn(caseId, target.node))
                }
                "clearInput" -> {
                    requireFields(op, "op", "address")
                    val target = address(op)
                    if (target.cell == null) CaseTextEditor.Operation.ClearInput(target.node, target.coord)
                    else CaseTextEditor.Operation.ClearCell(target.node, target.cell.first, target.cell.second,
                        catalog.tableKeyColumn(caseId, target.node))
                }
                "setParam" -> {
                    val id = string(op, "id")
                    if (op.fieldNames().asSequence().toSet() != setOf("op", "id", if (op.has("text")) "text" else "value")) bad("Unexpected setParam fields")
                    CaseTextEditor.Operation.SetParam(id, valueOrText(op, id, true))
                }
                "resetParam" -> { requireFields(op, "op", "id"); CaseTextEditor.Operation.ResetParam(string(op, "id")) }
                "insertRow" -> {
                    if (op.fieldNames().asSequence().any { it !in setOf("op", "table", "row", "rowText", "index") } || op.has("row") == op.has("rowText")) bad("Provide exactly one of row and rowText")
                    val table = string(op, "table")
                    val row = if (op.has("row")) parseValue(op["row"]) as? Value.MapV ?: bad("row must be a map value")
                    else {
                        val source = op["rowText"]?.takeIf(JsonNode::isObject) ?: bad("rowText must be an object")
                        if (source.size() !in 1..64) bad("rowText must contain 1–64 columns")
                        val texts = source.fields().asSequence().associate { (column, value) ->
                            if (!value.isTextual || value.textValue().length > 10_000) bad("rowText values must be text")
                            column to value.textValue()
                        }
                        catalog.parseEditRowText(caseId, table, texts)
                    }
                    CaseTextEditor.Operation.InsertRow(table, row, op["index"]?.let { integer(op, "index") })
                }
                "updateRow" -> { requireFields(op, "op", "table", "index", "row"); CaseTextEditor.Operation.UpdateRow(string(op, "table"), integer(op, "index"), parseValue(op["row"]) as? Value.MapV ?: bad("row must be a map value")) }
                "deleteRow" -> { requireFields(op, "op", "table", "index"); CaseTextEditor.Operation.DeleteRow(string(op, "table"), integer(op, "index")) }
                "moveRow" -> { requireFields(op, "op", "table", "from", "to"); CaseTextEditor.Operation.MoveRow(string(op, "table"), integer(op, "from"), integer(op, "to")) }
                "addExtension" -> { requireFields(op, "op", "slot", "id", "title", "formula"); CaseTextEditor.Operation.AddExtension(string(op, "slot"), string(op, "id"), string(op, "title"), string(op, "formula")) }
                "updateExtension" -> { requireFields(op, "op", "slot", "id", "title", "formula"); CaseTextEditor.Operation.UpdateExtension(string(op, "slot"), string(op, "id"), string(op, "title"), string(op, "formula")) }
                "removeExtension" -> { requireFields(op, "op", "slot", "id"); CaseTextEditor.Operation.RemoveExtension(string(op, "slot"), string(op, "id")) }
                "bindFormula" -> { requireFields(op, "op", "id", "formula"); CaseTextEditor.Operation.BindFormula(string(op, "id"), string(op, "formula")) }
                "unbindFormula" -> { requireFields(op, "op", "id"); CaseTextEditor.Operation.UnbindFormula(string(op, "id")) }
                "setMeta" -> { requireFields(op, "op", "key", "text"); CaseTextEditor.Operation.SetMeta(string(op, "key"), string(op, "text")) }
                "setBindings" -> {
                    if (op.fieldNames().asSequence().any { it !in setOf("op", "parameters", "layout") } || (!op.has("parameters") && !op.has("layout"))) bad("Invalid bindings")
                    val parameters = op["parameters"]?.let { array ->
                        if (!array.isArray || array.size() > 128 || array.any { !it.isTextual }) bad("Invalid parameters")
                        array.map(JsonNode::textValue)
                    }
                    val layoutNode = op["layout"]
                    val layout = layoutNode?.let { if (!it.isTextual && !it.isNull) bad("Invalid layout"); if (it.isNull) null else it.textValue() }
                    CaseTextEditor.Operation.SetBindings(parameters, layout, clearLayout = layoutNode?.isNull == true)
                }
                "addSource" -> {
                    requireFields(op, "op", "kind", "options")
                    val encoded = parseValue(op["options"]) as? Value.MapV ?: bad("options must be an encoded map")
                    val options = encoded.entries.map { (key, value) ->
                        (key as? Value.Kw)?.name?.let { it to value } ?: bad("option keys must be keywords")
                    }.toMap()
                    CaseTextEditor.Operation.AddSource(string(op, "kind"), options)
                }
                "removeSource" -> { requireFields(op, "op", "index"); CaseTextEditor.Operation.RemoveSource(integer(op, "index")) }
                else -> bad("Unknown edit operation")
            }
        }
        return revision to operations
    }

    private fun parseAuthoring(body: ByteArray, action: String): Triple<AuthoringTarget, String, Int?> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try { requestJson.readTree(body) ?: bad("Authoring body is required") }
            catch (error: WorkspaceException) { throw error }
            catch (_: Exception) { bad("Malformed authoring JSON") }
        val expected = if (action == "check") setOf("target", "source") else setOf("target", "source", "cursorOffset")
        if (!root.isObject || root.fieldNames().asSequence().toSet() != expected) bad("Unexpected authoring request fields")
        val source = root["source"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("source must be text")
        val cursor = if (action == "check") null else root["cursorOffset"]?.takeIf(JsonNode::isInt)?.intValue()
            ?.takeIf { it in 0..source.length } ?: bad("cursorOffset is outside source")
        val node = root["target"]?.takeIf(JsonNode::isObject) ?: bad("target must be an object")
        val kind = node["kind"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("target kind is required")
        fun string(name: String): String = node[name]?.takeIf(JsonNode::isTextual)?.textValue()
            ?.takeIf(String::isNotBlank) ?: bad("$name must be text")
        val target = when (kind) {
            "extension" -> {
                if (node.fieldNames().asSequence().toSet() != setOf("kind", "slot", "id", "title")) bad("Unexpected extension target fields")
                AuthoringTarget.Extension(string("slot"), string("id"), string("title"))
            }
            "formulaSlot" -> {
                if (node.fieldNames().asSequence().toSet() != setOf("kind", "id")) bad("Unexpected formula slot target fields")
                AuthoringTarget.FormulaSlot(string("id"))
            }
            else -> bad("Unknown authoring target")
        }
        return Triple(target, source, cursor)
    }

    private fun parseValue(node: JsonNode, depth: Int = 0): Value {
        fun bad(): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid encoded value")
        if (depth > 16) bad()
        if (node.isNull) return Value.Nil
        if (node.isBoolean) return Value.Bool(node.booleanValue())
        if (node.isTextual) return Value.Text(node.textValue())
        if (node.isArray) return Value.Vec(node.map { parseValue(it, depth + 1) })
        if (!node.isObject || node.size() != 1) bad()
        val field = node.fieldNames().next()
        val item = node[field]
        return when (field) {
            "n" -> {
                if (!item.isTextual || !Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?").matches(item.textValue())) bad()
                Value.Num(BigDecimal(item.textValue()))
            }
            "kw" -> { if (!item.isTextual || !Regex("[A-Za-z][A-Za-z0-9_-]*[?!*]?").matches(item.textValue())) bad(); Value.Kw(item.textValue()) }
            "date" -> { if (!item.isTextual) bad(); try { Value.Date(LocalDate.parse(item.textValue())) } catch (_: Exception) { bad() } }
            "map" -> {
                if (!item.isArray) bad()
                val entries = linkedMapOf<Value, Value>()
                item.forEach { entry ->
                    if (!entry.isArray || entry.size() != 2) bad()
                    val key = parseValue(entry[0], depth + 1)
                    if (entries.put(key, parseValue(entry[1], depth + 1)) != null) bad()
                }
                Value.MapV(entries)
            }
            else -> bad()
        }
    }

    private fun unavailable(exchange: HttpExchange) =
        error(exchange, 501, "MANTRA-WORKBENCH-UNAVAILABLE", "Endpoint is not implemented in the read-only phase")

    private fun eventStamp(): WorkspaceCatalog.WorkspaceStamp = synchronized(stampLock) {
        val now = System.nanoTime()
        val delay = if (stampError == null) 1_000_000_000L else 5_000_000_000L
        if (stampCheckedAt != 0L && now - stampCheckedAt < delay) {
            stampError?.let { throw it }
            sharedStamp?.let { return@synchronized it }
        }
        stampCheckedAt = now
        try {
            catalog.workspaceStamp(sharedStamp).also { sharedStamp = it; stampError = null }
        } catch (problem: WorkspaceException) {
            stampError = problem
            throw problem
        }
    }

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
                    eventStamp()
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
                      diagnostics: List<com.xqiou.mantra.core.Diagnostic> = emptyList(), correlationId: String? = null,
                      currentRevision: String? = null) {
        val detail = linkedMapOf<String, Any?>("code" to code, "message" to message)
        if (diagnostics.isNotEmpty()) detail["diagnostics"] = diagnostics.map(WorkbenchDocuments::diagnostic)
        if (correlationId != null) detail["correlationId"] = correlationId
        if (currentRevision != null) detail["currentRevision"] = currentRevision
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
