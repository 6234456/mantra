package com.xqiou.mantra.core.api.language

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostSyntaxLanguageTest {
    private val schemaUri = "file:///workspace/schema.mantra"
    private val caseUri = "file:///workspace/case.mantra"

    private fun analyze(text: String, caseText: String? = null): LanguageAnalysis {
        val source = SourceText(schemaUri, text)
        val schema = Mantra.loadSchema(source, SourceResolver { _, _ -> null })
        val caseSource = caseText?.let { SourceText(caseUri, it) }
        val facts = caseSource?.let(Mantra::loadCase) ?: CaseData.empty()
        return MantraLanguage.analyze(
            schema,
            facts,
            documents = listOfNotNull(
                LanguageDocumentContext(source, schema.identity),
                caseSource?.let { LanguageDocumentContext(it, schema.identity) },
            ),
        )
    }

    @Test
    fun `alias node definitions hover and formula completion retain authored positions`() {
        val text = """
            (schema test/language {:version "1"}
              (input source-value :decimal)
              (subtract discount "Discount" (* source-value 0.1))
              (info explanation "Explanation" (+ discount 1))
              (choose-min selected "Selected"
                (option :one "One" explanation)
                (option :two "Two" source-value))
              (choose-max maximum "Maximum"
                (option :one "One" selected)
                (option :two "Two" source-value)))
        """.trimIndent()
        val result = analyze(text)
        assertTrue(result.complete, result.issues.toString())
        val definitions = result.definitions.filter { it.id.kind == LanguageSymbolKind.NODE }.associateBy { it.name }
        assertEquals(setOf("discount", "explanation", "selected", "maximum"), definitions.keys)
        for ((name, definition) in definitions) {
            assertEquals(name, text.substring(definition.span.start, definition.span.end))
            assertEquals(definition.id, result.symbolAt(schemaUri, definition.span.start))
            assertTrue(assertNotNull(result.hover(schemaUri, definition.span.start)).text.contains(name))
        }
        val discount = definitions.getValue("discount")
        val reference = result.referencesOf(discount.id).single()
        assertEquals(text.indexOf("(+ discount") + 3, reference.start)
        assertEquals(discount.id, result.symbolAt(schemaUri, reference.start))
        assertEquals(listOf(discount), result.definitionsOf(discount.id))
        assertTrue(assertNotNull(result.hover(schemaUri, reference.start)).text.contains("discount"))
        val cursor = text.indexOf("(* source-value") + "(* source-va".length
        assertTrue(result.complete(schemaUri, cursor).any { it.label == "source-value" })
        assertEquals(2, result.formulas.count { it.ownerId == "selected" && it.role.startsWith("option.") })
    }

    @Test
    fun `rows values preserve input key references without indexing literal headers or cells as code`() {
        val text = """
            (schema test/table {:version "1"}
              (input entries :table {:columns {:amount :decimal :label :text}})
              (input amount :decimal)
              (info total-value "Total" (sum (map (fn [entry] entry.amount) entries))))
        """.trimIndent()
        val caseText = """
            (case sample {:schema "test/table" :schema-version "1"}
              (inputs {:entries (rows [:amount :label] [2 "entries"] [3 "amount"])
                       :amount 7}))
        """.trimIndent()
        val result = analyze(text, caseText)
        assertTrue(result.complete, result.issues.toString())
        val input = result.definitions.single { it.id.kind == LanguageSymbolKind.INPUT && it.name == "entries" }
        val inputKeys = result.referencesOf(input.id).filter { it.source == caseUri }
        assertEquals(1, inputKeys.size)
        assertEquals(caseText.indexOf(":entries") + 1, inputKeys.single().start)
        assertEquals("entries", caseText.substring(inputKeys.single().start, inputKeys.single().end))
        val amount = result.definitions.single { it.id.kind == LanguageSymbolKind.INPUT && it.name == "amount" }
        val amountKeys = result.referencesOf(amount.id).filter { it.source == caseUri }
        assertEquals(1, amountKeys.size)
        assertEquals(caseText.lastIndexOf(":amount") + 1, amountKeys.single().start)
        assertTrue(
            result.occurrences.none {
                it.span.source == caseUri &&
                    it.span.start == caseText.indexOf("[:amount") + 2
            },
        )
        assertTrue(assertNotNull(result.hover(caseUri, inputKeys.single().start)).text.contains("entries"))
    }

    @Test
    fun `catalog describes aliases their compatible owners and case table literal scope`() {
        val forms = LanguageCatalog.forms.associateBy { it.head }
        assertEquals(LanguageCatalog.forms.size, forms.size)
        for (head in listOf("subtract", "info", "choose-min", "choose-max")) {
            val alias = forms.getValue(head)
            assertEquals(setOf("schema", "fragment", "extend", "section"), alias.owners)
            assertTrue(alias.syntax.startsWith("($head id"))
            assertTrue(alias.summary.isNotBlank())
        }
        assertEquals(setOf("choice", "choose-min", "choose-max"), forms.getValue("option").owners)
        val rows = forms.getValue("rows")
        assertEquals(setOf("inputs"), rows.owners)
        assertTrue(rows.syntax.contains("[:column"))
        assertTrue(rows.summary.contains("literal cells"))
    }
}
