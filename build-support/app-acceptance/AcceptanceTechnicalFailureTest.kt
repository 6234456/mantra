package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Current public-kernel failure regression; the six real converge fixtures use the injected host. */
class AcceptanceTechnicalFailureTest {
    private val root = Path.of("/acceptance-fixture/apps")
    private val application = root.resolve("neutral")

    private fun binding(denominator: Int): AcceptanceCaseBinding {
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
            (schema acceptance/failure {:version "1" :headline failed-result}
              (input numerator :decimal {:default 1})
              (input denominator :decimal {:default 1})
              (section computation "Computation" {:display :schedule}
                (line failed-result "Result" (/ numerator denominator) {:op :info})))
        """,
            ),
            SourceResolver { _, _ -> null },
        )
        val case = Mantra.loadCase(
            SourceText(
                "case.mantra",
                """
            (case declared-failure
              {:schema "acceptance/failure" :schema-version "1"
               :expected-evaluation-code "MANTRA-EVALUATION"
               :expected-failed-values [{:node "failed-result" :coord []}]}
              (inputs {:denominator $denominator}))
        """,
            ),
        )
        val entry = AcceptanceCaseDocument(
            application.resolve("failure-cases/case-declared-failure.mantra"),
            case,
            AcceptanceCaseRole.TECHNICAL_FAILURE,
        )
        val schemaDocument = AcceptanceSchemaDocument(application.resolve("schema.mantra"), schema)
        val catalog = AcceptanceDocumentCatalog(
            listOf(schemaDocument),
            emptyList(),
            emptyList(),
            listOf(entry),
        )
        return AcceptanceCaseBinder.bind(catalog, application, root, entry)
    }

    private fun execute(binding: AcceptanceCaseBinding): AcceptanceExecution {
        val result = Mantra.calculateForAudit(binding.schema, binding.declaration.case, binding.parameters)
        return AcceptanceExecution(
            AcceptanceRunIdentity(binding.declaration.path, binding.schemaId, "in-memory-test"),
            result,
            result.succeeded,
            result.validationPassed,
            localDiagnostics = result.diagnostics,
        )
    }

    @Test
    fun actualEvaluationFailureHasNoNumericResultAndReachesWorkbench() {
        val binding = binding(0)
        val execution = execute(binding)
        AcceptanceAssertions.technicalFailure(binding, execution)
        assertTrue(execution.localDiagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
        assertEquals(Value.Nil, execution.result.value("failed-result"))
        val document = WorkbenchDocuments.run(execution.result.view, Render.defaultLayout(execution.result))
        AcceptanceAssertions.failureDocument(binding, document)
    }

    @Test
    fun aSuccessfulCalculationCannotPassADeclaredFailureContract() {
        val binding = binding(1)
        val execution = execute(binding)
        assertTrue(execution.result.succeeded)
        assertFailsWith<AssertionError> { AcceptanceAssertions.technicalFailure(binding, execution) }
        val document = WorkbenchDocuments.run(execution.result.view, Render.defaultLayout(execution.result))
        assertFailsWith<AssertionError> { AcceptanceAssertions.failureDocument(binding, document) }
    }
}
