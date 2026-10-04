package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Captured and uploaded text must not silently replace malformed UTF-8 with U+FFFD. */
internal fun decodeImportUtf8(bytes: ByteArray, location: SourceLocation? = null): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    val message = "Import text is not valid UTF-8"
    throw WorkspaceException(
        WorkspaceProblem.INVALID,
        message,
        listOf(Diagnostic(Severity.ERROR, "MANTRA-DATA-UTF8", message, location)),
    )
}
