package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelTableQueryTest {
    private fun calculate(body: String, facts: String = "{}", declarations: String = ""): CalculationResult {
        val schema = Mantra.loadSchema(
            SourceText("query.mantra", "(schema test/query $declarations (section report \"Report\" $body))"),
            SourceResolver { _, _ -> null },
        )
        return Mantra.calculateForAudit(schema, Mantra.loadCase(SourceText("case.mantra", "(case c (inputs $facts))")))
            .also { assertTrue(it.succeeded, it.diagnostics.toString()) }
    }

    private fun cell(export: ExcelWorkbook, address: String?): XSSFCell {
        val reference = CellReference(assertNotNull(address))
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun value(export: ExcelWorkbook, id: String) = cell(export, export.address(id)).numericCellValue

    private fun recalculate(export: ExcelWorkbook) {
        export.workbook.creationHelper.createFormulaEvaluator().apply {
            clearAllCachedResultValues()
            evaluateAll()
        }
    }

    private val declarations = """
        (input entries :table {:columns {:category :keyword :description :text :amount :decimal?
                                        :rank :integer :enabled :boolean}})
        (input selected-category :keyword {:default :A})
        (input selected-rank :integer {:default 1})
        (input selected-enabled :boolean {:default true})
    """.trimIndent()
    private val facts = """
        {:entries [{:category :A :description "Case" :amount 10 :rank 1 :enabled true}
                   {:category :A :description "case" :amount 5 :rank 1 :enabled false}
                   {:category :B :description "Other" :amount 99 :rank 2 :enabled true}]}
    """.trimIndent()
    private val queries = """
        (line selected-sum "Selected sum" (table/sum-where entries {:category selected-category} :amount))
        (line selected-count "Selected count" (table/count-where entries {:category selected-category}))
        (line exact-text "Exact text" (table/sum-where entries {:description "Case"} :amount))
        (line numeric-count "Numeric count" (table/count-where entries {:rank selected-rank}))
        (line boolean-count "Boolean count" (table/count-where entries {:enabled selected-enabled}))
    """.trimIndent()

    @Test
    fun `live cells criteria and matched amount errors recompute with typed equality`() {
        val result = calculate(queries, facts, declarations)
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(15.0, value(export, "selected-sum"))
            assertEquals(2.0, value(export, "selected-count"))
            assertEquals(10.0, value(export, "exact-text"))
            assertEquals(2.0, value(export, "numeric-count"))
            assertEquals(2.0, value(export, "boolean-count"))
            cell(export, export.address("selected-category")).setCellValue("B")
            cell(export, export.address("selected-rank")).setCellValue(2.0)
            cell(export, export.address("selected-enabled")).setCellValue(false)
            cell(export, export.tableAddress("entries", 2, "amount")).setCellValue(40.0)
            recalculate(export)
            assertEquals(40.0, value(export, "selected-sum"))
            assertEquals(1.0, value(export, "selected-count"))
            assertEquals(1.0, value(export, "numeric-count"))
            assertEquals(1.0, value(export, "boolean-count"))
            cell(export, export.tableAddress("entries", 0, "description")).setCellValue("case")
            cell(export, export.tableAddress("entries", 0, "amount")).setBlank()
            recalculate(export)
            assertEquals(0.0, value(export, "exact-text"))
            assertEquals(40.0, value(export, "selected-sum"))
            cell(export, export.tableAddress("entries", 2, "amount")).setBlank()
            recalculate(export)
            assertEquals(CellType.ERROR, cell(export, export.address("selected-sum")).cachedFormulaResultType)
            assertEquals(1.0, value(export, "selected-count"))
        }
    }

    @Test
    fun `literal selections preserve keyword text nil missing duplicates and numeric scale`() {
        val result = calculate(
            """
            (line keywords "Keywords" (table/count-where [{:k :A} {:k "A"} {:k :a}] {:k :A}))
            (line strings "Strings" (table/count-where [{:k :A} {:k "A"} {:k "a"}] {:k "A"}))
            (line nils "Nils" (table/count-where [{:k nil} {:other 0} {:k 0} {:k false} {:k ""}] {:k nil}))
            (line numerics "Numerics" (table/count-where [{:k 1} {:k 1.00} {:k "1"} {:k true}] {:k 1.0}))
            (line duplicates "Duplicates" (table/sum-where [{:k :A :amount 2} {:k :A :amount 2}] {:k :A} :amount))
            (line all-rows "All" (table/count-where [{:k :A} {:k :A}] {}))
            (line unmatched "Unmatched" (table/sum-where [{:k :A :amount "ignored"}] {:k :B} :amount))
            (line empty-sum "Empty" (table/sum-where [] {} :amount))
            """.trimIndent(),
        )
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            mapOf(
                "keywords" to 1.0,
                "strings" to 1.0,
                "nils" to 1.0,
                "numerics" to 2.0,
                "duplicates" to 4.0,
                "all-rows" to 2.0,
                "unmatched" to 0.0,
                "empty-sum" to 0.0,
            ).forEach { (id, expected) -> assertEquals(expected, value(export, id), id) }
        }
    }

    @Test
    fun `lazy callers defer invalid queries while argument errors remain eager when selected`() {
        val result = calculate(
            """
            (line guarded "Guarded" (if enabled (table/sum-where [{:k :A}] {:k :A} :amount) 7))
            (line strict-rows "Strict rows" (if enabled (table/count-where [{:k :A} 9] {:k :B}) 8))
            (line eager "Eager" (table/count-where [{:k :A :unused (/ 1 divisor)}] {:k :B}))
            """.trimIndent(),
            declarations = "(input enabled :boolean {:default false}) (input divisor :decimal {:default 1})",
        )
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(7.0, value(export, "guarded"))
            assertEquals(8.0, value(export, "strict-rows"))
            assertEquals(0.0, value(export, "eager"))
            cell(export, export.address("enabled")).setCellValue(true)
            cell(export, export.address("divisor")).setCellValue(0.0)
            recalculate(export)
            listOf("guarded", "strict-rows", "eager").forEach { id ->
                assertEquals(CellType.ERROR, cell(export, export.address(id)).cachedFormulaResultType, id)
            }
        }
    }

    @Test
    fun `invalid date criteria fail even when no record can match and remain lazy in callers`() {
        val result = calculate(
            """
            (line empty-date "Empty date"
              (if enabled (table/count-where [] {:k (date/parse "2026-01-01")}) 11))
            (line missing-date "Missing date"
              (if enabled (table/count-where [{:other 1}] {:k (date/parse "2026-01-01")}) 12))
            """.trimIndent(),
            declarations = "(input enabled :boolean {:default false})",
        )
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(11.0, value(export, "empty-date"))
            assertEquals(12.0, value(export, "missing-date"))
            cell(export, export.address("enabled")).setCellValue(true)
            recalculate(export)
            listOf("empty-date", "missing-date").forEach { id ->
                assertEquals(CellType.ERROR, cell(export, export.address(id)).cachedFormulaResultType, id)
            }
        }
    }

    @Test
    fun `deferred record producers are rejected without forcing ignored nested producers`() {
        val result = calculate(
            """
            (line producer "Producer"
              (if enabled (table/count-where (map (fn [x] (/ 1 0)) [1]) {}) 13))
            (line nested "Nested"
              (table/count-where [{:k :A :unused (map (fn [x] (/ 1 0)) [1])}] {:k :A}))
            """.trimIndent(),
            declarations = "(input enabled :boolean {:default false})",
        )
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(13.0, value(export, "producer"))
            assertEquals(1.0, value(export, "nested"))
            cell(export, export.address("enabled")).setCellValue(true)
            recalculate(export)
            assertEquals(FormulaError.NA.code, cell(export, export.address("producer")).errorCellValue)
            assertEquals(1.0, value(export, "nested"))
        }
    }

    @Test
    fun `valid conditional record and criteria shapes use explicit formula fallbacks`() {
        val result = calculate(
            """
            (line records-branch "Records" (table/count-where (if enabled [{:k :A}] []) {:k :A}))
            (line criteria-branch "Criteria" (table/count-where [{:k :A}] (if enabled {:k :A} {:k :B})))
            """.trimIndent(),
            declarations = "(input enabled :boolean {:default true})",
        )
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertEquals(setOf("records-branch", "criteria-branch"), export.report.fallbacks.map { it.nodeId }.toSet())
            assertTrue(export.report.fallbacks.all { it.reason.contains("conditional collection shapes") })
            assertEquals(1.0, value(export, "records-branch"))
            assertEquals(1.0, value(export, "criteria-branch"))
        }
    }

    @Test
    fun `text imported keyword names normalize before static and dynamic live selection`() {
        val result = calculate(
            """
            (line imported-count "Count" (table/count-where entries {:category :A}))
            (line imported-sum "Sum" (table/sum-where entries {:category :A} :amount))
            """.trimIndent(),
            "{:entries [{:category \":A\" :amount 2} {:category \"A\" :amount 3}]}",
            "(input entries :table {:columns {:category :keyword :amount :decimal}})",
        )
        listOf(ExcelOptions(), ExcelOptions(dynamicTableCapacities = mapOf("entries" to 5))).forEach { options ->
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, options).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                assertEquals("A", cell(export, export.tableAddress("entries", 0, "category")).stringCellValue)
                assertEquals(2.0, value(export, "imported-count"))
                assertEquals(5.0, value(export, "imported-sum"))
                if (options.dynamicTableCapacities.isNotEmpty()) {
                    export.replaceDynamicTableRows(
                        "entries",
                        listOf(mapOf("category" to Value.Text(":A"), "amount" to Value.Num(7.toBigDecimal()))),
                    )
                    recalculate(export)
                    assertEquals("A", cell(export, export.tableAddress("entries", 0, "category")).stringCellValue)
                    assertEquals(1.0, value(export, "imported-count"))
                    assertEquals(7.0, value(export, "imported-sum"))
                }
            }
        }
    }

    @Test
    fun `text imported scalar keyword criteria normalize and remain editable`() {
        val result = calculate(
            """
            (line selected-count "Count" (table/count-where entries {:category target}))
            (line selected-sum "Sum" (table/sum-where entries {:category target} :amount))
            """.trimIndent(),
            "{:target \":A\" :entries [{:category :A :amount 2} {:category :B :amount 3}]}",
            """
            (input target :keyword)
            (input entries :table {:columns {:category :keyword :amount :decimal}})
            """.trimIndent(),
        )
        assertEquals("1", result.decimal("selected-count").toPlainString())
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            val target = cell(export, export.address("target"))
            assertEquals("A", target.stringCellValue)
            assertEquals(1.0, value(export, "selected-count"))
            assertEquals(2.0, value(export, "selected-sum"))
            target.setCellValue("B")
            recalculate(export)
            assertEquals(1.0, value(export, "selected-count"))
            assertEquals(3.0, value(export, "selected-sum"))
            target.setCellValue("C")
            recalculate(export)
            assertEquals(0.0, value(export, "selected-count"))
            assertEquals(0.0, value(export, "selected-sum"))
        }
    }

    @Test
    fun `bounded query scans charge wide records before lookup amplification`() {
        val record = X.MapX(List(200) { "field-$it" }, List(200) { Ex.num(it.toLong()) })
            .also { it.keywordKeys = true }
        val criteria = X.MapX(listOf("field-199"), listOf(Ex.num(199))).also { it.keywordKeys = true }
        var remaining = 50L
        assertFailsWith<IllegalStateException> {
            tableQuery(X.Vec(listOf(record)), criteria, null, { it }) { work ->
                remaining -= work
                check(remaining >= 0) { "query scan budget exhausted" }
            }
        }
    }

    @Test
    fun `dynamic table insert delete and duplicate rows remain live`() {
        val result = calculate(queries, facts, declarations)
        ExcelExport.workbook(
            result,
            Presets.IFRS_SCHEDULE,
            ExcelOptions(dynamicTableCapacities = mapOf("entries" to 6)),
        )
            .use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                val duplicate = mapOf(
                    "category" to Value.Kw("A"),
                    "description" to Value.Text("Case"),
                    "amount" to Value.Num(10.toBigDecimal()),
                    "rank" to Value.Num(1.toBigDecimal()),
                    "enabled" to Value.Bool(true),
                )
                export.insertDynamicTableRow("entries", 3, duplicate)
                recalculate(export)
                assertEquals(25.0, value(export, "selected-sum"))
                assertEquals(3.0, value(export, "selected-count"))
                assertEquals(20.0, value(export, "exact-text"))
                export.removeDynamicTableRow("entries", 0)
                recalculate(export)
                assertEquals(15.0, value(export, "selected-sum"))
                assertEquals(2.0, value(export, "selected-count"))
                export.replaceDynamicTableRows("entries", emptyList())
                recalculate(export)
                assertEquals(0.0, value(export, "selected-sum"))
                assertEquals(0.0, value(export, "selected-count"))
            }
    }

    @Test
    fun `typed table normalization preserves optional nil and required numeric zero`() {
        val result = calculate(
            """
            (line nils "Nils" (table/count-where entries {:optional nil}))
            (line zeros "Zeros" (table/count-where entries {:required 0}))
            (line zero-sum "Zero sum" (table/sum-where entries {:required 0} :required))
            """.trimIndent(),
            "{:entries [{:id :A} {:id :B :optional nil :required nil} {:id :C :optional 0 :required 2}]}",
            "(input entries :table {:columns {:id :keyword :optional :decimal? :required :decimal}})",
        )
        listOf(ExcelOptions(), ExcelOptions(dynamicTableCapacities = mapOf("entries" to 5))).forEach { options ->
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, options).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                assertEquals(2.0, value(export, "nils"))
                assertEquals(2.0, value(export, "zeros"))
                assertEquals(0.0, value(export, "zero-sum"))
            }
        }
    }

    @Test
    fun `nil versus live empty text has an explicit static fallback and rejects dynamic export`() {
        val result = calculate(
            "(line nil-text \"Nil text\" (table/count-where entries {:description nil}))",
            "{:entries [{:description nil} {:description \"\"}]}",
            "(input entries :table {:columns {:description :text?}})",
        )
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertEquals(1, export.report.fallbacks.size)
            assertTrue(export.report.fallbacks.single().reason.contains("nil from empty text"))
            assertEquals(1.0, value(export, "nil-text"))
        }
        val rejected = assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(
                result,
                Presets.IFRS_SCHEDULE,
                ExcelOptions(dynamicTableCapacities = mapOf("entries" to 4)),
            )
        }
        assertTrue(rejected.message.orEmpty().contains("nil from empty text"))
    }
}
