package com.xqiou.mantra.apps

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent facts and Decimal calculations are documented in verify_expected.py. */
class IncomeTaxReconciliationTest {
    private val dir = Path.of("apps/ifrs-income-taxes")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val demo = Mantra.loadCase(dir.resolve("case-demo.mantra"))
    private val result = Mantra.calculate(schema, demo)

    private fun amount(expected: String, id: String, vararg coord: String) = assertEquals(
        0,
        BigDecimal(expected).compareTo(result.decimal(id, *coord)),
        "$id${coord.toList()}",
    )

    @Test
    fun `entity tax effects reconcile and the group rate weights accounting profit`() {
        assertTrue(result.succeeded)
        assertTrue(result.validationPassed)
        amount("60000", "expected-tax", "North")
        amount("16000", "expected-tax", "South")
        amount("5400", "tax-effect-total", "North")
        amount("-1600", "tax-effect-total", "South")
        amount("65400", "explained-tax", "North")
        amount("14400", "explained-tax", "South")
        val reconciliation = checkNotNull(result.node("tax-reconciliation").validations[listOf("North")])
        assertEquals(true, reconciliation.passed)
        val evidence = checkNotNull(reconciliation.reconciliation)
        assertEquals(0, BigDecimal("65400").compareTo(evidence.left))
        assertEquals(0, BigDecimal("65400").compareTo(evidence.right))
        assertEquals(0, BigDecimal("0.01").compareTo(evidence.tolerance))
        amount("79800", "group-tax")
        amount("0.249375", "group-effective-rate")
        assertEquals(
            0,
            BigDecimal("0.249375").compareTo(checkNotNull(result.node("effective-tax-rate").crossTotal())),
        )
        // Unweighted entity rates would produce 0.22625, which is not the group's rate.
        assertTrue(result.node("effective-tax-rate").crossTotal() != BigDecimal("0.22625"))
    }

    @Test
    fun `a supplied loss tax benefit has an exact positive effective rate`() {
        val loss = Mantra.calculate(schema, Mantra.loadCase(dir.resolve("case-loss.mantra")))
        assertTrue(loss.succeeded)
        assertTrue(loss.validationPassed)
        assertEquals(0, BigDecimal("-25000").compareTo(loss.decimal("expected-tax", "North")))
        assertEquals(0, BigDecimal("-23000").compareTo(loss.decimal("actual-tax", "North")))
        assertEquals(0, BigDecimal("0.23").compareTo(loss.decimal("group-effective-rate")))
    }

    @Test
    fun `zero profit keeps effective rates undefined and gives a business warning`() {
        val zero = Mantra.calculate(schema, Mantra.loadCase(dir.resolve("case-zero-profit.mantra")))
        assertTrue(zero.succeeded)
        assertTrue(zero.validationPassed)
        assertEquals(Value.Nil, zero.value("effective-tax-rate", "North"))
        assertEquals(Value.Nil, zero.value("group-effective-rate"))
        assertEquals(null, zero.node("effective-tax-rate").crossTotal())
        val warning = zero.diagnostics.single()
        assertEquals("MANTRA-AGGREGATE-ZERO-DENOMINATOR", warning.code)
        assertEquals(DiagnosticCategory.BUSINESS, warning.category)
        assertEquals(Severity.WARNING, warning.severity)
    }

    @Test
    fun `reconciliation and a missing reason remain visible without changing computed tax`() {
        val failed = Mantra.calculate(schema, Mantra.loadCase(dir.resolve("case-unreconciled.mantra")))
        assertTrue(failed.succeeded)
        assertFalse(failed.validationPassed)
        assertEquals(0, BigDecimal("65400").compareTo(failed.decimal("explained-tax", "North")))
        assertEquals(0, BigDecimal("-0.02").compareTo(failed.decimal("tax-reconciliation", "North")))
        assertEquals(3, failed.diagnostics.size)
        val reason = failed.diagnostics.single { it.code == "MANTRA-INPUT-REQUIRED" }
        assertEquals("tax-adjustments", reason.nodeId)
        assertEquals(0, reason.rowIndex)
        assertEquals("reason", reason.column)
        assertEquals(2, failed.diagnostics.count { it.code == "MANTRA-RECONCILE-FAILED" })
    }

    @Test
    fun `tax reconciliation includes the tolerance boundary and fails immediately outside it`() {
        fun changed(booked: String) = Mantra.calculate(
            schema,
            demo.copy(
                inputs = demo.inputs + (
                    "tax-entities" to Value.Vec(
                        (demo.inputs.getValue("tax-entities") as Value.Vec).items.mapIndexed { index, row ->
                            if (index == 0) {
                                Value.MapV((row as Value.MapV).entries + (Value.Kw("booked-tax") to Value.num(booked)))
                            } else {
                                row
                            }
                        },
                    )
                    ),
            ),
        )
        assertTrue(changed("65400.01").validationPassed)
        val outside = changed("65400.01000001")
        assertTrue(outside.succeeded)
        assertFalse(outside.validationPassed)
        assertEquals(2, outside.diagnostics.count { it.code == "MANTRA-RECONCILE-FAILED" })
    }

    @Test
    fun `parameter precision is reported without changing exact fixture tax amounts`() {
        val parameters = Mantra.loadParameters(dir.resolve("params-precision.mantra"))
        val precise = Mantra.calculate(schema, demo, listOf(parameters))
        assertTrue(precise.succeeded)
        assertTrue(precise.validationPassed)
        assertEquals("ifrs.ias12/precision", precise.node("expected-tax-scale").parameterSource)
        assertEquals(0, result.decimal("explained-tax", "North").compareTo(precise.decimal("explained-tax", "North")))
    }

    @Test
    fun `editing booked tax recalculates the reconciliation and effective rates in Excel`() {
        ExcelExport.workbook(result, Render.loadLayout(dir.resolve("layout.mantra"))).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val input = CellReference(checkNotNull(export.tableAddress("tax-entities", 0, "booked-tax")))
            export.workbook.getSheet(input.sheetName).getRow(input.row).getCell(input.col.toInt()).setCellValue(65500.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.clearAllCachedResultValues()
            fun evaluated(id: String, coord: List<String> = emptyList()): Double {
                val address = CellReference(checkNotNull(export.address(id, coord)))
                val cell = export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
                val evaluated = evaluator.evaluate(cell)
                assertEquals(CellType.NUMERIC, evaluated.cellType)
                return evaluated.numberValue
            }
            assertEquals(-100.0, evaluated("tax-reconciliation", listOf("North")), 1e-9)
            assertEquals(79900.0, evaluated("group-tax"), 1e-9)
            assertEquals(-100.0, evaluated("group-tax-reconciliation"), 1e-9)
            assertEquals(0.272917, evaluated("effective-tax-rate", listOf("North")), 1e-9)
            assertEquals(0.249688, evaluated("group-effective-rate"), 1e-9)
        }
    }
}
