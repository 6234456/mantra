package com.xqiou.mantra.lsp

import java.net.URI
import java.security.MessageDigest
import java.util.Collections

/** LSP positions are zero-based UTF-16; offsets refer to the exact current text. */
data class Position(val line: Int, val character: Int)
data class Range(val start: Position, val end: Position)
data class Change(val range: Range?, val text: String)

class LineIndex(private val text: String) {
    private val starts = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }
    fun offset(position: Position): Int {
        require(position.line in starts.indices && position.character >= 0) { "Position outside document" }
        val start = starts[position.line]
        val limit = if (position.line + 1 < starts.size) {
            var end = starts[position.line + 1] - 1
            if (end > start && text[end - 1] == '\r') end--
            end
        } else {
            text.length
        }
        val result = start + position.character
        require(result in start..limit) { "Character outside line" }
        require(
            result == 0 || result == text.length ||
                !(text[result - 1].isHighSurrogate() && text[result].isLowSurrogate()),
        ) {
            "Position splits a surrogate pair"
        }
        return result
    }
    fun position(offset: Int): Position {
        require(offset in 0..text.length)
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= offset) low = mid + 1 else high = mid - 1
        }
        return Position(high, offset - starts[high])
    }
    fun range(start: Int, end: Int): Range = Range(position(start), position(end))
}

data class DocumentSnapshot(val uri: String, val text: String, val version: Int?, val clientUri: String = uri) {
    val hash: String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val lines: LineIndex = LineIndex(text)
}

data class DocumentEpoch(val revision: Long, val overlays: Map<String, DocumentSnapshot>)

/** Mutations are atomic. Closed buffers disappear; future reads use the current confined disk file. */
class Documents(
    private val maxDocuments: Int = 256,
    private val maxChars: Int = 65_536,
    private val maxTotalChars: Int = 4 * 1024 * 1024,
) {
    private val open = linkedMapOf<String, DocumentSnapshot>()
    private var revision = 0L

    @Synchronized fun snapshot(): DocumentEpoch = DocumentEpoch(
        revision,
        Collections.unmodifiableMap(LinkedHashMap(open)),
    )

    @Synchronized fun invalidate() {
        revision++
    }

    @Synchronized fun current(epoch: Long): Boolean = revision == epoch

    @Synchronized fun open(uri: String, text: String, version: Int, clientUri: String = uri) {
        require(uri !in open) { "Document is already open" }
        require(open.size < maxDocuments) { "Open document limit reached" }
        replace(uri, text, version, clientUri)
    }

    @Synchronized fun change(uri: String, version: Int, changes: List<Change>) {
        val current = requireNotNull(open[uri]) { "Document is not open" }
        require(version > requireNotNull(current.version)) { "Document version must increase" }
        var text = current.text
        changes.forEach { change ->
            val range = change.range
            text = if (range == null) {
                change.text
            } else {
                val lines = LineIndex(text)
                val start = lines.offset(range.start)
                val end = lines.offset(range.end)
                require(end >= start)
                text.substring(0, start) + change.text + text.substring(end)
            }
            require(text.length <= maxChars) { "Document source limit reached" }
        }
        replace(uri, text, version)
    }

    @Synchronized fun close(uri: String) {
        require(open.remove(uri) != null) { "Document is not open" }
        revision++
    }
    private fun replace(uri: String, text: String, version: Int, clientUri: String = open[uri]?.clientUri ?: uri) {
        require(text.length <= maxChars) { "Document source limit reached" }
        val total = open.values.sumOf { it.text.length } - (open[uri]?.text?.length ?: 0) + text.length
        require(total <= maxTotalChars) { "Workspace source limit reached" }
        open[uri] = DocumentSnapshot(uri, text, version, clientUri)
        revision++
    }
}

internal fun fileUri(raw: String): URI {
    val uri = URI(raw)
    require(
        uri.scheme == "file" && uri.query == null && uri.fragment == null &&
            (uri.authority == null || uri.authority.isEmpty()),
    ) { "Only local file URIs are supported" }
    return uri
}
