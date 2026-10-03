package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.Render
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Einkommensteuer 2025 für einen fiktiven Fall. Die Sollwerte sind unabhängig nachgerechnet
 * mit apps/de-est/verify_expected.py (reines Python-Decimal, ohne Engine-Code);
 * § 32a EStG 2025 gemäß amtlicher Tarifformel.
 */
class EinkommensteuerTest {
    private val dir = Path.of("apps/de-est")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val result = Mantra.calculate(schema, Mantra.loadCase(dir.resolve("case-mustermann.mantra")))

    private fun assertAmount(expected: String, id: String, vararg coord: String) =
        assertEquals(0, BigDecimal(expected).compareTo(result.decimal(id, *coord)), "$id${coord.toList()} = ${result.decimal(id, *coord).toPlainString()}")

    @Test
    fun `computes the joint assessment including Guenstigerpruefung`() {
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        // Einkünfte
        assertAmount("66160", "einkuenfte-nsa", "A")
        assertAmount("29970", "einkuenfte-nsa", "B")
        assertAmount("1230", "werbungskosten", "B") // Pauschbetrag statt 650 € Einzelnachweis
        assertAmount("12400", "einkuenfte-gewerbe", "A")
        assertAmount("850", "einkuenfte-kap", "B") // Abgeltungsteuer, nicht im Tarif
        assertAmount("0", "veraeusserung-steuerpflichtig", "B") // Freigrenze
        assertAmount("3850", "einkuenfte-vuv")
        assertAmount("112380", "summe-der-einkuenfte")
        // Abzüge
        assertAmount("11850.50", "vorsorgeaufwendungen", "A")
        assertAmount("5511.60", "vorsorgeaufwendungen", "B")
        assertAmount("540", "schulgeld") // benutzerdefinierte Slot-Zeile
        assertAmount("19562.10", "sonderausgaben-summe")
        assertAmount("3830.50", "zumutbare-belastung")
        assertAmount("0", "agb-summe")
        assertAmount("92817.90", "einkommen")
        // Günstigerprüfung § 31 EStG
        assertAmount("18878", "est-ohne-kfb")
        assertAmount("15676", "est-mit-kfb")
        assertEquals(Value.Bool(true), result.value("kfb-guenstiger"))
        assertEquals("freibetraege", (result.node("kinderentlastung").trace() as NodeTrace.Choice).selected)
        assertAmount("83217.90", "zu-versteuerndes-einkommen")
        // Steuer
        assertAmount("15676", "tarifliche-est")
        assertAmount("740", "ermaessigung-35a")
        assertAmount("17996", "festzusetzende-est")
        assertAmount("0", "solidaritaetszuschlag")
        assertAmount("1344.24", "kirchensteuer")
        assertAmount("1462.24", "abrechnungsergebnis")
        assertAmount("212.50", "abgeltungsteuer")
        assertAmount("11.68", "abgeltung-soli")
    }

    @Test
    fun `parameter set 2026 recomputes the same case under 2026 law`() {
        val params = Mantra.loadParameters(dir.resolve("params-2026.mantra"))
        val result2026 = Mantra.calculate(schema, result.case, listOf(params))
        assertTrue(result2026.succeeded, result2026.diagnostics.joinToString("\n"))
        val tarif = result2026.node("tarif-gfb")
        assertEquals("de.est/params-2026", tarif.parameterSource)
        // Expected values: apps/de-est/verify_expected.py (parameters 2026).
        fun amount(expected: String, id: String) =
            assertEquals(0, BigDecimal(expected).compareTo(result2026.decimal(id)), "$id = ${result2026.decimal(id).toPlainString()}")
        amount("18618", "est-ohne-kfb")
        amount("15396", "est-mit-kfb")
        amount("83061.90", "zu-versteuerndes-einkommen")
        amount("17764", "festzusetzende-est")
        amount("1319.04", "kirchensteuer")
        amount("1205.04", "abrechnungsergebnis")
        // A case-level override still wins over the parameter set.
        val overridden = Mantra.calculate(schema, result.case.copy(params = mapOf("kindergeld-monat" to Value.num(300))), listOf(params))
        assertEquals("case", overridden.node("kindergeld-monat").parameterSource)
    }

    @Test
    fun `tariff 2025 matches the statutory formula at zone boundaries`() {
        val probe = Mantra.loadSchema(
            SourceText(
                "probe.mantra",
                Files.readString(dir.resolve("schema.mantra")).replace(
                    "(section merkmale",
                    """(section probe "Tarifproben" {:display :hidden}
                         (line t1 "12.096" (est-tarif 12096 false) {:op :info})
                         (line t2 "17.443" (est-tarif 17443 false) {:op :info})
                         (line t3 "40.000" (est-tarif 40000 false) {:op :info})
                         (line t4 "68.480" (est-tarif 68480 false) {:op :info})
                         (line t5 "300.000" (est-tarif 300000 false) {:op :info})
                         (line s1 "80.000 Splitting" (est-tarif 80000 true) {:op :info}))
                       (section merkmale""",
                ),
                dir.toAbsolutePath().toString(),
            ),
            com.xqiou.mantra.core.FileSources,
        )
        val tariff = Mantra.calculate(probe)
        assertTrue(tariff.succeeded, tariff.diagnostics.joinToString("\n"))
        fun t(id: String) = tariff.decimal(id).toBigInteger().toInt()
        assertEquals(0, t("t1"))
        assertEquals(1015, t("t2"))   // (932,30 · 0,5347 + 1.400) · 0,5347 = 1.015,13 → 1.015
        assertEquals(7320, t("t3"))
        assertEquals(17849, t("t4"))  // Zone 3: (176,64 · 5,1037 + 2.397) · 5,1037 + 1.015,13 = 17.849,77
        assertEquals(115753, t("t5")) // 0,45 · 300.000 − 19.246,67 = 115.753,33
        assertEquals(2 * 7320, t("s1"))
    }

    @Test
    fun `renders the German four-column working paper`() {
        val layout = Render.loadLayout(dir.resolve("layout.mantra"))
        val text = Render.text(result, layout, includeAudit = true)
        val html = Render.html(result, layout)
        val out = Path.of("apps/de-est/build/out").also(Files::createDirectories)
        Files.writeString(out.resolve("est-2025-mustermann.txt"), text)
        Files.writeString(out.resolve("est-2025-mustermann.html"), html)
        println(text)
        assertTrue("Zu versteuerndes Einkommen" in text)
        assertTrue("Person A" in text && "Person B" in text)
        assertTrue("./." in text)
    }
}
