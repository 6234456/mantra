package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.NegativeStyle
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkingPaperBuilderTest {
    private val schema = Mantra.loadSchema(
        SourceText(
            "t.mantra",
            """
            (schema t/paper {:title "Paper"}
              (input zusammen :boolean {:default true})
              (dimension person {:members [{:key :A :label "Person A"} {:key :B :label "Person B"}]})
              (section einkuenfte "Einkünfte" {:display :schedule}
                (section nsa "Arbeit" {:per person}
                  (field lohn "Lohn")
                  (line wk "Werbungskosten" (max 1230 wk-ist) {:op :minus})
                  (total einkuenfte-nsa "Einkünfte Arbeit"))
                (total summe "Summe der Einkünfte"))
              (input wk-ist :decimal {:per person})
              (section haupt "Hauptrechnung" {:display :schedule}
                (line summe-uebertrag "Summe der Einkünfte" summe)
                (section abzug "Abzüge" {:op :minus}
                  (field spende "Spende" {:op :info})
                  (line spende-abz "Spende abziehbar" (min spende 100))
                  (line null-zeile "Nie relevant" 0)
                  (line freigrenze "Freigrenze" (if (< spende 1000) 0 spende) {:op :info})
                  (total abzug-summe "Abzüge gesamt"))
                (total ergebnis "Ergebnis")))
            """.trimIndent(),
        ),
        SourceResolver { _, _ -> null },
    )
    private val result = Mantra.calculate(
        schema,
        Mantra.loadCase(SourceText("c.mantra", "(case c (inputs {:lohn {:A 50000 :B 20000} :wk-ist {:A 2000} :spende 400}))")),
    )

    private fun table(paperTables: List<PaperTable>, id: String) = paperTables.single { it.id == id }
    private fun column(table: PaperTable, content: ColumnContent) = table.columns.indexOfFirst { it.content == content }
    private fun row(table: PaperTable, label: String) = table.rows.single { r -> r.cells.any { it.startsWith(label) } }

    @Test
    fun `core metadata and application attributes render through separate columns`() {
        val application = Mantra.loadSchema(
            SourceText("metadata.mantra", """
                (schema t/metadata {}
                  (section result "Result"
                    (line amount "Amount" 5 {:reference "IAS 36.104" :note "checked" :source "ledger"
                                             :kz "110" :zeile "7"})))
            """.trimIndent()),
            SourceResolver { _, _ -> null },
        )
        val layout = LayoutReader.read(SourceText("metadata-layout.mantra", """
            (layout t/metadata-paper {:preset :ifrs-schedule}
              (columns :tiered :label (attribute :kz {:header "Kz."}) (attribute :zeile {:header "Zeile"})
                       :reference :note :source :value)
              (table result))
        """.trimIndent()))
        val paper = Render.paper(Mantra.calculate(application), layout).tables.single()
        val cells = paper.rows.single { it.nodeId == "amount" }.cells
        assertEquals(listOf("Amount", "110", "7", "IAS 36.104", "checked", "ledger", "5"), cells)
        assertEquals(listOf("Kz.", "Zeile"), paper.columns.slice(1..2).map { it.header })
    }

    @Test
    fun `schema classes combine default and named layout styles without changing calculation`() {
        val styledSchema = Mantra.loadSchema(
            SourceText("styled.mantra", "(schema t/styled {} (section result \"Result\" (line amount \"Amount\" (+ 2 3) {:class [:strong :accent]})))"),
            SourceResolver { _, _ -> null },
        )
        val calculated = Mantra.calculate(styledSchema)
        val layout = LayoutReader.read(SourceText("styled-layout.mantra", """
            (layout t/styled-paper {:preset :ifrs-schedule}
              (style {:all true} {:tone :muted})
              (style {:class :strong} {:weight :bold})
              (style {:class :accent} {:tone :accent}))
        """.trimIndent()))
        val styled = Render.paper(calculated, layout).tables.flatMap { it.rows }.single { it.nodeId == "amount" }
        assertEquals(listOf("strong", "accent"), styled.classes)
        assertEquals(StyleWeight.BOLD, styled.style.weight)
        assertEquals(StyleTone.ACCENT, styled.style.tone)
        assertEquals("5", calculated.decimal("amount").toPlainString())
        assertTrue("u-strong u-accent" in Render.html(calculated, layout))
    }

    @Test
    fun `style selectors resolve section level row parity and row number cells`() {
        val styledSchema = Mantra.loadSchema(
            SourceText("selector-schema.mantra", """
                (schema t/selectors {}
                  (section root "Root"
                    (line first "First" 1)
                    (line second "Second" 2)
                    (section nested "Nested"
                      (line third "Third" 3)
                      (section deeper "Deeper"
                        (line fourth "Fourth" 4)))))
            """.trimIndent()),
            SourceResolver { _, _ -> null },
        )
        val calculated = Mantra.calculate(styledSchema)
        val layout = LayoutReader.read(SourceText("selector-layout.mantra", """
            (layout t/selectors-paper {:preset :de-staffel-4 :row-numbers :table}
              (columns :tiered :label :value)
              (style {:section nested :depth 2 :indent 1 :kind :value} {:weight :bold})
              (style {:height 2 :kind :heading} {:tone :muted})
              (style {:nth-child :even :kind :value} {:fill :subtle})
              (style {:has-row-number true :column :row-number} {:tone :accent})
              (table root))
        """.trimIndent()))
        val paper = Render.paper(calculated, layout).tables.single()
        val number = paper.columns.indexOfFirst { it.content == ColumnContent.RowNumber }
        val value = paper.columns.indexOfFirst { it.content == ColumnContent.Value }
        val first = paper.rows.single { it.nodeId == "first" }
        val second = paper.rows.single { it.nodeId == "second" }
        val third = paper.rows.single { it.nodeId == "third" }
        val fourth = paper.rows.single { it.nodeId == "fourth" }
        val nestedHeading = paper.rows.single { it.kind == RowKind.HEADING && "Nested" in it.cells }
        val deeperHeading = paper.rows.single { it.kind == RowKind.HEADING && "Deeper" in it.cells }
        assertEquals(listOf("root"), first.cellContexts[value].sectionPath)
        assertEquals(1, first.cellContexts[value].depth)
        assertEquals(0, first.cellContexts[value].height)
        assertEquals(1, first.cellContexts[value].rowIndex)
        assertEquals(StyleFill.SUBTLE, second.cellStyles[value].fill)
        assertEquals(null, first.cellStyles[value].fill)
        assertEquals(StyleTone.ACCENT, first.cellStyles[number].tone)
        assertEquals(null, first.cellStyles[value].tone)
        assertEquals(listOf("root", "nested"), third.cellContexts[value].sectionPath)
        assertEquals(1, nestedHeading.cellContexts[value].depth)
        assertEquals(2, nestedHeading.cellContexts[value].height)
        assertEquals(StyleTone.MUTED, nestedHeading.cellStyles[value].tone)
        assertEquals(2, third.cellContexts[value].depth)
        assertEquals(0, third.cellContexts[value].height)
        assertEquals(2, deeperHeading.cellContexts[value].depth)
        assertEquals(1, deeperHeading.cellContexts[value].height)
        assertEquals(3, fourth.cellContexts[value].depth)
        assertEquals(0, fourth.cellContexts[value].height)
        assertEquals(StyleWeight.BOLD, third.cellStyles[value].weight)
        assertEquals(4, third.cellContexts[value].rowIndex) // The section heading is the third tbody row.
        assertEquals(StyleFill.SUBTLE, third.cellStyles[value].fill)
        val html = Render.html(calculated, layout)
        assertTrue("data-section=\"nested\" data-depth=\"2\" data-height=\"0\"" in html)
        assertTrue("data-column=\"row-number\" style=\"color:var(--accent)\"" in html)
        assertTrue("data-column=\"value\" style=\"background:var(--heading)\"" in html)
    }

    @Test
    fun `style requires a selector map followed by a declaration map`() {
        assertFailsWith<MantraException> {
            LayoutReader.read(SourceText("old-style.mantra", "(layout t/old {:preset :ifrs-schedule} (style :default {:fill :none}))"))
        }
    }

    @Test
    fun `row number mode adds the column and controls numbering across tables`() {
        val globalLayout = LayoutReader.read(SourceText("global-numbers.mantra", "(layout t/global {:preset :de-staffel-4 :row-numbers :global})"))
        val global = Render.paper(result, globalLayout)
        val globalNumbers = global.tables.flatMap { t ->
            val index = column(t, ColumnContent.RowNumber)
            t.rows.mapNotNull { it.cells[index].toIntOrNull() }
        }
        assertTrue(global.tables.size > 1)
        assertEquals((1..globalNumbers.size).toList(), globalNumbers)

        val localLayout = LayoutReader.read(SourceText("numbered.mantra", """
            (layout t/numbered {:preset :ifrs-schedule :row-numbers :table}
              (columns :tiered :label :value)
              (table haupt))
        """.trimIndent()))
        val local = Render.paper(result, localLayout).tables.single()
        assertEquals(ColumnContent.RowNumber, local.columns.first().content)
        assertEquals("1", local.rows.first { it.nodeId != null }.cells.first())
        assertFailsWith<MantraException> {
            LayoutReader.read(SourceText("invalid-numbers.mantra", "(layout t/invalid {:row-numbers :section})"))
        }
    }

    @Test
    fun `staffel places own items in the main column and nested components in the lead column`() {
        val paper = Render.paper(result, Presets.DE_STAFFEL_4)
        val haupt = table(paper.tables, "haupt")
        assertEquals(TableStyle.TIERED, haupt.style)
        val pre = column(haupt, ColumnContent.Pre)
        val main = column(haupt, ColumnContent.Main)
        assertEquals("66.770,00", row(haupt, "Summe der Einkünfte").cells[main])
        assertEquals("100,00", row(haupt, "Spende abziehbar").cells[pre])
        assertEquals("", row(haupt, "Spende abziehbar").cells[main])
        // The result of the opaque sub-section enters the main column with the section's operator.
        val abzug = row(haupt, "Abzüge gesamt")
        assertEquals(RowKind.RESULT, abzug.kind)
        assertEquals("100,00", abzug.cells[main])
        assertEquals("./.", abzug.cells[column(haupt, ColumnContent.Operator)])
        val ergebnis = row(haupt, "Ergebnis")
        assertTrue(RowFlag.GRAND in ergebnis.flags)
        assertEquals("66.670,00", ergebnis.cells[main])
    }

    @Test
    fun `carry-over lines cite the table that presents their source`() {
        val paper = Render.paper(result, Presets.DE_STAFFEL_4)
        val haupt = table(paper.tables, "haupt")
        val ref = table(paper.tables, "einkuenfte").ref
        assertTrue(haupt.rows.any { r -> r.cells.any { it == "Summe der Einkünfte (→ Tabelle $ref)" } })
    }

    @Test
    fun `zero rows are hidden unless they explain why a non-zero input became zero`() {
        val paper = Render.paper(result, Presets.DE_STAFFEL_4)
        val haupt = table(paper.tables, "haupt")
        assertFalse(haupt.rows.any { r -> r.cells.any { it.startsWith("Nie relevant") } })
        assertTrue(haupt.rows.any { r -> r.cells.any { it.startsWith("Freigrenze") } && RowFlag.EXPLAINS_ZERO in r.flags })
    }

    @Test
    fun `matrix tables expand member columns and cross-foot`() {
        val paper = Render.paper(result, Presets.DE_STAFFEL_4)
        val einkuenfte = table(paper.tables, "einkuenfte")
        assertEquals(TableStyle.MATRIX, einkuenfte.style)
        assertEquals(listOf("Person A", "Person B", "Gesamt"), einkuenfte.columns.filter { it.content is ColumnContent.Member || it.content == ColumnContent.CrossTotal }.map { it.header })
        val nsa = row(einkuenfte, "Einkünfte Arbeit")
        assertEquals(listOf("48.000,00", "18.770,00", "66.770,00"), nsa.cells.filter { it.contains(',') })
    }

    @Test
    fun `layout documents select presets, columns and signed display`() {
        val layout = LayoutReader.read(
            SourceText(
                "l.mantra",
                """
                (layout t/ifrs {:preset :ifrs-schedule :precision 1 :signed true :hide-zero true}
                  (columns :tiered :label (col :amount {:content :value :header "EUR"}))
                  (table haupt {:title "Main"}))
                """.trimIndent(),
            ),
        )
        assertEquals(NegativeStyle.PARENTHESES, layout.number.negative)
        assertEquals(1, layout.number.precision)
        val paper = Render.paper(result, layout)
        assertEquals(listOf("haupt"), paper.tables.map { it.id })
        val main = paper.tables.single()
        assertEquals("Main", main.title)
        assertEquals(listOf("Description", "EUR"), main.columns.map { it.header }) // label header falls back to the preset caption
        assertEquals("(100.0)", row(main, "Abzüge gesamt").cells[1])
    }

    @Test
    fun `html and text renderers produce complete documents`() {
        val html = Render.html(result, Presets.DE_STAFFEL_4)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue("prefers-color-scheme: dark" in html)
        assertTrue("id=\"audit\"" in html)
        val text = Render.text(result, Presets.DE_STAFFEL_4, includeAudit = true)
        assertTrue("Berechnungsnachweis" in text)
    }
}
