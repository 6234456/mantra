package com.xqiou.mantra.cli

import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateAcceptanceRunnerTest {
    @TempDir lateinit var root: Path

    private fun workspace(): WorkspaceCatalog {
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema test/acceptance {:version "1" :mainline [main]}
              (include "fragment.mantra")
              (input base-amount :decimal)
              (dimension member {:members [:A :B]})
              (section main "Main" {:panel true}
                (field base-amount "Amount" {:op :info})
                (info answer "Answer" (twice base-amount))
                (info nothing "Nothing" nil {:type :any})
                (info enabled "Enabled" false {:type :boolean})
                (info each-member "Each" 1 {:per [member]})
                (check nonnegative "Nonnegative" (>= base-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(root.resolve("fragment.mantra"), "(fragment (defn twice [^Decimal value] (* value 2)))")
        Files.writeString(root.resolve("layout.mantra"), "(layout test/paper {:locale \"de-DE\" :precision 2})")
        Files.writeString(
            root.resolve("case-a.mantra"),
            "(case first {:schema \"test/acceptance\" :layout \"test/paper\"} (inputs {:base-amount 10}))\r\n",
        )
        Files.writeString(
            root.resolve("case-b.mantra"),
            "(case second {:schema \"test/acceptance\" :layout \"test/paper\"} (inputs {:base-amount 20}))\n",
        )
        return WorkspaceCatalog(root)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cases(tree: Map<String, Any?>): List<Map<String, Any?>> =
        tree.getValue("cases") as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun documents(tree: Map<String, Any?>): List<Map<String, Any?>> =
        tree.getValue("documents") as List<Map<String, Any?>>

    private fun expectation(
        answer: String,
        validationPassed: Boolean = true,
        display: String? = null,
    ): Map<String, Any?> = linkedMapOf(
        "succeeded" to true,
        "validationPassed" to validationPassed,
        "values" to listOf(
            linkedMapOf<String, Any?>("node" to "answer", "present" to true, "value" to mapOf("n" to answer)).apply {
                put("active", true)
                put("validationPassed", null)
                if (display != null) put("display", display)
            },
        ),
    )

    private fun example(
        captured: Map<String, Any?>,
        id: String,
        input: String,
        expected: Map<String, Any?>?,
    ): Map<String, Any?> = captured + mapOf(
        "id" to id,
        "inputs" to listOf(mapOf("node" to "base-amount", "text" to input)),
        "panel" to "main",
        "includeZero" to true,
        "expect" to expected,
    )

    private fun suite(tree: Map<String, Any?>): TemplateAcceptanceSuite =
        parseTemplateAcceptanceSuite(WorkbenchJson.write(tree).toByteArray(Charsets.UTF_8))

    @Suppress("UNCHECKED_CAST")
    private fun results(run: AcceptanceRun): List<Map<String, Any?>> =
        run.report.getValue("cases") as List<Map<String, Any?>>

    @Test fun `capture pins full distinct case graphs without output-derived expectations or reusable handles`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra", "case-b.mantra"))
            assertEquals("mantra.template-acceptance/1", capture.suite["format"])
            assertEquals(2, documents(capture.suite).size)
            assertTrue(documents(capture.suite).all { it.keys == setOf("document", "role", "sha256", "text") })
            assertEquals(5, capture.sourceDocuments.size)
            val examples = cases(capture.suite)
            assertNotEquals(examples[0]["baseRevision"], examples[1]["baseRevision"])
            assertTrue(examples.all { it.containsKey("expect") && it["expect"] == null })
            assertTrue(examples.all { (it["baseRevisions"] as Map<*, *>).keys.contains("fragment.mantra") })
            assertEquals(2, suite(capture.suite).cases.size)
            val unfilled = runTemplateAcceptanceSuite(catalog, suite(capture.suite))
            assertEquals(2, unfilled.status)
            assertTrue(results(unfilled).all { it["status"] == "error" && it["proposedRevision"] == null })
            assertTrue(unfilled.sourceCaptureComplete)
        }
    }

    @Test fun `shared candidate verifies locale exact decimals and findings without source writes`() {
        workspace().use { catalog ->
            val originals = Files.list(root).use { it.toList().associateWith(Files::readAllBytes) }
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra", "case-b.mantra"))
            val examples = cases(capture.suite)
            val tree = capture.suite + mapOf(
                "documents" to documents(capture.suite).map { source ->
                    source + ("text" to (source["text"] as String).replace("(twice base-amount)", "(* base-amount 3)"))
                },
                "cases" to listOf(
                    example(
                        examples[0],
                        "locale",
                        "1,25",
                        expectation("3.7500", display = "3,75") + ("diagnostics" to emptyList<Any>()),
                    ),
                    // Independent decimal arithmetic: 9,007,199,254,740,993.0000000001 times three.
                    example(
                        examples[1],
                        "exact",
                        "9007199254740993,0000000001",
                        expectation("27021597764222979.0000000003"),
                    ),
                    example(
                        examples[0],
                        "finding",
                        "-2",
                        expectation("-6", validationPassed = false) + mapOf(
                            "values" to listOf(
                                mapOf("node" to "answer", "present" to true, "value" to mapOf("n" to "-6")),
                                mapOf(
                                    "node" to "nonnegative",
                                    "present" to true,
                                    "value" to false,
                                    "validationPassed" to false,
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val parsed = suite(tree)
            val run = runTemplateAcceptanceSuite(catalog, parsed)
            assertEquals(0, run.status, WorkbenchJson.write(run.report))
            assertEquals(true, run.report["passed"])
            assertEquals(parsed.sha256, run.report["suiteSha256"])
            assertEquals(
                mapOf("mantra" to RuntimeVersions.mantra, "normein" to RuntimeVersions.normein),
                run.report["engine"],
            )
            assertEquals(listOf("passed", "passed", "passed"), results(run).map { it["status"] })
            assertTrue(results(run).all { it["difference"] != null && it["error"] == null })
            assertNotEquals(results(run)[0]["proposedRevision"], results(run)[1]["proposedRevision"])
            assertTrue(run.sourceCaptureComplete)
            originals.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        }
    }

    @Test fun `mismatch invalid input and unfilled case cannot skip independent later cases`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra"))
            val captured = cases(capture.suite).single()
            val tree = capture.suite + (
                "cases" to listOf(
                    example(captured, "wrong", "2", expectation("99")),
                    example(captured, "bad-input", "nine", expectation("4")),
                    example(captured, "unfilled", "2", null),
                    example(captured, "good", "2", expectation("4")),
                )
                )
            val run = runTemplateAcceptanceSuite(catalog, suite(tree))
            assertEquals(2, run.status)
            assertEquals(listOf("failed", "error", "error", "passed"), results(run).map { it["status"] })
            val mismatch = results(run).first()
            val assertions = mismatch["assertions"] as List<*>
            assertTrue(assertions.any { (it as Map<*, *>)["expected"] == mapOf("n" to "99") && it["passed"] == false })
            assertNotNull(results(run)[1]["error"])
            val mismatchOnly =
                runTemplateAcceptanceSuite(catalog, suite(tree + ("cases" to listOf(cases(tree).first()))))
            assertEquals(3, mismatchOnly.status)
        }
    }

    @Test fun `nil false absent and invalid scalar targets have distinct assertion outcomes`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra"))
            val captured = cases(capture.suite).single()
            fun expected(values: List<Map<String, Any?>>) = mapOf(
                "succeeded" to true,
                "validationPassed" to true,
                "values" to values,
            )
            val tree = capture.suite + (
                "cases" to listOf(
                    example(
                        captured,
                        "distinct",
                        "2",
                        expected(
                            listOf(
                                mapOf("node" to "nothing", "present" to true, "value" to null),
                                mapOf("node" to "enabled", "present" to true, "value" to false),
                                mapOf("node" to "deleted-node", "present" to false),
                            ),
                        ),
                    ),
                    example(
                        captured,
                        "missing-is-not-nil",
                        "2",
                        expected(
                            listOf(
                                mapOf(
                                    "node" to "deleted-node",
                                    "present" to true,
                                    "value" to null,
                                ),
                            ),
                        ),
                    ),
                    example(
                        captured,
                        "nil-is-not-false",
                        "2",
                        expected(
                            listOf(
                                mapOf(
                                    "node" to "nothing",
                                    "present" to true,
                                    "value" to false,
                                ),
                            ),
                        ),
                    ),
                    example(
                        captured,
                        "dimension-is-not-absence",
                        "2",
                        expected(
                            listOf(
                                mapOf(
                                    "node" to "each-member",
                                    "present" to false,
                                ),
                            ),
                        ),
                    ),
                )
                )
            val run = runTemplateAcceptanceSuite(catalog, suite(tree))
            assertEquals(2, run.status)
            assertEquals(listOf("passed", "failed", "failed", "error"), results(run).map { it["status"] })
            assertTrue(WorkbenchJson.write(results(run).last()["error"]).contains("only scalar"))
        }
    }

    @Test fun `final include edit invalidates already passing cases and retains real preview evidence`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra", "case-b.mantra"))
            val tree = capture.suite + (
                "cases" to cases(capture.suite).mapIndexed { index, captured ->
                    example(captured, "example-$index", "2", expectation("4"))
                }
                )
            val run = runTemplateAcceptanceSuite(catalog, suite(tree)) {
                // Deliberate external mutation between preview and final whole-suite revision checks.
                Files.writeString(
                    root.resolve("fragment.mantra"),
                    Files.readString(root.resolve("fragment.mantra")) + "\n; external",
                )
            }
            assertEquals(2, run.status)
            assertEquals(false, run.report["passed"])
            assertTrue(results(run).all { it["status"] == "conflict" && it["proposedRevision"] != null })
            assertTrue(
                results(run).all {
                    (it["assertions"] as List<*>).all { assertion ->
                        (assertion as Map<*, *>)["passed"] ==
                            true
                    }
                },
            )
            assertTrue("fragment.mantra" in run.sourceDocuments)
            assertEquals(false, run.sourceCaptureComplete)
        }
    }

    @Test fun `full pinned maps and shared root descriptors are checked before any candidate result`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra"))
            val captured = cases(capture.suite).single()
            val missingInclude = example(captured, "missing", "2", expectation("4")) +
                ("baseRevisions" to (captured["baseRevisions"] as Map<*, *>).filterKeys { it != "fragment.mantra" })
            val run = runTemplateAcceptanceSuite(catalog, suite(capture.suite + ("cases" to listOf(missingInclude))))
            assertEquals(2, run.status)
            assertEquals("conflict", results(run).single()["status"])
            assertNull(results(run).single()["proposedRevision"])
            assertTrue("fragment.mantra" in run.sourceDocuments)
            assertTrue(run.sourceCaptureComplete)
            val badRoots = capture.suite + mapOf(
                "documents" to documents(capture.suite).map { it + ("sha256" to "0".repeat(64)) },
                "cases" to listOf(example(captured, "root-mismatch", "2", expectation("4"))),
            )
            assertEquals("conflict", results(runTemplateAcceptanceSuite(catalog, suite(badRoots))).single()["status"])
        }
    }

    @Test fun `baseline becoming unresolvable at the final check invalidates the previous success`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra"))
            val tree =
                capture.suite +
                    ("cases" to listOf(example(cases(capture.suite).single(), "was-valid", "2", expectation("4"))))
            val run = runTemplateAcceptanceSuite(catalog, suite(tree)) {
                Files.writeString(root.resolve("fragment.mantra"), "(fragment")
            }
            assertEquals(2, run.status)
            assertEquals("conflict", results(run).single()["status"])
            assertNotNull(results(run).single()["proposedRevision"])
            assertEquals(false, run.sourceCaptureComplete)
        }
    }

    @Test fun `unresolvable saved baseline marks source capture incomplete and does not claim assertion evidence`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra"))
            val tree =
                capture.suite +
                    ("cases" to listOf(example(cases(capture.suite).single(), "bad-baseline", "2", expectation("4"))))
            Files.writeString(root.resolve("schema.mantra"), "(schema")
            val run = runTemplateAcceptanceSuite(catalog, suite(tree))
            assertEquals(2, run.status)
            assertEquals(false, run.sourceCaptureComplete)
            assertEquals("error", results(run).single()["status"])
            assertNull(results(run).single()["proposedRevision"])
        }
    }

    @Test fun `explicit runtime negative is accepted while technical binding failure cannot satisfy it`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra"))
            val captured = cases(capture.suite).single()
            val negative = mapOf("succeeded" to false, "validationPassed" to true, "values" to emptyList<Any>())
            fun tree(expression: String) = capture.suite + mapOf(
                "documents" to
                    documents(capture.suite).map { source ->
                        source +
                            ("text" to (source["text"] as String).replace("(twice base-amount)", expression))
                    },
                "cases" to listOf(example(captured, "runtime-negative", "2", negative)),
            )
            val runtime = runTemplateAcceptanceSuite(catalog, suite(tree("(/ base-amount 0)")))
            assertEquals(0, runtime.status, WorkbenchJson.write(runtime.report))
            assertEquals(false, results(runtime).single()["succeeded"])
            assertTrue((results(runtime).single()["diagnostics"] as List<*>).isNotEmpty())
            val invalid = runTemplateAcceptanceSuite(catalog, suite(tree("unknown-symbol")))
            assertEquals(2, invalid.status)
            assertEquals("error", results(invalid).single()["status"])
            assertNull(results(invalid).single()["proposedRevision"])
        }
    }

    @Test fun `diagnostic expectations compare the full code severity multiset rather than a subset`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra"))
            val captured = cases(capture.suite).single()
            val run = runTemplateAcceptanceSuite(
                catalog,
                suite(
                    capture.suite + (
                        "cases" to listOf(
                            example(
                                captured,
                                "unexpected-finding",
                                "-2",
                                expectation("-4", false) + ("diagnostics" to emptyList<Any>()),
                            ),
                        )
                        ),
                ),
            )
            assertEquals(3, run.status)
            val assertions = results(run).single()["assertions"] as List<*>
            val diagnosticAssertion = assertions.map { it as Map<*, *> }.single { it["path"] == "/diagnostics" }
            assertEquals(false, diagnosticAssertion["passed"])
            assertTrue((diagnosticAssertion["actual"] as List<*>).isNotEmpty())
        }
    }

    @Test fun `capture rejects a different root layout even when schemas coincide`() {
        workspace().use { catalog ->
            Files.writeString(root.resolve("other-layout.mantra"), "(layout test/other-paper {:locale \"en-US\"})")
            Files.writeString(
                root.resolve("case-b.mantra"),
                "(case second {:schema \"test/acceptance\" :layout \"test/other-paper\"} (inputs {:base-amount 20}))",
            )
            assertFailsWith<IllegalArgumentException> {
                captureTemplateAcceptanceSuite(catalog, listOf("case-a.mantra", "case-b.mantra"))
            }
        }
    }
}
