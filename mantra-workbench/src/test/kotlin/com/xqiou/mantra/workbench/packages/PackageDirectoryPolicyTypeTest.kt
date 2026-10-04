package com.xqiou.mantra.workbench.packages

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackageDirectoryPolicyTypeTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val key = "old/cases/demo.mantra"

    private fun config(mount: Map<String, Any?>, editable: List<Map<String, Any?>> = emptyList()) = mapOf(
        "contract" to "mantra.package-workspace/1",
        "limits" to mapOf(
            "manifestBytes" to 65_536,
            "resourceBytes" to 65_536,
            "totalBytes" to 524_288,
            "resources" to 64,
            "jsonDepth" to 16,
            "containerEntries" to 128,
        ),
        "mounts" to listOf(mount),
        "policies" to emptyList<Any>(),
        "editableCases" to editable,
    )

    @Test fun `absent and valid textual policies open actual classpath package`() {
        val fixture = PackageHostFixture(temporary)
        fixture.snapshot("demo.old", "1.0.0")
        val mount = mapOf<String, Any?>("mount" to "old", "classpath" to "bundle")
        val jar = temporary.resolve("demo.old-1.0.0.jar").toUri().toURL()
        URLClassLoader(arrayOf(jar), null).use { loader ->
            listOf(null, "strict-handles", "trusted-local").forEach { policy ->
                val fields = if (policy == null) mount else mount + ("directoryPolicy" to policy)
                val bytes = json.writeValueAsBytes(config(fields))
                val host = PackageWorkspaceConfig.open(bytes, temporary, fixture.engine, loader)
                assertTrue(host.evaluate(key).graph.succeeded)
            }
        }
    }

    @Test fun `present null boolean number list and object policies are rejected on mounts and writable roots`() {
        val fixture = PackageHostFixture(temporary)
        fixture.snapshot("demo.old", "1.0.0")
        val mount = mapOf<String, Any?>("mount" to "old", "classpath" to "bundle")
        val hostRoot = Files.createDirectory(temporary.resolve("host"))
        val editable = mapOf<String, Any?>(
            "case" to key,
            "root" to hostRoot.toString(),
            "path" to "case.mantra",
            "maxBytes" to 65_536,
        )
        val jar = temporary.resolve("demo.old-1.0.0.jar").toUri().toURL()
        URLClassLoader(arrayOf(jar), null).use { loader ->
            val invalid = listOf(null, true, 1, listOf("trusted-local"), mapOf("name" to "trusted-local"))
            invalid.forEach { policy ->
                val bytes = json.writeValueAsBytes(config(mount + ("directoryPolicy" to policy)))
                val badMount = assertFailsWith<IllegalArgumentException> {
                    PackageWorkspaceConfig.open(bytes, temporary, fixture.engine, loader)
                }
                assertTrue(badMount.message.orEmpty().contains("directoryPolicy must be text"))
                val roots = listOf(editable + ("directoryPolicy" to policy))
                val writable = json.writeValueAsBytes(config(mount, roots))
                val badRoot = assertFailsWith<IllegalArgumentException> {
                    PackageWorkspaceConfig.open(writable, temporary, fixture.engine, loader)
                }
                assertTrue(badRoot.message.orEmpty().contains("directoryPolicy must be text"))
            }
        }
    }
}
