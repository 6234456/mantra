package com.xqiou.mantra.deest

import com.xqiou.mantra.acceptance.AcceptanceAssertions
import com.xqiou.mantra.acceptance.AcceptanceCalculationRequest
import com.xqiou.mantra.acceptance.AcceptanceCaseBinder
import com.xqiou.mantra.acceptance.AcceptanceEvidence
import com.xqiou.mantra.acceptance.AcceptanceExecution
import com.xqiou.mantra.acceptance.loadGenericAcceptanceEnvironment
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.NodeTrace
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Frozen loss/trade facts in independent/; no engine output is an expected-value source. */
class M3LinkedAssessmentTest {
    private val directory = Path.of(requireNotNull(System.getProperty("mantra.appDir")))
    private val environment by lazy { loadGenericAcceptanceEnvironment(directory) }

    private fun run(id: String): AcceptanceExecution {
        val entry = environment.ownedCases.single { it.case.id == id }
        val binding = AcceptanceCaseBinder.bind(environment.documents, directory, environment.workspaceRoot, entry)
        val execution = environment.calculations.calculate(
            AcceptanceCalculationRequest(binding, AcceptanceEvidence.FULL),
        )
        AcceptanceAssertions.ordinary(binding, execution, environment.sourcePaths)
        return execution
    }

    private fun amount(run: AcceptanceExecution, node: String, expected: String, coord: List<String> = emptyList()) {
        val actual = run.result.view.node(node).value(coord)
        assertTrue(actual is Value.Num, "$node$coord must be an actual decimal")
        assertEquals(0, BigDecimal(expected).compareTo(actual.value), "$node$coord")
    }

    @Test
    fun priorYearClosingBecomesNextYearOpeningAndARevisedSourceChangesTheBalance() {
        val base = run("consumer-2025")
        val revised = run("consumer-revised-2025")
        amount(base, "verlustvortrag", "45000")
        amount(base, "loss-used", "32000")
        amount(base, "closing-loss", "13000")
        amount(revised, "verlustvortrag", "55000")
        amount(revised, "closing-loss", "23000")
        val source = base.sources.single()
        assertEquals("de.est-loss/2024", source.identity.schema.id)
        assertEquals("2024.1", source.identity.schema.version)
        assertTrue(source.identity.revision.isNotBlank())
        assertTrue(revised.sources.single().identity.revision != source.identity.revision)
    }

    @Test
    fun linkedZeroRetainsLinkPresenceRatherThanBecomingTheTargetDefault() {
        val zero = run("consumer-zero-2025")
        amount(zero, "verlustvortrag", "0")
        val trace = assertNotNull(zero.result.nodes["verlustvortrag"]?.trace() as? NodeTrace.Input)
        assertEquals("LINK", trace.origin.name)
        assertEquals(1, zero.links.size)
        assertTrue((zero.links.single().value as Value.Num).value.signum() == 0)
    }

    @Test
    fun assessmentAmountAndTaxDueComeFromOneTradeCaseWithCompletePersonCoordinates() {
        val linked = run("consumer-rate-400")
        assertEquals(1, linked.sources.size, "Two mappings share one genuine source calculation")
        assertEquals(2, linked.links.size)
        assertTrue(linked.links.all { it.from.coord.isEmpty() && it.to.coord == listOf("A") })
        amount(linked, "gewst-messbetrag", "3605", listOf("A"))
        amount(linked, "gewst-due", "14420", listOf("A"))
        amount(linked, "festzusetzende-est", "25052")
        assertEquals("2025.1", linked.sources.single().identity.schema.version)
        assertEquals("2025.3", linked.identity.schema.version)
    }

    @Test
    fun aSourceBusinessErrorKeepsItsValueAndOwnedFindingAcrossTheYearLink() {
        val invalid = run("consumer-business-source-2025")
        assertTrue(invalid.succeeded)
        assertFalse(invalid.validationPassed)
        amount(invalid, "verlustvortrag", "4900")
        val finding = invalid.inheritedDiagnostics.single()
        assertEquals("new-loss-nonnegative", finding.diagnostic.nodeId)
        assertEquals("de.est-loss/2024", finding.source.schema.id)
        assertEquals("2024.1", finding.source.schema.version)
        assertTrue(finding.source.revision.isNotBlank())
    }
}
