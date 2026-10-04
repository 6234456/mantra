package com.xqiou.mantra.excel

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunUsage
import com.xqiou.mantra.core.view.Coord
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class ExcelOptions(
    /** Define workbook names for every node, so formulas read like the schema (`=MAX(wk.A, pausch)`). */
    val useNames: Boolean = true,
    /** Add a collapsed column with the original DSL formula of each line. */
    val formulaColumn: Boolean = true,
    /** Evaluate all formulas once with POI so that the file carries cached values. */
    val evaluate: Boolean = true,
    val maxSheets: Int = Int.MAX_VALUE,
    val maxCells: Int = Int.MAX_VALUE,
    /** Actual callback translation rows across this export; independent of engine iteration budgets. */
    val maxConvergenceSteps: Int = 100_000,
    /** One read request spanning paper construction, formula translation and POI evaluation. */
    val reading: CalculationOptions = CalculationOptions(),
) {
    init {
        require(maxSheets > 0 && maxCells > 0 && maxConvergenceSteps >= 0)
    }
}

class ExcelExportLimitException(message: String) : RuntimeException(message)

data class ExcelFallback(val sheet: String, val cell: String, val nodeId: String, val reason: String)

data class ExcelCellDescription(val address: String, val kind: String, val value: String?, val formula: String?)
data class ExcelSheetDescription(val name: String, val rows: Int, val columns: Int)
data class ExcelNameDescription(val name: String, val refersTo: String)
data class ExcelPreview(val rows: Int, val columns: Int, val truncated: Boolean, val cells: List<ExcelCellDescription>)
data class ExcelDescription(
    val sheets: List<ExcelSheetDescription>,
    val selectedSheet: String,
    val preview: ExcelPreview,
    val names: List<ExcelNameDescription>,
    val report: ExcelReport,
)

class ExcelReport(
    val sheets: List<String>,
    val formulaCells: Int,
    val inputCells: Int,
    val names: Int,
    /** Cells that hold a computed value instead of a formula, with the reason. */
    val fallbacks: List<ExcelFallback>,
    val evaluationErrors: List<String>,
    val readingUsage: RunUsage? = null,
)

/** A generated workbook plus the map of where every node lives (used by tests and re-import). */
class ExcelWorkbook internal constructor(
    val workbook: XSSFWorkbook,
    val report: ExcelReport,
    private val nodeAddresses: Map<String, Map<Coord, String>>,
    private val recordAddresses: Map<Triple<String, String, String>, String>,
    private val tableAddresses: Map<Triple<String, Int, String>, String>,
    private val aggregateAddresses: Map<Pair<String, Map<String, String>>, String> = emptyMap(),
) : AutoCloseable {
    override fun close() = workbook.close()

    fun bytes(maxBytes: Int = Int.MAX_VALUE): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        require(maxBytes > 0)
        try {
            workbook.write(BoundedWorkbookOutput(output, maxBytes))
        } catch (error: RuntimeException) {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is ExcelExportLimitException) throw cause
                cause = cause.cause
            }
            throw error
        }
        output.toByteArray()
    }

    /** Bounded, read-only description of the workbook actually written by this exporter. */
    fun describe(selectedSheet: String? = null): ExcelDescription? {
        val sheets = (0 until workbook.numberOfSheets).map { index ->
            val sheet = workbook.getSheetAt(index)
            ExcelSheetDescription(
                sheet.sheetName,
                if (sheet.physicalNumberOfRows == 0) 0 else sheet.lastRowNum + 1,
                sheet.maxOfOrNull { row -> row.lastCellNum.toInt().coerceAtLeast(0) } ?: 0,
            )
        }
        val chosen = selectedSheet ?: sheets.firstOrNull()?.name ?: return null
        val selected = sheets.firstOrNull { it.name == chosen } ?: return null
        val sheet = workbook.getSheet(chosen)
        val cells = buildList {
            for (rowIndex in 0 until minOf(selected.rows, 50)) {
                val row = sheet.getRow(rowIndex) ?: continue
                for (columnIndex in 0 until minOf(selected.columns, 20)) {
                    val cell = row.getCell(columnIndex) ?: continue
                    if (cell.cellType == CellType.BLANK) continue
                    val valueType = if (cell.cellType ==
                        CellType.FORMULA
                    ) {
                        cell.cachedFormulaResultType
                    } else {
                        cell.cellType
                    }
                    val value = when (valueType) {
                        CellType.NUMERIC -> java.math.BigDecimal.valueOf(cell.numericCellValue).toPlainString()
                        CellType.STRING -> cell.stringCellValue
                        CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        else -> null
                    }
                    add(
                        ExcelCellDescription(
                            CellReference(rowIndex, columnIndex).formatAsString(),
                            cell.cellType.name.lowercase(),
                            value,
                            if (cell.cellType == CellType.FORMULA) cell.cellFormula else null,
                        ),
                    )
                }
            }
        }
        return ExcelDescription(
            sheets,
            chosen,
            ExcelPreview(selected.rows, selected.columns, selected.rows > 50 || selected.columns > 20, cells),
            workbook.allNames.map { ExcelNameDescription(it.nameName, it.refersToFormula) },
            report,
        )
    }

    /**
     * A1 address (`'Sheet'!$C$5`) of a node's cell for a member coordinate.
     * `aggregate.<nodeId>` with an empty coordinate addresses the first visible cross-total cell.
     */
    fun address(nodeId: String, coord: Coord = emptyList()): String? = nodeAddresses[nodeId]?.get(coord)

    /** A1 address of an exported reduction, with dimensions explicitly fixed to member keys. */
    fun aggregateAddress(nodeId: String, fixed: Map<String, String> = emptyMap()): String? =
        aggregateAddresses[nodeId to fixed] ?: if (fixed.isEmpty()) address("aggregate.$nodeId") else null

    /** A1 address of a table-input cell: row identified by the member key of a dimension drawn from the table. */
    fun recordAddress(dimension: String, key: String, column: String): String? =
        recordAddresses[Triple(dimension, key, column)]

    /** A1 address of a table-input cell, with a zero-based row index. */
    fun tableAddress(inputId: String, rowIndex: Int, column: String): String? =
        tableAddresses[Triple(inputId, rowIndex, column)]

    fun write(path: java.nio.file.Path) {
        path.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.newOutputStream(path).use { workbook.write(it) }
    }
}

private class BoundedWorkbookOutput(private val target: java.io.OutputStream, private val limit: Int) :
    java.io.OutputStream() {
    private var written = 0L
    override fun write(value: Int) {
        checkCapacity(1)
        target.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkCapacity(length)
        target.write(bytes, offset, length)
    }
    private fun checkCapacity(length: Int) {
        if (written + length > limit) throw ExcelExportLimitException("Workbook exceeds $limit bytes")
        written += length
    }
}
