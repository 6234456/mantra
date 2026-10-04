package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AcceptanceCaseBindingTest {
    private val root = Path.of("/acceptance-fixture/apps")
    private val application = root.resolve("neutral")

    private fun schema(version: String, multiplier: Int): AcceptanceSchemaDocument {
        val text = """(schema acceptance/neutral {:version "$version" :headline closing}
            (param multiplier $multiplier)
            (input base-amount :decimal {:default 0})
            (input rows :table {:default [] :columns {:id :keyword :amount :decimal}})
            (section computation "Computation" {:display :schedule}
                (line closing "Closing" (* base-amount multiplier) {:op :info})))"""
        return AcceptanceSchemaDocument(
            application.resolve("$version/schema.mantra"),
            Mantra.loadSchema(SourceText("schema-$version.mantra", text), SourceResolver { _, _ -> null }),
        )
    }

    private fun parameters(id: String, value: Int) = AcceptanceParameterDocument(
        application.resolve("$id.mantra"),
        ParameterSet(
            id,
            mapOf("for" to Value.Text("acceptance/neutral")),
            mapOf("multiplier" to Value.Num(BigDecimal(value))),
            emptyMap(),
            SourceLocation(id, 1, 1),
        ),
    )

    private fun layout(id: String) = AcceptanceLayoutDocument(
        application.resolve("$id.mantra"),
        id,
        LayoutReader.read(SourceText("layout.mantra", """(layout $id {:title "$id" :preset :de-staffel-4})""")),
    )

    private fun case(meta: String, source: Boolean = false): AcceptanceCaseDocument {
        val sources = if (source) {
            """(sources (csv {:path "data/rows.csv" :input "rows" :delimiter "," :decimal "." :grouping ""}))"""
        } else {
            ""
        }
        val text = """(case neutral {:schema "acceptance/neutral" $meta} (inputs {:base-amount 4}) $sources)"""
        return AcceptanceCaseDocument(
            application.resolve("2/case-demo.mantra"),
            Mantra.loadCase(SourceText("case.mantra", text)),
        )
    }

    private fun catalog(cases: List<AcceptanceCaseDocument> = emptyList()) = AcceptanceDocumentCatalog(
        listOf(schema("1", 2), schema("2", 3)),
        listOf(parameters("acceptance/selected", 5), parameters("acceptance/unselected", 99)),
        listOf(layout("acceptance/layout-one"), layout("acceptance/layout-two")),
        cases,
    )

    @Test
    fun exactVersionAndOwnMetadataDetermineTheRealCalculation() {
        val entry = case(""":schema-version "2" :parameters ["acceptance/selected"] :layout "acceptance/layout-two" """)
        val bound = AcceptanceCaseBinder.bind(catalog(), application, root, entry)
        assertEquals(AcceptanceSchemaId("acceptance/neutral", "2"), bound.schemaId)
        assertEquals(listOf("acceptance/selected"), bound.parameters.map { it.id })
        assertEquals("acceptance/layout-two", bound.layoutDocument?.id)
        val result = Mantra.calculate(bound.schema, entry.case, bound.parameters)
        assertEquals(0, (result.value("closing") as Value.Num).value.compareTo(BigDecimal("20")))
    }

    @Test
    fun missingOrUnknownVersionCannotSelectAnArbitraryCandidate() {
        assertFailsWith<IllegalArgumentException> { AcceptanceCaseBinder.bind(catalog(), application, root, case("")) }
        assertFailsWith<IllegalArgumentException> {
            AcceptanceCaseBinder.bind(catalog(), application, root, case(""":schema-version "3" """))
        }
        assertFailsWith<IllegalArgumentException> {
            AcceptanceCaseBinder.bind(catalog(), application, root, case(""":schema-version "2.0" """))
        }
    }

    @Test
    fun unversionedLegacyCaseRemainsValidOnlyWithOneSchemaCandidate() {
        val documents = catalog().copy(schemas = listOf(schema("1", 2)))
        val entry = case("")
        val bound = AcceptanceCaseBinder.bind(documents, application, root, entry)
        assertEquals("1", bound.schemaId.version)
        assertTrue(bound.parameters.isEmpty())
    }

    @Test
    fun explicitEmptyParameterListAndSourcesRemainAuthored() {
        val entry = case(""":schema-version "2" :parameters []""", source = true)
        val bound = AcceptanceCaseBinder.bind(catalog(), application, root, entry)
        assertTrue(bound.parameters.isEmpty())
        assertSame(entry.case, bound.declaration.case)
        assertEquals("data/rows.csv", (bound.declaration.case.sources.single().options["path"] as Value.Text).value)
        assertEquals(BigDecimal("4"), (bound.declaration.case.inputs.getValue("base-amount") as Value.Num).value)
    }

    @Test
    fun duplicateSchemaParameterOrLayoutIdentityIsRejected() {
        val entry = case(""":schema-version "2" :parameters ["acceptance/selected"] :layout "acceptance/layout-two" """)
        val documents = catalog()
        assertFailsWith<IllegalArgumentException> {
            AcceptanceCaseBinder.bind(
                documents.copy(
                    schemas =
                    documents.schemas + documents.schemas.last(),
                ),
                application,
                root,
                entry,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AcceptanceCaseBinder.bind(
                documents.copy(
                    parameters =
                    documents.parameters + documents.parameters.first(),
                ),
                application,
                root,
                entry,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AcceptanceCaseBinder.bind(
                documents.copy(
                    layouts =
                    documents.layouts + documents.layouts.last(),
                ),
                application,
                root,
                entry,
            )
        }
    }

    @Test
    fun explicitLayoutPrecedesTheHostDeclaredLegacyLayout() {
        val documents = catalog().let { catalog ->
            catalog.copy(
                defaultLayoutIdsBySchema = mapOf(catalog.schemas.last().path to "acceptance/layout-one"),
            )
        }
        val legacy = case(""":schema-version "2" """)
        val authored = case(""":schema-version "2" :layout "acceptance/layout-two" """)
        val defaultBinding = AcceptanceCaseBinder.bind(documents, application, root, legacy)
        val authoredBinding = AcceptanceCaseBinder.bind(documents, application, root, authored)
        assertEquals("acceptance/layout-one", defaultBinding.layoutDocument?.id)
        assertEquals("acceptance/layout-two", authoredBinding.layoutDocument?.id)
    }

    @Test
    fun sameBasenameAcrossVersionsCannotOverwriteAnArtifact() {
        val first = case(""":schema-version "1" """).copy(path = application.resolve("1/case-demo.mantra"))
        val second = case(""":schema-version "2" """)
        assertEquals(
            Path.of("1/case-demo"),
            AcceptanceArtifactNames.base(AcceptanceCaseBinder.bind(catalog(), application, root, first)),
        )
        assertEquals(
            Path.of("2/case-demo"),
            AcceptanceArtifactNames.base(AcceptanceCaseBinder.bind(catalog(), application, root, second)),
        )
        val rootCase = first.copy(path = application.resolve("case-demo.mantra"))
        assertEquals(
            Path.of("case-demo"),
            AcceptanceArtifactNames.base(AcceptanceCaseBinder.bind(catalog(), application, root, rootCase)),
        )
    }
}
