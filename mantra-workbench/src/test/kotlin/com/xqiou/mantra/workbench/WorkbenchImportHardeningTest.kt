package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.packages.DirectoryPolicy
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.packages.SemanticVersion
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkbenchImportHardeningTest {
    @TempDir lateinit var temporary: Path

    @Test fun `captured CSV and JSON reject malformed UTF8 instead of replacing a text fact`() {
        for (format in listOf("csv", "json")) {
            val prefix = if (format == "csv") "input;value\nnote;" else "{\"note\":\""
            val suffix = if (format == "csv") "\n" else "\"}"
            val invalid = prefix.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + suffix.toByteArray()
            val snapshot = captured(format, invalid, "invalid-$format")
            val entry = snapshot.manifest.cases.single()
            // A captured DATA resource is integrity-valid binary; UTF-8 is validated by its importer.
            assertTrue(snapshot.dataSources("sample").single().bytes().contentEquals(invalid))
            val error = assertFailsWith<WorkspaceException> {
                BoundSources.loadCaptured(
                    snapshot.case("sample"),
                    snapshot.schema(entry.schema),
                    snapshot.dataSources("sample"),
                )
            }
            assertEquals(WorkspaceProblem.INVALID, error.problem)
            assertEquals("MANTRA-DATA-UTF8", error.diagnostics.single().code)
            assertEquals(DiagnosticCategory.STRUCTURAL, error.diagnostics.single().category)
            assertTrue(error.diagnostics.single().location != null)
        }
    }

    @Test fun `browser CSV and JSON uploads use the same strict UTF8 import diagnostic`() {
        for (format in listOf("csv", "json")) {
            val bytes = if (format == "csv") {
                "input;value\nnote;".toByteArray() + byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()) +
                    "\n".toByteArray()
            } else {
                "{\"note\":\"".toByteArray() + byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()) +
                    "\"}".toByteArray()
            }
            val error = assertFailsWith<WorkspaceException> { ImportFiles.inspect("facts.$format", format, bytes) }
            assertEquals(WorkspaceProblem.INVALID, error.problem)
            assertEquals("MANTRA-DATA-UTF8", error.diagnostics.single().code)
            assertEquals(DiagnosticCategory.STRUCTURAL, error.diagnostics.single().category)
        }
    }

    @Test fun `valid captured Unicode zero and false survive immutable source replay and upload inspection`() {
        for (format in listOf("csv", "json")) {
            val text = if (format == "csv") {
                "input;value\nnote;Grüße 世界\nbase-value;0\nenabled;false\n"
            } else {
                "{\"note\":\"Grüße 世界\",\"base-value\":0,\"enabled\":false}"
            }
            val snapshot = captured(format, text.toByteArray(), "valid-$format")
            val entry = snapshot.manifest.cases.single()
            // Replay remains detached from the host's later edits and caller-owned byte arrays.
            Files.writeString(temporary.resolve("valid-$format/data/facts.$format"), "invalid after capture")
            snapshot.dataSources("sample").single().bytes().fill(0)
            val loaded = BoundSources.loadCaptured(
                snapshot.case("sample"),
                snapshot.schema(entry.schema),
                snapshot.dataSources("sample"),
            )
            assertEquals(Value.Text("Grüße 世界"), loaded.case.inputs["note"])
            assertEquals(Value.num(0), loaded.case.inputs["base-value"])
            assertEquals(Value.Bool(false), loaded.case.inputs["enabled"])
            assertEquals("$format:facts.$format", loaded.case.inputOrigins.getValue("base-value").getValue(""))
            assertEquals("$format:facts.$format", loaded.case.inputOrigins.getValue("enabled").getValue(""))
            val result = Mantra.calculate(snapshot.schema(entry.schema), loaded.case)
            assertTrue(result.succeeded, result.diagnostics.toString())
            assertEquals(Value.num(0), result.value("base-value"))
            assertEquals(Value.Bool(false), result.value("enabled"))
            assertEquals(
                if (format ==
                    "csv"
                ) {
                    3
                } else {
                    1
                },
                ImportFiles.inspect("facts.$format", format, text.toByteArray())["rowCount"],
            )
        }
    }

    @Test fun `captured workbook formula errors prevent successful defaulted calculation`() {
        val bytes = XSSFWorkbook().use { workbook ->
            val cell = workbook.createSheet("Facts").createRow(0).createCell(0)
            cell.cellFormula = "1/0"
            workbook.createName().apply {
                nameName = "base_value"
                refersToFormula = "'Facts'!\$A\$1"
            }
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }
        val snapshot = captured("xlsx", bytes, "error-workbook")
        val entry = snapshot.manifest.cases.single()
        val error = assertFailsWith<WorkspaceException> {
            BoundSources.loadCaptured(
                snapshot.case("sample"),
                snapshot.schema(entry.schema),
                snapshot.dataSources("sample"),
            )
        }
        assertEquals(WorkspaceProblem.INVALID, error.problem)
        assertEquals("MANTRA-DATA-XLSX-CELL", error.diagnostics.single().code)
        assertEquals("base-value", error.diagnostics.single().nodeId)
        assertTrue(error.diagnostics.single().message.contains("#DIV/0!"))
    }

    @Test fun `captured two dimensional workbook supplies full coordinates and zero false date provenance`() {
        val bytes = XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Facts")
            val values = listOf("base_value" to 0.0, "enabled" to false, "occurred_on" to LocalDate.parse("2026-01-02"))
            values.forEachIndexed { index, (name, value) ->
                val cell = sheet.createRow(index).createCell(0)
                when (value) {
                    is Double -> cell.setCellValue(value)
                    is Boolean -> cell.setCellValue(value)
                    is LocalDate -> cell.setCellValue(DateUtil.getExcelDate(value))
                }
                workbook.createName().apply {
                    nameName = "${name}__A__P1"
                    refersToFormula = "'Facts'!\$A\$${index + 1}"
                }
            }
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }
        val schemaText = """
            (schema test/import-hardening {:version "1.0.0"}
              (dimension entity {:members [:A]})
              (dimension timeline {:periods {:start "2026-01-01" :unit :year :count 1}})
              (input base-value :decimal {:per [entity timeline]})
              (input enabled :boolean {:per [entity timeline]})
              (input occurred-on :date {:per [entity timeline]}))
        """.trimIndent()
        val snapshot = captured("xlsx", bytes, "two-workbook", schemaText)
        val entry = snapshot.manifest.cases.single()
        val schema = snapshot.schema(entry.schema)
        val loaded = BoundSources.loadCaptured(snapshot.case("sample"), schema, snapshot.dataSources("sample"))
        for (id in listOf("base-value", "enabled", "occurred-on")) {
            assertEquals("xlsx:facts.xlsx", loaded.case.inputOrigins.getValue(id).getValue("A/P1"))
        }
        val result = Mantra.calculate(schema, loaded.case)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(0), result.value("base-value", "A", "P1"))
        assertEquals(Value.Bool(false), result.value("enabled", "A", "P1"))
        assertEquals(Value.Date(LocalDate.parse("2026-01-02")), result.value("occurred-on", "A", "P1"))
    }

    @Test fun `browser workbook upload enforces expanded ZIP limit before OOXML parsing`() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for (name in listOf("[Content_Types].xml", "xl/workbook.xml")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("<root/>".toByteArray())
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("xl/oversized.xml"))
                val chunk = ByteArray(64 * 1024)
                repeat(257) { zip.write(chunk) } // 16 MiB + one chunk, before any POI object is opened.
                zip.closeEntry()
            }
        }.toByteArray()
        val error = assertFailsWith<WorkspaceException> { ImportFiles.inspect("oversized.xlsx", "xlsx", bytes) }
        assertEquals(WorkspaceProblem.TOO_LARGE, error.problem)
        assertEquals("MANTRA-DATA-XLSX-LIMIT", error.diagnostics.single().code)
    }

    private fun captured(
        format: String,
        data: ByteArray,
        directory: String,
        schemaText: String? = null,
    ): PackageSnapshot {
        val schema = (
            schemaText ?: """
            (schema test/import-hardening {:version "1.0.0"}
              (input note :text) (input base-value :decimal) (input enabled :boolean))
            """.trimIndent()
            ).toByteArray()
        val case = """
            (case sample {:schema "test/import-hardening" :schema-version "1.0.0"}
              (sources ($format {:path "../data/facts.$format"})))
        """.trimIndent().toByteArray()
        val resources = linkedMapOf(
            "schema.mantra" to schema,
            "cases/sample.mantra" to case,
            "data/facts.$format" to data,
        )
        val listing = resources.entries.joinToString(",") { (path, bytes) ->
            val role = when (path) {
                "schema.mantra" -> "schema"
                "cases/sample.mantra" -> "case"
                else -> "data"
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            """{"path":"$path","role":"$role","byteLength":${bytes.size},"sha256":"$hash"}"""
        }
        val identity = """{"id":"test/import-hardening","version":"1.0.0","versionMode":"semver"}"""
        val manifest = """
            {"format":"mantra.package/1","id":"import-hardening","version":"1.0.0","engine":">=0.4.0 <0.6.0",
             "resources":[$listing],"schemas":[{"schema":$identity,"path":"schema.mantra"}],
             "parameters":[],"layouts":[],"dependencies":[],
             "cases":[{"id":"sample","schema":$identity,"path":"cases/sample.mantra","parameters":[],"layout":null}]}
        """.trimIndent()
        val root = Files.createDirectories(temporary.resolve(directory))
        resources.forEach { (path, bytes) ->
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        Files.writeString(root.resolve("manifest.json"), manifest)
        return PackageLoader.directory(
            root,
            SemanticVersion.parse("0.5.0"),
            PackageLimits(64_000, 64_000, 256_000, 20, 20, 100),
            DirectoryPolicy.TRUSTED_LOCAL,
        )
    }
}
