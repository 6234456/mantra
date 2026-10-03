package com.xqiou.mantra.benchmarks

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.excel.ExcelWorkbook
import com.xqiou.mantra.render.layout.ExplainMode
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.math.BigDecimal

/** A domain-free fixture, with independent arithmetic expectations for every calculated value. */
data class Scenario(val name: String, val lines: Int, val members: Int, val tableRows: Int) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9-]*")))
        require(lines in 1..400 && members in 1..100 && tableRows in 1..1_000) {
            "Keep this baseline within the pinned parser and workbook limits: " +
                "lines 1..400, members 1..100, rows 1..1000"
        }
    }
}

class SyntheticFixture(val scenario: Scenario) {
    val schemaText: String = buildString {
        appendLine(
            "(schema benchmark/${scenario.name} {:title \"Synthetic baseline\" " +
                ":mainline [matrix summary] :result answer}",
        )
        appendLine("  (param factor 1.25)")
        appendLine("  (input basis :decimal {:per entity})")
        appendLine("  (input facts :table {:columns {:amount :decimal}})")
        append("  (dimension entity {:members [")
        repeat(scenario.members) { member -> append("{:key :m$member :label \"Member $member\"} ") }
        appendLine("]})")
        appendLine("  (section matrix \"Member calculations\" {:per entity :layout :matrix :panel true}")
        repeat(scenario.lines) { line ->
            val expression = if (line == 0) "(* basis factor)" else "(+ step${line - 1} 1)"
            appendLine("    (line step$line \"Step $line\" $expression {:op :info})")
        }
        appendLine("  )")
        appendLine("  (section summary \"Scalar summary\" {:panel true}")
        appendLine("    (line table-sum \"Table sum\" (sum (map (fn [row] row.amount) facts)) {:op :info})")
        appendLine("    (line member-sum \"Member sum\" (dim/sum all.step${scenario.lines - 1}) {:op :info})")
        appendLine("    (line answer \"Combined answer\" (+ table-sum member-sum) {:op :info})))")
    }
    val caseText: String = buildString {
        append("(case synthetic (inputs {:basis {")
        repeat(scenario.members) { member -> append(":m$member ${member + 1} ") }
        append("} :facts [")
        repeat(scenario.tableRows) { row -> append("{:amount ${row + 1}} ") }
        append("]}))")
    }
    val schema: Schema = Mantra.loadSchema(SourceText("schema.mantra", schemaText), SourceResolver { _, _ -> null })
    val case: CaseData = Mantra.loadCase(SourceText("case.mantra", caseText))
    val layout = Presets.IFRS_SCHEDULE.copy(
        id = "benchmark/layout",
        number = Presets.IFRS_SCHEDULE.number.copy(precision = 2),
        explain = ExplainMode.APPENDIX,
    )
    val explainNode: String = "step${scenario.lines - 1}"
    val explainCoord: List<String> = listOf("m${scenario.members - 1}")

    fun expectedStep(line: Int, member: Int): BigDecimal =
        BigDecimal.valueOf(member + 1L).multiply(BigDecimal("1.25")).add(BigDecimal.valueOf(line.toLong()))

    val expectedTableSum: BigDecimal = BigDecimal.valueOf(scenario.tableRows.toLong())
        .multiply(BigDecimal.valueOf(scenario.tableRows + 1L)).divide(BigDecimal.valueOf(2))
    val expectedMemberSum: BigDecimal = (0 until scenario.members)
        .fold(BigDecimal.ZERO) { sum, member -> sum.add(expectedStep(scenario.lines - 1, member)) }
    val expectedAnswer: BigDecimal = expectedTableSum.add(expectedMemberSum)

    fun verify(result: CalculationResult) {
        check(result.succeeded) { result.diagnostics.joinToString("\n") }
        repeat(scenario.lines) { line ->
            repeat(scenario.members) { member ->
                equalValue("step$line/m$member", expectedStep(line, member), result.decimal("step$line", "m$member"))
            }
        }
        equalValue("table-sum", expectedTableSum, result.decimal("table-sum"))
        equalValue("member-sum", expectedMemberSum, result.decimal("member-sum"))
        equalValue("answer", expectedAnswer, result.decimal("answer"))
    }

    /** Checks cached workbook values after the exporter has recalculated its formulas with POI. */
    fun verify(workbook: ExcelWorkbook) {
        check(workbook.report.evaluationErrors.isEmpty()) { workbook.report.evaluationErrors.joinToString("\n") }
        check(workbook.report.fallbacks.isEmpty()) {
            "Baseline workbook must remain recalculable:\n" +
                workbook.report.fallbacks.joinToString("\n") { "${it.nodeId}: ${it.reason}" }
        }
        fun value(id: String, coord: List<String>, expected: BigDecimal) {
            val address = checkNotNull(workbook.address(id, coord)) { "No workbook address for $id/$coord" }
            val reference = CellReference(address)
            val cell = workbook.workbook.getSheet(reference.sheetName).getRow(reference.row.toInt())
                .getCell(reference.col.toInt())
            val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
            check(type == CellType.NUMERIC) { "$id/$coord exported as $type at $address" }
            equalValue("xlsx:$id/$coord", expected, BigDecimal.valueOf(cell.numericCellValue))
        }
        repeat(scenario.lines) { line ->
            repeat(scenario.members) { member -> value("step$line", listOf("m$member"), expectedStep(line, member)) }
        }
        value("table-sum", emptyList(), expectedTableSum)
        value("member-sum", emptyList(), expectedMemberSum)
        value("answer", emptyList(), expectedAnswer)
    }

    private fun equalValue(label: String, expected: BigDecimal, actual: BigDecimal) {
        check(expected.compareTo(actual) == 0) { "$label: expected $expected but received $actual" }
    }
}

val baselineScenarios = listOf(
    Scenario("small", 25, 5, 25),
    Scenario("lines", 250, 5, 25),
    Scenario("members", 25, 50, 25),
    Scenario("table-rows", 25, 5, 1_000),
    Scenario("combined", 250, 50, 1_000),
)
