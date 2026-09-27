package com.xqiou.mantra.core

import com.xqiou.mantra.core.engine.NodeTrace
import com.xqiou.mantra.core.engine.InputOrigin
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.structure.StructureJson
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeKind
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CalculationViewTest {
    private val noIncludes = SourceResolver { _, _ -> null }

    @Test
    fun `view preserves calculated coordinates metadata and traces without exposing plan`() {
        val schema = Mantra.loadSchema(SourceText("schema.mantra", """
            (schema app/view {:version "1" :period "P"}
              (input base :decimal {:per member :default 0 :min 0 :reference "R"})
              (dimension member {:members [:A :B]})
              (section root "Root"
                (section panel "Panel" {:per member :panel true}
                  (line doubled "Doubled" base {:op :plus}))))
        """.trimIndent()), noIncludes)
        val case = Mantra.loadCase(SourceText("case.mantra", "(case c (inputs {:base {:A 3 :B 4}}))"))
        val result = Mantra.calculate(schema, case)
        val view = CalculationView.of(result)

        assertEquals("app/view", view.schema.id)
        assertEquals("1", view.schema.text("version"))
        assertEquals(result.diagnostics, view.diagnostics)
        assertEquals(result.members.keys, view.members.keys)
        assertEquals(NodeKind.INPUT, view.node("base").kind)
        assertEquals("R", view.node("base").presentation.reference)
        assertEquals(Value.Num(BigDecimal.ZERO), view.node("base").input?.default)
        assertEquals(listOf("member"), view.node("doubled").dims)
        assertEquals(result.node("doubled").values, view.node("doubled").values)
        assertEquals(result.node("doubled").active, view.node("doubled").active)
        assertEquals(result.node("doubled").traces, view.node("doubled").traces)
        assertEquals(0, BigDecimal("3").compareTo(view.decimal("doubled", "A")))
        assertIs<NodeTrace.Computed>(view.node("doubled").trace(listOf("A")))
        assertEquals(InputOrigin.CASE, (view.node("base").trace(listOf("B")) as NodeTrace.Input).origin)
        assertEquals(0, BigDecimal("7").compareTo(view.node("doubled").crossTotal()))
        assertTrue(view.structure.panels.any { it.id == "panel" })
        assertEquals(StructureJson.write(view), StructureJson.write(view.structure, result.plan, result))
    }

    @Test
    fun `presentation consumers import no planner vertex types`() {
        var root: Path? = Path.of("").toAbsolutePath()
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.parent
        val project = requireNotNull(root) { "project root not found" }
        val consumers = listOf(
            "mantra-core/src/main/kotlin/com/xqiou/mantra/core/structure/StructureJson.kt",
            "mantra-render/src/main/kotlin/com/xqiou/mantra/render/paper/WorkingPaperBuilder.kt",
            "mantra-excel/src/main/kotlin/com/xqiou/mantra/excel/ExcelWorkbookBuilder.kt",
        )
        val vertexImport = Regex("(?m)^import com\\.xqiou\\.mantra\\.core\\.engine\\.\\w*Vertex\\s*$")
        consumers.forEach { relative ->
            val source = Files.readString(project.resolve(relative))
            assertTrue(!vertexImport.containsMatchIn(source), "$relative imports a planner vertex type")
            assertTrue("result.plan" !in source, "$relative reads the calculation plan")
        }
    }

    @Test
    fun `view identifies formula slots and user extensions with their owning slot`() {
        val schema = Mantra.loadSchema(SourceText("schema.mantra", """
            (schema app/hooks {}
              (input base :decimal)
              (section root "Root"
                (formula-slot adjustable "Adjustable" base {:uses [base]})
                (slot additions "Additions")
                (total total "Total")))
        """.trimIndent()), noIncludes)
        val case = Mantra.loadCase(SourceText("case.mantra", """
            (case c (inputs {:base 2})
              (extend additions (line extra "Extra" 3)))
        """.trimIndent()))
        val view = CalculationView.of(Mantra.calculate(schema, case))
        assertEquals(NodeKind.FORMULA_SLOT, view.node("adjustable").kind)
        assertEquals(NodeKind.EXTENSION, view.node("extra").kind)
        assertTrue(view.node("extra").userDefined)
        assertEquals("additions", view.node("extra").slotId)
        assertEquals(NodeKind.TOTAL, view.node("total").kind)
        assertEquals(0, BigDecimal("5").compareTo(view.node("total").crossTotal()))
    }
}
