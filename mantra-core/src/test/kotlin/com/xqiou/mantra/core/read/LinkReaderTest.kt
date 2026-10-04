// M3 test draft only: not compiled, integrated or verified.
package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.normein.dsl.form.DslForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkReaderTest {
    private fun read(text: String): Pair<List<com.xqiou.mantra.core.model.CaseLink>, DiagnosticSink> {
        val sink = DiagnosticSink()
        val document = checkNotNull(Document.read(SourceText("case.mantra", text.trimIndent()), sink))
        return LinkReader.read(document, document.root as DslForm.Sequence, sink) to sink
    }

    @Test
    fun `explicit scalar and mixed keyword text coordinates keep authored key identities`() {
        val (links, sink) = read(
            """
            (links {:path "../source/case.mantra" :schema "test/source" :schema-version "2025.01"
              :mappings [{:from {:node :closing :coord []}
                          :to {:input :amount :coord [:A "FY2026"]}}]})
            """,
        )
        assertTrue(sink.all.isEmpty(), sink.all.joinToString("\n"))
        assertEquals("2025.01", links.single().schema.version)
        assertEquals(emptyList(), links.single().mappings.single().from.coord)
        assertEquals(listOf("A", "FY2026"), links.single().mappings.single().to.coord)
    }

    @Test
    fun `omitted coordinates and transfer expressions are structural errors`() {
        val (links, sink) = read(
            """
            (links {:path "source.mantra" :schema "test/source" :schema-version "1"
              :mappings [{:from {:node :closing}
                          :to {:input :amount :coord []}}]})
            """,
        )
        assertTrue(links.isEmpty())
        assertTrue(sink.hasErrors)
        val (expressions, failures) = read(
            """
            (links {:path "source.mantra" :schema "test/source" :schema-version "1"
              :mappings [{:from {:node :closing :coord [] :expr (+ closing 1)}
                          :to {:input :amount :coord []}}]})
            """,
        )
        assertTrue(expressions.isEmpty())
        assertTrue(failures.hasErrors)
    }

    @Test
    fun `blank version is rejected and duplicate record keys remain errors`() {
        val (links, sink) = read(
            """
            (links {:path "source.mantra" :schema "test/source" :schema-version " "
              :mappings [{:from {:node :closing :coord []} :to {:input :amount :coord []}}]})
            """,
        )
        assertTrue(links.isEmpty())
        assertTrue(sink.hasErrors)
        val (_, duplicate) = read(
            """
            (links {:path "first.mantra" :path "second.mantra" :schema "test/source" :schema-version "1"
              :mappings [{:from {:node :closing :coord []} :to {:input :amount :coord []}}]})
            """,
        )
        assertTrue(duplicate.all.any { it.code == "MANTRA-READ-DUPLICATE-KEY" })
        // CaseReader must reject the complete source whenever this sink has an error.
    }

    @Test
    fun `nil and booleans are not schema or node symbols`() {
        for (literal in listOf("nil", "true", "false")) {
            val (links, sink) = read(
                """(links {:path "source.mantra" :schema $literal :schema-version "1"
                :mappings [{:from {:node :closing :coord []} :to {:input :amount :coord []}}]})""",
            )
            assertTrue(links.isEmpty())
            assertTrue(sink.hasErrors)
            val (badNodes, errors) = read(
                """(links {:path "source.mantra" :schema "test/source" :schema-version "1"
                :mappings [{:from {:node $literal :coord []} :to {:input :amount :coord []}}]})""",
            )
            assertTrue(badNodes.isEmpty())
            assertTrue(errors.hasErrors)
        }
    }
}
