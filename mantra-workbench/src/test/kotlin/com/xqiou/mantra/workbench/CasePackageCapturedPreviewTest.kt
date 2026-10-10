package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.CaseRunResult
import com.xqiou.mantra.core.api.RunCancellation
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A candidate reads the captured source graph, including imported facts, rather than a second live graph. */
class CasePackageCapturedPreviewTest {
    @TempDir lateinit var root: Path
    private val key = CanonicalCaseKey("case.mantra")

    private fun fixture(): Map<String, String> = linkedMapOf(
        "case.mantra" to """
            (case seed {:schema "test/captured" :parameters ["test/captured-rates"]
                        :layout "test/captured-layout" :title "Captured case"}
              (sources (csv {:path "facts.csv"}))
              (inputs {:offset 1}))
        """.trimIndent(),
        "schema.mantra" to """
            (schema test/captured {:title "Captured schema" :mainline [main]}
              (include "rules.mantra")
              (input imported :decimal)
              (input offset :decimal)
              (param rate 2)
              (section main "Captured section" {:panel true}
                (line answer "Captured answer" (+ (* (bump imported) rate) offset))))
        """.trimIndent(),
        "rules.mantra" to "(fragment (defn bump [^Decimal amount] (+ amount 1)))",
        "parameters.mantra" to "(parameters test/captured-rates {:for \"test/captured\"} (value rate 2))",
        "layout.mantra" to """
            (layout test/captured-layout {:language :en :title "Captured layout"}
              (table main {:style :matrix} :label :value))
        """.trimIndent(),
        "facts.csv" to "input;value\nimported;10\n",
    ).also { documents -> documents.forEach { (name, text) -> Files.writeString(root.resolve(name), text) } }

    private fun run(loader: CasePackageLoader): CaseRunResult = CaseGraphRunner(loader, 0).use {
        it.run(CaseRunRequest(CaseReference(key.value)))
    }.also { assertTrue(it.succeeded, it.diagnostics.toString()) }

    private fun answer(run: CaseRunResult): String = checkNotNull(run.result).decimal("answer").toPlainString()

    @Test fun `fork freezes parsed documents includes layout parameters and CSV while a new loader sees changes`() {
        val original = fixture()
        val loader = CasePackageLoader(root)
        val before = run(loader)
        assertEquals("23", answer(before), "(10 + 1) * 2 + 1")
        val capturedTexts = loader.sourceTexts(key)
        assertEquals(original.keys - "facts.csv", capturedTexts.keys)
        assertTrue(
            loader.binding(key).packageData.sources.any {
                it.role == SourceRole.DATA &&
                    it.identity == "facts.csv"
            },
        )
        val candidateText = original.getValue("case.mantra").replace(":offset 1", ":offset 4")
        val candidate = loader.fork(CasePackageOverrides(key.value, candidateText))

        original.forEach { (name, text) ->
            val changed = when (name) {
                "case.mantra" -> text.replace(":offset 1", ":offset 5").replace("Captured case", "Live case")
                "rules.mantra" -> text.replace("amount 1", "amount 10")
                "parameters.mantra" -> text.replace("rate 2", "rate 3")
                "facts.csv" -> text.replace("imported;10", "imported;20")
                else -> text.replace("Captured", "Live")
            }
            Files.writeString(root.resolve(name), changed)
        }

        val proposed = run(candidate)
        assertEquals("26", answer(proposed), "(10 + 1) * 2 + 4 uses the original graph and the candidate case")
        assertEquals("Captured layout", candidate.binding(key).layout?.title)
        assertEquals(candidateText, candidate.sourceTexts(key).getValue(key.value).text)
        capturedTexts.filterKeys { it != key.value }.forEach { (name, source) ->
            assertEquals(source.text, candidate.sourceTexts(key).getValue(name).text, name)
        }
        val originalSources = loader.binding(key).packageData.sources.associateBy { it.identity }
        candidate.binding(key).packageData.sources.filter { it.identity != key.value }.forEach { source ->
            assertEquals(originalSources.getValue(source.identity), source, source.identity)
        }
        assertNotEquals(before.cases.getValue(key).revision, proposed.cases.getValue(key).revision)

        val live = CasePackageLoader(root)
        assertEquals("95", answer(run(live)), "(20 + 10) * 3 + 5 uses the new live graph")
        assertEquals("Live layout", live.binding(key).layout?.title)
        assertEquals(original.getValue("case.mantra"), loader.sourceTexts(key).getValue(key.value).text)
    }

