package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlAnchorTest {
    @Test
    fun `audit citations resolve real hyphenated row addresses and hidden tables have no dead links`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
                (schema test/anchors {}
                  (dimension item {:members [:long-key :other-key]})
                  (dimension year {:members [:first-year :last-year]})
                  (input seed-value :decimal {:per item})
                  (section bridge-main "Bridge" {:per [item year]}
                    (line opening-value "Opening" seed-value {:op :info})
                    (line closing-value "Closing" (+ opening-value 10) {:op :info}))
                  (section empty-aux "Empty auxiliary"
                    (line all-zero "Zero" 0 {:op :info})))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val result = Mantra.calculateForAudit(
            schema,
            Mantra.loadCase(
                SourceText("case.mantra", "(case test/c (inputs {:seed-value {:long-key 20 :other-key 30}}))"),
            ),
        )
        for ((style, columns) in listOf(
            "matrix" to ":label (members year) :cross-total",
            "transpose" to ":label (node opening-value) (node closing-value)",
        )) {
            val layout = LayoutReader.read(
                SourceText(
                    "layout.mantra",
                    """
                    (layout test/paper {:language :en :locale "en-GB" :hide-zero true}
                      (table bridge-main {:style :$style :row-dimension item} $columns)
                      (table empty-aux {:style :tiered} :label :value))
                    """.trimIndent(),
                ),
            )
            val paper = Render.paper(result, layout)
            assertEquals(1, paper.tables.size)
            assertTrue(paper.audit.isNotEmpty())
            val html = Render.html(result, layout)
            val ids = Regex("\\bid=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
            assertEquals(ids.size, ids.toSet().size, "Duplicate anchors for $style")
            val links = Regex("\\bhref=\"#([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
            assertTrue(links.any { Regex("t[0-9]+-r").containsMatchIn(it) }, "Missing audit row backlink for $style")
            assertTrue(links.all { it in ids }, "Dangling anchors for $style: ${links.filterNot { it in ids }}")
        }
    }
}
