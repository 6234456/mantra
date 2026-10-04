package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Ownership-port tests with actual public results; real linked graph integration remains mandatory. */
class AcceptanceInheritedBusinessTest {
    private val root = Path.of("/acceptance-fixture/apps")
    private val application = root.resolve("neutral")
    private val sourcePath = root.resolve("source/case-source.mantra")
    private val sourcePaths =
        AcceptanceSourcePathResolver { author, relative -> author.parent.resolve(relative).normalize() }

    private fun fixture(): Pair<AcceptanceCaseBinding, AcceptanceExecution> {
        val targetSchema = Mantra.loadSchema(
            SourceText(
                "target.mantra",
                """
            (schema acceptance/neutral {:version "2"}
              (section result "Result" (line closing "Closing" 5 {:op :info})))
        """,
            ),
            SourceResolver { _, _ -> null },
        )
        val targetCase = Mantra.loadCase(
            SourceText(
                "case.mantra",
                """
            (case target {:schema "acceptance/neutral" :schema-version "2"
              :expected-source-business [{:case "../source/case-source.mantra"
                :schema "acceptance/source" :schema-version "1"
                :code "MANTRA-CHECK-FAILED" :node "positive-fact" :coord [] :severity :error}]})
        """,
            ),
        )
        val sourceSchema = Mantra.loadSchema(
            SourceText(
                "source.mantra",
                """
            (schema acceptance/source {:version "1"}
              (section source "Source"
                (line closing "Closing" -5 {:op :info})
                (check positive-fact "Positive supplied fact" (>= closing 0))))
        """,
            ),
            SourceResolver { _, _ -> null },
        )
        val sourceCase = Mantra.loadCase(
            SourceText("source-case.mantra", """(case source {:schema "acceptance/source" :schema-version "1"})"""),
        )
        val entry = AcceptanceCaseDocument(application.resolve("case-target.mantra"), targetCase)
        val catalog =
            AcceptanceDocumentCatalog(
                listOf(AcceptanceSchemaDocument(application.resolve("schema.mantra"), targetSchema)),
                emptyList(),
                emptyList(),
                listOf(entry),
            )
        val binding = AcceptanceCaseBinder.bind(catalog, application, root, entry)
        val target = Mantra.calculate(targetSchema, targetCase)
        val source = Mantra.calculate(sourceSchema, sourceCase)
        val identity =
            AcceptanceRunIdentity(sourcePath, AcceptanceSchemaId("acceptance/source", "1"), "source-revision")
        val findings = source.diagnostics.filter { it.category == DiagnosticCategory.BUSINESS }
            .map { AcceptanceInheritedDiagnostic(identity, it) }
        return binding to AcceptanceExecution(
            AcceptanceRunIdentity(entry.path, binding.schemaId, "root-revision"),
            target,
            target.succeeded && source.succeeded,
            target.validationPassed && source.validationPassed,
            localDiagnostics = target.diagnostics,
            sources = listOf(AcceptanceSourceRun(identity, source, source.diagnostics)),
            inheritedDiagnostics = findings,
        )
    }

    @Test
    fun sourceBusinessErrorRetainsIdentityAndFalseAggregateValidation() {
        val (binding, execution) = fixture()
        AcceptanceAssertions.ordinary(binding, execution, sourcePaths)
    }

    @Test
    fun omittedSourceDiagnosticCannotMasqueradeAsValidationSuccess() {
        val (binding, execution) = fixture()
        assertFailsWith<AssertionError> {
            AcceptanceAssertions.ordinary(
                binding,
                execution.copy(validationPassed = true, inheritedDiagnostics = emptyList()),
                sourcePaths,
            )
        }
    }

    @Test
    fun sourceDiagnosticWithAnotherRevisionCannotBeAttachedToAnActualSourceRun() {
        val (binding, execution) = fixture()
        val altered = execution.inheritedDiagnostics.map {
            it.copy(source = it.source.copy(revision = "stale-revision"))
        }
        assertFailsWith<AssertionError> {
            AcceptanceAssertions.ordinary(binding, execution.copy(inheritedDiagnostics = altered), sourcePaths)
        }
    }
}
