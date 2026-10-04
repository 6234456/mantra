package com.xqiou.mantra.apps.deest

import com.xqiou.mantra.core.api.RuntimeVersions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EStMigrationDemoTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `real legacy migration previews unchanged facts then commits reviewed host copy and preserves old package`() {
        val packageRoot = Path.of(System.getProperty("mantra.appDir")).toRealPath()
        val original = Files.readAllBytes(packageRoot.resolve("case-mustermann.mantra"))
        val host = temporary.resolve("host")
        val output = temporary.resolve("review")
        val common = arrayOf(
            "--package-root", packageRoot.toString(), "--host-root", host.toString(),
            "--case-path", "cases/mustermann.mantra", "--out", output.toString(),
            "--engine-version", RuntimeVersions.mantra, "--directory-policy", "trusted-local",
        )
        EStMigrationDemo.main(common + arrayOf("--mode", "prepare"))
        EStMigrationDemo.main(common + arrayOf("--mode", "preview"))
        assertEquals(String(original, Charsets.UTF_8), Files.readString(host.resolve("cases/mustermann.mantra")))
        assertFalse(Files.exists(output.resolve("receipt.json")))
        val token = Files.readString(output.resolve("review-token.txt")).trim()
        EStMigrationDemo.main(common + arrayOf("--mode", "apply", "--review-token", token))
        val migrated = Files.readString(host.resolve("cases/mustermann.mantra"))
        assertTrue(migrated.contains(":schema-version \"2025.3\""))
        assertTrue(migrated.contains("(extend weitere-sonderausgaben"))
        assertEquals(Files.readString(output.resolve("candidate.mantra")), migrated)
        assertTrue(Files.readString(output.resolve("receipt.json")).contains("\"oldVersionStillExecutable\":true"))
        assertTrue(original.contentEquals(Files.readAllBytes(packageRoot.resolve("case-mustermann.mantra"))))
    }
}
