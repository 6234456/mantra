package com.xqiou.mantra.workbench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RatioAggregateExplainTest {
    private fun workspace(zero: Boolean = false, test: (WorkspaceCatalog) -> Unit) {
        val root = Files.createTempDirectory("mantra-ratio-explain-")
        try {
            Files.writeString(
                root.resolve("schema.mantra"),
                """
                (schema test/ratio {}
                  (dimension group {:members [:G1 :G2]})
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per [group member]})
                  (input units :decimal {:per [group member]})
                  (section grouped "Grouped" {:per group}
                    (section detail "Detail" {:per [group member]}
                      (line rate "Rate" (if (zero? units) nil (decimal/divide charge units 8))
                      {:when (= member.key :A) :format :percent :precision 4
                       :aggregate {:ratio [charge units] :round [8 :half-up]}}))
                    (total checkpoint "Checkpoint")))
                """.trimIndent(),
            )
            Files.writeString(
                root.resolve("case.mantra"),
                if (zero) {
                    "(case c {:schema \"test/ratio\"} (inputs {:charge {:G1 {:A 0 :B 10} :G2 {:A 0 :B 20}} :units {:G1 {:A 0 :B 100} :G2 {:A 0 :B 100}}}))"
                } else {
                    "(case c {:schema \"test/ratio\"} (inputs {:charge {:G1 {:A 20 :B 100} :G2 {:A 90 :B 500}} :units {:G1 {:A 100 :B 100} :G2 {:A 300 :B 300}}}))"
                },
            )
            WorkspaceCatalog(root).use(test)
        } finally {
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun number(value: Any?) = (value as Map<*, *>)["n"]

    @Test
    fun `aggregate Explain retains engine mask and partial cross foot evidence without fake kernel steps`() =
        workspace { catalog ->
            val full = catalog.explain("case.mantra", ExplainAddress("aggregate.rate")).data
            val trace = full["aggregate"] as Map<*, *>
            assertEquals("110", number(trace["numeratorTotal"]))
            assertEquals("400", number(trace["denominatorTotal"]))
            assertEquals("0.27500000", number(trace["result"]))
            assertEquals(4, trace["memberCount"])
            assertEquals(2, trace["activeMemberCount"])
            assertEquals(
                listOf(true, false, true, false),
                (trace["members"] as List<*>).map {
                    (it as Map<*, *>)["active"]
                },
            )
            assertEquals(emptyList<Any>(), full["steps"])
            assertEquals(emptyList<Any>(), full["branches"])
            assertEquals("ratio-aggregate", full["kind"])
            assertEquals((full["result"] as Map<*, *>)["display"], (trace["display"] as Map<*, *>)["result"])
            val partial = catalog.explain("case.mantra", ExplainAddress("aggregate.rate", listOf("group=G1"))).data
            val slice = partial["aggregate"] as Map<*, *>
            assertEquals(mapOf("group" to "G1"), slice["fixed"])
            assertEquals("20", number(slice["numeratorTotal"]))
            assertEquals("100", number(slice["denominatorTotal"]))
            assertEquals("0.20000000", number(slice["result"]))
            assertEquals((partial["result"] as Map<*, *>)["display"], (slice["display"] as Map<*, *>)["result"])
            val checkpoint = catalog.explain("case.mantra", ExplainAddress("checkpoint", listOf("G1"))).data
            val part = (checkpoint["parts"] as List<*>).single() as Map<*, *>
            assertEquals(mapOf("node" to "aggregate.rate", "coord" to listOf("group=G1")), part["address"])
            assertEquals(slice, part["aggregate"])
            assertEquals(part["display"], (slice["display"] as Map<*, *>)["result"])
        }

    @Test
    fun `IAS 12 weighted evidence preserves the declared percentage precision in Run and Explain`() {
        WorkspaceCatalog(Path.of("apps/ifrs-income-taxes")).use { catalog ->
            val explanation = catalog.explain("case-demo.mantra", ExplainAddress("aggregate.effective-tax-rate")).data
            val displayed = (explanation["result"] as Map<*, *>)["display"]
            val evidence = explanation["aggregate"] as Map<*, *>
            assertEquals("24.9375 %", displayed)
            assertEquals(displayed, (evidence["display"] as Map<*, *>)["result"])
            assertEquals("0.249375", number(evidence["result"]))
            val run = catalog.document("case-demo.mantra", "run").data
            val aggregate = (run["aggregates"] as Map<*, *>)["effective-tax-rate"] as Map<*, *>
            assertEquals(displayed, (aggregate["display"] as Map<*, *>)["result"])
        }
    }

    @Test
    fun `aggregate Explain distinguishes undefined from zero and rejects invalid masks`() =
        workspace(zero = true) { catalog ->
            val full = catalog.explain("case.mantra", ExplainAddress("aggregate.rate")).data
            val trace = full["aggregate"] as Map<*, *>
            assertEquals("zero-denominator", trace["undefinedReason"])
            assertEquals(null, trace["result"])
            assertEquals(null, (full["result"] as Map<*, *>)["value"])
            assertEquals("0", number(trace["denominatorTotal"]))
            for (address in listOf(
                ExplainAddress("aggregate.rate", listOf("other=G1")),
                ExplainAddress("aggregate.rate", listOf("group=missing")),
                ExplainAddress("aggregate.rate", listOf("member=A", "group=G1")),
                ExplainAddress("aggregate.rate", cell = "0" to "amount"),
            )) {
                val failure = assertFailsWith<WorkspaceException> { catalog.explain("case.mantra", address) }
                assertTrue(failure.problem in setOf(WorkspaceProblem.REQUEST, WorkspaceProblem.NOT_FOUND))
            }
        }
}
