// Root wiring example only. Supply the generic host factory and public graph adapter first.
package com.xqiou.mantra.acceptance

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationAcceptanceTest {
    private val directory = Path.of(requireNotNull(System.getProperty("mantra.appDir")))
    private val environment by lazy { loadGenericAcceptanceEnvironment(directory) } // root-owned loader/adapter
    private val acceptance by lazy { SharedApplicationAcceptance(environment) }

    @Test fun ordinaryCasesRenderAndRecalculate() = acceptance.ordinaryCases()

    @Test fun typedImportsUseTheirOwnSchema() = acceptance.sampleImports()

    @Test fun declaredTechnicalFailuresReallyFailAndReachWorkbench() = acceptance.technicalFailures()

    @Test fun focusedAmountContractsRemainExplicit() {
        // Each focused case names a frozen independently sourced numeric reference.
        // The shared harness checks these amounts without claiming whole-tax accuracy.
        val actual = acceptance.focusedCases()
        assertEquals(environment.ownedCases.count { it.role == AcceptanceCaseRole.FOCUSED_AMOUNT }, actual.size)
    }
}
