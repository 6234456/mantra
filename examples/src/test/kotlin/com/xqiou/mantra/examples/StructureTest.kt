package com.xqiou.mantra.examples

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.structure.PanelRole
import com.xqiou.mantra.core.structure.SchemaMaps
import com.xqiou.mantra.core.structure.StructureJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructureTest {
    private val dir = Path.of("examples/de-est-2025")
    private val plan = Mantra.plan(Mantra.loadSchema(dir.resolve("schema.mantra")), Mantra.loadCase(dir.resolve("case-mustermann.mantra")))
    private val map = SchemaMaps.of(plan)

    @Test
    fun `mainline and branch panels are derived from the schema`() {
        assertEquals(listOf("zve", "est", "zuschlagsteuern", "abrechnung"), map.mainline)
        val roles = map.panels.associate { it.id to it.role }
        assertEquals(PanelRole.BRANCH, roles["einkuenfte"])
        assertEquals(PanelRole.BRANCH, roles["sonderausgaben"])
        assertEquals(PanelRole.BRANCH, roles["kinder-pruefung"])
        assertEquals(PanelRole.AUXILIARY, roles["abgeltung"])
        assertEquals(PanelRole.MAINLINE, roles["est"])

        val sonderausgaben = map.panel("sonderausgaben")
        assertEquals(1, sonderausgaben.position?.step)
        assertEquals("sonderausgaben-abzug", sonderausgaben.position?.viaNode)
        // Branch panels import context values from the mainline (Spendenhöchstbetrag needs the GdE).
        assertTrue(sonderausgaben.imports.any { it.fromNode == "gesamtbetrag-der-einkuenfte" && it.fromPanel == "zve" })
        // The child-allowance comparison feeds three mainline steps.
        assertEquals(listOf(1, 2, 3), map.panel("kinder-pruefung").entries.map { it.step }) // zvE, ESt and Soli/KiSt base
        assertEquals(
            listOf("mainline", "1 Ermittlung des zu versteuernden Einkommens", "Sonderausgaben", "Sonderausgaben"),
            sonderausgaben.breadcrumb.map { it.label },
        )
    }

    @Test
    fun `inputs get a home panel when only one panel uses them`() {
        assertTrue("verlustvortrag" in map.panel("zve").fields)
        assertTrue("kirchensteuersatz" in map.panel("zuschlagsteuern").fields)
        assertTrue("bruttoarbeitslohn" in map.panel("einkuenfte").fields)
        assertTrue("veranlagungsart" in map.generalInputs)
        assertTrue("kinder" in map.generalInputs)
    }

    @Test
    fun `structure is exported as JSON for UI clients`() {
        val result = Mantra.calculate(plan)
        val json = StructureJson.write(map, plan, result)
        Files.createDirectories(Path.of("examples/build/out"))
        Files.writeString(Path.of("examples/build/out/est-2025-structure.json"), json)
        assertTrue("\"mainline\"" in json && "\"breadcrumb\"" in json)
        assertTrue("\"resultValue\": 83217.90" in json, json.take(2000))
    }
}
