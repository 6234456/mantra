package com.xqiou.mantra.core.api.language

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.read.SchemaReader
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StaticLanguageTest {
    private fun analyze(text: String): LanguageAnalysis {
        val source = SourceText("file:///workspace/schema.mantra", text)
        val sink = DiagnosticSink()
        val schema = requireNotNull(SchemaReader(SourceResolver { _, _ -> null }).read(source, sink))
        sink.throwIfErrors()
        return MantraLanguage.analyze(
            schema,
            CaseData.empty(),
            documents = listOf(LanguageDocumentContext(source, schema.identity)),
        )
    }

    @Test fun `lexical shadowing and qualified roots retain separate identities`() {
        val text = """
            (schema test/language {:version "1"}
              (input source-value :decimal)
              (line result "Result" (let [source-value 7] (+ source-value mantra/source-value))))
        """.trimIndent()
        val result = analyze(text)
        assertTrue(result.complete, result.issues.toString())
        val input = result.definitions.single { it.id.kind == LanguageSymbolKind.INPUT }
        val local = result.definitions.single { it.id.kind == LanguageSymbolKind.LOCAL }
        assertNotEquals(input.id, local.id)
        val rootUse = result.occurrences.single { it.symbol == input.id }
        assertEquals("source-value", text.substring(rootUse.span.start, rootUse.span.end))
        assertEquals(text.indexOf("mantra/source-value") + 7, rootUse.span.start)
        assertEquals(1, result.referencesOf(local.id).size)
        assertEquals(local.id, result.symbolAt(local.span.source, result.referencesOf(local.id).single().start))
    }

    @Test fun `unused named bodies and skipped branches enter static references without execution`() {
        val text = """
            (schema test/language {:version "1"}
              (input source-value :decimal)
              (defn never-called [^Decimal x] (+ x source-value))
              (line result "Result" (if false (/ source-value 0) 5)))
        """.trimIndent()
        val result = analyze(text)
        assertTrue(result.complete, result.issues.toString())
        val input = result.definitions.single { it.id.kind == LanguageSymbolKind.INPUT }.id
        val uses = result.referencesOf(input)
        assertEquals(2, uses.size)
        assertTrue(uses.any { it.start < text.indexOf("(line result") })
        assertTrue(uses.any { it.start > text.indexOf("(line result") })
        assertTrue(result.diagnostics.isEmpty()) // /0 was compiled, never evaluated.
    }

    @Test fun `previous source overlay targets authored node and does not expose private roots`() {
        val text = """
            (schema test/period {:version "1"}
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (section stock "Stock" {:per [year]}
                (line opening "Opening" (prev closing 10) {:aggregate {:first year}})
                (line closing "Closing" (+ opening 1) {:aggregate {:last year}})))
        """.trimIndent()
        val result = analyze(text)
        assertTrue(result.complete, result.issues.toString())
        val use = result.occurrences.single { it.kind == LanguageUseKind.PREVIOUS }
        assertEquals("closing", text.substring(use.span.start, use.span.end))
        assertEquals("closing", use.symbol?.name)
        assertTrue(result.occurrences.none { it.symbol?.name?.startsWith("mantra-internal") == true })
    }

    @Test fun `same spelling local prev remains a lexical callable`() {
        val text = """
            (schema test/local {:version "1"}
              (line result "Result" (let [prev (fn [^Decimal x] (+ x 1))] (prev 2))))
        """.trimIndent()
        val result = analyze(text)
        assertTrue(result.complete, result.issues.toString())
        assertTrue(result.occurrences.none { it.kind == LanguageUseKind.PREVIOUS })
        val local = result.definitions.single { it.name == "prev" }
        assertEquals(LanguageSymbolKind.LOCAL, local.id.kind)
        assertEquals(1, result.referencesOf(local.id).size)
    }

    @Test fun `typed row context and local record completion use actual scope`() {
        val text = """
            (schema test/table {:version "1"}
              (input entries :table {:columns {:quantity :decimal :label :text}})
              (line result "Result" (let [total-value (sum (map (fn [entry] entry.quantity) entries))]
                (if (nil? total-value) 0 total-value))))
        """.trimIndent()
        val result = analyze(text)
        assertTrue(result.complete, result.issues.toString())
        val cursor = text.indexOf("entry.quantity") + "entry.qu".length
        val items = result.complete("file:///workspace/schema.mantra", cursor)
        assertTrue(items.any { it.label.endsWith("quantity") }, items.toString())
        assertTrue(items.none { it.label.startsWith("mantra-internal/") })
    }

    @Test fun `annotated let binder uses original name token rather than type or value`() {
        val text = """
            (schema test/annotation (line result "Result" (let [^Decimal local-value 2] (+ local-value 1))))
        """.trimIndent()
        val result = analyze(text)
        assertTrue(result.complete, result.issues.toString())
        val local = result.definitions.single { it.id.kind == LanguageSymbolKind.LOCAL }
        assertEquals("local-value", text.substring(local.span.start, local.span.end))
        assertEquals(text.indexOf("local-value"), local.span.start)
        assertEquals(1, result.referencesOf(local.id).size)
    }

    @Test fun `failure never reuses a successful semantic index and snapshots reject mutation`() {
        val good = analyze("(schema test/x (line result \"Result\" 2))")

        @Suppress("UNCHECKED_CAST")
        val mutable = good.definitions as MutableList<LanguageDefinition>
        assertFailsWith<UnsupportedOperationException> { mutable.clear() }
        val broken = analyze("(schema test/x (line result \"Result\" unknown-root))")
        assertFalse(broken.complete)
        assertTrue(broken.diagnostics.isNotEmpty())
        assertTrue(broken.occurrences.isEmpty())
    }
}
