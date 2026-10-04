package com.xqiou.mantra.circular

import com.xqiou.mantra.acceptance.AcceptanceAssertions
import com.xqiou.mantra.acceptance.AcceptanceCalculationRequest
import com.xqiou.mantra.acceptance.AcceptanceCaseBinder
import com.xqiou.mantra.acceptance.AcceptanceCaseRole
import com.xqiou.mantra.acceptance.AcceptanceEvidence
import com.xqiou.mantra.acceptance.AcceptanceExecution
import com.xqiou.mantra.acceptance.loadGenericAcceptanceEnvironment
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent cent-orbit references are evidence, never replayed runtime traces. */
class RoundedFixedPointTest {
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

    private fun amount(run: AcceptanceExecution, node: String, expected: String) {
        val value = run.result.value(node)
        assertTrue(value is Value.Num, "$node must really contain a converged decimal")
        assertEquals(0, BigDecimal(expected).compareTo(value.value), node)
    }

    @Test
    fun centRoundingCanGiveDifferentValidFixedPointsForDifferentSeeds() {
        val low = run("gross-up-main")
        val high = run("gross-up-seed-high")
        amount(low, "converged-amount", "1333.33")
        amount(high, "converged-amount", "1333.34")
        amount(low, "money-equation-residual", "0")
        amount(high, "money-equation-residual", "0")
        assertTrue(low.validationPassed && high.validationPassed)
        amount(run("bonus-main"), "converged-amount", "9090.91")
    }

    @Test
    fun adjacentIterationToleranceDoesNotSuppressAnIndependentEquationCheck() {
        val coarse = run("gross-up-coarse-tolerance")
        amount(coarse, "converged-amount", "1333.01")
        amount(coarse, "net-crossfoot", "-0.24")
        assertTrue(coarse.succeeded)
        assertFalse(coarse.validationPassed)
        assertEquals("equation-residual", coarse.localDiagnostics.single().nodeId)
    }

    @Test
    fun allSixDeclaredCyclesDivergenceAndInsufficientCallsReallyReturnNoAmount() {
        val failures = environment.ownedCases.filter { it.role == AcceptanceCaseRole.TECHNICAL_FAILURE }
        assertEquals(6, failures.size, "The independently frozen failure set must remain registered")
        failures.forEach { entry ->
            val binding = AcceptanceCaseBinder.bind(environment.documents, directory, environment.workspaceRoot, entry)
            val execution = environment.calculations.calculate(
                AcceptanceCalculationRequest(binding, AcceptanceEvidence.FULL),
            )
            AcceptanceAssertions.technicalFailure(binding, execution)
            assertEquals(Value.Nil, execution.result.value("converged-amount"))
        }
    }
}
