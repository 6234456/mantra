package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceText
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaseTextEditorTest {
    private fun source(name: String) = Files.readString(Path.of("apps/$name"))
    private fun read(text: String) = Mantra.loadCase(SourceText("case.mantra", text))

    @Test
    fun `input replacement preserves every byte outside its value across three domains`() {
        val cases = listOf(
            Triple("de-est/case-mustermann.mantra", "spenden", "450"),
            Triple("ifrs-impairment/case-demo.mantra", "allocable-corporate", "211"),
            Triple("cost-accounting/case-demo.mantra", "primary-postings", "4000"),
        )
        for ((name, input, original) in cases) {
            val text = source(name)
            val op = if (input == "primary-postings") {
                CaseTextEditor.Operation.UpdateRow(
                    input,
                    0,
                    Value.MapV(
                        linkedMapOf(
                            Value.Kw("cost-element") to Value.Text("500100"),
                            Value.Kw("expense-account") to Value.Text("500100"),
                            Value.Kw("mode") to Value.Kw("direct"),
                            Value.Kw("order-id") to Value.Kw("O100"),
                            Value.Kw("amount") to Value.num("4001"),
                        ),
                    ),
                )
            } else {
                CaseTextEditor.Operation.SetInput(input, Value.num(if (input == "spenden") "451" else "151"))
            }
            val edited = CaseTextEditor.apply(text, listOf(op))
            read(edited)
            val start = if (input ==
                "primary-postings"
            ) {
                text.indexOf("{:cost-element")
            } else {
                text.indexOf(":$input $original") + input.length +
                    2
            }
            val end = if (input == "primary-postings") text.indexOf('}', start) + 1 else start + original.length
            assertTrue(edited.startsWith(text.substring(0, start)))
            assertTrue(edited.endsWith(text.substring(end)))
            assertTrue(edited.contains(";;"))
        }
    }

    @Test
    fun `member maps and decimal scale round trip while comments stay intact`() {
        val text = source("de-est/case-mustermann.mantra")
        val changed = CaseTextEditor.apply(
            text,
            listOf(
                CaseTextEditor.Operation.SetInput("rv-beitraege", Value.Num(BigDecimal("12742.00")), listOf("A")),
                CaseTextEditor.Operation.SetInput("spenden", Value.num("451.50")),
                CaseTextEditor.Operation.ClearInput("gewinn-gewerbe", listOf("A")),
            ),
        )
        val parsed = read(changed)
        assertEquals(
            "12742.00",
            ((parsed.inputs["rv-beitraege"] as Value.MapV).entries[Value.Kw("A")] as Value.Num).value.toPlainString(),
        )
        assertEquals("451.50", (parsed.inputs["spenden"] as Value.Num).value.toPlainString())
        assertFalse(parsed.inputs.containsKey("gewinn-gewerbe"))
        assertTrue(changed.contains(";; Anlage G (Nebengewerbe, kein Gewerbesteuermessbetrag)"))
        assertTrue(changed.contains(":wk-nachgewiesen {:A 2340 :B 650}"))
    }

    @Test
    fun `table cell address updates only selected column`() {
        val original = source("de-est/case-mustermann.mantra")
        val changed = CaseTextEditor.apply(
            original,
            listOf(
                CaseTextEditor.Operation.SetCell("vermietungsobjekte", "leipzig", "mieten", Value.num("9700.00"), "id"),
            ),
        )
        val rows = read(changed).inputs["vermietungsobjekte"] as Value.Vec
        assertEquals(Value.num("9700.00"), (rows.items.single() as Value.MapV).entries[Value.Kw("mieten")])
        assertEquals(original.substringBefore(":mieten 9600"), changed.substringBefore(":mieten 9700.00"))
        assertEquals(original.substringAfter(":mieten 9600"), changed.substringAfter(":mieten 9700.00"))
    }

    @Test
    fun `parameters metadata extensions and bindings round trip`() {
        val original = source("de-est/case-mustermann.mantra")
        val edited = CaseTextEditor.apply(
            original,
            listOf(
                CaseTextEditor.Operation.SetParam("tarif-gfb", Value.num("12348")),
                CaseTextEditor.Operation.SetMeta("reviewed-by", "A \"B\"\\C"),
                CaseTextEditor.Operation.SetBindings(listOf("de.est/params-2026"), "de.est/steuerberechnung"),
                CaseTextEditor.Operation.AddExtension("weitere-sonderausgaben", "other", "Other", "(+ 1 2)"),
                CaseTextEditor.Operation.BindFormula("test-slot", "(+ 1 2)"),
            ),
        )
        val parsed = read(edited)
        assertEquals(Value.num("12348"), parsed.params["tarif-gfb"])
        assertEquals("A \"B\"\\C", parsed.text("reviewed-by"))
        assertEquals(Value.Vec(listOf(Value.Text("de.est/params-2026"))), parsed.meta["parameters"])
        assertEquals(2, parsed.extensions["weitere-sonderausgaben"]?.size)
        assertTrue(parsed.formulaBindings.containsKey("test-slot"))
        assertTrue(edited.contains(";; Benutzerdefinierte Zeile"))
        val undone = CaseTextEditor.apply(
            edited,
            listOf(
                CaseTextEditor.Operation.ResetParam("tarif-gfb"),
                CaseTextEditor.Operation.RemoveExtension("weitere-sonderausgaben", "other"),
                CaseTextEditor.Operation.UnbindFormula("test-slot"),
            ),
        )
        val after = read(undone)
        assertFalse(after.params.containsKey("tarif-gfb"))
        assertEquals(1, after.extensions["weitere-sonderausgaben"]?.size)
        assertFalse(after.formulaBindings.containsKey("test-slot"))
    }

    @Test
    fun `updating extension title and formula preserves every option and comment`() {
        val original = """(case sample {:schema "demo"}
  (extend custom
    ;; Keep this description.
    (line detailed "Original" (+ 1 2) ; formula note
      {:per person :when true :type :decimal :round [2 :floor] :op :minus :spread false})))"""
        val edited = CaseTextEditor.apply(
            original,
            listOf(
                CaseTextEditor.Operation.UpdateExtension("custom", "detailed", "Changed", "(+ 3 4)"),
            ),
        )
        assertEquals(original.replace("\"Original\"", "\"Changed\"").replace("(+ 1 2)", "(+ 3 4)"), edited)
        val line = read(edited).extensions.getValue("custom").single() as com.xqiou.mantra.core.model.LineItem
        assertEquals("Changed", line.label)
        assertEquals("(+ 3 4)", line.formula.source)
    }

    @Test
    fun `seeded edit sequences preserve existing comments and read back exact inputs`() {
        val random = Random(71)
        val initial = source("de-est/case-mustermann.mantra")
        repeat(25) {
            var text = initial
            var expected = read(initial).inputs.toMutableMap()
            repeat(12) {
                val id = listOf("spenden", "agb-aufwendungen", "handwerkerleistungen").random(random)
                val value = Value.Num(
                    BigDecimal("${random.nextInt(1, 999)}.${random.nextInt(0, 100).toString().padStart(2, '0')}"),
                )
                text = CaseTextEditor.apply(text, listOf(CaseTextEditor.Operation.SetInput(id, value)))
                expected[id] = value
            }
            assertEquals(expected, read(text).inputs)
            assertTrue(text.contains(";; Anlage N\n"))
            assertTrue(text.contains(";; Benutzerdefinierte Zeile im vom Schema vorgesehenen Slot."))
        }
    }

    @Test
    fun `moving table rows keeps row comments byte for byte`() {
        val text = """(case demo {:schema "demo" :layout "layout"}
  (inputs {:items [
    ;; first row
    {:id :A :amount 1}
    ;; second row
    {:id :B :amount 2}]}))"""
        val moved = CaseTextEditor.apply(text, listOf(CaseTextEditor.Operation.MoveRow("items", 0, 1)))
        val rows = (read(moved).inputs["items"] as Value.Vec).items
        assertEquals(Value.Kw("B"), (rows[0] as Value.MapV).entries[Value.Kw("id")])
        assertEquals(Value.Kw("A"), (rows[1] as Value.MapV).entries[Value.Kw("id")])
        assertTrue(moved.contains(";; first row"))
        assertTrue(moved.contains(";; second row"))
        assertEquals(text.substringBefore("["), moved.substringBefore("["))
        assertEquals(text.substringAfter("]"), moved.substringAfter("]"))
    }

    @Test
    fun `unicode before edited span does not shift source offsets`() {
        val text = "(case demo {:schema \"demo\" :title \"🔎 Case\"} (inputs {:amount 12.50}))"
        val changed = CaseTextEditor.apply(
            text,
            listOf(CaseTextEditor.Operation.SetInput("amount", Value.num("13.50"))),
        )
        assertEquals(text.replace("12.50", "13.50"), changed)
    }

    @Test
    fun `extension alias editing preserves authored head and fixed contribution`() {
        for (head in listOf("subtract", "info")) {
            val original = """(case demo (extend custom
              ($head example "Original" (+ 1 2) ; retain this note
                {:round [2 :floor] :when true})))"""
            val edited = CaseTextEditor.apply(
                original,
                listOf(CaseTextEditor.Operation.UpdateExtension("custom", "example", "Changed", "(+ 3 4)")),
            )
            assertEquals(original.replace("\"Original\"", "\"Changed\"").replace("(+ 1 2)", "(+ 3 4)"), edited)
            val before = read(original).extensions.getValue("custom").single() as com.xqiou.mantra.core.model.LineItem
            val after = read(edited).extensions.getValue("custom").single() as com.xqiou.mantra.core.model.LineItem
            assertEquals(before.op, after.op)
        }
    }
}
