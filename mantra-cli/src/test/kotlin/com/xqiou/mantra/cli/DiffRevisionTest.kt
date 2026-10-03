package com.xqiou.mantra.cli

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DiffRevisionTest {
    @Test
    fun `revision is independent of checkout and external file paths but preserves parameter order`() {
        val temp = Files.createTempDirectory("mantra-diff-revision-")
        try {
            fun files(root: Path): Triple<Path, Path, List<Path>> {
                val schema = root.resolve("work/schema.mantra")
                val source = root.resolve("work/fragment.mantra")
                val case = root.resolve("external/case.mantra")
                val first = root.resolve("external/first.mantra")
                val second = root.resolve("external/second.mantra")
                listOf(schema, source, case, first, second).forEach { Files.createDirectories(it.parent) }
                Files.writeString(schema, "schema bytes")
                Files.writeString(source, "fragment bytes")
                Files.writeString(case, "case bytes")
                Files.writeString(first, "first parameters")
                Files.writeString(second, "second parameters")
                return Triple(schema, case, listOf(first, second))
            }
            val (schemaA, caseA, paramsA) = files(temp.resolve("checkout-a"))
            val (schemaB, caseB, paramsB) = files(temp.resolve("checkout-b"))
            fun revision(schema: Path, case: Path, params: List<Path>) = DiffRevision.calculate(
                schema,
                listOf("schema.mantra", "fragment.mantra"),
                case,
                null,
                null,
                emptyList(),
                params,
            )
            val first = revision(schemaA, caseA, paramsA)
            assertEquals(first, revision(schemaB, caseB, paramsB))
            assertNotEquals(first, revision(schemaA, caseA, paramsA.reversed()))
            Files.writeString(paramsB[0], "changed parameters")
            assertNotEquals(first, revision(schemaB, caseB, paramsB))
        } finally {
            Files.walk(temp).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `CLI revision matches the ESt Compare golden`() {
        val directory = Path.of("apps/de-est")
        val schemaPath = directory.resolve("schema.mantra")
        val schema = Mantra.loadSchema(schemaPath)
        val revision = DiffRevision.calculate(
            schemaPath,
            schema.sources,
            directory.resolve("case-mustermann.mantra"),
            null,
            directory.resolve("layout.mantra"),
            emptyList(),
            listOf(directory.resolve("params-2026.mantra")),
        )
        val goldenPath = Path.of(
            "mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/compare-2026.json",
        )
        val golden = Json.parse(Files.readString(goldenPath)) as Value.MapV
        assertEquals(revision, (golden.entries.getValue(Value.Kw("revision")) as Value.Text).value)
    }
}
