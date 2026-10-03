package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import org.apache.poi.ss.util.CellReference
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Synthetic SAP CO-style flow, checked independently against the amounts in the example README. */
class SapCoProductCostTest {
    private val dir = Path.of("apps/cost-accounting")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val case = Mantra.loadCase(dir.resolve("case-demo.mantra"))
    private val result = Mantra.calculate(schema, case)

    private fun amount(expected: String, id: String, vararg coord: String) {
        val actual = result.decimal(id, *coord)
        assertEquals(0, BigDecimal(expected).compareTo(actual), "$id${coord.toList()} = $actual")
    }

    @Test
    fun `collects cost elements and calculates actual versus standard product costs`() {
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        amount("12400", "direct-primary-total")
        amount("900", "primary-pool")
        amount("1800", "secondary-pool")
        amount("15100", "source-cost-total")

        mapOf("O100" to "150", "O101" to "450", "O200" to "300").forEach { (id, cost) ->
            amount(cost, "primary-allocated", id)
        }
        mapOf("O100" to "300", "O101" to "900", "O200" to "600").forEach { (id, cost) ->
            amount(cost, "secondary-allocated", id)
        }
        mapOf("O100" to "5450", "O101" to "4350", "O200" to "5300").forEach { (id, cost) ->
            amount(cost, "actual-order-cost", id)
        }

        amount("150", "product-quantity", "A")
        amount("9800", "product-actual-cost", "A")
        amount("9000", "product-standard-cost", "A")
        amount("65.3333", "actual-weighted-unit", "A")
        amount("60", "standard-weighted-unit", "A")
        amount("800", "product-variance", "A")
        amount("80", "product-quantity", "B")
        amount("5300", "product-actual-cost", "B")
        amount("4960", "product-standard-cost", "B")
        amount("66.25", "actual-weighted-unit", "B")
        amount("62", "standard-weighted-unit", "B")
        amount("340", "product-variance", "B")
        amount("0", "unassigned-cost")
        amount("0", "product-crossfoot")
        amount("1140", "total-variance")
        assertEquals(null, result.node("actual-weighted-unit").crossTotal())
        assertEquals(null, result.node("actual-order-unit-cost").crossTotal())
        assertEquals(0, BigDecimal("15100").compareTo(result.node("product-actual-cost").crossTotal()))
    }

    @Test
    fun `changing the allocation base moves actual cost between products`() {
        val rows = (case.inputs.getValue("orders") as Value.Vec).items.map { row ->
            val entries = (row as Value.MapV).entries
            val id = (entries.getValue(Value.Kw("id")) as Value.Kw).name
            val base = if (id == "O200") 10L else 20L
            Value.MapV(entries + (Value.Kw("allocation-base") to Value.num(base)))
        }
        val changed = Mantra.calculate(schema, case.copy(inputs = case.inputs + ("orders" to Value.Vec(rows))))
        assertTrue(changed.succeeded, changed.diagnostics.joinToString("\n"))
        assertEquals(0, BigDecimal("10160").compareTo(changed.decimal("product-actual-cost", "A")))
        assertEquals(0, BigDecimal("4940").compareTo(changed.decimal("product-actual-cost", "B")))
        assertEquals(0, BigDecimal("15100").compareTo(changed.decimal("all-product-cost")))
        assertEquals(0, BigDecimal.ZERO.compareTo(changed.decimal("product-crossfoot")))
    }

    @Test
    fun `an order referring to an unknown product is rejected by the engine`() {
        val rows = (case.inputs.getValue("orders") as Value.Vec).items.map { row ->
            val entries = (row as Value.MapV).entries
            if (entries[Value.Kw("id")] == Value.Kw("O200")) {
                Value.MapV(entries + (Value.Kw("product-id") to Value.Kw("missing")))
            } else {
                row
            }
        }
        val invalid = Mantra.calculate(schema, case.copy(inputs = case.inputs + ("orders" to Value.Vec(rows))))
        assertFalse(invalid.succeeded)
        assertTrue(invalid.diagnostics.any { it.code == "MANTRA-DIMENSION-PARENT" })
    }

    @Test
    fun `a primary posting referring to an unknown order is rejected by the engine`() {
        val rows = (case.inputs.getValue("primary-postings") as Value.Vec).items.mapIndexed { index, row ->
            if (index ==
                0
            ) {
                Value.MapV((row as Value.MapV).entries + (Value.Kw("order-id") to Value.Kw("missing")))
            } else {
                row
            }
        }
        val invalid = Mantra.calculate(
            schema,
            case.copy(inputs = case.inputs + ("primary-postings" to Value.Vec(rows))),
        )
        assertFalse(invalid.succeeded)
        assertTrue(invalid.diagnostics.any { it.code == "MANTRA-INPUT-REFERENCE" && "missing" in it.message })
    }

