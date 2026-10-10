package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaseRowsReaderTest {
    private fun read(text: String) = Mantra.loadCase(SourceText("case.mantra", text))

    @Test
    fun `rows expand in authored row and column order without changing literal values`() {
        val compact = read(
            """(case demo (inputs {:items (rows [:id :amount :enabled :note :nested]
              [:B 1.2300 false nil {:keys [:X "text"]}]
              [:A 0 true "" {}]) :empty (rows [:amount])}))""",
        )
        val ordinary = read(
            """(case demo (inputs {:items [
              {:id :B :amount 1.2300 :enabled false :note nil :nested {:keys [:X "text"]}}
              {:id :A :amount 0 :enabled true :note "" :nested {}}] :empty []}))""",
        )
        assertEquals(ordinary.inputs, compact.inputs)
        val first = ((compact.inputs.getValue("items") as Value.Vec).items.first() as Value.MapV).entries
        assertEquals(listOf("id", "amount", "enabled", "note", "nested"), first.keys.map { (it as Value.Kw).name })
        assertEquals("1.2300", (first.getValue(Value.Kw("amount")) as Value.Num).value.toPlainString())
        assertEquals(Value.Nil, first[Value.Kw("note")])
        assertFalse(first.containsKey(Value.Kw("missing")))
        assertEquals(Value.Vec(emptyList()), compact.inputs["empty"])
    }

    @Test
    fun `compact cells and rows retain their real source spans including unicode`() {
        val text = """(case demo {:title "🔎 Example"}
  (inputs {:items (rows [:id :amount :note]
    [:A 12.50 nil]
    [:B -4.00 "text"])}))"""
        val case = read(text)
        val cells = case.inputCells.getValue("items")
        val amount = cells.single { it.rowIndex == 1 && it.column == "amount" }.location
        assertEquals(4, amount.line)
        assertEquals(9, amount.column)
        assertEquals(text.indexOf("-4.00"), amount.startOffset)
        assertEquals("-4.00", text.substring(checkNotNull(amount.startOffset), checkNotNull(amount.endOffset)))
        val nil = cells.single { it.rowIndex == 0 && it.column == "note" }.location
        assertEquals("nil", text.substring(checkNotNull(nil.startOffset), checkNotNull(nil.endOffset)))
        val row = cells.single { it.rowIndex == 0 && it.column == null }.location
        assertEquals("[:A 12.50 nil]", text.substring(checkNotNull(row.startOffset), checkNotNull(row.endOffset)))
    }

    @Test
    fun `malformed compact literals reject headers row lengths and executable cells`() {
        val malformed = listOf(
            "(rows)" to "MANTRA-CASE-ROWS-HEADER",
            "(rows [])" to "MANTRA-CASE-ROWS-HEADER",
            "(rows :amount [1])" to "MANTRA-CASE-ROWS-HEADER",
            "(rows [amount] [1])" to "MANTRA-CASE-ROWS-COLUMN",
            "(rows [:amount :amount] [1 2])" to "MANTRA-CASE-ROWS-COLUMN",
            "(rows [:amount] [])" to "MANTRA-CASE-ROWS-ROW",
            "(rows [:amount] [1 2])" to "MANTRA-CASE-ROWS-ROW",
            "(rows [:amount] {:amount 1})" to "MANTRA-CASE-ROWS-ROW",
            "(rows [:amount] [(+ 1 2)])" to "MANTRA-READ-LITERAL",
            "(rows [:amount] [source])" to "MANTRA-READ-LITERAL",
        )
        malformed.forEach { (literal, code) ->
            val error = assertFailsWith<MantraException>(literal) {
                read("(case demo (inputs {:items $literal}))")
            }
            assertTrue(error.diagnostics.any { it.code == code }, error.message)
        }
    }

    @Test
    fun `header and row shape diagnostics point to offending authored forms`() {
        val text = "(case demo (inputs {:items (rows [:id :id] [:A :B])}))"
        val duplicate = assertFailsWith<MantraException> { read(text) }.diagnostics.single()
        assertEquals("MANTRA-CASE-ROWS-COLUMN", duplicate.code)
        assertEquals(text.indexOf(":id", text.indexOf(":id") + 1), duplicate.location?.startOffset)
        assertEquals("items", duplicate.nodeId)
        val invalid = "(case demo (inputs {:items (rows [:id :amount] [:A])}))"
        val row = assertFailsWith<MantraException> { read(invalid) }.diagnostics.single()
        assertEquals(invalid.indexOf("[:A]"), row.location?.startOffset)
        assertEquals(0, row.rowIndex)
    }

    @Test
    fun `rows are unavailable in parameters and nested literal positions`() {
        listOf(
            "(case demo (params {:rate (rows [:amount] [1])}))",
            "(case demo (inputs {:items [(rows [:amount] [1])]}))",
            "(case demo (inputs {:items {:A (rows [:amount] [1])}}))",
            "(case demo (inputs {:items (rows [:amount] [(rows [:nested] [1])])}))",
        ).forEach { text ->
            val sink = DiagnosticSink()
            CaseReader.read(SourceText("case.mantra", text), sink)
            assertTrue(sink.all.any { it.code == "MANTRA-READ-LITERAL" }, text)
        }
    }

    @Test
    fun `ordinary table validation uses the authored compact cell location`() {
        val schema = Mantra.loadSchema(
            SourceText("schema.mantra", "(schema test/rows (input items :table {:columns {:amount :decimal}}))"),
            SourceResolver { _, _ -> null },
        )
        val text = "(case demo (inputs {:items (rows [:amount] [\"bad\"])}))"
        val result = Mantra.calculate(schema, read(text))
        val diagnostic = result.diagnostics.single { it.rowIndex == 0 && it.column == "amount" }
        assertEquals(text.indexOf("\"bad\""), diagnostic.location?.startOffset)
        assertFalse(result.succeeded)
    }
}
