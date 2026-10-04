package com.xqiou.mantra.benchmarks

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.excel.ExcelWorkbook
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.paper.WorkingPaper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.math.BigDecimal

/** A domain-free two-axis recurrence, independently checked with triangular closed-form sums. */
class PeriodFixture(val seriesCount: Int = 200, val periodCount: Int = 10) {
    init {
        require(seriesCount in 1..200 && periodCount in 1..10)
    }

    val schemaText = buildString {
        appendLine("(schema benchmark/continuous-periods {:title \"Continuous-period fixture\" :mainline [continuity]}")
        appendLine("  (input seed :decimal {:per series})")
        append("  (dimension series {:members [")
        repeat(seriesCount) { append("{:key :S${it + 1} :label \"Series ${it + 1}\"} ") }
        appendLine("]})")
        appendLine("  (dimension timeline {:periods {:start \"2026-01-01\" :unit :year :count $periodCount}})")
        appendLine("  (section continuity \"Continuous values\" {:per [series timeline] :display :schedule}")
        appendLine("    (line opening \"Opening stock\" (prev closing seed) {:aggregate {:first timeline}})")
        appendLine("    (line movement \"Period movement\" (* 3 (+ timeline.index 1)))")
        appendLine("    (total closing \"Closing stock\" {:aggregate {:last timeline}})))")
    }
    val caseText = buildString {
        append("(case continuous {:schema \"benchmark/continuous-periods\"} (inputs {:seed {")
        repeat(seriesCount) { append(":S${it + 1} ${(it + 1) * 100} ") }
        append("}}))")
    }
    val schema = Mantra.loadSchema(SourceText("period-schema.mantra", schemaText), SourceResolver { _, _ -> null })
    val case = Mantra.loadCase(SourceText("period-case.mantra", caseText))
    val matrixText = """
        (layout benchmark/period-matrix {:preset :ifrs-schedule :title "Continuous-period matrix" :precision 0 :grouping false :zero "0"}
          (table continuity {:style :matrix :row-dimension series}
            :label (members timeline) :cross-total))
    """.trimIndent()
    val transposeText = """
        (layout benchmark/period-transpose {:preset :ifrs-schedule :title "Final-period stocks" :precision 0 :grouping false :zero "0"}
          (table continuity {:style :transpose :row-dimension series :fixed {:timeline :P$periodCount}}
            :label (node opening) (node movement) (node closing)))
    """.trimIndent()
    val layouts: Map<String, LayoutSpec> = linkedMapOf(
        "matrix" to LayoutReader.read(SourceText("period-matrix.mantra", matrixText)),
        "transpose" to LayoutReader.read(SourceText("period-transpose.mantra", transposeText)),
    )
    val explainNode = "opening"
    val explainCoord = listOf("S$seriesCount", "P$periodCount")
    private val numericNodes = listOf("opening", "movement", "closing")

    fun changedCase(firstSeedDelta: Long): CaseData {
        val seeds = case.inputs.getValue("seed") as Value.MapV
        return case.copy(
            inputs = case.inputs + (
                "seed" to Value.MapV(
                    seeds.entries + (Value.Kw("S1") to Value.num((100 + firstSeedDelta).toString())),
                )
                ),
        )
    }

    /** No recurrence or engine values are used by these expectations. */
    fun expected(id: String, series: Int, period: Int, firstSeedDelta: Long = 0): BigDecimal {
        val seed = (series + 1L) * 100 + if (series == 0) firstSeedDelta else 0
        val value = when (id) {
            "seed" -> seed
            "opening" -> seed + 3L * period * (period + 1) / 2
            "movement" -> 3L * (period + 1)
            "closing" -> seed + 3L * (period + 1) * (period + 2) / 2
            else -> error("Unknown fixture node $id")
        }
        return BigDecimal.valueOf(value)
    }

    /** Closed-form scoped expectations distinguish period stock boundaries from movement sums. */
    fun expectedReduction(id: String, fixed: Map<String, String> = emptyMap(), firstSeedDelta: Long = 0): BigDecimal {
        val selectedSeries = fixed["series"]?.removePrefix("S")?.toInt()?.minus(1)
        val selectedPeriod = fixed["timeline"]?.removePrefix("P")?.toInt()?.minus(1)
        val count = if (selectedSeries == null) seriesCount.toLong() else 1L
        val seed = if (selectedSeries == null) {
            100L * seriesCount * (seriesCount + 1) / 2 + firstSeedDelta
        } else {
            (selectedSeries + 1L) * 100 + if (selectedSeries == 0) firstSeedDelta else 0
        }
        val index = selectedPeriod ?: if (id == "opening") 0 else periodCount - 1
        val value = when (id) {
            "opening" -> seed + count * 3L * index * (index + 1) / 2
            "closing" -> seed + count * 3L * (index + 1) * (index + 2) / 2
            "movement" -> if (selectedPeriod ==
                null
            ) {
                count * 3L * periodCount * (periodCount + 1) / 2
            } else {
                count * 3L * (index + 1)
            }
            else -> error("Unknown fixture reduction $id")
        }
        return BigDecimal.valueOf(value)
    }

