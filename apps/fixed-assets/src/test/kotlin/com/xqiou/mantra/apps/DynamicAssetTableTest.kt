package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.excel.ExcelOptions
import com.xqiou.mantra.excel.ExcelWorkbook
import com.xqiou.mantra.excel.auditSnapshotStatusAddress
import com.xqiou.mantra.excel.dynamicTableRows
import com.xqiou.mantra.excel.insertDynamicTableRow
import com.xqiou.mantra.excel.removeDynamicTableRow
import com.xqiou.mantra.excel.replaceDynamicTableRows
import com.xqiou.mantra.render.Render
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A nondepreciable 1200 parcel has a closed-form oracle independent of the calculation engine. */
class DynamicAssetTableTest {
    private val dir = Path.of("apps/fixed-assets")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val original = Mantra.loadCase(dir.resolve("case-demo.mantra"))
    private val reference = (
        Json.parse(
            Files.readString(dir.resolve("references/demo-scale2-values.json")),
        ) as Value.MapV
        )
        .entries.map { (key, value) -> (key as Value.Kw).name to (value as Value.Text).value.toBigDecimal() }.toMap()
    private fun fields(row: Value): Map<String, Value> =
        (row as Value.MapV).entries.mapKeys { (it.key as Value.Kw).name }
    private val land = fields((original.inputs.getValue("assets") as Value.Vec).items.last())
    private val parcel = land + mapOf(
        "id" to Value.Kw("Parcel"),
        "title" to Value.Text("Additional land parcel"),
        "opening-cost" to Value.num(1200),
        "available-from" to Value.Date(LocalDate.of(2026, 1, 1)),
    )
    private val periods = listOf("P1", "P2", "P3", "P4", "P5")
    private fun cell(wb: XSSFWorkbook, address: String) = CellReference(address).let {
        wb.getSheet(it.sheetName).getRow(it.row).getCell(it.col.toInt())
    }
    private fun recalc(wb: XSSFWorkbook) = wb.creationHelper.createFormulaEvaluator().apply {
        clearAllCachedResultValues()
        evaluateAll()
    }
    private fun independent(keys: Set<String>): Map<String, BigDecimal> {
        val expected = linkedMapOf<String, BigDecimal>()
        reference.forEach { (address, amount) ->
            val coord = address.substringAfter('@', "").split('/')
            if ('*' !in address && coord.firstOrNull() in keys) expected[address] = amount
            if ('*' !in address && coord.firstOrNull() == "Land" && "Parcel" in keys) {
                expected[address.replace("@Land", "@Parcel")] = amount * BigDecimal("0.024")
            }
        }
        val names = listOf(
            "gross-opening",
            "gross-additions",
            "gross-disposals",
            "gross-closing",
            "accumulated-opening",
            "accumulated-disposals",
            "accumulated-closing",
        )
        for (name in names) {
            for (period in periods) {
                expected["period-$name@$period"] =
                    keys.fold(BigDecimal.ZERO) { sum, key -> sum + expected.getValue("$name@$key/$period") }
            }
        }
        for ((summary, member) in listOf(
            "carrying-opening" to "carrying-opening",
            "additions" to "gross-additions",
            "depreciation" to "depreciation",
            "carrying-disposals" to "carrying-disposals",
            "carrying-closing" to "carrying-closing",
        )) {
            for (period in periods) {
                expected["period-$summary@$period"] =
                    keys.fold(BigDecimal.ZERO) { sum, key -> sum + expected.getValue("$member@$key/$period") }
            }
        }
        for (period in periods) {
            expected["reported-closing@$period"] = expected.getValue("period-carrying-closing@$period")
            expected["period-carrying-reconciliation@$period"] = BigDecimal.ZERO
        }
        expected["currency-scale"] = BigDecimal("2")
        return expected
    }
    private fun verify(output: ExcelWorkbook, keys: Set<String>, state: String) {
        val expected = independent(keys)
        output.replaceDynamicTableRows(
            "reported-closings",
            periods.map { period ->
                mapOf(
                    "period" to Value.Kw(period),
                    "carrying-amount" to Value.Num(expected.getValue("reported-closing@$period")),
                )
            },
        )
        recalc(output.workbook)
        val inputs = original.inputs.toMutableMap()
        for (id in listOf("assets", "reported-closings")) {
            inputs[id] =
                Value.Vec(output.dynamicTableRows(id).map { row -> Value.MapV(row.mapKeys { Value.Kw(it.key) }) })
        }
        val result = Mantra.calculate(schema, original.copy(inputs = inputs))
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertTrue(result.validationPassed, result.diagnostics.toString())
        val addresses = linkedMapOf<String, String>()
        expected.forEach { (name, amount) ->
            val id = name.substringBefore('@')
            val coord = name.substringAfter('@', "").takeIf(String::isNotEmpty)?.split('/').orEmpty()
            val address = assertNotNull(output.address(id, coord), name)
            addresses[name] = address
            val value = cell(output.workbook, address)
            assertEquals(
                CellType.NUMERIC,
                if (value.cellType ==
                    CellType.FORMULA
                ) {
                    value.cachedFormulaResultType
                } else {
                    value.cellType
                },
                name,
            )
            assertEquals(amount.toDouble(), value.numericCellValue, 1e-7, name)
            assertEquals(0, amount.compareTo(result.decimal(id, *coord.toTypedArray())), "engine $name")
        }
        val file = dir.resolve("build/out/dynamic-member-states/$state.xlsx")
        Files.createDirectories(file.parent)
        Files.write(file, output.bytes())
        XSSFWorkbook(Files.newInputStream(file)).use { reopened ->
            recalc(reopened)
            expected.forEach { (name, amount) ->
                assertEquals(
                    amount.toDouble(),
                    cell(reopened, addresses.getValue(name)).numericCellValue,
                    1e-7,
                    "reopened $name",
                )
            }
        }
        assertEquals(
            "outdated",
            cell(output.workbook, assertNotNull(output.auditSnapshotStatusAddress())).stringCellValue,
        )
    }

    @Test fun `new asset keeps typed dates false nil and every period after real member changes`() {
        ExcelExport.workbook(
            Mantra.calculateForAudit(schema, original),
            Render.loadLayout(dir.resolve("layout.mantra")),
            ExcelOptions(dynamicTableCapacities = mapOf("assets" to 4, "reported-closings" to 5)),
        ).use { output ->
            assertEquals(emptyList(), output.report.fallbacks)
            assertEquals(emptyList(), output.report.evaluationErrors)
            output.insertDynamicTableRow("assets", 3, parcel)
            verify(output, setOf("Machine", "Equipment", "Land", "Parcel"), "inserted")
            output.removeDynamicTableRow("assets", 2)
            verify(output, setOf("Machine", "Equipment", "Parcel"), "deleted")
            assertNull(output.address("carrying-closing", listOf("Land", "P5")))
            output.replaceDynamicTableRows("assets", output.dynamicTableRows("assets").reversed())
            verify(output, setOf("Machine", "Equipment", "Parcel"), "reordered")
            assertEquals(Value.Bool(false), output.dynamicTableRows("assets").first()["depreciable"])
            assertEquals(Value.Nil, output.dynamicTableRows("assets").first()["remaining-months"])
            assertEquals(
                Value.Date(LocalDate.of(2026, 1, 1)),
                output.dynamicTableRows("assets").first()["available-from"],
            )
        }
    }
}
