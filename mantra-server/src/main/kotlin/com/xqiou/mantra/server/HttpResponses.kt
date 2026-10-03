package com.xqiou.mantra.server

import com.sun.net.httpserver.HttpExchange
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson

internal fun error(
    exchange: HttpExchange,
    status: Int,
    code: String,
    message: String,
    diagnostics: List<com.xqiou.mantra.core.Diagnostic> = emptyList(),
    correlationId: String? = null,
    currentRevision: String? = null,
) {
    val detail = linkedMapOf<String, Any?>("code" to code, "message" to message)
    if (diagnostics.isNotEmpty()) detail["diagnostics"] = diagnostics.map(WorkbenchDocuments::diagnostic)
    if (correlationId != null) detail["correlationId"] = correlationId
    if (currentRevision != null) detail["currentRevision"] = currentRevision
    json(exchange, status, WorkbenchJson.write(mapOf("error" to detail)))
}

internal fun json(exchange: HttpExchange, status: Int, payload: String) =
    send(exchange, status, "application/json; charset=utf-8", payload.toByteArray(Charsets.UTF_8))

internal fun send(exchange: HttpExchange, status: Int, contentType: String, bytes: ByteArray, head: Boolean = false) {
    exchange.responseHeaders.set("Content-Type", contentType)
    exchange.sendResponseHeaders(status, if (head) -1 else bytes.size.toLong())
    if (!head) exchange.responseBody.use { it.write(bytes) }
}
