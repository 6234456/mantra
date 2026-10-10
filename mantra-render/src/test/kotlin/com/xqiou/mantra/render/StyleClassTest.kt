package com.xqiou.mantra.render

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutLanguageCatalog
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleClassTest {
    private fun layout(text: String) = LayoutReader.read(SourceText("styles.mantra", text))

    @Test
    fun `style presets are opt in and never mutate existing preset instances`() {
        for ((name, preset) in Presets.ALL) {
            val plain = layout("(layout test/unchanged {:preset :$name})")
            val empty = layout("(layout test/unchanged {:preset :$name :style-preset []})")
            assertEquals(preset.copy(id = "test/unchanged"), plain)
            assertEquals(plain, empty)
            assertEquals(StyleSpec(), plain.styleFor(listOf("result", "strong")))
            val styled = layout("(layout test/styled {:preset :$name :style-preset [:utilities :working-paper]})")
            assertEquals(14, styled.styleRules.size)
            assertEquals(preset, Presets.of(name))
            assertTrue(preset.styleRules.isEmpty())
        }
        val emptyUse = layout("(layout test/empty (style {:all true} {:use [] :fill :none}))")
        assertEquals(StyleSpec(fill = StyleFill.NONE), emptyUse.styleFor(emptyList()))
    }

    @Test
    fun `both palette profiles lower to the documented controlled declarations`() {
        val utilities = layout("(layout test/utilities {:style-preset :utilities})")
        val utilityExpectations = linkedMapOf(
            "normal" to StyleSpec(weight = StyleWeight.NORMAL),
            "strong" to StyleSpec(weight = StyleWeight.BOLD),
            "muted" to StyleSpec(tone = StyleTone.MUTED),
            "accent" to StyleSpec(tone = StyleTone.ACCENT),
            "subtle" to StyleSpec(fill = StyleFill.SUBTLE),
            "highlight" to StyleSpec(fill = StyleFill.ACCENT),
        )
        assertEquals(utilityExpectations.keys.toList(), utilities.styleRules.map { it.selector.klass })
        utilityExpectations.forEach { (name, style) -> assertEquals(style, utilities.styleFor(listOf(name)), name) }
        val paper = layout("(layout test/paper {:style-preset :working-paper})")
        val paperExpectations = linkedMapOf(
            "source" to StyleSpec(tone = StyleTone.MUTED, fill = StyleFill.NONE),
            "assumption" to StyleSpec(tone = StyleTone.MUTED, fill = StyleFill.SUBTLE),
            "detail" to StyleSpec(weight = StyleWeight.NORMAL, tone = StyleTone.DEFAULT),
            "subtotal" to StyleSpec(weight = StyleWeight.BOLD, fill = StyleFill.SUBTLE),
            "result" to StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.ACCENT, fill = StyleFill.ACCENT),
            "note" to StyleSpec(tone = StyleTone.MUTED),
            "variance" to StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.ACCENT, fill = StyleFill.SUBTLE),
            "control" to StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.ACCENT),
        )
        assertEquals(paperExpectations.keys.toList(), paper.styleRules.map { it.selector.klass })
        paperExpectations.forEach { (name, style) -> assertEquals(style, paper.styleFor(listOf(name)), name) }
        assertEquals(StyleSpec(), paper.styleFor(listOf("future-tag")))
    }

    @Test
    fun `selected profile order controls precedence independently of item tag order`() {
        val tags = listOf("strong", "muted", "detail")
        val profiles = listOf(
            ":utilities :working-paper" to StyleSpec(weight = StyleWeight.NORMAL, tone = StyleTone.DEFAULT),
            ":working-paper :utilities" to StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.MUTED),
        )
        for ((selected, expected) in profiles) {
            val spec = layout("(layout test/order {:style-preset [$selected]})")
            assertEquals(expected, spec.styleFor(tags))
            assertEquals(expected, spec.styleFor(tags.reversed()))
            val again = layout("(layout test/order {:style-preset [$selected]})")
            assertEquals(spec, again)
        }
    }

    @Test
    fun `named references resolve forward merge left to right then apply explicit overrides`() {
        val spec = layout(
            """
            (layout test/reuse {:style-preset :working-paper}
              (style {:class :reuse} {:use [:result :custom] :weight :normal})
              (style {:class :custom} {:tone :default})
              (style-class :custom {:tone :muted :fill :subtle})
              (style-class :result {:tone :muted})
              (style {:class :last} {:use :result})
              (style {:class :reverse} {:use [:custom :result]}))
            """,
        )
        assertEquals(
            StyleSpec(weight = StyleWeight.NORMAL, tone = StyleTone.MUTED, fill = StyleFill.SUBTLE),
            spec.styleFor(listOf("reuse")),
        )
        val result = StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.MUTED, fill = StyleFill.ACCENT)
        assertEquals(result, spec.styleFor(listOf("result")))
        assertEquals(result, spec.styleFor(listOf("last")))
        assertEquals(result, spec.styleFor(listOf("reverse")))
        assertEquals(StyleSpec(tone = StyleTone.MUTED, fill = StyleFill.SUBTLE), spec.styleFor(listOf("custom")))
        val resultRules = spec.styleRules.filter { it.selector.klass == "result" }
        assertEquals(2, resultRules.size)
        assertEquals(StyleSpec(tone = StyleTone.MUTED), resultRules.last().style)
        assertEquals("reuse", spec.styleRules[8].selector.klass)
        assertEquals("custom", spec.styleRules[9].selector.klass)
        assertEquals("custom", spec.styleRules[10].selector.klass)
        assertEquals("result", spec.styleRules[11].selector.klass)
    }

    @Test
    fun `local class rules stay at authored positions rather than preceding explicit rules`() {
        val spec = layout(
            """
            (layout test/order
              (style {:class :shared} {:tone :accent :weight :bold})
              (style-class :shared {:tone :muted})
              (style {:class :shared} {:fill :none}))
            """,
        )
        assertEquals(
            StyleSpec(weight = StyleWeight.BOLD, tone = StyleTone.MUTED, fill = StyleFill.NONE),
            spec.styleFor(listOf("shared")),
        )
        assertEquals(StyleSpec(tone = StyleTone.MUTED), spec.styleRules[1].style)
    }

    @Test
    fun `invalid presets definitions references and properties diagnose authored Unicode CRLF positions`() {
        val examples = listOf(
            Triple("{:style-preset :missing}", ":missing", "MANTRA-LAYOUT-STYLE-PRESET"),
            Triple("{:style-preset \"utilities\"}", "\"utilities\"", "MANTRA-LAYOUT-STYLE-PRESET"),
            Triple("{:style-preset [:utilities :utilities]}", ":utilities", "MANTRA-LAYOUT-STYLE-PRESET"),
            Triple("(style-class :bad/name {})", ":bad/name", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style-class :Upper {})", ":Upper", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style-class :under_score {})", ":under_score", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style-class :trailing! {})", ":trailing!", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style-class \"custom\" {})", "\"custom\"", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style-class :custom 42)", "42", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style-class :custom {} 42)", "42", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style-class :same {}) (style-class :same {})", ":same", "MANTRA-LAYOUT-STYLE-CLASS"),
            Triple("(style {:all true} {:use :missing})", ":missing", "MANTRA-LAYOUT-STYLE-USE"),
            Triple("(style {:all true} {:use :Upper})", ":Upper", "MANTRA-LAYOUT-STYLE-USE"),
            Triple("(style {:all true} {:use [42]})", "42", "MANTRA-LAYOUT-STYLE-USE"),
            Triple("(style {:all true} {:use \"custom\"})", "\"custom\"", "MANTRA-LAYOUT-STYLE-USE"),
            Triple("(style-class :custom {:use :other})", ":use", "MANTRA-LAYOUT-STYLE-USE"),
            Triple("(style-class :custom {:weight :heavy})", ":heavy", "MANTRA-LAYOUT-STYLE"),
            Triple("(style {:all true} {:css \"color:red\"})", ":css", "MANTRA-LAYOUT-STYLE"),
            Triple("(style {:all true} {} 42)", "42", "MANTRA-LAYOUT-STYLE"),
            Triple("(style {:all true} {:fill :none :fill :accent})", ":fill", "MANTRA-READ-DUPLICATE-KEY"),
        )
        for ((body, token, code) in examples) {
            val text = "(layout test/invalid ; 金额 😀\r\n  $body)"
            val failure = assertFailsWith<MantraException>(body) { layout(text) }
            val finding = failure.diagnostics.first { it.code == code }
            val position = assertNotNull(finding.location)
            assertEquals("styles.mantra", position.source)
            assertEquals(2, position.line, body)
            assertEquals(text.lastIndexOf(token), position.startOffset, body)
            assertEquals(text.lastIndexOf(token) + token.length, position.endOffset, body)
        }
    }

    @Test
    fun `ordinary style declaration map errors retain legacy reader codes spans and duplicate overwrite`() {
        val cases = listOf(
            Triple("(style {:all true} 42)", "42", "MANTRA-READ-OPTIONS"),
            Triple("(style {:all true} {\"weight\" :bold})", "\"weight\"", "MANTRA-READ-OPTIONS"),
            Triple("(style {:all true} {:fill :none :fill :accent})", ":fill", "MANTRA-READ-DUPLICATE-KEY"),
        )
        for ((body, token, code) in cases) {
            val text = "(layout test/compatibility ; 金额\r\n  $body)"
            val sink = DiagnosticSink()
            val spec = assertNotNull(LayoutReader.read(SourceText("styles.mantra", text), sink))
            val finding = sink.all.single { it.code == code }
            assertEquals(text.lastIndexOf(token), finding.location?.startOffset)
            assertEquals(text.lastIndexOf(token) + token.length, finding.location?.endOffset)
            if (code == "MANTRA-READ-DUPLICATE-KEY") {
                assertEquals(StyleFill.ACCENT, spec.styleFor(emptyList()).fill)
            }
            assertTrue(sink.hasErrors)
        }
    }

    @Test
    fun `local definition and use bounds reject the first excess authored name`() {
        val definitions = (1..256).joinToString("\n") { "(style-class :class-$it {:tone :muted})" }
        val allowed = layout("(layout test/limit $definitions)")
        assertEquals(256, allowed.styleRules.size)
        val overflow = assertFailsWith<MantraException> {
            layout("(layout test/limit $definitions (style-class :class-257 {:tone :accent}))")
        }
        assertTrue(overflow.diagnostics.any { it.code == "MANTRA-LAYOUT-STYLE-CLASS" })
        val references = List(64) { ":strong" }.joinToString(" ")
        val bounded = layout(
            "(layout test/limit {:style-preset :utilities} (style {:all true} {:use [$references]}))",
        )
        assertEquals(StyleWeight.BOLD, bounded.styleFor(emptyList()).weight)
        val tooMany = assertFailsWith<MantraException> {
            layout("(layout test/limit {:style-preset :utilities} (style {:all true} {:use [$references :strong]}))")
        }
        assertTrue(tooMany.diagnostics.any { it.code == "MANTRA-LAYOUT-STYLE-USE" })
        val profiles = assertFailsWith<MantraException> {
            layout("(layout test/limit {:style-preset [:utilities :working-paper :utilities]})")
        }
        assertTrue(profiles.diagnostics.any { it.code == "MANTRA-LAYOUT-STYLE-PRESET" })
    }

    @Test
    fun `resolved row and cell styles preserve values and never inherit section tags`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
                (schema test/styles
                  (section main "Main" {:class :result}
                    (line detail-value "Detail" 12.5000 {:class [:detail :strong]})
                    (line ordinary-value "Ordinary" 2.0000 {:class :future-tag})
                    (line deduction "Deduction" 1.2500 {:op :minus :class :source})
                    (total closing "Closing" {:class :result})))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val result = Mantra.calculate(schema)
        val spec = layout(
            """
            (layout test/styles {:preset :ifrs-schedule :style-preset [:utilities :working-paper]}
              (columns :tiered :label :value)
              (style {:class :result :column :label} {:use [:strong :muted] :weight :normal})
              (table main))
            """,
        )
        val paper = Render.paper(result, spec)
        val rows = paper.tables.single().rows.associateBy { it.nodeId }
        assertEquals(StyleWeight.NORMAL, rows.getValue("detail-value").style.weight)
        assertEquals(StyleTone.DEFAULT, rows.getValue("detail-value").style.tone)
        assertEquals(StyleSpec(), rows.getValue("ordinary-value").style)
        assertTrue(rows.getValue("ordinary-value").cellStyles.all { it == StyleSpec() })
        val closing = rows.getValue("closing")
        assertEquals(StyleWeight.BOLD, closing.style.weight)
        assertEquals(StyleWeight.NORMAL, closing.cellStyles[0].weight)
        assertEquals(StyleTone.MUTED, closing.cellStyles[0].tone)
        assertEquals(StyleFill.ACCENT, closing.cellStyles[0].fill)
        assertEquals(StyleWeight.BOLD, closing.cellStyles[1].weight)
        assertEquals("13.2500", result.decimal("closing").toPlainString())
        assertNull(rows.getValue("ordinary-value").style.fill)
        assertTrue("u-future-tag" in Render.html(result, spec))
    }

    @Test
    fun `language catalog exposes class declarations reuse and opt in profile names`() {
        val forms = LayoutLanguageCatalog.forms.associateBy { it.head }
        assertEquals(LayoutLanguageCatalog.forms.size, forms.size)
        assertEquals(setOf("layout"), forms.getValue("style-class").owners)
        assertTrue(forms.getValue("style-class").syntax.contains(":name"))
        assertTrue(forms.getValue("style").syntax.contains(":use"))
        assertTrue(forms.getValue("layout").summary.contains(":utilities"))
        assertTrue(forms.getValue("layout").summary.contains(":working-paper"))
    }
}
