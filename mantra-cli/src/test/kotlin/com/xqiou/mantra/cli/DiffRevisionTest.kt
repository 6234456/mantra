package com.xqiou.mantra.cli

import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.workbench.CasePackageLoader
import com.xqiou.mantra.workbench.CasePackageOverrides
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiffRevisionTest {
    @Test
    fun `CLI diff revision uses both actual graph revisions and compares source parameterized values`() {
        CliGraphFixture().use { fixture ->
            fun revision(parameters: List<java.nio.file.Path>?): String {
                val loader = CasePackageLoader(
                    fixture.workspace,
                    CasePackageOverrides(
                        "case.mantra",
                        schemaPath = fixture.schema,
                        parameterPaths = parameters,
                    ),
                )
                return CaseGraphRunner(loader).use { runner ->
                    val graph = runner.run(CaseRunRequest(CaseReference("case.mantra")))
                    assertTrue(graph.succeeded, graph.diagnostics.toString())
                    graph.cases.getValue(requireNotNull(graph.root)).revision
                }
            }
            val base = revision(null)
            val variant = revision(listOf(fixture.overrideParameters))
            val output = fixture.command("diff", "--variant-parameters", fixture.overrideParameters.toString())
            assertEquals(0, output.status, output.err)
            val envelope = output.json()
            assertEquals(DiffRevision.calculate(base, variant), envelope.textAt("revision"))
            assertNotEquals(DiffRevision.calculate(base, variant), DiffRevision.calculate(variant, base))
            val groups = envelope.objectAt("data").entry("changes") as Value.Vec
            val change = groups.items.flatMap { group -> ((group as Value.MapV).entry("items") as Value.Vec).items }
                .map { it as Value.MapV }.single { it.textAt("node") == "answer" }
            assertEquals("12", change.objectAt("base").textAt("n"))
            assertEquals("36", change.objectAt("variant").textAt("n"))
            assertEquals("24", change.objectAt("delta").textAt("n"))
        }
    }

    @Test
    fun `participating upstream bytes change comparison revision even when financial values stay equal`() {
        CliGraphFixture().use { fixture ->
            val first = fixture.command("diff", "--variant-parameters", fixture.overrideParameters.toString())
            assertEquals(0, first.status, first.err)
            Files.writeString(
                fixture.sourceCase,
                Files.readString(fixture.sourceCase) + ";; revised source provenance\n",
            )
            val second = fixture.command("diff", "--variant-parameters", fixture.overrideParameters.toString())
            assertEquals(0, second.status, second.err)
            assertNotEquals(first.json().textAt("revision"), second.json().textAt("revision"))
            assertEquals(first.json().objectAt("data"), second.json().objectAt("data"))
        }
    }

    @Test
    fun `actual revisions are independent of absolute checkout locations`() {
        CliGraphFixture().use { first ->
            CliGraphFixture().use { second ->
                val firstParameters = first.workspace.resolve("variant-parameters.mantra")
                val secondParameters = second.workspace.resolve("variant-parameters.mantra")
                Files.copy(first.overrideParameters, firstParameters)
                Files.copy(second.overrideParameters, secondParameters)
                val before = first.command("diff", "--variant-parameters", firstParameters.toString())
                val after = second.command("diff", "--variant-parameters", secondParameters.toString())
                assertEquals(0, before.status, before.err)
                assertEquals(0, after.status, after.err)
                assertEquals(before.json().textAt("revision"), after.json().textAt("revision"))
                assertEquals(before.json().objectAt("data"), after.json().objectAt("data"))
            }
        }
    }
}
