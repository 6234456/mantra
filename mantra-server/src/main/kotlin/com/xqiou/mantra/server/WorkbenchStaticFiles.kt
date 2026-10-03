package com.xqiou.mantra.server

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom
import java.util.UUID

/** Serves the built UI with per-session metadata while confining reads to the UI root. */
internal class WorkbenchStaticFiles(private val uiDirectory: Path?, private val token: String) {
    fun serve(exchange: HttpExchange, rawPath: String, head: Boolean) {
        val root = uiDirectory?.toAbsolutePath()?.normalize()?.takeIf(Files::isDirectory)
            ?: return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Live UI build was not found")
        val decoded = decode(rawPath)
        val candidate = root.resolve(decoded.removePrefix("/")).normalize()
        if (!candidate.startsWith(
                root,
            )
        ) {
            return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Static file was not found")
        }
        val file = if (Files.isRegularFile(
                candidate,
                LinkOption.NOFOLLOW_LINKS,
            )
        ) {
            candidate
        } else {
            root.resolve("index.html")
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root.toRealPath())) {
            return error(exchange, 404, "MANTRA-WORKBENCH-NOT-FOUND", "Static file was not found")
        }
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
            exchange.responseHeaders.set(
                "Content-Security-Policy",
                "default-src 'self'; connect-src 'self'; script-src 'self'; " +
                    "style-src 'self' 'nonce-$styleNonce'; img-src 'self' data:; " +
                    "object-src 'none'; base-uri 'none'",
            )
            val meta = "<meta name=\"mantra-session-token\" content=\"$token\">" +
                "<meta name=\"mantra-style-nonce\" content=\"$styleNonce\">"
            if (!html.contains("</head>")) {
                return error(
                    exchange,
                    500,
                    "MANTRA-WORKBENCH-INTERNAL",
                    "Live UI index is invalid",
                    correlationId = UUID.randomUUID().toString(),
                )
            }
            html.replaceFirst("</head>", "$meta</head>").toByteArray(Charsets.UTF_8)
        } else {
            Files.readAllBytes(file)
        }
        send(exchange, 200, contentType, bytes, head)
    }
}
