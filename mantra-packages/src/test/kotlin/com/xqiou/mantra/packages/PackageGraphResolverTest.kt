package com.xqiou.mantra.packages

import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PackageGraphResolverTest {
    @TempDir lateinit var temporary: Path
    private fun snapshot(fixture: PackageFixture, name: String = "bundle"): PackageSnapshot {
        val path = temporary.resolve("$name.jar")
        fixture.jar(path)
        return URLClassLoader(arrayOf(path.toUri().toURL()), null).use {
            PackageLoader.classpath("bundle", it, SemanticVersion.parse("0.5.0"), fixture.limits)
        }
    }

    private fun resolver(
        snapshot: PackageSnapshot,
        policy: PackageParameterPolicy = PackageParameterPolicy {
            null
        },
    ): PackageGraphResolver {
        val catalog = PackageCatalog()
        catalog.register("demo", snapshot)
        return PackageGraphResolver(catalog, parameters = policy)
    }

    @Test fun `ordinary graph preserves manifest identity false zero and participating bytes`() {
        val captured = snapshot(PackageFixture())
        val loader = resolver(captured)
        CaseGraphRunner(loader).use { runner ->
            val run = runner.run(CaseRunRequest(CaseReference("demo/cases/zero.mantra")))
            assertTrue(run.result!!.succeeded)
            val node = run.cases.values.single()
            assertEquals("zero", node.caseId)
            assertEquals(Value.num(0), node.view.value("answer"))
            assertTrue(
                node.sources.any {
                    it.identity == "fictional.demo@1.0.0/manifest.json" &&
                        it.role == SourceRole.INCLUDED
                },
            )
            assertEquals(captured.totalByteLength, node.sources.sumOf { it.byteLength })
            assertEquals(Value.Bool(false), loader.binding(node.key).prepared.caseData.inputs["enabled"])
        }
    }

    @Test fun `explicit date participates in graph revision even when values match`() {
        val fixture = PackageFixture().parameter("year", "2", "2025-01-01", "2026-01-01")
        fixture.files["cases/zero.mantra"] =
            fixture.files.getValue("cases/zero.mantra").replace(":base-value 0", ":base-value 10")
        val captured = snapshot(fixture)
        fun run(date: String): Pair<String, Value?> {
            val loader = resolver(
                captured,
                PackageParameterPolicy {
                    PackageParameterChoice(
                        LocalDate.parse(date),
                        ParameterSelectionMode.EFFECTIVE_DATE,
                        listOf("year"),
                        setOf("rate"),
                    )
                },
            )
            return CaseGraphRunner(loader).use { runner ->
                val result = runner.run(CaseRunRequest(CaseReference("demo/cases/zero.mantra")))
                assertTrue(result.result!!.succeeded)
                val node = result.cases.values.single()
                assertEquals(
                    LocalDate.parse(date),
                    loader.binding(node.key).selection!!.provenance.single().effectiveDate,
                )
                node.revision to node.view.value("answer")
            }
        }
        val first = run("2025-01-01")
        val second = run("2025-07-01")
        assertEquals(Value.num(20), first.second)
        assertEquals(first.second, second.second)
        assertNotEquals(first.first, second.first)
    }

    @Test fun `import requirement is a structural graph failure without filesystem fallback`() {
        val fixture = PackageFixture()
        fixture.files["data/facts.csv"] = "key;value\nbase-value;20\n"
        fixture.files["cases/zero.mantra"] = fixture.files.getValue("cases/zero.mantra").dropLast(1) +
            """ (sources (csv {:path "../data/facts.csv" :delimiter ";" :decimal "." :grouping ""})))"""
        CaseGraphRunner(resolver(snapshot(fixture))).use { runner ->
            val result = runner.run(CaseRunRequest(CaseReference("demo/cases/zero.mantra")))
            assertEquals("MANTRA-PACKAGE-IMPORT", result.diagnostics.single().finding.code)
            assertEquals(null, result.result)
        }
    }
}
