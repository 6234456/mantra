package com.xqiou.mantra.server

import com.sun.net.httpserver.HttpExchange
import com.xqiou.mantra.workbench.ExplainAddress
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import java.io.ByteArrayOutputStream
import java.net.URLDecoder

internal fun validHost(host: String, port: Int): Boolean {
    val authority = host.trim().lowercase()
    return authority == "127.0.0.1:$port" || authority == "[::1]:$port" ||
        (port == 80 && authority in setOf("127.0.0.1", "[::1]"))
}

internal fun query(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val pairs = raw.split('&').map { part ->
        val split = part.split('=', limit = 2)
        decode(split[0]) to decode(split.getOrElse(1) { "" })
    }
    if (pairs.map { it.first }.distinct().size != pairs.size) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Duplicate query parameter")
    }
    return pairs.toMap()
}

internal fun decode(value: String): String = try {
    URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
} catch (_: IllegalArgumentException) {
    throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed URL encoding")
}

internal fun parseExplainAddress(value: String): ExplainAddress {
    fun invalid(): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, "Malformed Explain address")
    val cellSplit = value.split('#', limit = 2)
    val nodeSplit = cellSplit[0].split('@', limit = 2)
    if (nodeSplit[0].isBlank()) invalid()
    val node = decode(nodeSplit[0]).takeIf(String::isNotBlank) ?: invalid()
    val coord = if (nodeSplit.size == 1) {
        emptyList()
    } else {
        nodeSplit[1].split('/').map {
            if (it.isBlank()) invalid()
            decode(it).takeIf(String::isNotBlank) ?: invalid()
        }
    }
    val cell = if (cellSplit.size == 1) {
        null
    } else {
        val parts = cellSplit[1].split('.', limit = 2)
        if (parts.size != 2 || parts.any(String::isBlank)) invalid()
        val row = decode(parts[0]).takeIf(String::isNotBlank) ?: invalid()
        val column = decode(parts[1]).takeIf(String::isNotBlank) ?: invalid()
        row to column
    }
    return ExplainAddress(node, coord, cell)
}

internal fun readBody(exchange: HttpExchange, limit: Int): ByteArray? {
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
