package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceSessionTest {
    @Test
    fun `different request threads reuse rebuild evict and close sessions on their owner`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
                (schema test/thread-cache
                  (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
                  (input seed :decimal)
                  (line closing "Closing" (+ (prev closing seed) 1)
                    {:per year :aggregate {:last year}})
                  (line fixed "Unchanged" 99))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val initial = Mantra.loadCase(SourceText("case.mantra", "(case c (inputs {:seed 2}))"))
        val changed = initial.copy(inputs = mapOf("seed" to Value.num(7)))
        val sessions = WorkspaceSessions(limit = 1)
        val requestThreads = List(3) { Executors.newSingleThreadExecutor() }
        try {
            val first = request(requestThreads[0]) {
                sessions.calculate("c", "schema-one", schema, initial, emptyList(), audit = false)
            }
            assertEquals(Value.num(4), first.value("closing", "P2"))
            val next = request(requestThreads[1]) {
                sessions.calculate("c", "schema-one", schema, changed, emptyList(), audit = false)
            }
            assertTrue(next.succeeded, next.diagnostics.joinToString("\n"))
            assertEquals(Value.num(9), next.value("closing", "P2"))
            assertEquals(Value.num(4), first.value("closing", "P2"), "Detached snapshots cross threads safely")
            val stats = request(requestThreads[2]) { requireNotNull(sessions.stats("c")) }
            assertFalse(stats.fullRebuild)
            assertEquals(2, stats.formulaEvaluations)
            assertTrue(stats.reusedTasks > 0)
            request(requestThreads[1]) {
                assertFailsWith<MantraException> {
                    sessions.calculate(
                        "c",
                        "schema-one",
                        schema,
                        changed.copy(inputs = changed.inputs + ("unknown" to Value.ZERO)),
                        emptyList(),
                        audit = false,
                    )
                }
            }
            val audited = request(requestThreads[0]) {
                sessions.calculate("c", "schema-one", schema, changed, emptyList(), audit = true)
            }
            assertTrue(audited.succeeded, audited.diagnostics.joinToString("\n"))
            request(requestThreads[1]) {
                sessions.calculate("c", "schema-two", schema, changed, emptyList(), audit = false)
            }
            assertTrue(requireNotNull(sessions.stats("c")).fullRebuild, "Replacement disposes the old runtime")
            request(requestThreads[2]) {
                sessions.calculate(
                    "other",
                    "schema-two",
                    schema,
                    initial.copy(id = "other"),
                    emptyList(),
                    audit = false,
                )
            }
            assertEquals(null, sessions.stats("c"), "LRU eviction closes the former owner-thread runtime")
            request(requestThreads[0]) {
                sessions.calculate("c", "schema-two", schema, changed, emptyList(), audit = false)
            }
            request(requestThreads[2]) {
                // Cleanup must finish even when an HTTP request thread was interrupted.
                Thread.currentThread().interrupt()
                try {
                    sessions.close()
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
            }
            assertTrue(sessions.isTerminated, "Close waits for the task-owned worker to stop")
            assertEquals(null, sessions.stats("c"))
            assertFailsWith<IllegalStateException> {
                sessions.calculate("c", "schema-two", schema, changed, emptyList(), audit = false)
            }
        } finally {
            sessions.close()
            requestThreads.forEach { executor ->
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `editing one asset reuses other coordinates while schema edits and audit remain coherent`() {
        val root = Files.createTempDirectory("mantra-workspace-session-")
        try {
            val schema = """
                (schema test/session {}
                  (dimension asset {:members [:A :B]})
                  (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
                  (input seed :decimal {:per asset})
                  (section bridge "Bridge" {:per [asset year]}
                    (line opening "Opening" (prev closing seed) {:aggregate {:first year}})
                    (line movement "Movement" 20)
                    (total closing "Closing" {:aggregate {:last year}})))
            """.trimIndent()
            Files.writeString(root.resolve("schema.mantra"), schema)
            val case = "(case c {:schema \"test/session\" :layout \"test/paper\"} (inputs {:seed {:A 100 :B 200}}))"
            Files.writeString(root.resolve("case.mantra"), case)
            Files.writeString(
                root.resolve("layout.mantra"),
                """
                (layout test/paper {:language :en :explain :appendix :hide-zero false}
                  (table bridge {:style :matrix :row-dimension asset} :label (members year) :cross-total))
                """.trimIndent(),
            )
            WorkspaceCatalog(root).use { catalog ->
                val first = catalog.document("case.mantra", "run").data
                val initial = requireNotNull(catalog.sessions.stats("case.mantra"))
                assertEquals("160", number(first, "closing", "A/P3"))
                Files.writeString(root.resolve("case.mantra"), case.replace(":A 100", ":A 110"))
                val changed = catalog.document("case.mantra", "run").data
                val incremental = requireNotNull(catalog.sessions.stats("case.mantra"))
                assertEquals("170", number(changed, "closing", "A/P3"))
                assertEquals("260", number(changed, "closing", "B/P3"))
                assertTrue(incremental.invalidatedTasks > 0)
                assertTrue(incremental.reusedTasks > 0)
                assertTrue(incremental.evaluatedTasks < initial.evaluatedTasks)
                assertTrue(!incremental.fullRebuild)
                assertEquals("160", number(first, "closing", "A/P3"), "Earlier public documents stay immutable")
                val paper = catalog.document("case.mantra", "paper").data
                val audit = paper["audit"] as List<*>
                assertTrue(
                    audit.map { it as Map<*, *> }.any {
                        val explanation = it["explanation"] as? Map<*, *>
                        (explanation?.get("steps") as? List<*>)?.isNotEmpty() == true
                    },
                    "Audit requests collect actual FULL evidence",
                )
                Files.writeString(root.resolve("schema.mantra"), schema.replace("\"Movement\" 20", "\"Movement\" 30"))
                val rebuilt = catalog.document("case.mantra", "run").data
                assertEquals("200", number(rebuilt, "closing", "A/P3"))
                assertEquals("290", number(rebuilt, "closing", "B/P3"))
                assertTrue(requireNotNull(catalog.sessions.stats("case.mantra")).fullRebuild)
                Files.writeString(root.resolve("case.mantra"), case.replace(":A 100", ":A true"))
                val rejected = catalog.document("case.mantra", "run").data
                assertEquals(false, rejected["succeeded"])
                Files.writeString(root.resolve("case.mantra"), case)
                assertEquals("190", number(catalog.document("case.mantra", "run").data, "closing", "A/P3"))
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun number(run: Map<String, Any?>, node: String, coordinate: String): String {
        val values = run["values"] as Map<*, *>
        val nodes = values[node] as Map<*, *>
        val cell = nodes[coordinate] as Map<*, *>
        return (cell["value"] as Map<*, *>)["n"] as String
    }

    private fun <T> request(executor: ExecutorService, action: () -> T): T = executor.submit(
        Callable {
            action()
        },
    ).get(10, TimeUnit.SECONDS)
}
