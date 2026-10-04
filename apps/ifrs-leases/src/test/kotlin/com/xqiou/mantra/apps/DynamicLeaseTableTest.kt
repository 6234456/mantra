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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Original references precede M4; the new zero-rate lease has an independent closed-form oracle. */
class DynamicLeaseTableTest {
    private val dir = Path.of("apps/ifrs-leases")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val original = Mantra.loadCase(dir.resolve("case-demo.mantra"))
    private val reference = (
        Json.parse(
            Files.readString(dir.resolve("references/demo-scale2-values.json")),
        ) as Value.MapV
        )
        .entries.map { (key, value) -> (key as Value.Kw).name to (value as Value.Text).value.toBigDecimal() }.toMap()
    private fun record(vararg pairs: Pair<String, Value>): Value.MapV = Value.MapV(
        pairs.associate {
            Value.Kw(it.first) to
                it.second
        },
    )
    private val extra = record(
        "id" to Value.Kw("Workshop"),
        "title" to Value.Text("Zero-rate workshop"),
        "annual-rate" to Value.num(0),
        "term-years" to Value.num(3),
        "commencement-payment" to Value.num(0),
        "initial-direct-costs" to Value.num(0),
        "commencement-incentive" to Value.num(0),
    )
    private fun fields(row: Value): Map<String, Value> =
        (row as Value.MapV).entries.mapKeys { (it.key as Value.Kw).name }
    private fun rows(id: String) = (original.inputs.getValue(id) as Value.Vec).items.map(::fields)
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
            if ('*' !in address && coord.firstOrNull() == "Storage" && "Workshop" in keys) {
                expected[address.replace("@Storage", "@Workshop")] = amount.divide(BigDecimal("6"))
            }
        }
        // Portfolio totals sum independently frozen member values, including the zero-rate oracle.
        val names = listOf(
            "liability-opening",
            "interest",
            "payment",
            "principal-reduction",
            "liability-closing",
            "rou-opening",
            "rou-depreciation",
            "rou-closing",
        )
        for (name in names) {
            for (period in listOf("P1", "P2", "P3")) {
                expected["period-$name@$period"] =
                    keys.fold(BigDecimal.ZERO) { sum, key -> sum + expected.getValue("$name@$key/$period") }
            }
        }
        expected["currency-scale"] = BigDecimal("2")
        return expected
    }
    private fun verify(output: ExcelWorkbook, keys: Set<String>, state: String) {
        recalc(output.workbook)
        val expected = independent(keys)
        // Secondary full engine run uses exactly the changed typed facts, never supplies the oracle.
        val inputs = original.inputs.toMutableMap()
        for (id in listOf("leases", "lease-payments", "reported-closings")) {
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

    @Test fun `new lease participates in each period after real insertion deletion ordering and reopening`() {
        val result = Mantra.calculateForAudit(schema, original)
        ExcelExport.workbook(
            result,
            Render.loadLayout(dir.resolve("layout.mantra")),
            ExcelOptions(
                dynamicTableCapacities = mapOf("leases" to 3, "lease-payments" to 9, "reported-closings" to 9),
            ),
        ).use { output ->
            assertEquals(emptyList(), output.report.fallbacks)
            assertEquals(emptyList(), output.report.evaluationErrors)
            output.insertDynamicTableRow("leases", 2, fields(extra))
            for ((index, period) in listOf("P1", "P2", "P3").withIndex()) {
                output.insertDynamicTableRow(
                    "lease-payments",
                    6 + index,
                    mapOf(
                        "lease-id" to Value.Kw("Workshop"),
                        "period-id" to Value.Kw(period),
                        "payment" to Value.num(1000),
                    ),
                )
                output.insertDynamicTableRow(
                    "reported-closings",
                    6 + index,
                    mapOf(
                        "lease-id" to Value.Kw("Workshop"),
                        "period-id" to Value.Kw(period),
                        "liability-amount" to Value.num((2000 - index * 1000).toLong()),
                    ),
                )
            }
            verify(output, setOf("Office", "Storage", "Workshop"), "inserted")
            output.removeDynamicTableRow("leases", 0)
            for (id in listOf("lease-payments", "reported-closings")) {
                output.replaceDynamicTableRows(
                    id,
                    output.dynamicTableRows(id).filter { it["lease-id"] != Value.Kw("Office") },
                )
            }
            verify(output, setOf("Storage", "Workshop"), "deleted")
            assertNull(output.address("liability-closing", listOf("Office", "P1")))
            for (id in listOf("leases", "lease-payments", "reported-closings")) {
                output.replaceDynamicTableRows(id, output.dynamicTableRows(id).reversed())
            }
            verify(output, setOf("Storage", "Workshop"), "reordered")
        }
    }
}
