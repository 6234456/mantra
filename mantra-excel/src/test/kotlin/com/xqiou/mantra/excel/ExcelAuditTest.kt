package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelAuditTest {
    private val schema = Mantra.loadSchema(
        SourceText(
            "snapshot.mantra",
            """
            (schema t/snapshot {}
              (param multiplier 1.5)
              (input base :decimal)
              (input description :text {:optional true})
              (input facts :table {:columns {:amount :decimal}})
              (section main "Main"
                (line amount "Amount" (+ base (* multiplier (sum (map (fn [r] r.amount) facts)))))))
            """.trimIndent(),
        ),
        SourceResolver { _, _ -> null },
    )

    private fun result(rows: Int = 2) = Mantra.calculateForAudit(
        schema,
        Mantra.loadCase(
            SourceText(
                "case.mantra",
                "(case c (inputs {:base 2 :description \"A\" :facts [" +
                    (1..rows).joinToString(" ") { "{:amount $it}" } + "]}))",
            ),
        ),
    )

    private fun cell(export: ExcelWorkbook, address: String): XSSFCell {
        val reference = CellReference(address)
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun status(export: ExcelWorkbook): String {
        export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
        return cell(export, assertNotNull(export.auditSnapshotStatusAddress())).stringCellValue
    }

    @Test
    fun `XLSX audit retains the paper trace and becomes outdated after scalar or parameter edits`() {
        val result = result()
        val working = Render.paper(result, Presets.IFRS_SCHEDULE).audit.single().working
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty())
            assertTrue(export.report.evaluationErrors.isEmpty())
            val snapshot = assertNotNull(export.workbook.getSheet("Audit snapshot"))
            assertTrue(snapshot.protect)
            fun hasOriginalTrace() = snapshot.any { row ->
                row.any { it.cellType == CellType.STRING && it.stringCellValue == working }
            }
            assertTrue(hasOriginalTrace())
            assertEquals("current", status(export))
            val base = cell(export, assertNotNull(export.address("base")))
            base.setCellValue(3.0)
            assertEquals("outdated", status(export))
            assertEquals(7.5, cell(export, assertNotNull(export.address("amount"))).numericCellValue)
            assertTrue(hasOriginalTrace())
            base.setCellValue(2.0)
            assertEquals("current", status(export))
            cell(export, assertNotNull(export.address("multiplier"))).setCellValue(2.0)
            assertEquals("outdated", status(export))
            assertEquals(8.0, cell(export, assertNotNull(export.address("amount"))).numericCellValue)
        }
    }

    @Test
    fun `one thousand table rows recalculate stale status without long variadic formulas or fallbacks`() {
        ExcelExport.workbook(result(1_000), Presets.IFRS_SCHEDULE, ExcelOptions(useNames = false)).use { export ->
            assertTrue(export.report.fallbacks.isEmpty())
            assertTrue(export.report.evaluationErrors.isEmpty())
            assertEquals("current", status(export))
            cell(export, assertNotNull(export.tableAddress("facts", 999, "amount"))).setCellValue(1_100.0)
            assertEquals("outdated", status(export))
            assertEquals(750_902.0, cell(export, assertNotNull(export.address("amount"))).numericCellValue)
            val snapshot = assertNotNull(export.workbook.getSheet("Audit snapshot"))
            val formulas = snapshot.flatMap { row -> row.filter { it.cellType == CellType.FORMULA } }
            assertTrue(formulas.size >= 1_003)
            assertTrue(formulas.all { it.cellFormula.length <= 8_192 })
            val staleFormula = cell(export, assertNotNull(export.auditSnapshotStatusAddress())).cellFormula
            assertFalse(staleFormula.contains("SUM(IF("))
        }
    }

    @Test
    fun `snapshot comparison distinguishes case sensitive text blank zero and presence edits`() {
        ExcelExport.workbook(result(), Presets.IFRS_SCHEDULE).use { export ->
            val description = cell(export, assertNotNull(export.address("description")))
            description.setCellValue("a")
            assertEquals("outdated", status(export))
            description.setCellValue("A")
            assertEquals("current", status(export))
            val base = cell(export, assertNotNull(export.address("base")))
            base.setBlank()
            assertEquals("outdated", status(export))
            base.setCellValue(2.0)
            assertEquals("current", status(export))
        }
        val validation = Mantra.loadSchema(
            SourceText(
                "required.mantra",
                """
                (schema t/required {}
                  (input base :decimal {:required-when true})
                  (section main "Main" (line amount "Amount" (+ base 1))))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val result = Mantra.calculateForAudit(
            validation,
            Mantra.loadCase(SourceText("case.mantra", "(case c (inputs {:base 0}))")),
        )
        assertEquals(Value.num(0), result.value("base"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertEquals("current", status(export))
            val checks = assertNotNull(export.workbook.getSheet("Checks"))
            val presenceHeader = checks.first { row ->
                row.any { it.cellType == CellType.STRING && it.stringCellValue == "Provided" }
            }
            val providedColumn = presenceHeader.first {
                it.cellType == CellType.STRING && it.stringCellValue == "Provided"
            }.columnIndex
            val provided = checks.filter { it.rowNum > presenceHeader.rowNum }.firstNotNullOf { row ->
                row.getCell(providedColumn)?.takeIf { it.cellType == CellType.BOOLEAN }
            }
            provided.setCellValue(false)
            assertEquals("outdated", status(export))
        }
    }

    @Test
    fun `weighted aggregation snapshot is retained when its source is edited`() {
        val model = Mantra.loadSchema(
            SourceText(
                "ratio.mantra",
                """
                (schema t/ratio {}
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per member})
                  (input units :decimal {:per member})
                  (section main "Main" {:per member}
                    (line ratio "Ratio" (decimal/divide charge units 8)
                      {:op :info :aggregate {:ratio [charge units] :round [8 :half-up]}})))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val result = Mantra.calculateForAudit(
            model,
            Mantra.loadCase(
                SourceText(
                    "case.mantra",
                    "(case c (inputs " +
                        "{:charge {:A 20 :B 90} :units {:A 100 :B 300}}))",
                ),
            ),
        )
        val original = Render.paper(result, Presets.IFRS_SCHEDULE).audit.single { it.nodeId == "aggregate.ratio" }
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty())
            assertTrue(export.report.evaluationErrors.isEmpty())
            val audit = assertNotNull(export.workbook.getSheet("Audit snapshot"))
            fun originalIsPresent() = audit.any { row ->
                row.any { it.cellType == CellType.STRING && it.stringCellValue == original.working }
            }
            assertTrue(originalIsPresent())
            assertEquals("current", status(export))
            cell(export, assertNotNull(export.address("charge", listOf("A")))).setCellValue(30.0)
            assertEquals("outdated", status(export))
            assertTrue(originalIsPresent())
        }
    }
}
