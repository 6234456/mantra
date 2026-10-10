package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperBrowsingTest {
    @TempDir lateinit var root: Path

    private fun rows(paper: Map<String, Any?>): List<Map<*, *>> =
        (paper["tables"] as List<*>).flatMap { ((it as Map<*, *>)["rows"] as List<*>).map { row -> row as Map<*, *> } }

    private fun valueCells(row: Map<*, *>): List<Map<*, *>> =
        (row["cells"] as List<*>).map { it as Map<*, *> }.filter { it["address"] != null }

    @Test
    fun `revealing zero rows preserves values hidden and inactive policies and authored documents`() {
        val schema = """
            (schema test/browsing {:title "Browsing"}
              (section main "Main" {:panel true :display :schedule}
                (line amount "Amount" 12)
                (line blank "Blank" 0)
                (line inactive "Inactive" 9 {:when false})
                (line concealed "Concealed" 13 {:hidden true})
                (total sum "Sum")))
        """.trimIndent()
        val case = "(case c {:schema \"test/browsing\"})"
        Files.writeString(root.resolve("schema.mantra"), schema)
        Files.writeString(root.resolve("case.mantra"), case)
        WorkspaceCatalog(root).use { catalog ->
            val run = catalog.document("case.mantra", "run")
            val original = catalog.document("case.mantra", "paper", "main")
            val expanded = catalog.document("case.mantra", "paper", "main", includeZero = true)
            assertEquals(mapOf("includeZero" to false, "hideZero" to true), original.data["browsing"])
            assertEquals(mapOf("includeZero" to true, "hideZero" to true), expanded.data["browsing"])
            assertFalse(rows(original.data).any { it["node"] == "blank" })
            assertTrue(rows(expanded.data).any { it["node"] == "blank" })
            assertFalse(rows(expanded.data).any { it["node"] in setOf("inactive", "concealed") })
            for (node in listOf("amount", "sum")) {
                assertEquals(
                    valueCells(rows(original.data).single { it["node"] == node }),
                    valueCells(rows(expanded.data).single { it["node"] == node }),
                )
            }
            val nextRun = catalog.document("case.mantra", "run")
            assertEquals(run.revision, nextRun.revision)
            for (field in listOf("values", "members", "aggregates", "succeeded", "validationPassed")) {
                assertEquals(run.data[field], nextRun.data[field], field)
            }
            val collapsed = catalog.document("case.mantra", "paper", "main", includeZero = false)
            assertEquals(original.revision, collapsed.revision)
            assertEquals(original.data["tables"], collapsed.data["tables"])
            assertEquals(original.data["browsing"], collapsed.data["browsing"])
            assertEquals(original.revision, expanded.revision)
            assertEquals(schema, Files.readString(root.resolve("schema.mantra")))
            assertEquals(case, Files.readString(root.resolve("case.mantra")))
        }
    }

    @Test
    fun `matrix browsing reveals zero rows with exact coordinates without changing reductions`() {
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema test/matrix-browsing {}
              (dimension asset {:members [:A :B]})
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (section main "Main" {:panel true :per [asset year]}
                (line amount "Amount" 12 {:op :info})
                (line blank "Blank" 0 {:op :info})
                (line inactive "Inactive" 9 {:when false :op :info})
                (line concealed "Concealed" 13 {:hidden true :op :info})))
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("case.mantra"),
            "(case c {:schema \"test/matrix-browsing\" :layout \"test/matrix\"})",
        )
        Files.writeString(
            root.resolve("layout.mantra"),
            """
            (layout test/matrix {:language :en :locale "en-US" :hide-zero true}
              (table main {:style :matrix :row-dimension asset} :label (members year) :cross-total))
            """.trimIndent(),
        )
        WorkspaceCatalog(root).use { catalog ->
            val original = catalog.document("case.mantra", "paper", "main")
            val expanded = catalog.document("case.mantra", "paper", "main", includeZero = true)
            assertFalse(rows(original.data).any { it["node"] == "blank" })
            val zeros = rows(expanded.data).filter { it["node"] == "blank" }
            // The declared matrix has A and B scopes, followed by the row-dimension aggregate.
            val firstValueAddresses = zeros.map { row ->
                ((row["cells"] as List<*>)[1] as Map<*, *>)["address"]
            }
            assertEquals(
                listOf(
                    mapOf("case" to null, "node" to "blank", "coord" to listOf("A", "P1")),
                    mapOf("case" to null, "node" to "blank", "coord" to listOf("B", "P1")),
                    mapOf("case" to null, "node" to "aggregate.blank", "coord" to listOf("year=P1")),
                ),
                firstValueAddresses,
            )
            assertFalse(rows(expanded.data).any { it["node"] in setOf("inactive", "concealed") })
            assertEquals(
                rows(original.data).filter { it["node"] == "amount" }.map { it["cells"] },
                rows(expanded.data).filter { it["node"] == "amount" }.map { it["cells"] },
            )
        }
    }

    @Test
    fun `fixture projection omits interactive metadata and does not mutate the preset`() {
        val view = Mantra.calculateForAudit(
            Mantra.loadSchema(
                SourceText("schema.mantra", "(schema test/plain (line zero \"Zero\" 0))"),
                SourceResolver { _, _ -> null },
            ),
            Mantra.loadCase(SourceText("case.mantra", "(case c)")),
        ).view
        val original = WorkbenchDocuments.paper(view, Presets.DE_STAFFEL_4)
        WorkbenchDocuments.paper(view, Presets.DE_STAFFEL_4, null, true)
        assertFalse("browsing" in original)
        assertTrue(Presets.DE_STAFFEL_4.hideZero)
        assertEquals(original, WorkbenchDocuments.paper(view, Presets.DE_STAFFEL_4))
    }
}
