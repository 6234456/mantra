package com.xqiou.mantra.excel

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.data.CsvSource
import com.xqiou.mantra.core.data.JsonSource
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XlsxRegionSourceTest {
    private val schema = Mantra.loadSchema(
        SourceText(
            "schema.mantra",
            """
            (schema test/region
              (input facts :table {:columns {:id :keyword :amount :decimal :enabled :boolean
                :effective-date :date :note :text?}})
              (section main "Main"
                (line amount-total "Total" (sum (map (fn [row] row.amount) facts)))))
            """.trimIndent(),
        ),
        SourceResolver { _, _ -> null },
    )
    private val empty = Mantra.loadCase(SourceText("case.mantra", "(case sample)"))
    private val mapping = mapOf(
        "Item" to "id",
        "Amount" to "amount",
        "Enabled" to "enabled",
        "Effective date" to "effective-date",
        "Note" to "note",
    )

    private fun bytes(workbook: XSSFWorkbook) = ByteArrayOutputStream().also(workbook::write).toByteArray()

    private fun workbook(): XSSFWorkbook = XSSFWorkbook().apply {
        val sheet = createSheet("Facts")
        sheet.createRow(1).apply {
            mapping.keys.forEachIndexed { index, title -> createCell(index + 1).setCellValue(title) }
        }
        sheet.createRow(2).apply {
            createCell(1).setCellValue("A")
            createCell(2).setCellValue(0.0)
            createCell(3).setCellValue(false)
            createCell(4).setCellValue(DateUtil.getExcelDate(LocalDate.of(2026, 1, 2)))
        }
        sheet.createRow(3)
        sheet.createRow(4).apply {
            createCell(1).setCellValue("B")
            createCell(2).setCellValue(12.5)
            createCell(3).setCellValue(true)
            createCell(4).setCellValue(DateUtil.getExcelDate(LocalDate.of(2026, 2, 3)))
            createCell(5).setCellValue("second")
        }
    }

    private fun read(workbook: XSSFWorkbook, sink: DiagnosticSink = DiagnosticSink()): Map<String, Value> =
        XlsxRegionSource(Path.of("absent.xlsx"), "facts", "Facts", "B2:F5", mapping, bytes(workbook)).read(schema, sink)

    @Test fun `unnamed Excel CSV JSON and an independent literal case have identical typed facts and results`() {
        val expected = Mantra.loadCase(
            SourceText(
                "expected.mantra",
                """(case expected (inputs {:facts [
                  {:id :A :amount 0 :enabled false :effective-date "2026-01-02"}
                  {:id :B :amount 12.5 :enabled true :effective-date "2026-02-03" :note "second"}]}))""",
            ),
        )
        val original = Mantra.calculate(schema, expected)
        assertTrue(original.succeeded, original.diagnostics.toString())
        val csv = """Item;Amount;Enabled;Effective date;Note
            |A;0;false;2026-01-02;
            |B;12.5;true;2026-02-03;second
        """.trimMargin()
        val json = """{"facts":[
            {"id":"A","amount":0,"enabled":false,"effective-date":"2026-01-02"},
            {"id":"B","amount":12.5,"enabled":true,"effective-date":"2026-02-03","note":"second"}]}"""
        workbook().use { wb ->
            assertTrue(wb.allNames.isEmpty())
            val sources = listOf(
                XlsxRegionSource(Path.of("missing.xlsx"), "facts", "Facts", "B2:F5", mapping, bytes(wb)),
                CsvSource(
                    Path.of("missing.csv"),
                    input = "facts",
                    decimal = '.',
                    grouping = null,
                    columns = mapping,
                    capturedText = csv,
                ),
                JsonSource(Path.of("missing.json"), capturedText = json),
            )
            sources.forEach { source ->
                val sink = DiagnosticSink()
                val imported = source.read(schema, sink)
                assertFalse(sink.hasErrors, sink.all.toString())
                val result = Mantra.calculate(schema, empty.copy(inputs = imported))
                assertTrue(result.succeeded, result.diagnostics.toString())
                assertEquals(original.value("facts"), result.value("facts"), source.description)
                assertEquals(Value.num("12.5"), result.value("amount-total"), source.description)
                assertEquals(
                    original.diagnostics.map { it.code to it.category },
                    result.diagnostics.map {
                        it.code to
                            it.category
                    },
                )
            }
            val rows = (read(wb).getValue("facts") as Value.Vec).items
            assertEquals(2, rows.size)
            val first = rows[0] as Value.MapV
            assertEquals(Value.num(0), first.entries[Value.Kw("amount")])
            assertEquals(Value.Bool(false), first.entries[Value.Kw("enabled")])
            assertFalse(Value.Kw("note") in first.entries)
            wb.getSheet("Facts").getRow(2).getCell(2).setBlank()
            val missing = ((read(wb).getValue("facts") as Value.Vec).items[0] as Value.MapV)
            assertFalse(Value.Kw("amount") in missing.entries)
        }
    }

    @Test fun `region type failures have the same stable engine diagnostic as CSV and JSON`() {
        workbook().use { wb ->
            wb.getSheet("Facts").getRow(2).getCell(2).setCellValue("not a number")
            val sources = listOf(
                XlsxRegionSource(Path.of("missing.xlsx"), "facts", "Facts", "B2:F5", mapping, bytes(wb)),
                CsvSource(
                    Path.of("missing.csv"),
                    input = "facts",
                    decimal = '.',
                    grouping = null,
                    columns = mapping,
                    capturedText = listOf(
                        "Item;Amount;Enabled;Effective date;Note",
                        "A;not a number;false;2026-01-02;",
                        "B;12.5;true;2026-02-03;second",
                    ).joinToString("\n"),
                ),
                JsonSource(
                    Path.of("missing.json"),
                    capturedText = """{"facts":[
                    {"id":"A","amount":"not a number","enabled":false,"effective-date":"2026-01-02"},
                    {"id":"B","amount":12.5,"enabled":true,"effective-date":"2026-02-03","note":"second"}]}""",
                ),
            )
            sources.forEach { source ->
                val sink = DiagnosticSink()
                val result = Mantra.calculate(schema, empty.copy(inputs = source.read(schema, sink)))
                assertFalse(sink.hasErrors)
                assertFalse(result.succeeded)
                val finding = result.diagnostics.single { it.code == "MANTRA-INPUT-TYPE" }
                assertEquals("facts", finding.nodeId)
                assertEquals(0, finding.rowIndex)
                assertEquals("amount", finding.column)
            }
        }
    }

    @Test fun `default header mapping accepts absolute ranges and rejects normalized target collisions`() {
        workbook().use { wb ->
            val header = wb.getSheet("Facts").getRow(1)
            listOf("id", "amount", "enabled", "Effective_Date", "note").forEachIndexed { index, name ->
                header.getCell(index + 1).setCellValue(name)
            }
            val sink = DiagnosticSink()
            val values = XlsxRegionSource(
                Path.of("missing.xlsx"),
                "facts",
                "Facts",
                "\$B\$2:\$F\$5",
                capturedBytes = bytes(wb),
            ).read(schema, sink)
            assertFalse(sink.hasErrors, sink.all.toString())
            assertEquals(2, (values.getValue("facts") as Value.Vec).items.size)
            header.getCell(3).setCellValue("Amount")
            val duplicate = DiagnosticSink()
            assertTrue(
                XlsxRegionSource(
                    Path.of("missing.xlsx"),
                    "facts",
                    "Facts",
                    "B2:F5",
                    capturedBytes = bytes(wb),
                ).read(schema, duplicate).isEmpty(),
            )
            assertTrue(duplicate.all.single().message.contains("duplicate target"))
        }
    }

    @Test fun `formula caches are snapshots and missing or error caches never become zero`() {
        workbook().use { wb ->
            val cell = wb.getSheet("Facts").getRow(4).getCell(2)
            cell.cellFormula = "1+2"
            cell.ctCell.v = "7.5"
            assertEquals(
                Value.num("7.5"),
                ((read(wb).getValue("facts") as Value.Vec).items[1] as Value.MapV).entries[Value.Kw("amount")],
            )
            cell.ctCell.unsetV()
            val missing = DiagnosticSink()
            assertTrue(read(wb, missing).isEmpty())
            assertEquals("MANTRA-DATA-XLSX-CELL", missing.all.single().code)
            assertTrue(missing.all.single().message.contains("no stored result"))
            cell.setCellErrorValue(FormulaError.DIV0.code)
            val cachedError = DiagnosticSink()
            assertTrue(read(wb, cachedError).isEmpty())
            assertTrue(cachedError.all.single().message.contains("#DIV/0!"))
            assertEquals(1, cachedError.all.single().rowIndex)
            assertEquals("amount", cachedError.all.single().column)
            assertTrue(cachedError.all.single().message.contains("Facts!C5"))
        }
    }

    @Test fun `ambiguous headers mappings and merged geometry fail without a partial table`() {
        workbook().use { wb ->
            val selected = wb.getSheet("Facts")
            selected.getRow(1).getCell(2).setCellValue("Item")
            val duplicate = DiagnosticSink()
            assertTrue(read(wb, duplicate).isEmpty())
            assertEquals("MANTRA-DATA-XLSX-REGION", duplicate.all.single().code)
            selected.getRow(1).getCell(2).setCellValue("Amount")
            selected.addMergedRegion(CellRangeAddress(2, 2, 1, 2))
            val merged = DiagnosticSink()
            assertTrue(read(wb, merged).isEmpty())
            assertTrue(merged.all.single().message.contains("Merged"))
            selected.removeMergedRegion(0)
            for (badMapping in listOf(
                mapOf("Absent" to "id"),
                mapOf("Item" to "unknown"),
                mapOf("Item" to "id", "Amount" to "id"),
            )) {
                val sink = DiagnosticSink()
                assertTrue(
                    XlsxRegionSource(
                        Path.of("missing.xlsx"),
                        "facts",
                        "Facts",
                        "B2:F5",
                        badMapping,
                        bytes(wb),
                    ).read(schema, sink).isEmpty(),
                )
                assertEquals("MANTRA-DATA-XLSX-REGION", sink.all.single().code)
            }
        }
    }

    @Test fun `local range boundaries and cell budgets are checked before opening any source`() {
        for (range in listOf("Facts!A1:B2", "A1:B2,C1:D2", "A:B", "B2:A1", "A0:B2", "A1:XFE2", "AAAAAA1:BBBBBB2")) {
            val sink = DiagnosticSink()
            assertTrue(XlsxRegionSource(Path.of("missing.xlsx"), "facts", "Facts", range).read(schema, sink).isEmpty())
            assertEquals("MANTRA-DATA-XLSX-REGION", sink.all.single().code)
        }
        val limited = DiagnosticSink()
        assertTrue(
            XlsxRegionSource(Path.of("missing.xlsx"), "facts", "Facts", "A1:Z10000").read(schema, limited).isEmpty(),
        )
        assertEquals("MANTRA-DATA-XLSX-LIMIT", limited.all.single().code)
    }

    @Test fun `date serials respect the workbook system and reject phantom fractional or out of range dates`() {
        workbook().use { wb ->
            for (bad in listOf(60.0, 46000.5, 1e9)) {
                wb.getSheet("Facts").getRow(2).getCell(4).setCellValue(bad)
                val sink = DiagnosticSink()
                assertTrue(read(wb, sink).isEmpty())
                assertEquals("effective-date", sink.all.single().column)
            }
            (wb.ctWorkbook.workbookPr ?: wb.ctWorkbook.addNewWorkbookPr()).date1904 = true
            wb.getSheet("Facts").getRow(2).getCell(4).setCellValue(60.0)
            val sink = DiagnosticSink()
            val first = ((read(wb, sink).getValue("facts") as Value.Vec).items[0] as Value.MapV)
            assertFalse(sink.hasErrors)
            assertEquals(Value.Date(LocalDate.of(1904, 3, 1)), first.entries[Value.Kw("effective-date")])
        }
    }

    @Test fun `retained rows are charged before reading cells and cancellation propagates`() {
        workbook().use { wb ->
            var rows = 0
            val failure = CancellationException("stop")
            val thrown = assertFailsWith<CancellationException> {
                XlsxRegionSource(
                    Path.of("missing.xlsx"),
                    "facts",
                    "Facts",
                    "B2:F5",
                    mapping,
                    bytes(wb),
                    onRow = {
                        rows++
                        throw failure
                    },
                ).read(schema, DiagnosticSink())
            }
            assertEquals(failure, thrown)
            assertEquals(1, rows)
            var checks = 0
            assertFailsWith<CancellationException> {
                XlsxRegionSource(
                    Path.of("missing.xlsx"),
                    "facts",
                    "Facts",
                    "B2:F5",
                    mapping,
                    bytes(wb),
                    checkpoint = {
                        checks++
                        throw failure
                    },
                ).read(schema, DiagnosticSink())
            }
            assertEquals(1, checks)
        }
    }
}
