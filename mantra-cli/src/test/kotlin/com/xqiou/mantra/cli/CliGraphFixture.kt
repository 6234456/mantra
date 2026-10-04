package com.xqiou.mantra.cli

import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Small fictional documents exercising the actual CLI -> confined loader -> public graph path. */
internal class CliGraphFixture : AutoCloseable {
    val directory: Path = Files.createTempDirectory("mantra-cli-graph-")
    val workspace: Path = Files.createDirectories(directory.resolve("workspace"))
    val schema: Path = workspace.resolve("schema.mantra")
    val case: Path = workspace.resolve("case.mantra")
    val sourceSchema: Path = workspace.resolve("source/schema-v1.mantra")
    val sourceCase: Path = workspace.resolve("source/case.mantra")
    val overrideParameters: Path = directory.resolve("explicit-parameters.mantra")

    init {
        write(
            schema,
            """
            (schema test/root {:version "1" :title "Root" :mainline [main]}
              (param factor 1)
              (input transferred :decimal {:default 99 :required true})
              (input enabled :boolean {:default true :required true})
              (section main "Root summary"
                (field transferred "Transferred" {:op :info})
                (field enabled "Enabled" {:type :boolean :op :info})
                (line answer "Answer" (* transferred factor))))
        """,
        )
        sourceSchema("(* base-value factor)")
        write(
            workspace.resolve("source/schema-v2.mantra"),
            """
            (schema test/source {:version "2"}
              (param factor 1) (input base-value :decimal) (input flag :boolean)
              (line exported "Wrong version" (+ (* base-value factor) 10000)))
        """,
        )
        sourceInputs(2)
        write(
            workspace.resolve("source/params.mantra"),
            """
            (parameters test/source-parameters {:for "test/source"} (value factor 2))
        """,
        )
        write(
            workspace.resolve("params.mantra"),
            """
            (parameters test/root-parameters {:for "test/root"} (value factor 3))
        """,
        )
        write(
            overrideParameters,
            """
            (parameters test/root-override {:for "test/root"} (value factor 9))
        """,
        )
        write(
            workspace.resolve("layout.mantra"),
            """
            (layout test/root-paper {:locale "en-US" :precision 0} (schedule main))
        """,
        )
        write(
            case,
            """
            (case consumer {:schema "test/root" :schema-version "1"
                            :parameters ["test/root-parameters"] :layout "test/root-paper"}
              (links {:path "source/case.mantra" :schema "test/source" :schema-version "1"
                      :mappings [{:from {:node :exported :coord []} :to {:input :transferred :coord []}}
                                 {:from {:node :flag :coord []} :to {:input :enabled :coord []}}]}))
        """,
        )
    }

    fun write(file: Path, text: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    fun sourceSchema(expression: String, businessFailure: Boolean = false) {
        write(
            sourceSchema,
            """
            (schema test/source {:version "1"}
              (param factor 1) (input base-value :decimal) (input flag :boolean)
              (line exported "Exported" $expression)
              ${if (businessFailure) "(check source-control \"Source control\" false)" else ""})
        """,
        )
    }

    fun sourceInputs(base: Int, flag: Boolean = false) {
        write(
            sourceCase,
            """
            (case source {:schema "test/source" :schema-version "1" :parameters ["test/source-parameters"]}
              (inputs {:base-value $base :flag $flag}))
        """,
        )
    }

    data class Output(val status: Int, val out: String, val err: String) {
        fun json(): Value.MapV = Json.parse(out) as Value.MapV
    }

    fun command(command: String, vararg args: String): Output {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val status = PrintStream(stdout, true, Charsets.UTF_8).use { out ->
            PrintStream(stderr, true, Charsets.UTF_8).use { err ->
                executeCli(arrayOf(command, schema.toString(), "--case", case.toString(), *args), out, err)
            }
        }
        return Output(status, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    override fun close() {
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

internal fun Value.MapV.entry(key: String): Value = entries.getValue(Value.Kw(key))
internal fun Value.MapV.objectAt(key: String): Value.MapV = entry(key) as Value.MapV
internal fun Value.MapV.textAt(key: String): String = (entry(key) as Value.Text).value
