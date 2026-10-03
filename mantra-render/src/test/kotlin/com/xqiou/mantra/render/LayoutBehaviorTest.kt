package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import com.xqiou.mantra.render.paper.RowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutBehaviorTest {
    private fun calculate(source: String) = Mantra.calculate(
        Mantra.loadSchema(SourceText("schema.mantra", source.trimIndent()), SourceResolver { _, _ -> null }),
    )

    private fun layout(source: String) = LayoutReader.read(SourceText("layout.mantra", source.trimIndent()))

    @Test
    fun `selectors combine classes and custom column ids in declaration order`() {
        val result =
            calculate(
                """
            (schema t/styling {}
              (section values "Values"
                (line emphasized "Emphasized" 8 {:class :emphasis})
                (line ordinary "Ordinary" 9)))
        """,
            )
        val spec =
            layout(
                """
            (layout t/styling-paper {:preset :ifrs-schedule}
              (columns :tiered :label (col :amount {:content :value :header "Amount"}))
              (style {:all true} {:tone :muted})
              (style {:class :emphasis} {:weight :bold})
              (style {:column :amount} {:tone :accent})
              (style {:class :emphasis :column :value} {:fill :subtle})
              (style {:class :emphasis :column :label} {:weight :normal})
              (style {:has-row-number false :column :label} {:fill :accent})
              (table values))
        """,
            )
        val table = Render.paper(result, spec).tables.single()
        val emphasized = table.rows.single { it.nodeId == "emphasized" }
        val ordinary = table.rows.single { it.nodeId == "ordinary" }
        assertEquals(StyleWeight.BOLD, emphasized.style.weight)
        assertEquals(StyleWeight.NORMAL, emphasized.cellStyles[0].weight)
        assertEquals(StyleWeight.BOLD, emphasized.cellStyles[1].weight)
        assertEquals(StyleTone.MUTED, emphasized.cellStyles[0].tone)
        assertEquals(StyleTone.ACCENT, emphasized.cellStyles[1].tone)
        assertEquals(StyleFill.ACCENT, emphasized.cellStyles[0].fill)
        assertEquals(StyleFill.SUBTLE, emphasized.cellStyles[1].fill)
        assertEquals("amount", emphasized.cellContexts[1].columnId)
        assertEquals("value", emphasized.cellContexts[1].columnRole)
        assertFalse(emphasized.cellContexts[0].hasRowNumber)
        assertNull(ordinary.cellStyles[1].weight)
        assertNull(ordinary.cellStyles[1].fill)
        assertEquals(StyleTone.ACCENT, ordinary.cellStyles[1].tone)
        assertEquals("8", result.decimal("emphasized").toPlainString())
    }

    @Test
    fun `malformed selectors report their stable layout diagnostic`() {
        listOf(
            "{}", "{:all false}", "{:all true :class :emphasis}", "{:depth -1}",
            "{:height 1.5}", "{:indent \"2\"}", "{:nth-child :third}",
            "{:has-row-number :true}", "{:column 4}", "{:unknown :value}",
        ).forEach { selector ->
            val error = assertFailsWith<MantraException>(selector) {
                layout("(layout t/invalid {} (style $selector {:weight :bold}))")
            }
            assertTrue(error.diagnostics.any { it.code == "MANTRA-LAYOUT-SELECTOR" }, selector)
        }
    }

    @Test
    fun `all presets retain values while applying locale sign and zero display`() {
        val result =
            calculate(
                """
            (schema t/formats {}
              (section schedule "Schedule" {:display :schedule}
                (line increase "Increase" 1234.5)
                (line decrease "Decrease" 12.5 {:op :minus})
                (line zero "Zero" 0)
                (total net "Net")))
        """,
            )
        val expected = mapOf(
            "de-staffel-4" to listOf("1.234,50", "12,50", "1.222,00"),
            "de-staffel-3" to listOf("1.234,50", "12,50", "1.222,00"),
            "ifrs-schedule" to listOf("1,235", "(13)", "1,222"),
        )
        Presets.ALL.forEach { (name, preset) ->
            val table = Render.paper(result, preset).tables.single { it.id == "schedule" }
            val amount = table.columns.indexOfFirst { it.content == ColumnContent.Main }
            val values = listOf("increase", "decrease", "net").map { id ->
                table.rows.single { it.nodeId == id }.cells[amount]
            }
            assertEquals(expected.getValue(name), values, name)
            assertEquals(name != "ifrs-schedule", table.rows.none { it.nodeId == "zero" }, name)
            if (name == "ifrs-schedule") assertEquals("–", table.rows.single { it.nodeId == "zero" }.cells[amount])
            assertEquals("1222.0", result.decimal("net").toPlainString())
        }
    }

    @Test
    fun `layout overrides leave shared presets unchanged and unknown presets fail`() {
        val original = Presets.IFRS_SCHEDULE
        val changed = layout("(layout t/override {:preset :ifrs-schedule :precision 3 :signed false :hide-zero true})")
        assertEquals(3, changed.number.precision)
        assertFalse(changed.signedValues)
        assertTrue(changed.hideZero)
        assertEquals(0, original.number.precision)
        assertTrue(original.signedValues)
        assertFalse(original.hideZero)
        assertEquals(original, Presets.of("ifrs-schedule"))
        val error = assertFailsWith<MantraException> { layout("(layout t/invalid {:preset :missing})") }
        assertTrue(error.diagnostics.any { it.code == "MANTRA-LAYOUT-PRESET" })
    }

    @Test
    fun `row numbers skip hidden rows and headings and restart only in table mode`() {
        val result =
            calculate(
                """
            (schema t/numbering {}
              (section first "First"
                (line visible "Visible" 5)
                (line hidden-zero "Hidden zero" 0)
                (section detail "Detail" (line detail-value "Detail value" 2)))
              (section second "Second" (line last-value "Last value" 8)))
        """,
            )
        mapOf(
            "global" to listOf(listOf("1", "2"), listOf("3")),
            "table" to listOf(listOf("1", "2"), listOf("1")),
        ).forEach { (mode, expected) ->
            val spec =
                layout(
                    """
                (layout t/numbering-paper {:preset :de-staffel-3 :row-numbers :$mode}
                  (table first)
                  (table second))
            """,
                )
            val paper = Render.paper(result, spec)
            assertEquals(
                expected,
                paper.tables.map { table ->
                    val number = table.columns.indexOfFirst { it.content == ColumnContent.RowNumber }
                    assertEquals(1, table.columns.count { it.content == ColumnContent.RowNumber })
                    assertTrue(table.rows.none { it.nodeId == "hidden-zero" })
                    table.rows.filter { it.kind == RowKind.HEADING }.forEach { assertEquals("", it.cells[number]) }
                    table.rows.map { it.cells[number] }.filter(String::isNotEmpty)
                },
            )
            val anchors = paper.tables.flatMap { it.rows }.mapNotNull { it.anchor }
            assertEquals(anchors.size, anchors.toSet().size)
        }
    }

    @Test
    fun `explicit row number column is not duplicated when numbering is enabled`() {
        val result = calculate("(schema t/numbers {} (section values \"Values\" (line value \"Value\" 1)))")
        val spec =
            layout(
                """
            (layout t/numbers-paper {:preset :ifrs-schedule :row-numbers :global}
              (columns :tiered :row-number :label :value)
              (table values))
        """,
            )
        val table = Render.paper(result, spec).tables.single()
        assertEquals(1, table.columns.count { it.content == ColumnContent.RowNumber })
        assertEquals("1", table.rows.single().cells.first())
    }
}
