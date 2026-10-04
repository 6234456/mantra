package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.api.RunFailure
import com.xqiou.mantra.core.api.RunFailureKind
import com.xqiou.mantra.core.api.RunStage
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XlsxImportHardeningTest {
    private fun schema(name: String, body: String) =
        Mantra.loadSchema(SourceText(name, body), SourceResolver { _, _ -> null })
    private val scalar = schema("source.mantra", "(schema test/xlsx-import (input imported :decimal))")
    private fun bytes(workbook: XSSFWorkbook): ByteArray = ByteArrayOutputStream().also(workbook::write).toByteArray()

    @Test fun `formula and literal error cells fail import without nil or implicit zero replacement`() {
        listOf(true, false).forEach { formula ->
            XSSFWorkbook().use { workbook ->
                val cell = workbook.createSheet("Facts").createRow(0).createCell(0)
                if (formula) cell.cellFormula = "1/0" else cell.setCellErrorValue(FormulaError.DIV0.code)
                workbook.createName().apply {
                    nameName = "imported"
                    refersToFormula = "'Facts'!\$A\$1"
                }
                val sink = DiagnosticSink()
                val values = XlsxSource(Path.of("error.xlsx"), bytes(workbook)).read(scalar, sink)
                assertTrue(values.isEmpty())
                assertTrue(sink.hasErrors)
                assertEquals("MANTRA-DATA-XLSX-CELL", sink.all.single().code)
                assertEquals("imported", sink.all.single().nodeId)
                assertTrue(sink.all.single().message.contains("#DIV/0!"))
                assertFailsWith<MantraException> { sink.throwIfErrors() }
            }
        }
    }

    @Test fun `unchanged public export imports every canonical two dimensional numeric false and date fact`() {
        val schema =
            schema(
                "two.mantra",
                """
            (schema test/two-inputs
              (input entity-records :table {:columns {:id :keyword :title :text}})
              (dimension entity {:from entity-records :key :id :title :title})
              (dimension timeline {:periods {:start "2026-01-01" :unit :month :count 2}})
              (input quantity :decimal {:per [timeline entity]})
              (input enabled :boolean {:per [entity timeline]})
              (input effective-date :date {:per [entity timeline]})
              (section facts "Facts" {:per [entity timeline] :display :schedule}
                (field quantity "Quantity" {:op :info})
                (field enabled "Enabled" {:op :info})
                (field effective-date "Date" {:op :info})))
                """.trimIndent(),
            )
        val case = Mantra.loadCase(
            SourceText(
                "two-case.mantra",
                """
            (case original (inputs
              {:entity-records [{:id :A :title "Alpha"} {:id :WORK-SITE :title "Work site"}]
               :quantity {:A {:P1 0 :P2 12.50} :WORK-SITE {:P1 7.25 :P2 2}}
               :enabled {:A {:P1 false :P2 true} :WORK-SITE {:P1 true :P2 false}}
               :effective-date {:A {:P1 "2026-01-02" :P2 "2026-02-03"}
                                :WORK-SITE {:P1 "2026-01-04" :P2 "2026-02-05"}}}))
                """.trimIndent(),
            ),
        )
        val original = Mantra.calculate(schema, case)
        assertTrue(original.succeeded)
        val layout = LayoutReader.read(
            SourceText(
                "layout.mantra",
                """
            (layout test/two {:hide-zero false}
              (table facts {:style :matrix :row-dimension entity} :label (members timeline) :cross-total))
                """.trimIndent(),
            ),
        )
        ExcelExport.workbook(original, layout).use { export ->
            assertTrue(export.report.fallbacks.isEmpty())
            val sink = DiagnosticSink()
            val imported = XlsxSource(Path.of("two.xlsx"), bytes(export.workbook)).read(schema, sink)
            assertFalse(sink.hasErrors, sink.all.toString())
            val restored = Mantra.calculate(schema, case.copy(id = "restored", inputs = imported))
            assertTrue(restored.succeeded, restored.diagnostics.toString())
            assertEquals(case.inputs["entity-records"], imported["entity-records"])
            for (input in listOf("quantity", "enabled", "effective-date")) {
                for (entity in listOf("A", "WORK-SITE")) {
                    for (period in listOf("P1", "P2")) {
                        val before = original.value(input, entity, period)
                        val after = restored.value(input, entity, period)
                        if (before is Value.Num &&
                            after is Value.Num
                        ) {
                            assertEquals(0, before.value.compareTo(after.value))
                        } else {
                            assertEquals(before, after)
                        }
                    }
                }
            }
            assertEquals(Value.Bool(false), restored.value("enabled", "A", "P1"))
            assertEquals(Value.num(0), restored.value("quantity", "A", "P1"))
        }
    }

    @Test fun `incomplete member coordinates and scalar ranges are explicit technical input name failures`() {
        val schema =
            schema(
                "axes.mantra",
                """
            (schema test/axes (dimension entity {:members [:A]})
              (dimension timeline {:periods {:start "2026-01-01" :unit :year :count 2}})
              (input imported :decimal {:per [entity timeline]}))
                """.trimIndent(),
            )
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Facts")
            sheet.createRow(0).createCell(0).setCellValue(5.0)
            sheet.getRow(0).createCell(1).setCellValue(6.0)
            workbook.createName().apply {
                nameName = "imported__A"
                refersToFormula = "'Facts'!\$A\$1"
            }
            val sink = DiagnosticSink()
            assertTrue(XlsxSource(Path.of("axes.xlsx"), bytes(workbook)).read(schema, sink).isEmpty())
            assertEquals("MANTRA-DATA-XLSX-NAME", sink.all.single().code)
            workbook.removeName(workbook.allNames.single())
            workbook.createName().apply {
                nameName = "imported"
                refersToFormula = "'Facts'!\$A\$1:\$B\$1"
            }
            val scalarSink = DiagnosticSink()
            assertTrue(XlsxSource(Path.of("range.xlsx"), bytes(workbook)).read(scalar, scalarSink).isEmpty())
            assertEquals("MANTRA-DATA-XLSX-NAME", scalarSink.all.single().code)
        }
    }

    @Test fun `ambiguous sanitized declared member keys fail rather than bind a different member`() {
        val schema =
            schema(
                "ambiguous.mantra",
                """
            (schema test/ambiguous-name (dimension entity {:members [:A-B :A_B]})
              (input imported :decimal {:per entity}))
                """.trimIndent(),
            )
        XSSFWorkbook().use { workbook ->
            workbook.createSheet("Facts").createRow(0).createCell(0).setCellValue(7.0)
            workbook.createName().apply {
                nameName = "imported__A_B"
                refersToFormula = "'Facts'!\$A\$1"
            }
            val sink = DiagnosticSink()
            assertTrue(XlsxSource(Path.of("ambiguous.xlsx"), bytes(workbook)).read(schema, sink).isEmpty())
            assertEquals("MANTRA-DATA-XLSX-NAME", sink.all.single().code)
            assertTrue(sink.all.single().message.contains("ambiguous"))
        }
    }

    @Test fun `table name enumeration cannot change member index or error row indices`() {
        val schema =
            schema(
                "table-order.mantra",
                """
            (schema test/table-order
              (input facts :table {:columns {:base-value :decimal :id :keyword :enabled :boolean}})
              (dimension entity {:from facts :key :id})
              (section ordered "Ordered" {:per entity}
                (line physical-position "Position" entity.index {:op :info})))
                """.trimIndent(),
            )
        XSSFWorkbook().use { workbook ->
            workbook.createSheet("Facts")
            workbook.createSheet("Later")
            val locations = mapOf("A" to ("Facts" to 10), "B" to ("Facts" to 20), "C" to ("Later" to 0))
            locations.forEach { (key, location) ->
                workbook.getSheet(location.first).createRow(location.second).apply {
                    createCell(0).setCellValue(if (key == "A") 0.0 else 5.0)
                    createCell(1).setCellValue(key)
                    createCell(2).setCellValue(key != "A")
                }
            }
            // Intentionally register names in reverse row order and a different column order.
            for (key in listOf("C", "B", "A")) {
                for ((name, column) in listOf(
                    "enabled" to "C",
                    "id" to "B",
                    "base_value" to "A",
                )) {
                    val location = locations.getValue(key)
                    workbook.createName().apply {
                        nameName = "facts__${key}__$name"
                        refersToFormula = "'${location.first}'!\$$column\$${location.second + 1}"
                    }
                }
            }
            val sink = DiagnosticSink()
            var rows = 0
            val imported = XlsxSource(Path.of("ordered.xlsx"), bytes(workbook), onRow = { rows++ }).read(schema, sink)
            assertFalse(sink.hasErrors, sink.all.toString())
            assertEquals(3, rows)
            val result = Mantra.calculate(
                schema,
                Mantra.loadCase(SourceText("case.mantra", "(case ordered)")).copy(inputs = imported),
            )
            assertTrue(result.succeeded, result.diagnostics.toString())
            assertEquals(Value.num(0), result.value("physical-position", "A"))
            assertEquals(Value.num(1), result.value("physical-position", "B"))
            assertEquals(Value.num(2), result.value("physical-position", "C"))
            for ((sheet, row) in locations.values) workbook.getSheet(sheet).getRow(row).getCell(0).cellFormula = "1/0"
            val errors = DiagnosticSink()
            var failedRows = 0
            assertTrue(
                XlsxSource(Path.of("errors.xlsx"), bytes(workbook), onRow = {
                    failedRows++
                }).read(schema, errors).isEmpty(),
            )
            assertEquals(3, failedRows)
            assertEquals(listOf(0, 1, 2), errors.all.map { it.rowIndex })
            assertTrue(errors.all.all { it.code == "MANTRA-DATA-XLSX-CELL" && it.column == "base-value" })
        }
    }

    @Test fun `ambiguous table geometry is rejected instead of inventing record order`() {
        val schema =
            schema(
                "table-geometry.mantra",
                """
            (schema test/table-geometry (input facts :table {:columns {:id :keyword :base-value :decimal}}))
                """.trimIndent(),
            )
        for (splitRecord in listOf(true, false)) {
            XSSFWorkbook().use { workbook ->
                workbook.createSheet("First").createRow(0).apply {
                    createCell(0).setCellValue("A")
                    createCell(1).setCellValue(1.0)
                    createCell(2).setCellValue("B")
                    createCell(3).setCellValue(2.0)
                }
                workbook.createSheet("Second").createRow(0).createCell(0).setCellValue(1.0)
                workbook.createName().apply {
                    nameName = "facts__A__id"
                    refersToFormula = "'First'!\$A\$1"
                }
                workbook.createName().apply {
                    nameName = "facts__A__base_value"
                    refersToFormula = if (splitRecord) "'Second'!\$A\$1" else "'First'!\$B\$1"
                }
                if (!splitRecord) {
                    workbook.createName().apply {
                        nameName = "facts__B__id"
                        refersToFormula = "'First'!\$C\$1"
                    }
                    workbook.createName().apply {
                        nameName = "facts__B__base_value"
                        refersToFormula = "'First'!\$D\$1"
                    }
                }
                val sink = DiagnosticSink()
                assertTrue(XlsxSource(Path.of("ambiguous-table.xlsx"), bytes(workbook)).read(schema, sink).isEmpty())
                assertEquals("MANTRA-DATA-XLSX-NAME", sink.all.single().code)
            }
        }
    }

    @Test fun `ZIP compressed entry total and count limits all fail before POI without global policy mutation`() {
        val ratio = ZipSecureFile.getMinInflateRatio()
        val cases = listOf(
            ByteArray(XlsxContainerPreflight.MAX_COMPRESSED_BYTES + 1) to "Compressed",
            container(listOf(17 * 1024 * 1024)) to "entry",
            container(List(5) { 14 * 1024 * 1024 }) to "container exceeds 64",
            container(List(999) { 1 }) to "1000 entries",
        )
        cases.forEach { (bytes, message) ->
            val exception = assertFailsWith<MantraException> { XlsxSource.validateContainer(bytes) }
            assertEquals("MANTRA-DATA-XLSX-LIMIT", exception.diagnostics.single().code)
            assertTrue(exception.message.orEmpty().contains(message))
        }
        assertEquals(ratio, ZipSecureFile.getMinInflateRatio())
    }

    @Test fun `streamed preflight checkpoints preserve cancellation rather than turn it into empty input`() {
        val bytes = container(listOf(1024 * 1024))
        var checkpoints = 0
        assertFailsWith<CancellationException> {
            XlsxSource.validateContainer(bytes) {
                if (++checkpoints ==
                    8
                ) {
                    throw CancellationException("test cancellation")
                }
            }
        }
        assertEquals(8, checkpoints)
    }

    @Test fun `structured host control failure is not converted into input diagnostics`() {
        val bytes = container(listOf(16))
        for (kind in RunFailureKind.entries) {
            val failure = RunFailure(kind, RunStage.IMPORTING, null)
            val exception =
                MantraException(listOf(Diagnostic(Severity.ERROR, failure.code, "host control")), runFailure = failure)
            val sink = DiagnosticSink()
            var calls = 0
            val actual = assertFailsWith<MantraException> {
                XlsxSource(Path.of("controlled.xlsx"), bytes, checkpoint = {
                    if (++calls ==
                        3
                    ) {
                        throw exception
                    }
                }).read(scalar, sink)
            }
            assertSame(exception, actual)
            assertSame(failure, actual.runFailure)
            assertTrue(sink.all.isEmpty())
        }
    }

    @Test fun `central directory cannot select an uncounted oversized inner local entry`() {
        val bytes = centralShadowContainer()
        // The outer stored entry is tiny in the local-header view; its body embeds a second header.
        var localExpanded = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (zip.nextEntry != null) {
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    localExpanded += count
                }
                zip.closeEntry()
            }
        }
        assertTrue(localExpanded < 1024 * 1024)
        val error = assertFailsWith<MantraException> { XlsxSource.validateContainer(bytes) }
        assertEquals("MANTRA-DATA-XLSX-LIMIT", error.diagnostics.single().code)
        assertTrue(error.message.orEmpty().contains("entry exceeds 16 MiB"))
    }

    private data class RawEntry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressed: ByteArray,
        val expanded: Int,
        val offset: Int,
    )

    private fun centralShadowContainer(): ByteArray {
        val output = ByteArrayOutputStream()
        val central = mutableListOf<RawEntry>()
        fun local(name: String, method: Int, crc: Long, bytes: ByteArray, expanded: Int): RawEntry {
            val entry = RawEntry(name, method, crc, bytes, expanded, output.size())
            writeLocal(output, entry)
            return entry
        }
        for (name in listOf("[Content_Types].xml", "xl/workbook.xml")) {
            val text = "<root/>".toByteArray()
            central += local(name, 0, CRC32().apply { update(text) }.value, text, text.size)
        }
        val crc = CRC32()
        val compressed = ByteArrayOutputStream().also { result ->
            val deflater = Deflater(9, true)
            try {
                DeflaterOutputStream(result, deflater).use { compressedStream ->
                    val chunk = ByteArray(64 * 1024)
                    repeat(272) {
                        compressedStream.write(chunk)
                        crc.update(chunk)
                    } // 17 MiB expands from a small raw deflate stream.
                }
            } finally {
                deflater.end()
            }
        }.toByteArray()
        val inner = RawEntry("xl/hidden.xml", 8, crc.value, compressed, 17 * 1024 * 1024, 0)
        val body = ByteArrayOutputStream().also { writeLocal(it, inner) }.toByteArray()
        val outer = local("xl/wrapper.bin", 0, CRC32().apply { update(body) }.value, body, body.size)
        central += inner.copy(offset = outer.offset + 30 + outer.name.toByteArray().size)
        val centralOffset = output.size()
        central.forEach { entry ->
            val name = entry.name.toByteArray()
            output.u32(0x02014b50)
            output.u16(20)
            output.u16(20)
            output.u16(0)
            output.u16(entry.method)
            output.u16(0)
            output.u16(0)
            output.u32(entry.crc)
            output.u32(entry.compressed.size.toLong())
            output.u32(entry.expanded.toLong())
            output.u16(name.size)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(0)
            output.u32(entry.offset.toLong())
            output.write(name)
        }
        val centralSize = output.size() - centralOffset
        output.u32(0x06054b50)
        output.u16(0)
        output.u16(0)
        output.u16(central.size)
        output.u16(central.size)
        output.u32(centralSize.toLong())
        output.u32(centralOffset.toLong())
        output.u16(0)
        return output.toByteArray()
    }

    private fun writeLocal(output: ByteArrayOutputStream, entry: RawEntry) {
        val name = entry.name.toByteArray()
        output.u32(0x04034b50)
        output.u16(20)
        output.u16(0)
        output.u16(entry.method)
        output.u16(0)
        output.u16(0)
        output.u32(entry.crc)
        output.u32(entry.compressed.size.toLong())
        output.u32(entry.expanded.toLong())
        output.u16(name.size)
        output.u16(0)
        output.write(name)
        output.write(entry.compressed)
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 255)
        write((value ushr 8) and 255)
    }
    private fun ByteArrayOutputStream.u32(value: Long) {
        repeat(4) { write(((value ushr (8 * it)) and 255).toInt()) }
    }

    private fun container(sizes: List<Int>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for (name in listOf("[Content_Types].xml", "xl/workbook.xml")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("<root/>".toByteArray())
                zip.closeEntry()
            }
            val buffer = ByteArray(64 * 1024)
            sizes.forEachIndexed { index, size ->
                zip.putNextEntry(ZipEntry("xl/part$index.xml"))
                var remaining = size
                while (remaining >
                    0
                ) {
                    val count = minOf(remaining, buffer.size)
                    zip.write(buffer, 0, count)
                    remaining -= count
                }
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
