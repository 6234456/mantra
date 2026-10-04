package com.xqiou.mantra.cli

import com.xqiou.mantra.core.model.Value
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CliCaseGraphTest {
    @Test
    fun `root explicit parameters and layout leave source exact version and parameters intact`() {
        CliGraphFixture().use { fixture ->
            val default = fixture.command("explain", "--address", "answer")
            assertEquals(0, default.status, default.err)
            assertEquals("12", default.json().objectAt("data").objectAt("result").objectAt("value").textAt("n"))
            val layout = fixture.directory.resolve("explicit-layout.mantra")
            fixture.write(layout, """(layout test/explicit-paper {:locale "en-US" :precision 4} (schedule main))""")
            val root = fixture.command(
                "explain",
                "--address",
                "answer",
                "--parameters",
                fixture.overrideParameters.toString(),
                "--layout",
                layout.toString(),
            )
            assertEquals(0, root.status, root.err)
            val result = root.json().objectAt("data").objectAt("result")
            assertEquals("36", result.objectAt("value").textAt("n"))
            assertEquals("36.0000", result.textAt("display"))
            val linked = fixture.command(
                "explain",
                "--address",
                "transferred",
                "--parameters",
                fixture.overrideParameters.toString(),
            )
            assertEquals(0, linked.status, linked.err)
            val data = linked.json().objectAt("data")
            assertEquals("4", data.objectAt("result").objectAt("value").textAt("n"))
            val link = data.objectAt("link")
            assertEquals("source/case.mantra", link.textAt("case"))
            assertEquals("test/source", link.objectAt("schema").textAt("id"))
            assertEquals("1", link.objectAt("schema").textAt("version"))
            val run = fixture.command("run", "--parameters", fixture.overrideParameters.toString())
            assertEquals(0, run.status, run.err)
            assertContains(run.out, "Answer")
            assertContains(run.out, "36")
            assertEquals(0, fixture.command("check").status)
        }
    }

    @Test
    fun `linked zero and false stay provided rather than using root defaults`() {
        CliGraphFixture().use { fixture ->
            fixture.sourceInputs(0, false)
            val number = fixture.command("explain", "--address", "transferred")
            assertEquals(0, number.status, number.err)
            assertEquals("0", number.json().objectAt("data").objectAt("result").objectAt("value").textAt("n"))
            val boolean = fixture.command("explain", "--address", "enabled")
            assertEquals(0, boolean.status, boolean.err)
            val data = boolean.json().objectAt("data")
            assertEquals(Value.Bool(false), data.objectAt("result").entry("value"))
            assertEquals("source/case.mantra", data.objectAt("link").textAt("case"))
            val reference = (data.entry("references") as Value.Vec).items.single() as Value.MapV
            assertEquals("link", reference.textAt("kind"))
            assertEquals("source/case.mantra", reference.objectAt("address").textAt("case"))
            assertEquals(0, fixture.command("check").status)
        }
    }

    @Test
    fun `source business error keeps its value and typed source identity in diagnostics`() {
        CliGraphFixture().use { fixture ->
            fixture.sourceSchema("(* base-value factor)", businessFailure = true)
            val output = fixture.command("explain", "--address", "answer")
            assertEquals(0, output.status, output.err)
            assertEquals("12", output.json().objectAt("data").objectAt("result").objectAt("value").textAt("n"))
            assertContains(output.err, "source/case.mantra@")
            assertContains(output.err, "MANTRA-CHECK-FAILED")
            assertContains(output.err, "source-control")
            assertEquals(0, fixture.command("check").status)
        }
    }

    @Test
    fun `source technical failure cannot render an old successful result in any executing command`() {
        CliGraphFixture().use { fixture ->
            fixture.sourceSchema("(/ 10 base-value)")
            val initial = fixture.command("explain", "--address", "answer")
            assertEquals(0, initial.status, initial.err)
            assertEquals("15", initial.json().objectAt("data").objectAt("result").objectAt("value").textAt("n"))
            fixture.sourceInputs(0)
            val commands = listOf(
                fixture.command("run"),
                fixture.command("explain", "--address", "answer"),
                fixture.command("diff", "--variant-parameters", fixture.overrideParameters.toString()),
            )
            commands.forEach { failure ->
                assertEquals(3, failure.status, failure.err)
                assertEquals("", failure.out)
                assertContains(failure.err, "source/case.mantra")
                assertTrue(failure.err.contains("MANTRA-") || failure.err.contains("DSL-"), failure.err)
            }
            val staticCheck = fixture.command("check")
            assertEquals(0, staticCheck.status, staticCheck.err)
            assertContains(staticCheck.out, "Schema test/root")
            assertEquals("", staticCheck.err, "Static check must not execute source formulas")
            fixture.sourceInputs(2)
            assertEquals(0, fixture.command("run").status)
        }
    }

    @Test
    fun `root schema file must match case pin and source must match link version`() {
        CliGraphFixture().use { fixture ->
            Files.writeString(
                fixture.case,
                Files.readString(fixture.case).replace(":schema-version \"1\"", ":schema-version \"2\""),
            )
            val root = fixture.command("check")
            assertEquals(3, root.status, root.err)
            assertContains(root.err, "MANTRA-LINK-VERSION")
        }
        CliGraphFixture().use { fixture ->
            Files.writeString(
                fixture.sourceCase,
                Files.readString(fixture.sourceCase).replace(":schema-version \"1\"", ":schema-version \"2\""),
            )
            val source = fixture.command("run")
            assertEquals(3, source.status, source.err)
            assertContains(source.err, "MANTRA-LINK-VERSION")
            assertEquals("", source.out)
        }
    }

    @Test
    fun `fresh explain updates both root and source revision when participating source bytes change`() {
        CliGraphFixture().use { fixture ->
            val first = fixture.command("explain", "--address", "transferred")
            assertEquals(0, first.status, first.err)
            val before = first.json()
            fixture.sourceInputs(5)
            val second = fixture.command("explain", "--address", "transferred")
            assertEquals(0, second.status, second.err)
            val after = second.json()
            assertNotEquals(before.textAt("revision"), after.textAt("revision"))
            assertEquals(after.textAt("revision"), after.objectAt("data").textAt("revision"))
            assertNotEquals(
                before.objectAt("data").objectAt("link").textAt("revision"),
                after.objectAt("data").objectAt("link").textAt("revision"),
            )
            assertEquals("10", after.objectAt("data").objectAt("result").objectAt("value").textAt("n"))
        }
    }

    @Test
    fun `explicit schema outside workspace does not authorize a linked source outside it`() {
        CliGraphFixture().use { fixture ->
            val externalSchema = fixture.directory.resolve("schema.mantra")
            Files.copy(fixture.schema, externalSchema)
            Files.delete(fixture.schema)
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val args =
                arrayOf("explain", externalSchema.toString(), "--case", fixture.case.toString(), "--address", "answer")
            val status = executeCli(args, PrintStream(stdout), PrintStream(stderr))
            assertEquals(0, status, stderr.toString())
            assertContains(stdout.toString(), "\"n\":\"12\"")
            val outside = fixture.directory.resolve("outside-case.mantra")
            Files.copy(fixture.sourceCase, outside)
            Files.writeString(
                fixture.case,
                Files.readString(fixture.case).replace("source/case.mantra", "../outside-case.mantra"),
            )
            // Use the actual explicit schema capability again; no common-ancestor workspace expansion.
            val failedOut = ByteArrayOutputStream()
            val failedErr = ByteArrayOutputStream()
            val failed = executeCli(
                arrayOf(
                    "run",
                    externalSchema.toString(),
                    "--case",
                    fixture.case.toString(),
                    "--workspace",
                    fixture.workspace.toString(),
                ),
                PrintStream(failedOut),
                PrintStream(failedErr),
            )
            assertTrue(failed != 0, failedErr.toString())
            assertEquals("", failedOut.toString())
            assertContains(failedErr.toString(), "outside")
        }
    }

    @Test
    fun `schema only run and check retain their previous entry points`() {
        CliGraphFixture().use { fixture ->
            fixture.write(fixture.schema, """(schema test/simple (line result-value "Result" 7))""")
            listOf("run", "check").forEach { command ->
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                val status =
                    executeCli(arrayOf(command, fixture.schema.toString()), PrintStream(output), PrintStream(errors))
                assertEquals(0, status, errors.toString())
                assertTrue(output.size() > 0)
            }
        }
    }
}
