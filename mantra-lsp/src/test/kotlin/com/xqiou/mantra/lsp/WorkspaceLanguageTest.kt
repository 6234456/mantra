package com.xqiou.mantra.lsp

import com.xqiou.mantra.core.api.language.LanguageSymbolKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceLanguageTest {
    @TempDir lateinit var directory: Path
    private val schema = """
        (schema test/rename {:version "1"}
          (input source-value :decimal)
          (line result "Source label" (+ source-value 1)))
    """.trimIndent()
    private val case = """
        (case sample {:schema "test/rename" :schema-version "1"}
          (inputs {:source-value 10}))
    """.trimIndent()
    private fun workspace(): Triple<Documents, WorkspaceAnalyzer, Pair<String, String>> {
        val schemaPath = directory.resolve("schema.mantra")
        val casePath = directory.resolve("sample.mantra")
        Files.writeString(schemaPath, schema)
        Files.writeString(casePath, case)
        val documents = Documents()
        val schemaUri = schemaPath.toRealPath().toUri().toString()
        val caseUri = casePath.toRealPath().toUri().toString()
        documents.open(schemaUri, schema, 10)
        documents.open(caseUri, case, 20)
        return Triple(documents, WorkspaceAnalyzer(listOf(directory)), schemaUri to caseUri)
    }

    @Test fun `rename edits declaration compiled reference and typed case key with pinned versions`() {
        val (documents, analyzer, uris) = workspace()
        val before = analyzer.analyze(documents.snapshot())
        assertTrue(before.complete, (before.issues + before.analyses.flatMap { it.issues }).toString())
        val input = before.definitions.single { it.id.kind == LanguageSymbolKind.INPUT }
        val edits = SafeRename(analyzer, documents).rename(before, uris.first, input.span.start, "updated-value")
        assertEquals(setOf(uris.first, uris.second), edits.map { it.uri }.toSet())
        assertEquals(2, edits.single { it.uri == uris.first }.edits.size)
        assertEquals(10, edits.single { it.uri == uris.first }.version)
        assertEquals(20, edits.single { it.uri == uris.second }.version)
        assertEquals(schema, Files.readString(directory.resolve("schema.mantra")))
        assertEquals(case, Files.readString(directory.resolve("sample.mantra")))
        assertTrue(
            edits.flatMap { it.edits }.all { edit ->
                before.documents.getValue(edit.span.source).text.substring(edit.span.start, edit.span.end) ==
                    "source-value"
            },
        )
    }

    @Test fun `capture refuses even when speculative formula would still compile`() {
        val (documents, analyzer, uris) = workspace()
        val text = schema.replace("(+ source-value 1)", "(let [captured-value 7] (+ source-value captured-value))")
        documents.change(uris.first, 11, listOf(Change(null, text)))
        val before = analyzer.analyze(documents.snapshot())
        assertTrue(before.complete, before.issues.toString())
        val input = before.definitions.single { it.id.kind == LanguageSymbolKind.INPUT }
        val failure = assertFailsWith<RenameRefused> {
            SafeRename(analyzer, documents).rename(before, uris.first, input.span.start, "captured-value")
        }
        assertTrue(failure.reason.contains("binding identity"), failure.reason)
    }

    @Test fun `closed affected document stale overlay and changed disk are refused`() {
        val (documents, analyzer, uris) = workspace()
        documents.close(uris.second)
        val before = analyzer.analyze(documents.snapshot())
        val input = before.definitions.single { it.id.kind == LanguageSymbolKind.INPUT }
        val missing = assertFailsWith<RenameRefused> {
            SafeRename(analyzer, documents).rename(before, uris.first, input.span.start, "new-value")
        }
        assertEquals(listOf(uris.second), missing.affectedUris)
        documents.open(uris.second, case, 21)
        assertFailsWith<RenameRefused> {
            SafeRename(analyzer, documents).rename(before, uris.first, input.span.start, "new-value")
        }
        val current = analyzer.analyze(documents.snapshot())
        val unrelated = directory.resolve("unrelated.mantra")
        Files.writeString(unrelated, "(schema test/other (line other \"Other\" 1))")
        // New consumers must also invalidate inventory through didChangeWatchedFiles. A closed
        // document already captured in this epoch is explicitly hash-checked before edits return.
        val captured = analyzer.analyze(documents.snapshot())
        Files.writeString(unrelated, "(schema test/other (line other \"Other\" 2))")
        assertFailsWith<RenameRefused> { analyzer.verifySources(captured) }
    }

    @Test fun `include overlay references resolve to included declaration without filesystem writes`() {
        Files.writeString(
            directory.resolve("schema.mantra"),
            "(schema test/include {:version \"1\"} (include \"part.mantra\") (line result " +
                "\"Result\" (+ source-value 1)))",
        )
        val fragment = directory.resolve("part.mantra")
        Files.writeString(fragment, "(fragment (input source-value :decimal))")
        val documents = Documents()
        val uri = fragment.toRealPath().toUri().toString()
        documents.open(uri, "(fragment (input source-value :decimal) (input extra-value :decimal))", 1)
        val result = WorkspaceAnalyzer(listOf(directory)).analyze(documents.snapshot())
        val source = result.definitions.single { it.id.kind == LanguageSymbolKind.INPUT && it.name == "source-value" }
        assertEquals(uri, source.span.source)
        assertEquals(1, result.references(source.id, false).size)
        assertEquals("(fragment (input source-value :decimal))", Files.readString(fragment))
    }

    @Test fun `symlink escapes and private directories cannot be read`() {
        val privatePath = directory.resolve(".aws")
        Files.createDirectory(privatePath)
        val hidden = privatePath.resolve("secret.mantra")
        Files.writeString(hidden, "(fragment)")
        val sources = ConfinedSources(listOf(directory), DocumentEpoch(1, emptyMap()), {})
        assertFailsWith<IllegalArgumentException> { sources.read(hidden.toUri().toString()) }
        val outside = Files.createTempDirectory("mantra-lsp-outside-")
        try {
            Files.writeString(outside.resolve("outside.mantra"), "(fragment)")
            val link = directory.resolve("escape.mantra")
            Files.createSymbolicLink(link, outside.resolve("outside.mantra"))
            assertFailsWith<IllegalArgumentException> { sources.read(link.toUri().toString()) }
        } finally {
            Files.deleteIfExists(outside.resolve("outside.mantra"))
            Files.deleteIfExists(outside)
        }
    }
}