    fun verify(result: CalculationResult, firstSeedDelta: Long = 0) {
        check(result.succeeded) { result.diagnostics.joinToString("\n") }
        check(result.validationPassed)
        repeat(seriesCount) { series ->
            equal(
                "seed/S${series + 1}",
                expected("seed", series, 0, firstSeedDelta),
                result.decimal("seed", "S${series + 1}"),
            )
            repeat(periodCount) { period ->
                numericNodes.forEach { id ->
                    equal(
                        "$id/S${series + 1}/P${period + 1}",
                        expected(id, series, period, firstSeedDelta),
                        result.decimal(id, "S${series + 1}", "P${period + 1}"),
                    )
                }
            }
        }
        numericNodes.forEach { id ->
            fun reduced(fixed: Map<String, String>) {
                val value = result.view.reduce(id, fixed).value as? Value.Num ?: error("Undefined reduction $id/$fixed")
                equal("aggregate:$id/$fixed", expectedReduction(id, fixed, firstSeedDelta), value.value)
            }
            reduced(emptyMap())
            repeat(seriesCount) { reduced(mapOf("series" to "S${it + 1}")) }
            repeat(periodCount) { reduced(mapOf("timeline" to "P${it + 1}")) }
        }
    }

    fun verify(paper: WorkingPaper, firstSeedDelta: Long = 0): Int {
        var compared = 0
        paper.tables.forEach { table ->
            table.rows.forEach { row ->
                row.valueAddresses.forEachIndexed { column, address ->
                    if (address == null || address.nodeId !in numericNodes) return@forEachIndexed
                    val value = if (address.aggregate) {
                        expectedReduction(address.nodeId, address.fixed, firstSeedDelta)
                    } else {
                        expected(
                            address.nodeId,
                            address.coord[0].removePrefix("S").toInt() - 1,
                            address.coord[1].removePrefix("P").toInt() - 1,
                            firstSeedDelta,
                        )
                    }
                    // This fixture deliberately uses integer output so locale separators can be removed.
                    val displayed = row.cells[column].filter { it.isDigit() || it == '-' }
                    equal("paper:${address.nodeId}/${address.fixed}/${address.coord}", value, BigDecimal(displayed))
                    compared++
                }
            }
        }
        check(compared > 0) { "No addressed numeric cells in the paper" }
        return compared
    }

    fun verify(workbook: ExcelWorkbook, paper: WorkingPaper, firstSeedDelta: Long = 0): Int {
        check(workbook.report.fallbacks.isEmpty()) { workbook.report.fallbacks.joinToString { it.reason } }
        check(workbook.report.evaluationErrors.isEmpty()) { workbook.report.evaluationErrors.joinToString() }
        var compared = 0
        fun value(address: String?, label: String, expected: BigDecimal) {
            val reference = CellReference(checkNotNull(address) { "No workbook address for $label" })
            val cell = workbook.workbook.getSheet(
                reference.sheetName,
            ).getRow(reference.row).getCell(reference.col.toInt())
            val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
            check(type == CellType.NUMERIC) { "$label exported as $type" }
            equal(label, expected, BigDecimal.valueOf(cell.numericCellValue))
            compared++
        }
        repeat(seriesCount) { series ->
            value(
                workbook.address("seed", listOf("S${series + 1}")),
                "seed/S${series + 1}",
                expected("seed", series, 0, firstSeedDelta),
            )
            repeat(periodCount) { period ->
                numericNodes.forEach { id ->
                    val coord = listOf("S${series + 1}", "P${period + 1}")
                    value(workbook.address(id, coord), "$id/$coord", expected(id, series, period, firstSeedDelta))
                }
            }
        }
        paper.tables.flatMap { table -> table.rows.flatMap { it.valueAddresses.filterNotNull() } }
            .filter { it.aggregate && it.nodeId in numericNodes }.distinctBy { it.nodeId to it.fixed }.forEach {
                value(
                    workbook.aggregateAddress(it.nodeId, it.fixed),
                    "aggregate:${it.nodeId}/${it.fixed}",
                    expectedReduction(it.nodeId, it.fixed, firstSeedDelta),
                )
            }
        return compared
    }

    private fun equal(label: String, expected: BigDecimal, actual: BigDecimal) {
        check(expected.compareTo(actual) == 0) { "$label: expected $expected, received $actual" }
    }
}
