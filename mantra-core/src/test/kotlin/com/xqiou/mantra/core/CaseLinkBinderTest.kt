// M3 test draft only: not compiled, integrated or verified.
package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CaseLinkBinding
import com.xqiou.mantra.core.api.ResolvedLinkSource
import com.xqiou.mantra.core.data.DataSource
import com.xqiou.mantra.core.data.DataSources
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.CaseLink
import com.xqiou.mantra.core.model.CaseLinkMapping
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.SchemaReference
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.xqiou.mantra.core.api.CaseLinkBinder as PublicCaseLinkBinder

class CaseLinkBinderTest {
    /** Normal fixtures explicitly author the supplied declarations; coverage regressions call the API directly. */
    private object CaseLinkBinder {
        fun bind(schema: com.xqiou.mantra.core.model.Schema, case: CaseData, sources: List<ResolvedLinkSource>) =
            PublicCaseLinkBinder.bind(schema, case.copy(links = sources.map { it.declaration }), sources)
    }

    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text.trimIndent()), SourceResolver { _, _ -> null })

    private fun target() = schema(
        """
        (schema test/target {:version "1"}
          (dimension outer {:members [:A :Other]})
          (dimension inner {:members [:B :C]})
          (input target :decimal {:per [outer inner]}))
        """,
    )

    private fun map(vararg entries: Pair<Value, Value>) = Value.MapV(linkedMapOf(*entries))

    private fun resolved(value: Value, to: List<String>): ResolvedLinkSource {
        val sourceSchema = schema("(schema test/source {:version \"1\"} (input exported :any {:optional true}))")
        val view = Mantra.calculate(
            sourceSchema,
            CaseData.empty("source").copy(inputs = mapOf("exported" to value)),
        ).view
        assertTrue(view.succeeded, view.diagnostics.joinToString("\n"))
        val mapping = CaseLinkMapping(
            InputAddress("exported", emptyList()),
            InputAddress("target", to),
            SourceLocation("consumer.mantra", 5, 3),
        )
        return ResolvedLinkSource(
            CaseLink("source.mantra", SchemaReference("test/source", "1"), listOf(mapping), mapping.location),
            "canonical/source.mantra",
            "revision-source",
            view,
        )
    }

    @Test
    fun `import and local alias overlap rejects the entire multidimensional transfer`() {
        val target = target()
        val local = CaseData.empty("consumer").copy(
            inputs = mapOf("target" to map(Value.Text("A") to map(Value.Kw("C") to Value.Nil))),
        )
        val importer = object : DataSource {
            override val description = "fixture:import"
            override fun read(schema: com.xqiou.mantra.core.model.Schema, sink: DiagnosticSink) =
                mapOf("target" to map(Value.Kw("A") to map(Value.Kw("B") to Value.num(1))))
        }
        val merged = DataSources.apply(local, target, listOf(importer), DiagnosticSink())
        val original = merged.inputs.getValue("target")
        val failure = assertIs<CaseLinkBinding.Failure>(
            CaseLinkBinder.bind(target, merged, listOf(resolved(Value.num(30), listOf("A", "C")))),
        )
        assertEquals(listOf("MANTRA-LINK-CONFLICT"), failure.diagnostics.map { it.code })
        assertEquals(original, merged.inputs["target"])
        assertEquals(
            Value.Nil,
            ((original as Value.MapV).entries.getValue(Value.Text("A")) as Value.MapV)
                .entries.getValue(Value.Kw("C")),
        )
    }

    @Test
    fun `two competing branches conflict even when both lack the requested leaf`() {
        val existing = map(
            Value.Kw("A") to map(Value.Kw("B") to Value.num(1)),
            Value.Text("A") to map(Value.Kw("B") to Value.num(2)),
        )
        val failure = assertIs<CaseLinkBinding.Failure>(
            CaseLinkBinder.bind(
                target(),
                CaseData.empty().copy(inputs = mapOf("target" to existing)),
                listOf(resolved(Value.num(30), listOf("A", "C"))),
            ),
        )
        assertEquals("MANTRA-LINK-CONFLICT", failure.diagnostics.single().code)
    }

    @Test
    fun `a text branch is extended and the actual target calculation reads its new value`() {
        val target = target()
        val existing = map(Value.Text("A") to map(Value.Text("B") to Value.num(7)))
        val source = resolved(Value.num(30), listOf("A", "C"))
        val bound = assertIs<CaseLinkBinding.Success>(
            CaseLinkBinder.bind(target, CaseData.empty().copy(inputs = mapOf("target" to existing)), listOf(source)),
        )
        val outer = bound.case.inputs.getValue("target") as Value.MapV
        assertFalse(outer.entries.containsKey(Value.Kw("A")))
        val inner = outer.entries.getValue(Value.Text("A")) as Value.MapV
        assertEquals(Value.num(7), inner.entries[Value.Text("B")])
        assertEquals(Value.num(30), inner.entries[Value.Kw("C")])
        val calculated = Mantra.calculate(target, bound.case)
        assertTrue(calculated.succeeded, calculated.diagnostics.joinToString("\n"))
        assertEquals(0, BigDecimal("30").compareTo((calculated.value("target", "A", "C") as Value.Num).value))
        assertEquals(source.caseKey, bound.provenance.getValue(InputAddress("target", listOf("A", "C"))).caseKey)
    }

    @Test
    fun `aliases on an unrelated member do not block a separate member transfer`() {
        val existing = map(
            Value.Kw("A") to map(Value.Kw("B") to Value.num(1)),
            Value.Text("A") to map(Value.Kw("B") to Value.num(2)),
        )
        val bound = assertIs<CaseLinkBinding.Success>(
            CaseLinkBinder.bind(
                target(),
                CaseData.empty().copy(inputs = mapOf("target" to existing)),
                listOf(resolved(Value.num(30), listOf("Other", "C"))),
            ),
        )
        val outer = bound.case.inputs.getValue("target") as Value.MapV
        assertEquals(existing.entries[Value.Kw("A")], outer.entries[Value.Kw("A")])
        assertEquals(existing.entries[Value.Text("A")], outer.entries[Value.Text("A")])
        assertEquals(Value.num(30), (outer.entries.getValue(Value.Kw("Other")) as Value.MapV).entries[Value.Kw("C")])
    }

    @Test
    fun `an explicit nil conflicts and a failed second mapping publishes no first mapping`() {
        val local = CaseData.empty().copy(
            inputs = mapOf("target" to map(Value.Kw("A") to map(Value.Text("C") to Value.Nil))),
        )
        val failure = assertIs<CaseLinkBinding.Failure>(
            CaseLinkBinder.bind(
                target(),
                local,
                listOf(resolved(Value.num(11), listOf("Other", "B")), resolved(Value.num(30), listOf("A", "C"))),
            ),
        )
        assertEquals("MANTRA-LINK-CONFLICT", failure.diagnostics.single().code)
        assertFalse((local.inputs.getValue("target") as Value.MapV).entries.containsKey(Value.Kw("Other")))
    }

    @Test
    fun `zero and false are exact materialized facts rather than absent defaults`() {
        val number = schema("(schema test/number {:version \"1\"} (input target :decimal {:default 99}))")
        val zero = assertIs<CaseLinkBinding.Success>(
            CaseLinkBinder.bind(number, CaseData.empty(), listOf(resolved(Value.ZERO, emptyList()))),
        )
        assertEquals(Value.ZERO, zero.case.inputs["target"])
        assertEquals(Value.ZERO, Mantra.calculate(number, zero.case).value("target"))
        val boolean = schema("(schema test/boolean {:version \"1\"} (input target :boolean {:default true}))")
        val no = assertIs<CaseLinkBinding.Success>(
            CaseLinkBinder.bind(boolean, CaseData.empty(), listOf(resolved(Value.Bool(false), emptyList()))),
        )
        assertEquals(Value.Bool(false), no.case.inputs["target"])
        assertEquals(Value.Bool(false), Mantra.calculate(boolean, no.case).value("target"))
        assertEquals(listOf(InputAddress("target", emptyList())), no.targets)
    }

    @Test
    fun `source nil fails even when the target is optional and has a numeric default`() {
        val target =
            schema("(schema test/optional {:version \"1\"} (input target :decimal {:optional true :default 99}))")
        val failure = assertIs<CaseLinkBinding.Failure>(
            CaseLinkBinder.bind(target, CaseData.empty(), listOf(resolved(Value.Nil, emptyList()))),
        )
        assertEquals("MANTRA-LINK-UNDEFINED", failure.diagnostics.single().code)
        assertEquals(DiagnosticCategory.EVALUATION, failure.diagnostics.single().category)
    }

    @Test
    fun `source business errors permit materialization while the runner retains their validation status`() {
        val sourceSchema = schema(
            """
            (schema test/source {:version "1"}
              (input exported :decimal)
              (check nonnegative "Business check" (>= exported 0)))
            """,
        )
        val source = resolved(Value.num(-1), emptyList()).copy(
            view = Mantra.calculate(
                sourceSchema,
                CaseData.empty("source").copy(inputs = mapOf("exported" to Value.num(-1))),
            ).view,
        )
        assertTrue(source.view.succeeded)
        assertFalse(source.view.validationPassed)
        assertTrue(
            source.view.diagnostics.any {
                it.category == DiagnosticCategory.BUSINESS &&
                    it.severity == Severity.ERROR
            },
        )
        val target = schema("(schema test/number {:version \"1\"} (input target :decimal))")
        val bound = assertIs<CaseLinkBinding.Success>(CaseLinkBinder.bind(target, CaseData.empty(), listOf(source)))
        assertEquals(Value.num(-1), bound.case.inputs["target"])
        // This binder test does not claim propagation: graph tests must publish validationPassed=false.
    }

    @Test
    fun `a technical error anywhere in the source cannot provide a healthy selected node`() {
        val sourceSchema = schema(
            """
            (schema test/source {:version "1"}
              (input exported :decimal)
              (input divisor :decimal)
              (line invalid "Technical failure" (/ 10 divisor)))
            """,
        )
        val source = resolved(Value.num(4), emptyList()).copy(
            view = Mantra.calculate(
                sourceSchema,
                CaseData.empty("source").copy(
                    inputs = mapOf(
                        "exported" to Value.num(4),
                        "divisor" to Value.ZERO,
                    ),
                ),
            ).view,
        )
        assertFalse(source.view.succeeded)
        assertEquals(Value.num(4), source.view.value("exported"))
        val target = schema("(schema test/number {:version \"1\"} (input target :decimal))")
        val failure = assertIs<CaseLinkBinding.Failure>(CaseLinkBinder.bind(target, CaseData.empty(), listOf(source)))
        assertEquals("MANTRA-LINK-UNDEFINED", failure.diagnostics.single().code)
        // The graph runner separately retains the original source's division diagnostic and identity.
    }

    @Test
    fun `exact opaque version matching is neither trimmed nor ordered`() {
        val source = resolved(Value.num(4), emptyList())
        val mismatched = source.copy(
            declaration = source.declaration.copy(schema = SchemaReference("test/source", "01")),
        )
        val target = schema("(schema test/number {:version \"1\"} (input target :decimal))")
        val failure =
            assertIs<CaseLinkBinding.Failure>(CaseLinkBinder.bind(target, CaseData.empty(), listOf(mismatched)))
        assertEquals("MANTRA-LINK-VERSION", failure.diagnostics.single().code)
    }

    @Test
    fun `runtime target membership remains unverified until the genuinely linked calculation`() {
        val target = schema(
            """
            (schema test/dynamic {:version "1"}
              (input enabled :boolean)
              (dimension member {:members [{:key :A :when enabled}]})
              (input target :decimal {:per member}))
            """,
        )
        val enabledSource = resolved(Value.Bool(true), emptyList()).let { original ->
            val mapping = original.declaration.mappings.single().copy(to = InputAddress("enabled", emptyList()))
            original.copy(declaration = original.declaration.copy(mappings = listOf(mapping)))
        }
        val amountSource = resolved(Value.num(4), listOf("A"))
        val enabled = assertIs<CaseLinkBinding.Success>(
            CaseLinkBinder.bind(target, CaseData.empty(), listOf(enabledSource, amountSource)),
        )
        val live = Mantra.calculate(target, enabled.case).view
        assertTrue(live.node("target").isActive(listOf("A")))
        assertEquals(Value.num(4), live.value("target", "A"))

        val disabled = assertIs<CaseLinkBinding.Success>(
            CaseLinkBinder.bind(
                target,
                CaseData.empty().copy(inputs = mapOf("enabled" to Value.Bool(false))),
                listOf(amountSource),
            ),
        )
        val empty = Mantra.calculate(target, disabled.case).view
        assertFalse(empty.node("target").values.containsKey(listOf("A")))
        // A graph runner must reject this bound target with MANTRA-LINK-ADDRESS/UNDEFINED.
        // Binder Success alone is deliberately insufficient; no default-case pre-run is valid.
    }

    @Test
    fun `one missing resolved declaration cannot publish a partially materialized success`() {
        val first = resolved(Value.num(11), listOf("A", "B"))
        val second = resolved(Value.num(30), listOf("Other", "C"))
        val case = CaseData.empty().copy(links = listOf(first.declaration, second.declaration))
        val missing = assertIs<CaseLinkBinding.Failure>(PublicCaseLinkBinder.bind(target(), case, listOf(first)))
        assertEquals("MANTRA-LINK-ADDRESS", missing.diagnostics.single().code)
        assertTrue(case.inputs.isEmpty())
        val duplicate =
            assertIs<CaseLinkBinding.Failure>(PublicCaseLinkBinder.bind(target(), case, listOf(first, first)))
        assertEquals("MANTRA-LINK-ADDRESS", duplicate.diagnostics.single().code)
    }
}
