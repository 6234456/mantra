package com.xqiou.mantra.lsp

import com.xqiou.mantra.core.api.language.LanguageSymbolKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LinkIdentityTest {
    @TempDir lateinit var directory: Path

    @Test fun `same schema case extensions remain distinct and pinned mapping resolves actual source`() {
        val files = mapOf(
            "source-schema.mantra" to "(schema test/source {:version \"1\"} (slot additions \"Additions\"))",
            "source-a.mantra" to "(case source-a {:schema \"test/source\" :schema-version \"1\"} (extend additions " +
                "(line extra \"Extra\" 1)))",
            "source-b.mantra" to "(case source-b {:schema \"test/source\" :schema-version \"1\"} (extend additions " +
                "(line extra \"Extra\" 2)))",
            "consumer-schema.mantra" to
                "(schema test/consumer {:version \"1\"} (input supplied :decimal) (line result " +
                "\"Result\" supplied))",
            "consumer.mantra" to """
                (case consumer {:schema "test/consumer" :schema-version "1"}
                  (links {:path "source-a.mantra" :schema "test/source" :schema-version "1"
                    :mappings [{:from {:node :extra :coord []} :to {:input :supplied :coord []}}]}))
            """.trimIndent(),
        )
        val documents = Documents()
        files.forEach { (name, text) ->
            val path = directory.resolve(name)
            Files.writeString(path, text)
            documents.open(path.toRealPath().toUri().toString(), text, 1)
        }
        val analysis = WorkspaceAnalyzer(listOf(directory)).analyze(documents.snapshot())
        assertTrue(
            analysis.complete,
            (
                analysis.issues + analysis.analyses.flatMap { it.issues } +
                    analysis.analyses.flatMap { it.diagnostics }
                ).toString(),
        )
        val extra = analysis.definitions.filter { it.name == "extra" && it.id.kind == LanguageSymbolKind.NODE }
        assertEquals(2, extra.size)
        assertNotEquals(extra[0].id, extra[1].id)
        val consumer = directory.resolve("consumer.mantra").toRealPath().toUri().toString()
        val use = analysis.occurrences.single { it.span.source == consumer && it.symbol?.name == "extra" }
        assertEquals(directory.resolve("source-a.mantra").toRealPath().toUri().toString(), use.symbol?.ownerSource)
        val edits = SafeRename(WorkspaceAnalyzer(listOf(directory)), documents).rename(
            analysis,
            consumer,
            use.span.start,
            "source-extra",
        )
        assertEquals(
            setOf(consumer, directory.resolve("source-a.mantra").toRealPath().toUri().toString()),
            edits.map { it.uri }.toSet(),
        )
    }
}
