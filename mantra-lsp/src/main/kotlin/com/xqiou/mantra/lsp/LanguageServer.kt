package com.xqiou.mantra.lsp

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.xqiou.mantra.core.api.language.LanguageCatalog
import com.xqiou.mantra.core.api.language.LanguageCompletion
import com.xqiou.mantra.core.api.language.LanguageSpan
import com.xqiou.mantra.render.layout.LayoutLanguageCatalog
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded worker owns semantic compilation. Input processing can cancel it and supersede text. */
class LanguageServer(
    input: InputStream,
    output: OutputStream,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val analysisFactory: (List<Path>) -> LanguageAnalysisEngine = { WorkspaceAnalyzer(it) },
) : AutoCloseable {
    private val frames = JsonRpcFraming(input, output)
    private val documents = Documents()
    private val tokens = ConcurrentHashMap<String, AtomicBoolean>()
    private val diagnosticsQueued = AtomicBoolean()
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64),
        { work -> Thread(work, "mantra-lsp-analysis").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var analyzer: LanguageAnalysisEngine? = null

    @Volatile private var initialized = false

    @Volatile private var shutdown = false

    @Volatile private var running = true
    private var cache: WorkspaceAnalysis? = null // Accessed only by the semantic worker.
    private var roots: List<Path> = emptyList()

    fun serve(): Int {
        try {
            while (running) {
                val message = try {
                    frames.read()
                } catch (_: JsonProcessingException) {
                    error(NullNode.instance, RpcProblem(-32700, "Invalid JSON-RPC JSON body"))
                    continue
                } ?: break
                receive(message)
            }
        } catch (failure: Exception) {
            log("Language transport stopped: ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            close()
        }
        return if (shutdown) 0 else 1
    }

    private fun receive(message: JsonNode) {
        val id = message.get("id")
        val responseId = id?.takeIf { it.isTextual || it.isIntegralNumber } ?: NullNode.instance
        // An invalid envelope is not a notification. It receives an Invalid Request reply,
        // including null id when no usable correlation ID can be recovered.
        if (!message.isObject || message.get("jsonrpc")?.isTextual != true ||
            message.get("jsonrpc")?.textValue() != "2.0" || message.get("method")?.isTextual != true ||
            (id != null && !id.isTextual && !id.isIntegralNumber)
        ) {
            error(responseId, RpcProblem(-32600, "Invalid JSON-RPC request"))
            return
        }
        try {
            val method = message.text("method")
            val params = message.get("params") ?: frames.mapper.createObjectNode()
            if (id != null && method in setOf("$/cancelRequest", "exit")) {
                throw RpcProblem(-32600, "$method is a notification and must not carry a request ID")
            }
            if (method == "$/cancelRequest") {
                tokens[idKey(params.required("id"))]?.set(true)
                return
            }
            if (method == "exit") {
                running = false
                return
            }
            if (id == null) {
                notification(method, params)
                return
            }
            val key = idKey(id)
            if (tokens.size >= 128) throw RpcProblem(-32001, "Pending request limit reached")
            val cancellation = AtomicBoolean()
            if (tokens.putIfAbsent(key, cancellation) != null) throw RpcProblem(-32600, "Duplicate active request ID")
            try {
                worker.execute {
                    try {
                        fun checkpoint() {
                            if (cancellation.get() ||
                                Thread.currentThread().isInterrupted
                            ) {
                                throw RpcProblem(-32800, "Request cancelled")
                            }
                        }
                        checkpoint()
                        val result = request(method, params, ::checkpoint)
                        checkpoint()
                        reply(id, result)
                    } catch (problem: RpcProblem) {
                        error(id, problem)
                    } catch (oversize: FrameRejected) {
                        error(id, RpcProblem(-32001, oversize.message ?: "Protocol output limit reached"))
                    } catch (refused: RenameRefused) {
                        error(
                            id,
                            RpcProblem(
                                -32803,
                                refused.reason,
                                mapOf("affectedUris" to refused.affectedUris),
                            ),
                        )
                    } catch (problem: IllegalArgumentException) {
                        error(id, RpcProblem(-32602, problem.message ?: "Invalid argument"))
                    } catch (problem: Exception) {
                        log("Language request failed: ${problem.javaClass.simpleName}: ${problem.message}")
                        error(id, RpcProblem(-32603, "Static language analysis failed"))
                    } finally {
                        tokens.remove(key, cancellation)
                    }
                }
            } catch (_: RejectedExecutionException) {
                tokens.remove(key, cancellation)
                throw RpcProblem(-32001, "Analysis queue limit reached")
            }
        } catch (problem: RpcProblem) {
            if (id != null) error(id, problem) else log("Ignored malformed notification: ${problem.message}")
        } catch (problem: IllegalArgumentException) {
            if (id != null) {
                error(id, RpcProblem(-32602, problem.message ?: "Invalid argument"))
            } else {
                log("Ignored malformed notification: ${problem.message}")
            }
        }
    }

    private fun notification(method: String, params: JsonNode) {
        if (method == "initialized") return
        if (!initialized || shutdown) return
        val raw = params.get("textDocument")
        when (method) {
            "textDocument/didOpen" -> {
                val document = requireNotNull(raw)
                documents.open(
                    canonical(document.text("uri")),
                    document.text("text"),
                    document.integer("version"),
                    document.text("uri"),
                )
            }
            "textDocument/didChange" -> {
                val document = requireNotNull(raw)
                val changes = params.required("contentChanges")
                if (!changes.isArray || changes.size() > 256) throw RpcProblem(-32602, "Invalid content changes")
                documents.change(
                    canonical(document.text("uri")),
                    document.integer("version"),
                    changes.map {
                        Change(it.get("range")?.takeUnless { range -> range.isNull }?.range(), it.text("text"))
                    },
                )
            }
            "textDocument/didClose" -> {
                val clientUri = requireNotNull(raw).text("uri")
                val uri = canonical(clientUri)
                documents.close(uri)
                send("textDocument/publishDiagnostics", mapOf("uri" to clientUri, "diagnostics" to emptyList<Any>()))
            }
            "workspace/didChangeWatchedFiles", "textDocument/didSave" -> {
                // Disk changes invalidate cache. The immutable snapshot store also advances epoch
                // so queued responses cannot describe a superseded closed file.
                documents.invalidate()
            }
            else -> return
        }
        scheduleDiagnostics()
    }

    private fun request(method: String, params: JsonNode, checkpoint: () -> Unit): Any? {
        if (method == "initialize") {
            if (initialized) throw RpcProblem(-32600, "Server was already initialized")
            val supported = params.get("capabilities")?.get("general")?.get("positionEncodings")
            if (supported?.isArray == true && supported.none { it.asText() == "utf-16" }) {
                throw RpcProblem(-32602, "This server requires UTF-16 positions")
            }
            val folders = params.get("workspaceFolders")
            val uris = if (folders?.isArray == true) {
                folders.map { it.text("uri") }
            } else {
                listOfNotNull(params.get("rootUri")?.takeIf { it.isTextual }?.asText())
            }
            roots = uris.map { Path.of(fileUri(it)).toRealPath() }
            require(roots.size <= 16) { "Workspace root limit reached" }
            analyzer = analysisFactory(roots)
            initialized = true
            return mapOf(
                "capabilities" to mapOf(
                    "positionEncoding" to "utf-16",
                    "textDocumentSync" to mapOf("openClose" to true, "change" to 2, "save" to true),
                    "completionProvider" to mapOf("triggerCharacters" to listOf("(", ".", ":", "/")),
                    "hoverProvider" to true,
                    "definitionProvider" to true,
                    "referencesProvider" to true,
                    "renameProvider" to mapOf("prepareProvider" to true),
                ),
                "serverInfo" to mapOf("name" to "mantra-lsp", "version" to "preparation"),
            )
        }
        if (!initialized) throw RpcProblem(-32002, "Server is not initialized")
        if (method == "shutdown") {
            shutdown = true
            return null
        }
        if (shutdown) throw RpcProblem(-32600, "Server has shut down")
        val supported = setOf(
            "textDocument/completion",
            "textDocument/hover",
            "textDocument/definition",
            "textDocument/references",
            "textDocument/prepareRename",
            "textDocument/rename",
        )
        if (method !in supported) throw RpcProblem(-32601, "Method not found")
        val analysis = analysis(checkpoint)
        val uri = canonical(params.required("textDocument").text("uri"))
        val snapshot = analysis.documents[uri] ?: throw RpcProblem(-32602, "Document is outside analyzed sources")
        val offset = snapshot.lines.offset(params.required("position").position())
        fun current() {
            checkpoint()
            if (!documents.current(analysis.epoch.revision)) throw RpcProblem(-32801, "Document content changed")
        }
        val symbol = analysis.symbolAt(uri, offset)
        val result: Any? = when (method) {
            "textDocument/completion" -> {
                val alternatives = analysis.analyses.filter { owner ->
                    owner.formulas.any {
                        it.span.source == uri && offset in it.span.start..it.span.end
                    }
                }.map { it.complete(uri, offset).sortedBy { item -> item.label } }.distinct()
                val typed = alternatives.singleOrNull().orEmpty()
                val items = if (typed.isNotEmpty()) typed else hostCompletion(snapshot, offset)
                mapOf(
                    "isIncomplete" to !analysis.complete,
                    "items" to items.map { item ->
                        mapOf(
                            "label" to item.label,
                            "kind" to when (item.kind) {
                                "FUNCTION" -> 3
                                "FIELD" -> 5
                                "ROOT" -> 6
                                "TYPE" -> 7
                                else -> 14
                            },
                            "detail" to item.detail,
                            "documentation" to (item.documentation ?: ""),
                            "textEdit" to mapOf(
                                "range" to rangeMap(
                                    snapshot.lines.range(
                                        item.replacement.start,
                                        item.replacement.end,
                                    ),
                                ),
                                "newText" to item.insertText,
                            ),
                        )
                    },
                )
            }
            "textDocument/hover" -> analysis.analyses.mapNotNull {
                it.hover(uri, offset)
            }.distinct().singleOrNull()?.let {
                mapOf(
                    "range" to rangeMap(snapshot.lines.range(it.span.start, it.span.end)),
                    "contents" to mapOf("kind" to "plaintext", "value" to it.text),
                )
            }
            "textDocument/definition" -> if (symbol == null) {
                analysis.paths.filter {
                    it.span.source == uri && it.span.contains(offset)
                }.map { mapOf("uri" to it.uri, "range" to rangeMap(Range(Position(0, 0), Position(0, 0)))) }
            } else {
                analysis.definitions.filter { it.id == symbol }.map { location(it.span, analysis) }.distinct()
            }
            "textDocument/references" -> symbol?.let { id ->
                analysis.references(
                    id,
                    params.get("context")?.get("includeDeclaration")?.asBoolean() == true,
                ).map { location(it, analysis) }
            }
                ?: emptyList<Any>()
            "textDocument/prepareRename" -> SafeRename(
                requireNotNull(analyzer),
                documents,
            ).prepare(analysis, uri, offset)
                .let { rangeMap(snapshot.lines.range(it.start, it.end)) }
            "textDocument/rename" -> {
                val edits = SafeRename(requireNotNull(analyzer), documents).rename(
                    analysis,
                    uri,
                    offset,
                    params.text("newName"),
                    ::current,
                )
                mapOf(
                    "documentChanges" to edits.map { document ->
                        mapOf(
                            "textDocument" to
                                mapOf(
                                    "uri" to analysis.documents.getValue(document.uri).clientUri,
                                    "version" to document.version,
                                ),
                            "edits" to document.edits.map { edit ->
                                mapOf(
                                    "range" to
                                        rangeMap(
                                            analysis.documents.getValue(
                                                document.uri,
                                            ).lines.range(edit.span.start, edit.span.end),
                                        ),
                                    "newText" to edit.text,
                                )
                            },
                        )
                    },
                )
            }
            else -> null
        }
        current()
        return result
    }

    private fun analysis(checkpoint: () -> Unit): WorkspaceAnalysis {
        val epoch = documents.snapshot()
        cache?.takeIf { it.epoch.revision == epoch.revision }?.let { return it }
        val result = requireNotNull(analyzer).analyze(epoch) {
            checkpoint()
            if (!documents.current(epoch.revision)) throw RpcProblem(-32801, "Document content changed")
        }
        if (!documents.current(epoch.revision)) throw RpcProblem(-32801, "Document content changed")
        cache = result
        return result
    }

    private fun scheduleDiagnostics() {
        if (!diagnosticsQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                diagnosticsQueued.set(false)
                if (shutdown || !running) return@execute
                try {
                    val result = analysis {}
                    result.epoch.overlays.values.forEach { snapshot ->
                        if (shutdown || !running || !documents.current(result.epoch.revision)) return@execute
                        val findings = result.diagnostics.filter { it.location?.source == snapshot.uri }
                        val projection = findings.map { diagnostic(it, snapshot) }
                        val status = if (result.complete) {
                            emptyList()
                        } else {
                            val reasons = result.issues + result.analyses.flatMap { owner ->
                                owner.issues.map { it.reason }
                            }
                            listOf(
                                mapOf(
                                    "range" to rangeMap(Range(Position(0, 0), Position(0, 0))),
                                    "severity" to 2,
                                    "code" to "language/incomplete-index",
                                    "source" to "mantra-lsp",
                                    "message" to "Static reference graph is incomplete: " +
                                        reasons.distinct().take(3).joinToString("; "),
                                    "data" to mapOf("spanUnavailable" to true),
                                ),
                            )
                        }
                        send(
                            "textDocument/publishDiagnostics",
                            mapOf(
                                "uri" to snapshot.clientUri,
                                "version" to snapshot.version,
                                "diagnostics" to (projection + status),
                            ),
                        )
                    }
                } catch (problem: RpcProblem) {
                    if (problem.code != -32801) log("Diagnostics failed: ${problem.message}")
                } catch (problem: Exception) {
                    log("Diagnostics failed: ${problem.javaClass.simpleName}: ${problem.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            diagnosticsQueued.set(false)
            log("Diagnostic queue limit reached")
        }
    }

    private fun hostCompletion(document: DocumentSnapshot, offset: Int): List<LanguageCompletion> {
        var start = offset
        while (start > 0 && (document.text[start - 1].isLetterOrDigit() || document.text[start - 1] == '-')) start--
        // Conservative lexical context only: no typed binding or accepting alternate grammar.
        if (start == 0 || document.text.substring(0, start).trimEnd().lastOrNull() != '(') return emptyList()
        if (!codePosition(document.text, offset)) return emptyList()
        val prefix = document.text.substring(start, offset)
        return (LanguageCatalog.forms + LayoutLanguageCatalog.forms).filter { it.head.startsWith(prefix) }.map {
            LanguageCompletion(
                it.head,
                LanguageSpan(document.uri, start, offset),
                it.head,
                "KEYWORD",
                it.syntax,
                it.summary,
            )
        }
    }

    private fun canonical(uri: String): String = if (uri.startsWith("untitled:")) {
        uri
    } else {
        ConfinedSources(roots, documents.snapshot(), {}).canonical(uri)
    }
    private fun reply(id: JsonNode, result: Any?) = frames.write(
        frames.mapper.createObjectNode()
            .put(
                "jsonrpc",
                "2.0",
            ).set<ObjectNode>("id", id).set<ObjectNode>("result", frames.mapper.valueToTree(result)),
    )
    private fun error(id: JsonNode, problem: RpcProblem) {
        val safeId = if (id.isTextual || id.isIntegralNumber || id.isNull) id else NullNode.instance
        val detail = frames.mapper.createObjectNode().put("code", problem.code).put("message", problem.message)
        if (problem.data != null) detail.set<ObjectNode>("data", frames.mapper.valueToTree(problem.data))
        frames.write(
            frames.mapper.createObjectNode().put("jsonrpc", "2.0").set<ObjectNode>("id", safeId)
                .set<ObjectNode>("error", detail),
        )
    }
    private fun send(method: String, params: Any) = frames.write(
        frames.mapper.createObjectNode()
            .put("jsonrpc", "2.0").put("method", method).set<ObjectNode>("params", frames.mapper.valueToTree(params)),
    )

    override fun close() {
        running = false
        tokens.values.forEach { it.set(true) }
        worker.shutdownNow()
        if (!worker.awaitTermination(
                2,
                TimeUnit.SECONDS,
            )
        ) {
            log("Synchronous compiler has not reached a cancellation checkpoint")
        }
    }
}

/** String/comment tracking serves completion context only; it cannot validate a document. */
private fun codePosition(text: String, offset: Int): Boolean {
    var quoted = false
    var escaped = false
    var comment = false
    text.take(offset).forEach { char ->
        when {
            comment -> if (char == '\n') comment = false
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            !quoted && char == ';' -> comment = true
        }
    }
    return !quoted && !comment
}

fun main() {
    val code = LanguageServer(System.`in`, System.out).serve()
    if (code != 0) kotlin.system.exitProcess(code)
}
