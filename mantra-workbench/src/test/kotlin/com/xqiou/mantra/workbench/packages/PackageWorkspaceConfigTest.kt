package com.xqiou.mantra.workbench.packages

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackageWorkspaceConfigTest {
    @TempDir lateinit var temporary: Path

    @Test fun `explicit classpath config opens captured package without a filesystem fallback`() {
        val fixture = PackageHostFixture(temporary)
        fixture.snapshot("demo.old", "1.0.0")
        val config = mapOf(
            "contract" to "mantra.package-workspace/1",
            "limits" to mapOf(
                "manifestBytes" to 65_536,
                "resourceBytes" to 65_536,
                "totalBytes" to 524_288,
                "resources" to 64,
                "jsonDepth" to 16,
                "containerEntries" to 128,
            ),
            "mounts" to listOf(mapOf("mount" to "old", "classpath" to "bundle")),
            "policies" to emptyList<Any>(),
            "editableCases" to emptyList<Any>(),
        )
        val bytes = ObjectMapper().writeValueAsBytes(config)
        URLClassLoader(arrayOf(temporary.resolve("demo.old-1.0.0.jar").toUri().toURL()), null).use { loader ->
            val host = PackageWorkspaceConfig.open(bytes, temporary, fixture.engine, loader)
            assertTrue(host.evaluate("old/cases/demo.mantra").graph.succeeded)
            val data = host.document("old/cases/demo.mantra", "parameters")["data"] as Map<*, *>
            val source = (data["parameterSources"] as List<*>).single() as Map<*, *>
            assertEquals("declared", source["mode"])
            assertEquals(null, source["effectiveDate"])
            assertEquals(null, source["validForDate"])
            assertEquals("2027-01-01", source["validUntil"])
            val unknown = ObjectMapper().writeValueAsBytes(config + ("allowExternalReads" to true))
            assertFailsWith<IllegalArgumentException> {
                PackageWorkspaceConfig.open(unknown, temporary, fixture.engine, loader)
            }
            val duplicate = bytes.toString(
                Charsets.UTF_8,
            ).replace("\"contract\":", "\"contract\":\"bad\",\"contract\":")
            assertFailsWith<com.fasterxml.jackson.core.JsonParseException> {
                PackageWorkspaceConfig.open(duplicate.toByteArray(), temporary, fixture.engine, loader)
            }
        }
    }
}
