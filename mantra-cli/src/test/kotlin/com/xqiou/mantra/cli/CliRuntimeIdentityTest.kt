package com.xqiou.mantra.cli

import com.xqiou.mantra.core.api.RuntimeVersions
import kotlin.test.Test
import kotlin.test.assertEquals

class CliRuntimeIdentityTest {
    @Test
    fun `explain and diff report the embedded kernel before and after a caller supplies a source lock`() {
        CliGraphFixture().use { fixture ->
            val before = fixture.command("explain", "--address", "answer")
            assertEquals(0, before.status, before.err)
            assertEquals(RuntimeVersions.normein, before.json().objectAt("engine").textAt("normein"))
            fixture.write(fixture.directory.resolve("normein-build.lock"), "normeinCommit=caller-controlled-commit")
            val after = fixture.command("explain", "--address", "answer")
            val comparison = fixture.command("diff", "--variant-parameters", fixture.overrideParameters.toString())
            listOf(after, comparison).forEach { result ->
                assertEquals(0, result.status, result.err)
                assertEquals(RuntimeVersions.normein, result.json().objectAt("engine").textAt("normein"))
            }
            assertEquals("12", after.json().objectAt("data").objectAt("result").objectAt("value").textAt("n"))
        }
    }
}