    private class CountingControl(private val limit: Long = Long.MAX_VALUE) : CaseLoadControl {
        override val deadline = Instant.MAX
        override val cancellation = RunCancellation.NONE
        var bytes = 0L
            private set
        var rows = 0L
            private set
        override fun checkpoint() = Unit
        override fun chargeParticipatingBytes(amount: Long) {
            bytes += amount
            if (bytes > limit) throw IllegalStateException("Captured source byte budget exhausted")
        }
        override fun chargeInputRows(amount: Long) {
            rows += amount
        }
    }

    @Test fun `fork reuses index but charges selected captures and new CSV against its own budget`() {
        val original = fixture()
        Files.writeString(
            root.resolve("unrelated.mantra"),
            "; ${"index-only".repeat(1000)}\n" +
                "(parameters unrelated/rates {:for \"test/captured\"} (value rate 7))",
        )
        val loader = CasePackageLoader(root)
        val initialControl = CountingControl()
        loader.load(key, initialControl)
        val caseText = original.getValue(key.value)
        val baselineControl = CountingControl()
        loader.fork(CasePackageOverrides(key.value, caseText)).load(key, baselineControl)
        assertTrue(baselineControl.bytes > 0, "A new graph run must charge reused participating buffers")
        assertTrue(
            baselineControl.bytes < initialControl.bytes,
            "Reused index metadata does not reread index-only bytes",
        )

        val extra = "input;value\nimported;99\n"
        Files.writeString(root.resolve("additional.csv"), extra)
        val candidateText = caseText.replace(
            "(sources (csv {:path \"facts.csv\"}))",
            "(sources (csv {:path \"facts.csv\"}) (csv {:path \"additional.csv\"}))",
        )
        val candidateControl = CountingControl()
        val candidate = loader.fork(CasePackageOverrides(key.value, candidateText)).load(key, candidateControl)
        assertEquals(Value.num("99"), candidate.caseData.inputs["imported"])
        assertEquals(2L, candidateControl.rows, "Both declared CSV records use the candidate's row budget")
        assertTrue(candidate.sources.any { it.role == SourceRole.DATA && it.identity == "additional.csv" })
        val candidateTextGrowth =
            candidateText.toByteArray(Charsets.UTF_8).size - caseText.toByteArray(Charsets.UTF_8).size
        val existingCaptureBudget = baselineControl.bytes + candidateTextGrowth
        assertEquals(existingCaptureBudget + extra.toByteArray(Charsets.UTF_8).size, candidateControl.bytes)
        val boundedControl = CountingControl(existingCaptureBudget)
        val failure = assertFailsWith<IllegalStateException> {
            loader.fork(CasePackageOverrides(key.value, candidateText)).load(key, boundedControl)
        }
        assertEquals("Captured source byte budget exhausted", failure.message)
        assertTrue(boundedControl.bytes > existingCaptureBudget)
    }

    @Test fun `fork freezes symlink ABA path identity while fresh loaders observe its target`() {
        val original = fixture()
        val source = root.resolve("facts.csv")
        val capturedTarget = root.resolve("original.csv")
        Files.move(source, capturedTarget)
        Files.createSymbolicLink(source, capturedTarget.fileName)
        Files.writeString(root.resolve("transient.csv"), "input;value\nimported;99\n")
        val loader = CasePackageLoader(root)
        val before = run(loader)
        val candidate = loader.fork(
            CasePackageOverrides(key.value, original.getValue(key.value).replace(":offset 1", ":offset 4")),
        )

        Files.delete(source)
        Files.createSymbolicLink(source, Path.of("transient.csv"))
        val proposed = run(candidate)
        assertEquals("26", answer(proposed), "The candidate must not capture transient data through an old alias")
        assertEquals("201", answer(run(CasePackageLoader(root))), "A new live request sees (99 + 1) * 2 + 1")
        assertTrue(candidate.binding(key).packageData.sources.any { it.identity == "original.csv" })
        assertTrue(candidate.binding(key).packageData.sources.none { it.identity == "transient.csv" })

        Files.delete(source)
        Files.createSymbolicLink(source, capturedTarget.fileName)
        val restored = run(CasePackageLoader(root))
        assertEquals("23", answer(restored))
        assertEquals(before.cases.getValue(key).revision, restored.cases.getValue(key).revision)
    }
}
