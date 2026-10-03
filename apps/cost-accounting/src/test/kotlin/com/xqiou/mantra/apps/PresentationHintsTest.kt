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
    fun `SAP CO variance has the same hint semantics`() {
        val dir = Path.of("apps/cost-accounting")
        val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
        val result = CalculationView.of(Mantra.calculate(schema, Mantra.loadCase(dir.resolve("case-demo.mantra"))))
        val layout = Render.loadLayout(dir.resolve("layout.mantra"))
        assertEquals("total-variance", result.headlineId)
        assertEquals("Unfavourable variance", result.node("total-variance").displayLabel())
        assertEquals("Source data", result.groupTitle(result.node("primary-postings").groupKey!!))
        val paper = Render.paper(result, layout)
        assertEquals("Unfavourable variance", paper.headline?.label)
        assertTrue(paper.inputGroups.any { it.key == "source-data" && "primary-postings" in it.inputs })
        assertTrue("\"headline\": \"total-variance\"" in StructureJson.write(result))
    }
}