    @Test
    fun `renders an order and product working paper without summing unit costs`() {
        val layout = Render.loadLayout(dir.resolve("layout.mantra"))
        val paper = Render.text(result, layout, includeAudit = true)
        val html = Render.html(result, layout)
        val out = Path.of("apps/cost-accounting/build/out").also(Files::createDirectories)
        Files.writeString(out.resolve("cost-accounting-demo.txt"), paper)
        Files.writeString(out.resolve("cost-accounting-demo.html"), html)
        assertTrue("Allocated secondary cost elements" in paper)
        assertTrue("Weighted average actual unit cost" in paper)
        assertTrue("65.3333" in paper)
        assertFalse("207.7500" in paper)
        assertFalse("131.5833" in paper)
        assertTrue("<table class=\"calc matrix\">" in html)
        assertTrue("u-variance" in html)
        assertTrue("font-weight:700;color:var(--accent);background:var(--heading)" in html)
    }

    @Test
    fun `Excel product rollup recalculates when an order changes product`() {
        val workbook = ExcelExport.workbook(result, Render.loadLayout(dir.resolve("layout.mantra")))
        assertEquals(emptyList(), workbook.report.fallbacks)
        assertEquals(emptyList(), workbook.report.evaluationErrors)
        val varianceAddress =
            CellReference(workbook.address("product-variance", listOf("A")) ?: fail("no variance cell"))
        val varianceCell = workbook.workbook.getSheet(
            varianceAddress.sheetName,
        ).getRow(varianceAddress.row).getCell(varianceAddress.col.toInt())
        assertTrue(workbook.workbook.getFontAt(varianceCell.cellStyle.fontIndex).bold)
        assertEquals(
            listOf(0xEE, 0xF3, 0xFA),
            varianceCell.cellStyle.fillForegroundColorColor.rgb.map {
                it.toInt() and
                    0xFF
            },
        )
        val relationCell =
            CellReference(workbook.recordAddress("order", "O200", "product-id") ?: fail("no product-id cell"))
        workbook.workbook.getSheet(
            relationCell.sheetName,
        ).getRow(relationCell.row).getCell(relationCell.col.toInt()).setCellValue("A")
        val actualCell =
            CellReference(workbook.address("product-actual-cost", listOf("A")) ?: fail("no product cost cell"))
        val cell = workbook.workbook.getSheet(
            actualCell.sheetName,
        ).getRow(actualCell.row).getCell(actualCell.col.toInt())
        val evaluator = workbook.workbook.creationHelper.createFormulaEvaluator()
        evaluator.clearAllCachedResultValues()
        assertEquals(15100.0, evaluator.evaluate(cell).numberValue, 0.000001)
    }

    @Test
    fun `Excel source costs follow editable posting rows`() {
        val workbook = ExcelExport.workbook(result, Render.loadLayout(dir.resolve("layout.mantra")))
        assertEquals(emptyList(), workbook.report.fallbacks)
        assertEquals(emptyList(), workbook.report.evaluationErrors)
        val postingAddress =
            CellReference(workbook.tableAddress("primary-postings", 0, "amount") ?: fail("no posting amount cell"))
        workbook.workbook.getSheet(postingAddress.sheetName).getRow(postingAddress.row)
            .getCell(postingAddress.col.toInt()).setCellValue(4100.0)
        val evaluator = workbook.workbook.creationHelper.createFormulaEvaluator()
        evaluator.clearAllCachedResultValues()
        fun evaluated(id: String, vararg coord: String): Double {
            val address = CellReference(workbook.address(id, coord.toList()) ?: fail("no $id cell"))
            val cell = workbook.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
            return evaluator.evaluate(cell).numberValue
        }
        assertEquals(12500.0, evaluated("direct-primary-total"), 0.000001)
        assertEquals(5550.0, evaluated("actual-order-cost", "O100"), 0.000001)
        assertEquals(9900.0, evaluated("product-actual-cost", "A"), 0.000001)
        assertEquals(15200.0, evaluated("all-product-cost"), 0.000001)
        assertEquals(0.0, evaluated("unassigned-cost"), 0.000001)
    }
}
