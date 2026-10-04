package com.xqiou.mantra.gewst

import com.xqiou.mantra.acceptance.AcceptanceAssertions
import com.xqiou.mantra.acceptance.AcceptanceCalculationRequest
import com.xqiou.mantra.acceptance.AcceptanceCaseBinder
import com.xqiou.mantra.acceptance.AcceptanceEvidence
import com.xqiou.mantra.acceptance.AcceptanceExecution
import com.xqiou.mantra.acceptance.loadGenericAcceptanceEnvironment
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The historical 2025 parameters and fictional facts are frozen in independent/. */
class HistoricalTradeScopeTest {
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

    @Test
    fun historicalMinimumIsPinnedToTheAssessmentYearAndUnsupportedInputIsStillVisible() {
        val historical = run("historical-minimum-rate")
        val below = run("below-historical-minimum")
        assertTrue(historical.validationPassed)
        assertTrue(historical.succeeded)
        assertTrue(below.succeeded)
        assertFalse(below.validationPassed)
        val historicalDue = historical.result.value("gewerbesteuer")
        val belowDue = below.result.value("gewerbesteuer")
        assertTrue(historicalDue is Value.Num && belowDue is Value.Num)
        assertEquals(0, BigDecimal("7210").compareTo(historicalDue.value))
        assertEquals(0, BigDecimal("7173.95").compareTo(belowDue.value))
        assertEquals("2025.1", historical.identity.schema.version)
        assertTrue(
            below.localDiagnostics.any {
                it.category == DiagnosticCategory.BUSINESS && it.nodeId == "hebesatz-supported"
            },
        )
    }

    @Test
    fun missingExpenseCategoryReportsItsRecordRatherThanGuessingAWeight() {
        val missing = run("missing-category")
        assertTrue(missing.succeeded)
        assertFalse(missing.validationPassed)
        val finding = missing.localDiagnostics.single { it.category == DiagnosticCategory.BUSINESS }
        assertEquals("MANTRA-INPUT-REQUIRED", finding.code)
        assertEquals("financing-costs", finding.nodeId)
        assertEquals(0, finding.rowIndex)
        assertEquals("category", finding.column)
        val actual = missing.result.value("weighted-fee", *listOf("F1").toTypedArray())
        assertTrue(actual is Value.Num)
        assertEquals(0, actual.value.compareTo(BigDecimal.ZERO))
    }
}
