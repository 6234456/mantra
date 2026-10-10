package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchStyleClassesTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var root: Path

    // Same public pipeline as ExcelStyleClassesTest; the locked bridge is 13.2500.
    private val schemaText = """
        (schema test/class-pipeline {:title "Class pipeline" :version "1" :mainline [main]}
          (input postings :table {:columns {:bucket :keyword :amount :decimal}})
          (input adjustment :decimal)
          (section main "Bridge" {:panel true :class :accent}
            (line base "Base" (table/sum-where postings {:bucket :base} :amount) {:class :source})
            (line additions "Additions" (table/sum-where postings {:bucket :addition} :amount)
              {:class [:accent :strong :subtle]})
            (subtract deduction "Deduction" adjustment {:class :muted})
            (total result "Result" {:class :key-result})
            (info note "Note" 0.2500 {:class [:note :future-tag]})
            (info plain "Unclassified" 5 {:class :future-tag})))
    """.trimIndent()
    private val caseText = """
        (case demo {:schema "test/class-pipeline" :layout "test/class-paper"}
          (inputs {:postings (rows [:bucket :amount] [:base 12.5000] [:addition 2.0000])
                   :adjustment 1.2500}))
    """.trimIndent()
    private val layoutText = """
        (layout test/class-paper {:preset :ifrs-schedule :locale "en-US" :precision 4
                                 :hide-zero false :style-preset [:utilities :working-paper]}
          (style-class :key-result {:weight :bold :tone :accent :fill :subtle})
          (style {:class :key-result :column :value}
            {:use [:strong :accent] :weight :normal :fill :accent})
          (table main :label :value))
    """.trimIndent()

    private fun rows(paper: Map<String, Any?>): List<Map<*, *>> =
        (paper["tables"] as List<*>).flatMap { ((it as Map<*, *>)["rows"] as List<*>).map { row -> row as Map<*, *> } }

    private fun cell(row: Map<*, *>, numeric: Boolean): Map<*, *> =
        (row["cells"] as List<*>).map { it as Map<*, *> }.single { (it["address"] != null) == numeric }

    private fun expected(weight: String, tone: String, fill: String) =
        mapOf("weight" to weight, "tone" to tone, "fill" to fill)

    @Test
    fun `live Paper JSON resolves shared classes and uses existing strict style contract`() {
        val documents = mapOf(
            "schema.mantra" to schemaText,
            "case.mantra" to caseText,
            "layout.mantra" to layoutText,
            "plain.mantra" to """(layout test/plain-paper {:preset :ifrs-schedule :locale "en-US"
                                      :precision 4 :hide-zero false} (table main :label :value))""",
        )
        documents.forEach { (name, text) -> Files.writeString(root.resolve(name), text) }
        val catalog = workspaceCatalog(root)
        val run = catalog.document("case.mantra", "run")
        assertEquals(true, run.data["succeeded"])
        val values = run.data["values"] as Map<*, *>
        val totalValue = ((values["result"] as Map<*, *>)[""] as Map<*, *>)["value"]
        assertEquals(mapOf("n" to "13.2500"), totalValue)
        val styled = catalog.document("case.mantra", "paper", "main")
        val styledRows = rows(styled.data)
        val total = styledRows.single { it["node"] == "result" }
        assertEquals(expected("bold", "accent", "subtle"), cell(total, false)["style"])
        assertEquals(expected("normal", "accent", "accent"), cell(total, true)["style"])
        assertEquals(expected("bold", "accent", "subtle"), cell(total, false)["styleOverrides"])
        assertEquals(expected("normal", "accent", "accent"), cell(total, true)["styleOverrides"])
        assertEquals("13.2500", cell(total, true)["text"])
        assertEquals(
            mapOf("case" to null, "node" to "result"),
            cell(total, true)["address"],
        )
        val additions = styledRows.single { it["node"] == "additions" }
        assertEquals(listOf("accent", "strong", "subtle"), additions["classes"])
        assertEquals(expected("bold", "accent", "subtle"), cell(additions, true)["style"])
        assertEquals(
            expected("normal", "muted", "none"),
            cell(
                styledRows.single {
                    it["node"] == "base"
                },
                true,
            )["style"],
        )
        assertEquals(
            expected("normal", "muted", "none"),
            cell(
                styledRows.single {
                    it["node"] == "note"
                },
                true,
            )["style"],
        )
        assertEquals(
            mapOf("tone" to "muted", "fill" to "none"),
            cell(styledRows.single { it["node"] == "base" }, true)["styleOverrides"],
        )
        assertEquals(
            mapOf("tone" to "muted"),
            cell(styledRows.single { it["node"] == "note" }, true)["styleOverrides"],
        )
        val unknown = styledRows.single { it["node"] == "plain" }
        assertEquals(listOf("future-tag"), unknown["classes"])
        assertEquals(expected("normal", "default", "none"), cell(unknown, true)["style"])
        assertFalse(cell(unknown, true).containsKey("styleOverrides"))
        assertFalse((unknown["classes"] as List<*>).contains("accent"))
        val unstyled = catalog.document("case.mantra", "paper", "main", "test/plain-paper")
        val unstyledRows = rows(unstyled.data)
        assertTrue(
            unstyledRows.all { row ->
                (row["cells"] as List<*>).none { (it as Map<*, *>).containsKey("styleOverrides") }
            },
        )
        assertEquals(
            expected("normal", "default", "none"),
            cell(unstyledRows.single { it["node"] == "additions" }, true)["style"],
        )
        assertEquals(styledRows.map { it["node"] }, unstyledRows.map { it["node"] })
        styledRows.zip(unstyledRows).forEach { (withStyle, withoutStyle) ->
            fun content(row: Map<*, *>) = (row["cells"] as List<*>).map { value ->
                val cell = value as Map<*, *>
                listOf(cell["text"], cell["address"], cell["editable"])
            }
            assertEquals(content(withStyle), content(withoutStyle))
        }
        val actual = catalog.envelope(styled)
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/paper.schema.json"))
        val failures = schema.validate(actual, InputFormat.JSON)
        assertTrue(failures.isEmpty(), failures.toString())
        val html = catalog.export("case.mantra", "html").toString(Charsets.UTF_8)
        assertContains(html, "font-weight:400;color:var(--accent);background:color-mix")
        assertContains(html, "u-key-result")
        val after = catalog.document("case.mantra", "run")
        assertEquals(run.revision, after.revision)
        assertEquals(run.data["values"], after.data["values"])
        assertEquals(run.data["validationPassed"], after.data["validationPassed"])
        documents.forEach { (name, text) -> assertEquals(text, Files.readString(root.resolve(name))) }
    }
}
