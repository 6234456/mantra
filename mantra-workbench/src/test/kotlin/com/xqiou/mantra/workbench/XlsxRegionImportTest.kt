package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class XlsxRegionImportTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var temp: Path
    private val source = """
        (schema test/region (input facts :table {:columns {:id :keyword :amount :decimal :enabled :boolean}})
          (section main "Main" (line amount-total "Total" (sum (map (fn [row] row.amount) facts)))))
    """.trimIndent()
    private val options = mapOf(
        "input" to Value.Text("facts"),
        "sheet" to Value.Text("Facts"),
        "range" to Value.Text("B2:D4"),
        "columns" to Value.MapV(
            linkedMapOf(
                Value.Text("Item") to Value.Text("id"),
                Value.Text("Amount") to Value.Text("amount"),
                Value.Text("Enabled") to Value.Text("enabled"),
            ),
        ),
    )

    private fun workbook(): XSSFWorkbook = XSSFWorkbook().apply {
        createSheet("Facts").apply {
            createRow(1).apply {
                createCell(1).setCellValue("Item")
                createCell(2).setCellValue("Amount")
                createCell(3).setCellValue("Enabled")
            }
            createRow(2).apply {
                createCell(1).setCellValue("A")
                createCell(2).setCellValue(0.0)
                createCell(3).setCellValue(false)
            }
            createRow(3).apply {
                createCell(1).setCellValue("B")
                createCell(2).setCellValue(12.5)
                createCell(3).setCellValue(true)
            }
        }
    }

    private fun bytes(workbook: XSSFWorkbook) = ByteArrayOutputStream().also(workbook::write).toByteArray()

    private fun files(): Path {
        Files.writeString(temp.resolve("schema.mantra"), source)
        return temp.resolve("case.mantra").also {
            Files.writeString(it, "(case sample {:schema \"test/region\"} (inputs {:facts []}))")
        }
    }

    @Test fun `inspect exposes bounded sheet headers without replacing the named import fields`() {
        workbook().use { wb ->
            val inspection = ImportFiles.inspect("facts.xlsx", "xlsx", bytes(wb))
            assertEquals(emptyList<Any>(), inspection["columns"])
            assertEquals(1, inspection["rowCount"])
            val candidates = inspection["xlsxSheets"] as List<*>
            val selected = candidates.single() as Map<*, *>
            assertEquals("Facts", selected["name"])
            assertEquals(4, selected["rows"])
            assertEquals(4, selected["columns"])
            assertEquals("B2:D4", selected["suggestedRange"])
            assertEquals(
                listOf(
                    mapOf("column" to 1, "title" to "Item"),
                    mapOf("column" to 2, "title" to "Amount"),
                    mapOf("column" to 3, "title" to "Enabled"),
                ),
                selected["headers"],
            )
            wb.createName().apply {
                nameName = "known"
                refersToFormula = "'Facts'!\$C\$3"
            }
            val named = ImportFiles.inspect("facts.xlsx", "xlsx", bytes(wb))
            assertEquals(listOf(mapOf("name" to "known", "sample" to emptyList<String>())), named["columns"])
        }
    }

    @Test fun `inspection bounds large sheet metadata without scanning or suggesting oversized regions`() {
        XSSFWorkbook().use { wb ->
            repeat(20) { index ->
                wb.createSheet("Sheet$index").apply {
                    createRow(0).apply { repeat(100) { createCell(it).setCellValue("Column$it") } }
                    createRow(100_000)
                }
            }
            val candidates = ImportFiles.inspect("large.xlsx", "xlsx", bytes(wb))["xlsxSheets"] as List<*>
            assertEquals(16, candidates.size)
            candidates.forEach { entry ->
                val candidate = entry as Map<*, *>
                assertEquals(64, (candidate["headers"] as List<*>).size)
                assertFalse("suggestedRange" in candidate)
                assertEquals(100_001, candidate["rows"])
            }
        }
    }

    @Test fun `ordinary import options and mapping templates preserve region selection and revision provenance`() {
        val caseFile = files()
        // Empty manual table input would intentionally override imported records, so remove it first.
        Files.writeString(caseFile, "(case sample {:schema \"test/region\"})")
        val catalog = workspaceCatalog(temp)
        val initial = catalog.document("case.mantra", "run")
        val template = catalog.saveImportTemplate("worksheet", "xlsx", options)
        val templateBody = WorkbenchJson.write(template.data)
        assertContains(templateBody, "\"input\":\"facts\"")
        assertContains(templateBody, "\"sheet\":\"Facts\"")
        assertContains(templateBody, "\"range\":\"B2:D4\"")
        workbook().use { wb ->
            val imported = catalog.importApply(
                "case.mantra",
                initial.revision,
                "facts.xlsx",
                "xlsx",
                bytes(wb),
                options,
            )
            assertNotEquals(initial.revision, imported.revision)
            val run = WorkbenchJson.write(catalog.document("case.mantra", "run").data)
            assertContains(run, "\"n\":\"12.5\"")
            assertContains(run, "#Facts!B2:D4")
            val authored = Files.readString(caseFile)
            assertContains(authored, "xlsx")
            assertContains(authored, ":range \"B2:D4\"")
            assertEquals(1, Files.list(temp.resolve("imports")).use { it.count() }.toInt())
            val saved = Mantra.loadCase(SourceText("case.mantra", authored))
            assertEquals(options["columns"], saved.sources.single().options["columns"])
        }
    }

    @Test fun `partial region options fail import and roll back newly captured files without altering the case`() {
        val caseFile = files()
        val before = Files.readString(caseFile)
        val catalog = workspaceCatalog(temp)
        val revision = catalog.document("case.mantra", "run").revision
        workbook().use { wb ->
            val error = assertFailsWith<WorkspaceException> {
                catalog.importApply("case.mantra", revision, "facts.xlsx", "xlsx", bytes(wb), options - "sheet")
            }
            assertEquals(WorkspaceProblem.INVALID, error.problem)
            assertTrue(error.message!!.contains(":sheet"))
            assertEquals(before, Files.readString(caseFile))
            assertEquals(0L, Files.list(temp.resolve("imports")).use { it.count() })
        }
    }

    @Test fun `bound regions consume captured bytes instead of reopened file contents`() {
        val caseFile = files()
        Files.writeString(
            caseFile,
            """(case sample {:schema "test/region"}
            (sources (xlsx {:path "facts.xlsx" :input "facts" :sheet "Facts" :range "B2:D4"
              :columns {"Item" "id" "Amount" "amount" "Enabled" "enabled"}})))""",
        )
        workbook().use { wb ->
            val captured = bytes(wb)
            wb.getSheet("Facts").getRow(3).getCell(2).setCellValue(999.0)
            Files.write(temp.resolve("facts.xlsx"), bytes(wb))
            val schema = Mantra.loadSchema(SourceText("schema.mantra", source), SourceResolver { _, _ -> null })
            val case = Mantra.loadCase(SourceText("case.mantra", Files.readString(caseFile)))
            val loaded = BoundSources.load(case, schema, caseFile, temp, capturedRead = { captured })
            val result = Mantra.calculate(schema, loaded.case)
            assertTrue(result.succeeded, result.diagnostics.toString())
            assertEquals(Value.num("12.5"), result.value("amount-total"))
        }
    }
}
