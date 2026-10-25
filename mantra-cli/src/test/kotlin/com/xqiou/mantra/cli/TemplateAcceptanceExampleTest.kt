package com.xqiou.mantra.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateAcceptanceExampleTest {
    @Test fun `downloadable suite and actual report conform to standalone schemas without changing example files`() {
        val root = Path.of("docs/examples/template-acceptance")
        val suite = root.resolve("suite.json")
        val before = Files.list(root).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val status = PrintStream(stdout, true, Charsets.UTF_8).use { out ->
            PrintStream(stderr, true, Charsets.UTF_8).use { err ->
                executeCli(arrayOf("template-verify", "$suite", "--workspace", "$root"), out, err)
            }
        }
        assertEquals(0, status, stderr.toString(Charsets.UTF_8))
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        fun validate(payload: String, name: String) {
            val schema = registry.getSchema(
                SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json"),
            )
            val errors = schema.validate(payload, InputFormat.JSON)
            assertTrue(errors.isEmpty(), errors.toString())
        }
        validate(Files.readString(suite), "template-acceptance")
        val report = stdout.toString(Charsets.UTF_8)
        validate(report, "template-acceptance-report")
        val tree = ObjectMapper().readTree(report)
        assertEquals(4, tree["cases"].size())
        assertTrue(tree["passed"].asBoolean())
        before.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(file), "$file") }
        assertEquals(before.size, Files.list(root).use { it.count().toInt() })
    }
}
