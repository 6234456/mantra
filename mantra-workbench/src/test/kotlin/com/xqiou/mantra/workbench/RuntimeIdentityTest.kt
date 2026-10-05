package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeIdentityTest {
    @Test
    fun `workspace kernel identity ignores an ancestor source lock and preserves explicit host overrides`() {
        val temporary = Files.createTempDirectory("mantra-runtime-identity-")
        try {
            val workspace = Files.createDirectories(temporary.resolve("workspace"))
            Files.writeString(temporary.resolve("normein-build.lock"), "normeinCommit=caller-controlled-commit")
            val document = WorkspaceCatalog.DocumentResult("revision", emptyMap())
            WorkspaceCatalog(workspace).use { catalog ->
                assertEquals(RuntimeVersions.normein, kernelVersion(catalog.envelope(document)))
            }
            WorkspaceCatalog(workspace, normeinVersion = "explicit-host-kernel").use { catalog ->
                assertEquals("explicit-host-kernel", kernelVersion(catalog.envelope(document)))
            }
        } finally {
            remove(temporary)
        }
    }

    @Test
    fun `generated fixtures identify the embedded kernel without consulting a workspace lock`() {
        val temporary = Files.createTempDirectory("mantra-fixture-runtime-")
        try {
            val workspace = Files.createDirectories(temporary.resolve("workspace"))
            val application = Files.createDirectories(workspace.resolve("application"))
            Files.writeString(temporary.resolve("normein-build.lock"), "normeinCommit=caller-controlled-commit")
            Files.writeString(
                application.resolve("schema.mantra"),
                """
                (schema test/runtime {:title "Runtime identity" :mainline [main]}
                  (input source-amount :decimal)
                  (section main "Main" {:panel true}
                    (field source-amount "Amount")
                    (total sum "Sum")))
                """.trimIndent(),
            )
            val casePath = application.resolve("case.mantra")
            Files.writeString(casePath, "(case one {:schema \"test/runtime\"} (inputs {:source-amount 7}))")
            val output = temporary.resolve("output")
            val entry = Fixtures.write(casePath, output, workspaceRoot = workspace)
            val runFile = (entry.files.getValue("run") as String).removePrefix("/fixtures/")
            assertEquals(RuntimeVersions.normein, kernelVersion(Files.readString(output.resolve(runFile))))
        } finally {
            remove(temporary)
        }
    }

    private fun kernelVersion(text: String): String {
        val envelope = Json.parse(text) as Value.MapV
        val engine = envelope.entries.getValue(Value.Kw("engine")) as Value.MapV
        return (engine.entries.getValue(Value.Kw("normein")) as Value.Text).value
    }

    private fun remove(path: Path) {
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
