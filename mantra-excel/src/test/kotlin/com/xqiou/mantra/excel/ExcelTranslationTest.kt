package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.engine.CalculationResult
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every kernel construct the translator supports must evaluate in Excel exactly like in the engine. */
class ExcelTranslationTest {
    private val schema = Mantra.loadSchema(
        SourceText(
            "t.mantra",
            """
            (schema t/xl {:title "Translation"}
              (input amount :decimal {:default 100})
              (input units :table {:columns {:id :keyword :cap :decimal? :w :decimal}})
              (dimension unit {:from units :key :id})
              (param rate 0.05)
              (defn twice [^Decimal x] (let [y (* 2 x)] y))
              (section s "Scalar functions" {:display :schedule}
                (line wf "Waterfall, last bucket" (get (alloc/waterfall mantra/amount {:a 30 :b 50 :c nil}) :c))
                (line band "Band lookup" (table/band 2024 [[2023 0.14] [2024 0.136] [2025 0.132]]))
                (line pmt "Annuity" (fin/pmt rate 10 100000 2))
                (line stepwise "Stepwise" (calc/stepwise 60000 [[15340 0.05] [51130 0.06] [nil 0.07]]))
                (line npv "NPV" (fin/npv rate [100 100 100] 2))
                (line df "Discount factor" (fin/df rate 3 6))
                (line rounded "Half-even" (* mantra/amount 1.23456) {:round [2 :half-even]})
                (line floor "Floor" (decimal/floor -12.345 2))
                (line ceil "Ceil" (decimal/ceil 12.341 2))
                (line conditional "Cond" (cond (> mantra/amount 1000) 1 (> mantra/amount 50) 2 :else 3))
                (line case-kw "Case" (case :b :a 1 :b 2 3))
                (line or-default "Or" (or nil 7))
                (line helper "defn" (twice mantra/amount))
                (line guarded "Guarded" 5 {:when (< mantra/amount 10)})
                (total s-total "Total"))
              (section per "Per unit" {:per unit :display :schedule}
                (line w "Weight" unit.w)
                (line share "Pro rata" (alloc/pro-rata mantra/amount all.w 2) {:spread true})
                (line fill "Waterfall" (alloc/waterfall mantra/amount all.w) {:spread true})
                (choice best "Best" {:rule :max} (option :w "Weight" w) (option :cap "Cap" unit.cap {:when (some? unit.cap)}))
                (total per-total "Per unit total")))
            """.trimIndent(),
        ),
        SourceResolver { _, _ -> null },
    )
    private val result: CalculationResult = Mantra.calculate(
        schema,
        Mantra.loadCase(SourceText("c.mantra", "(case c (inputs {:units [{:id :a :w 1 :cap 5} {:id :b :w 1} {:id :c :w 1}]}))")),
    )

    @Test
    fun `workbook description exposes actual sheets formulas names and bounded cells`() {
        ExcelExport.workbook(result, Presets.DE_STAFFEL_4).use { export ->
            val descriptions = export.report.sheets.map { name -> requireNotNull(export.describe(name)) }
            assertEquals(export.report.sheets, descriptions.first().sheets.map { it.name })
            assertTrue(descriptions.any { it.preview.cells.any { cell -> cell.kind == "formula" && cell.formula != null } })
            assertEquals(export.report.names, descriptions.first().names.size)
            assertTrue(descriptions.all { it.preview.cells.all { cell -> cell.address.matches(Regex("[A-Z]+[1-9][0-9]*")) } })
            assertEquals(null, export.describe("missing"))
        }
    }

    @Test
    fun `excel formulas reproduce every engine value`() {
        assertTrue(result.succeeded, result.diagnostics.toString())
        // `amount` is also a Normein function: allowed with a warning, referenced as mantra/amount.
        assertTrue(result.diagnostics.any { it.code == "MANTRA-ID-SHADOWED" && it.nodeId == "amount" })
        val workbook = ExcelExport.workbook(result, Presets.DE_STAFFEL_4)
        assertEquals(emptyList(), workbook.report.fallbacks)
        assertEquals(emptyList(), workbook.report.evaluationErrors)
        val evaluator = workbook.workbook.creationHelper.createFormulaEvaluator()
        val mismatches = mutableListOf<String>()
        result.nodes.values.forEach { node ->
            node.values.forEach { (coord, value) ->
                val address = workbook.address(node.id, coord) ?: return@forEach
                val ref = CellReference(address)
                val cell = workbook.workbook.getSheet(ref.sheetName).getRow(ref.row).getCell(ref.col.toInt())
                val type = if (cell.cellType == CellType.FORMULA) evaluator.evaluateFormulaCell(cell) else cell.cellType
                val actual: Any? = when (type) {
                    CellType.NUMERIC -> cell.numericCellValue
                    CellType.BOOLEAN -> cell.booleanCellValue
                    CellType.STRING -> cell.stringCellValue
                    else -> null
                }
                val ok = when (value) {
                    is Value.Num -> actual is Double && abs(actual - value.value.toDouble()) < 1e-9
                    else -> true
                }
                if (!ok) mismatches += "${node.id}$coord: engine=$value excel=$actual formula=${cell.cellFormula}"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
        // Spot checks against hand-computed values.
        assertEquals(20.0, value(workbook, "wf"))
        assertEquals(12950.46, value(workbook, "pmt"))
        assertEquals(3535.30, value(workbook, "stepwise"), 1e-9)
        assertEquals(listOf(33.34, 33.33, 33.33), listOf("a", "b", "c").map { value(workbook, "share", listOf(it)) })
        assertEquals(listOf(1.0, 1.0, 1.0), listOf("a", "b", "c").map { value(workbook, "fill", listOf(it)) })
        assertEquals(5.0, value(workbook, "best", listOf("a")))
    }

    @Test
    fun `nested section guards use one matching member in Excel`() {
        val guardedSchema = Mantra.loadSchema(
            SourceText(
                "guards.mantra",
                """
                (schema t/guard-export {}
                  (dimension person {:members [:A :B]})
                  (section outer "Outer" {:per person :when (= person.key :A)}
                    (section inner "Inner" {:when (= person.key :B)}
                      (line scalar "Scalar" 10 {:per [] :op :info}))))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val guardedResult = Mantra.calculate(guardedSchema)
        assertTrue(guardedResult.succeeded, guardedResult.diagnostics.toString())
        assertTrue(!guardedResult.node("scalar").isActive())

        val workbook = ExcelExport.workbook(guardedResult, Presets.DE_STAFFEL_4)
        assertEquals(emptyList(), workbook.report.fallbacks)
        assertEquals(emptyList(), workbook.report.evaluationErrors)
        assertEquals(0.0, value(workbook, "scalar"))
    }

    private fun value(workbook: ExcelWorkbook, id: String, coord: List<String> = emptyList()): Double {
        val ref = CellReference(workbook.address(id, coord)!!)
        val cell = workbook.workbook.getSheet(ref.sheetName).getRow(ref.row).getCell(ref.col.toInt())
        workbook.workbook.creationHelper.createFormulaEvaluator().evaluateFormulaCell(cell)
        return cell.numericCellValue
    }

    private fun assertEquals(expected: Double, actual: Double, tolerance: Double) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
}
