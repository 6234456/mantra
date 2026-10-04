package com.xqiou.mantra.workbench

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeriodPaperExplainTest {
    private fun workspace(test: (WorkspaceCatalog) -> Unit) {
        val root = Files.createTempDirectory("mantra-period-paper-")
        try {
            Files.writeString(
                root.resolve("schema.mantra"),
                """
                (schema test/period-paper {}
                  (dimension asset {:members [:A :B]})
                  (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
                  (input seed :decimal {:per asset})
                  (line off "Inactive flow" 10 {:per [asset year] :when false})
                  (line off-stock "Inactive stock" 10 {:per [asset year] :when false :aggregate {:last year}})
                  (section bridge "Bridge" {:per [asset year]}
                    (line opening "Opening" (prev closing seed) {:aggregate {:first year}})
                    (line movement "Movement" 20)
                    (total closing "Closing" {:aggregate {:last year}})))
                """.trimIndent(),
            )
            Files.writeString(
                root.resolve("case.mantra"),
                """
                (case c {:schema "test/period-paper" :layout "test/matrix"}
                  (inputs {:seed {:A 100 :B 200}}))
                """.trimIndent(),
            )
            Files.writeString(
                root.resolve("layout.mantra"),
                """
                (layout test/matrix {:language :en :locale "en-US" :precision 2 :hide-zero false}
                  (table bridge {:style :matrix :row-dimension asset} :label (members year) :cross-total))
                """.trimIndent(),
            )
            Files.writeString(
                root.resolve("transpose.mantra"),
                """
                (layout test/transpose {:language :en :locale "en-US" :precision 2 :hide-zero false}
                  (table bridge {:style :transpose :row-dimension asset} :label (node opening) (node movement) (node closing)))
                """.trimIndent(),
            )
            WorkspaceCatalog(root).use(test)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `matrix and transpose cells carry independent exact or fixed aggregate addresses`() = workspace { catalog ->
        val matrix = catalog.document("case.mantra", "paper").data
        val table = (matrix["tables"] as List<*>).single() as Map<*, *>
        val rows = table["rows"] as List<*>
        val closing = rows.map { it as Map<*, *> }.first { it["node"] == "closing" }
        val cells = closing["cells"] as List<*>
        assertEquals(
            mapOf("case" to null, "node" to "closing", "coord" to listOf("A", "P2")),
            (cells[2] as Map<*, *>)["address"],
        )
        assertEquals(
            mapOf("case" to null, "node" to "aggregate.closing", "coord" to listOf("asset=A")),
            (cells.last() as Map<*, *>)["address"],
        )
        val transpose = catalog.document("case.mantra", "paper", layoutId = "test/transpose").data
        val transposed = (transpose["tables"] as List<*>).single() as Map<*, *>
        assertEquals("transpose", transposed["style"])
        val row = (transposed["rows"] as List<*>).first() as Map<*, *>
        val transposedCells = row["cells"] as List<*>
        assertEquals(
            mapOf("case" to null, "node" to "aggregate.movement", "coord" to listOf("asset=A")),
            (transposedCells[2] as Map<*, *>)["address"],
        )
        assertEquals("60.00", (transposedCells[2] as Map<*, *>)["text"])
    }

    @Test
    fun `aggregate Explain shows selected stock periods and exact flow totals from engine evidence`() =
        workspace { catalog ->
            val closing = catalog.explain("case.mantra", ExplainAddress("aggregate.closing", listOf("asset=A"))).data
            val boundary = closing["aggregate"] as Map<*, *>
            assertEquals("boundary", boundary["kind"])
            assertEquals("last", boundary["boundary"])
            assertEquals(mapOf("n" to "160"), boundary["result"])
            assertEquals(listOf("A", "P3"), ((boundary["selected"] as List<*>).single() as Map<*, *>)["coord"])
            val flow = catalog.explain("case.mantra", ExplainAddress("aggregate.movement", listOf("asset=A"))).data
            assertEquals("sum", (flow["aggregate"] as Map<*, *>)["kind"])
            assertEquals(mapOf("n" to "60"), (flow["result"] as Map<*, *>)["value"])
            assertEquals(emptyList<Any>(), flow["steps"])
            assertTrue((flow["references"] as List<*>).isNotEmpty())
        }

    @Test
    fun `inactive aggregate Explain retains status and neutral value`() = workspace { catalog ->
        for (id in listOf("off", "off-stock")) {
            val data = catalog.explain("case.mantra", ExplainAddress("aggregate.$id")).data
            assertEquals("inactive", data["status"])
            assertEquals(mapOf("n" to "0"), (data["result"] as Map<*, *>)["value"])
            val evidence = data["aggregate"] as Map<*, *>
            assertEquals(0, evidence["activeMemberCount"])
            if (id == "off-stock") {
                val selected = evidence["selected"] as List<*>
                assertTrue(selected.all { ((it as Map<*, *>)["coord"] as List<*>).last() == "P3" })
            }
        }
    }

    @Test
    fun `prev Explain points at the same asset in the actual preceding period`() = workspace { catalog ->
        val later = catalog.explain("case.mantra", ExplainAddress("opening", listOf("A", "P2"))).data
        val previous = (later["references"] as List<*>).map { it as Map<*, *> }.single { it["kind"] == "previous" }
        assertEquals(mapOf("case" to null, "node" to "closing", "coord" to listOf("A", "P1")), previous["address"])
        assertEquals(mapOf("n" to "120"), previous["value"])
        val first = catalog.explain("case.mantra", ExplainAddress("opening", listOf("A", "P1"))).data
        assertTrue((first["references"] as List<*>).map { it as Map<*, *> }.none { it["kind"] == "previous" })
        assertEquals(mapOf("n" to "100"), (first["result"] as Map<*, *>)["value"])
    }

    @Test
    fun `fixed ancestor scope survives aggregate Explain address validation`() {
        val root = Files.createTempDirectory("mantra-period-ancestor-")
        try {
            Files.writeString(
                root.resolve("schema.mantra"),
                """
                (schema test/ancestor
                  (dimension quarter {:periods {:start "2026-04-01" :unit :quarter :count 2}})
                  (dimension month {:periods {:start "2026-04-01" :unit :month :count 6} :parent quarter})
                  (section bridge "Bridge" {:per month :panel true}
                    (line balance "Balance" (+ month.index 10) {:aggregate {:last month}})))
                """.trimIndent(),
            )
            Files.writeString(root.resolve("case.mantra"), "(case c {:schema \"test/ancestor\"})")
            WorkspaceCatalog(root).use { catalog ->
                val data = catalog.explain(
                    "case.mantra",
                    ExplainAddress("aggregate.balance", listOf("quarter=P1")),
                ).data
                assertEquals(
                    mapOf("case" to null, "node" to "aggregate.balance", "coord" to listOf("quarter=P1")),
                    data["address"],
                )
                val evidence = data["aggregate"] as Map<*, *>
                assertEquals(mapOf("quarter" to "P1"), evidence["fixed"])
                assertEquals(listOf("P1", "P2", "P3"), evidence["periodKeys"])
                assertEquals(mapOf("n" to "12"), evidence["result"])
                val next = catalog.explain(
                    "case.mantra",
                    ExplainAddress("aggregate.balance", listOf("quarter=P2")),
                ).data
                assertEquals(mapOf("n" to "15"), (next["aggregate"] as Map<*, *>)["result"])
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
