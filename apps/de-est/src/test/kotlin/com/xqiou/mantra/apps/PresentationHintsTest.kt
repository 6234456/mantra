package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.StructureJson
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.displayLabel
import com.xqiou.mantra.core.view.groupKey
import com.xqiou.mantra.core.view.groupTitle
import com.xqiou.mantra.core.view.headlineId
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.ColumnContent
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresentationHintsTest {
    @Test
    fun `ESt headline sign labels and input groups reach paper structure and workbook`() {
        val dir = Path.of("apps/de-est")
        val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
        val original = Mantra.loadCase(dir.resolve("case-mustermann.mantra"))
        val layout = Render.loadLayout(dir.resolve("layout.mantra"))
        val positive = CalculationView.of(Mantra.calculate(schema, original))
        assertEquals("abrechnungsergebnis", positive.headlineId)
        assertEquals("Nachzahlung", positive.node("abrechnungsergebnis").displayLabel())
        assertEquals("assessment", positive.node("veranlagungsart").groupKey)
        assertEquals("Veranlagungsmerkmale", positive.groupTitle("assessment"))
        val paper = Render.paper(positive, layout)
        assertEquals("Nachzahlung", paper.headline?.label)
        assertTrue(paper.inputGroups.any { it.key == "assessment" && "veranlagungsart" in it.inputs })
        val resultRow = paper.tables.flatMap { it.rows }.single { it.nodeId == "abrechnungsergebnis" }
        val labelIndex = paper.tables.first { resultRow in it.rows }.columns.indexOfFirst {
            it.content ==
                ColumnContent.Label
        }
        assertEquals("Nachzahlung", resultRow.cells[labelIndex])
        val json = StructureJson.write(positive)
        assertTrue("\"headline\": \"abrechnungsergebnis\"" in json)
        assertTrue("\"groupTitle\": \"Veranlagungsmerkmale\"" in json)
        assertTrue("\"positive\": \"Nachzahlung\"" in json)

        val negativeCase = original.copy(inputs = original.inputs + ("vorauszahlungen" to Value.num(10000)))
        val negative = CalculationView.of(Mantra.calculate(schema, negativeCase))
        assertTrue(negative.decimal("abrechnungsergebnis").signum() < 0)
        assertEquals("Erstattung", negative.node("abrechnungsergebnis").displayLabel())
        assertEquals("Erstattung", Render.paper(negative, layout).headline?.label)

        val workbook = ExcelExport.workbook(Mantra.calculate(schema, original), layout)
        val formulaCells = workbook.workbook.sheetIterator().asSequence().flatMap { sheet ->
            sheet.rowIterator().asSequence().flatMap { row -> row.cellIterator().asSequence() }
        }.filter {
            it.cellType == CellType.FORMULA && "Nachzahlung" in it.cellFormula && "Erstattung" in it.cellFormula
        }.toList()
        assertTrue(formulaCells.size >= 2, "the table label and overview headline should both recalculate")
        val inputRef = CellReference(workbook.address("vorauszahlungen")!!)
        workbook.workbook.getSheet(
            inputRef.sheetName,
        ).getRow(inputRef.row).getCell(inputRef.col.toInt()).setCellValue(10000.0)
        val evaluator = workbook.workbook.creationHelper.createFormulaEvaluator()
        evaluator.clearAllCachedResultValues()
        assertTrue(formulaCells.all { evaluator.evaluate(it).stringValue == "Erstattung" })
        workbook.workbook.close()
    }
}
