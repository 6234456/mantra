package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CaseGraphWorkspaceTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var directory: Path

    private fun put(name: String, text: String) = Files.writeString(directory.resolve(name), text.trimIndent())

    private fun fixture() {
        put(
            "source-schema.mantra",
            """
            (schema test/source {:version "1"}
              (input source-amount :decimal)
              (input flag :boolean)
              (param rate 10)
              (section main "Source"
                (line exported "Exported" (* source-amount rate))
                (check positive "Positive" (> source-amount 0))))
        """,
        )
        put(
            "target-schema.mantra",
            """
            (schema test/target {:version "1"}
              (input received :decimal)
              (input received-flag :boolean)
              (param rate 999)
              (section main "Consumer" (line answer "Answer" (* received 2))))
        """,
        )
        put("parameters.mantra", "(parameters source/rate {:for \"test/source\"} (value rate 10))")
        put("root-parameters.mantra", "(parameters target/rate {:for \"test/target\"} (value rate 999))")
        put(
            "source.mantra",
            """
            (case source {:schema "test/source" :schema-version "1" :parameters ["source/rate"]}
              (inputs {:source-amount 3 :flag false}))
        """,
        )
        put(
            "consumer.mantra",
            """
            (case consumer {:schema "test/target" :schema-version "1" :parameters ["target/rate"]}
              (links {:path "./source.mantra" :schema "test/source" :schema-version "1"
                :mappings [{:from {:node exported :coord []} :to {:input received :coord []}}
                           {:from {:node flag :coord []} :to {:input received-flag :coord []}}]}))
        """,
        )
    }

    private fun value(data: Map<String, Any?>, node: String): Map<*, *> =
        ((data["values"] as Map<*, *>)[node] as Map<*, *>)[""] as Map<*, *>

    @Test fun `source parameter bindings and false link are visible in real run and deep Explain`() {
        fixture()
        val catalog = workspaceCatalog(directory)
        val run = catalog.document("consumer.mantra", "run")
        assertEquals(mapOf("n" to "60"), value(run.data, "answer")["value"])
        assertEquals(false, value(run.data, "received-flag")["value"])
        val link = value(run.data, "received")["link"] as Map<*, *>
        assertEquals("source.mantra", link["case"])
        assertEquals(mapOf("id" to "test/source", "version" to "1"), link["schema"])
        val tree = catalog.explain("consumer.mantra", ExplainAddress("answer"), 3).data
        val received = ((tree["references"] as List<*>).single() as Map<*, *>)["explanation"] as Map<*, *>
        val exported = ((received["references"] as List<*>).single() as Map<*, *>)["explanation"] as Map<*, *>
        assertEquals("source.mantra", (exported["address"] as Map<*, *>)["case"])
        assertEquals(link["revision"], exported["revision"])
        assertTrue((exported["steps"] as List<*>).isNotEmpty(), "Source steps are actual FULL kernel evidence")
    }

    @Test fun `same source-amount with changed source bytes updates provenance and rejects stale source evidence`() {
        fixture()
        val catalog = workspaceCatalog(directory)
        val first = catalog.document("consumer.mantra", "run")
        val oldLink = value(first.data, "received")["link"] as Map<*, *>
        Files.writeString(
            directory.resolve("source.mantra"),
            Files.readString(directory.resolve("source.mantra")) + "\n;; source reviewed\n",
        )
        val changed = catalog.document("consumer.mantra", "run")
        val nextLink = value(changed.data, "received")["link"] as Map<*, *>
        assertEquals(value(first.data, "answer")["value"], value(changed.data, "answer")["value"])
        assertNotEquals(first.revision, changed.revision)
        assertNotEquals(oldLink["revision"], nextLink["revision"])
        val stale = assertFailsWith<WorkspaceException> {
            catalog.explain(
                "consumer.mantra",
                ExplainAddress("exported", case = "source.mantra", expectedRevision = oldLink["revision"] as String),
            )
        }
        assertEquals(WorkspaceProblem.CONFLICT, stale.problem)
        assertEquals(nextLink["revision"], stale.currentRevision)
        val current = catalog.explain(
            "consumer.mantra",
            ExplainAddress("exported", case = "source.mantra", expectedRevision = nextLink["revision"] as String),
        )
        assertEquals(nextLink["revision"], current.data["revision"])
        assertEquals(changed.revision, current.revision)
    }

    @Test fun `linked inputs reject both clear and replacement before preview or write`() {
        fixture()
        val catalog = workspaceCatalog(directory)
        val file = directory.resolve("consumer.mantra")
        val original = Files.readString(file)
        val revision = catalog.document("consumer.mantra", "run").revision
        for (operation in listOf(
            CaseTextEditor.Operation.SetInput("received", Value.ZERO),
            CaseTextEditor.Operation.ClearInput("received"),
        )) {
            assertEquals(
                WorkspaceProblem.REQUEST,
                assertFailsWith<WorkspaceException> {
                    catalog.previewEdits("consumer.mantra", revision, listOf(operation))
                }.problem,
            )
            assertEquals(
                WorkspaceProblem.REQUEST,
                assertFailsWith<WorkspaceException> {
                    catalog.commitEdits("consumer.mantra", revision, listOf(operation))
                }.problem,
            )
            assertEquals(original, Files.readString(file))
        }
    }

    @Test fun `source business findings retain case ownership without stopping transfer`() {
        fixture()
        put(
            "source.mantra",
            "(case source {:schema \"test/source\" :schema-version \"1\" :parameters [\"source/rate\"]} (inputs {:source-amount -3 :flag false}))",
        )
        val run = workspaceCatalog(directory).document("consumer.mantra", "run").data
        assertEquals(true, run["succeeded"])
        assertEquals(false, run["validationPassed"])
        assertEquals(mapOf("n" to "-60"), value(run, "answer")["value"])
        val finding = (run["diagnostics"] as List<*>).map { it as Map<*, *> }.single {
            it["code"] ==
                "MANTRA-CHECK-FAILED"
        }
        assertEquals("source.mantra", (finding["address"] as Map<*, *>)["case"])
        assertTrue((finding["caseRevision"] as String).length == 64)
    }

    @Test fun `source technical failure never supplies previous successful consumer values`() {
        fixture()
        val catalog = workspaceCatalog(directory)
        assertEquals(true, catalog.document("consumer.mantra", "run").data["succeeded"])
        val schema = directory.resolve("source-schema.mantra")
        Files.writeString(schema, Files.readString(schema).replace("(* source-amount rate)", "(/ source-amount 0)"))
        val failure = assertFailsWith<WorkspaceException> { catalog.document("consumer.mantra", "run") }
        assertEquals(WorkspaceProblem.INVALID, failure.problem)
        assertTrue(failure.diagnostics.any { it.code == "MANTRA-EVALUATION" })
    }

    @Test fun `loader uses captured bytes after source files change during the graph load`() {
        fixture()
        val loader = CasePackageLoader(directory)
        val sourceFile = directory.resolve("source.mantra")
        val resolver = object : CasePackageResolver {
            override fun identify(reference: CaseReference, control: CaseLoadControl) =
                loader.identify(reference, control)
            override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage =
                loader.load(key, control).also {
                    if (key.value ==
                        "consumer.mantra"
                    ) {
                        Files.writeString(
                            sourceFile,
                            Files.readString(sourceFile).replace(":source-amount 3", ":source-amount 9"),
                        )
                    }
                }
        }
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(CaseRunRequest(CaseReference("consumer.mantra")))
            assertTrue(run.succeeded, run.diagnostics.toString())
            assertEquals(Value.num(60), run.result!!.value("answer"))
        }
        val fresh = workspaceCatalog(directory).document("consumer.mantra", "run")
        assertEquals(mapOf("n" to "180"), value(fresh.data, "answer")["value"])
    }

    @Test fun `exact version selection rejects an ambiguous legacy case and an escape link`() {
        fixture()
        put(
            "source-two.mantra",
            Files.readString(directory.resolve("source-schema.mantra")).replace(":version \"1\"", ":version \"2\""),
        )
        val catalog = workspaceCatalog(directory)
        assertEquals(true, catalog.document("consumer.mantra", "run").data["succeeded"])
        put("source.mantra", "(case source {:schema \"test/source\"} (inputs {:source-amount 3 :flag false}))")
        assertTrue(
            assertFailsWith<WorkspaceException> { catalog.document("consumer.mantra", "run") }.diagnostics.any {
                it.code ==
                    "MANTRA-LINK-VERSION"
            },
        )
        put(
            "consumer.mantra",
            Files.readString(directory.resolve("consumer.mantra")).replace("./source.mantra", "../source.mantra"),
        )
        assertEquals(
            WorkspaceProblem.NOT_FOUND,
            assertFailsWith<WorkspaceException> {
                catalog.document("consumer.mantra", "run")
            }.problem,
        )
    }
}
