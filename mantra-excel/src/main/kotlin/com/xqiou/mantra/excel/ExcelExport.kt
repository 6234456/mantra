package com.xqiou.mantra.excel

import com.xqiou.mantra.core.engine.CalculationResult
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutSpec

/**
 * Excel export that keeps the calculation logic: every calculated cell is an Excel formula over
 * named input and parameter cells, so the workbook recalculates when inputs change.
 */
object ExcelExport {
    fun workbook(result: CalculationResult, layout: LayoutSpec = Render.defaultLayout(result), options: ExcelOptions = ExcelOptions()): ExcelWorkbook =
        workbook(CalculationView.of(result), layout, options)

    fun workbook(view: CalculationView, layout: LayoutSpec = Render.defaultLayout(view), options: ExcelOptions = ExcelOptions()): ExcelWorkbook =
        ExcelWorkbookBuilder(view, layout, options).build()
}
