package com.xqiou.mantra.workbench

import com.xqiou.mantra.excel.XlsxRegionSource
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/** Bounded sheet/header candidates; inspection never enumerates a worksheet's data cells. */
internal fun xlsxRegionInspection(workbook: XSSFWorkbook): List<Map<String, Any?>> =
    (0 until minOf(workbook.numberOfSheets, 16)).map { index ->
        val sheet = workbook.getSheetAt(index)
        val header = if (sheet.physicalNumberOfRows == 0) null else sheet.getRow(sheet.firstRowNum)
        val firstColumn = header?.firstCellNum?.toInt()?.coerceAtLeast(0) ?: 0
        val lastColumn = header?.lastCellNum?.toInt()?.coerceAtLeast(0) ?: 0
        val headers = (firstColumn until minOf(lastColumn, firstColumn + 64)).mapNotNull { column ->
            val cell = header?.getCell(column)
            if (cell?.cellType != CellType.STRING) return@mapNotNull null
            val title = cell.stringCellValue.trim()
            if (title.isEmpty() || title.length > 256) return@mapNotNull null
            mapOf("column" to column, "title" to title)
        }
        val rows = if (header == null) 0 else sheet.lastRowNum + 1
        val usable = headers.size == lastColumn - firstColumn && headers.isNotEmpty() &&
            headers.map { it["title"] }.distinct().size == headers.size
        val cells = (rows - sheet.firstRowNum).toLong() * (lastColumn - firstColumn)
        val suggested = if (usable && cells <= XlsxRegionSource.MAX_REGION_CELLS) {
            CellReference(sheet.firstRowNum, firstColumn).formatAsString() + ":" +
                CellReference(sheet.lastRowNum, lastColumn - 1).formatAsString()
        } else {
            null
        }
        linkedMapOf<String, Any?>(
            "name" to sheet.sheetName,
            "rows" to rows,
            "columns" to lastColumn,
            "headers" to headers,
        ).also { if (suggested != null) it["suggestedRange"] = suggested }
    }
