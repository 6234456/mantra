package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSetReader
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.structure.SchemaMaps
import com.xqiou.mantra.core.structure.StructureJson
import com.xqiou.mantra.core.view.NodeTrace
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class EngineTest {
    private val noIncludes = com.xqiou.mantra.core.read.SourceResolver { _, _ -> null }

    private fun schema(text: String) = Mantra.loadSchema(SourceText("test.mantra", text), noIncludes)
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text))

    private fun assertDecimal(expected: String, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was ${actual.toPlainString()}")

    @Test
    fun `core reference and application attributes remain distinct in public projections`() {
        val application =
            schema(
                """
            (schema app/metadata {}
              (input amount :decimal {:reference "IAS 36.104" :source "ledger" :kz "110" :zeile "7"})
              (section result "Result" (line total "Total" 5)))
                """.trimIndent(),
            )
        val input = application.inputs.single()
        assertEquals("IAS 36.104", input.presentation.reference)
        assertEquals(Value.Text("110"), input.presentation.attributes["kz"])
        assertEquals(Value.Text("7"), input.presentation.attributes["zeile"])
        val calculated = Mantra.calculate(application, case("(case c (inputs {:amount 5}))"))
        val structure = StructureJson.write(SchemaMaps.of(calculated.plan), calculated.plan, calculated)
        assertTrue("\"reference\": \"IAS 36.104\"" in structure)
        assertTrue("\"attributes\"" in structure && "\"kz\": \"110\"" in structure)

        val sink = DiagnosticSink()
        val parameters = ParameterSetReader.read(
            SourceText(
                "parameters.mantra",
                """
            (parameters app/params {:for "app/metadata"} (value amount 5 {:reference "IAS 36.104"}))
                """.trimIndent(),
            ),
            sink,
        )
        assertTrue(sink.all.isEmpty(), sink.all.toString())
        assertEquals("IAS 36.104", parameters?.references?.get("amount"))
    }

    @Test
    fun `table layout names use the English core vocabulary`() {
        val valid =
            schema("(schema app/tiered {} (section result \"Result\" {:layout :tiered} (line amount \"Amount\" 5)))")
        assertEquals(
            "tiered",
            valid.root.children.filterIsInstance<com.xqiou.mantra.core.model.SectionItem>().single().layout,
        )
        assertFailsWith<MantraException> {
            schema("(schema app/old {} (section result \"Result\" {:layout :staffel} (line amount \"Amount\" 5)))")
        }
    }

    @Test
    fun `table foreign keys are validated against active dimension members`() {
        val application = schema(
            """
            (schema app/relations {}
              (input facts :table {:columns {:target :keyword? :amount :decimal}
                                   :references {:target category}})
              (dimension category {:members [:A :B]})
              (section result "Result" (line amount "Amount" (sum (map (fn [row] row.amount) facts)))))
            """.trimIndent(),
        )
        val valid = Mantra.calculate(
            application,
            case("(case c (inputs {:facts [{:target :A :amount 2} {:amount 3}]}))"),
        )
        assertTrue(valid.succeeded, valid.diagnostics.toString())
        assertDecimal("5", valid.decimal("amount"))
        val structure = StructureJson.write(SchemaMaps.of(valid.plan), valid.plan, valid)
        assertTrue("\"references\"" in structure && "\"target\": \"category\"" in structure)

        val invalid = Mantra.calculate(application, case("(case c (inputs {:facts [{:target :missing :amount 2}]}))"))
        assertFalse(invalid.succeeded)
        assertTrue(invalid.diagnostics.any { it.code == "MANTRA-INPUT-REFERENCE" && "missing" in it.message })

        val malformed = schema(
            """
            (schema app/bad-reference {}
              (input facts :table {:columns {:target :keyword} :references {:target absent}})
              (section result "Result" (line amount "Amount" 1)))
            """.trimIndent(),
        )
        val failure = assertFailsWith<MantraException> { Mantra.calculate(malformed, case("(case c)")) }
        assertTrue(failure.diagnostics.any { it.code == "MANTRA-INPUT-REFERENCE" })
    }

    @Test
    fun `case schema identity is enforced at the application boundary`() {
        val template = schema("(schema app/current {} (section result \"Result\" (line answer \"Answer\" 1)))")
        val mismatch = assertFailsWith<MantraException> {
            Mantra.calculate(template, case("(case c {:schema \"app/other\"})"))
        }
        assertTrue(mismatch.diagnostics.any { it.code == "MANTRA-CASE-SCHEMA-MISMATCH" })
        assertTrue(Mantra.calculate(template, case("(case c {:schema \"app/current\"})")).succeeded)
        assertTrue(Mantra.calculate(template, case("(case c)")).succeeded)
    }

    @Test
    fun `application formula slot accepts a typed case formula in its dimension context`() {
        val application = schema(
            """
            (schema app/weights {}
              (input base :decimal {:per member})
              (dimension member {:members [:A :B]})
              (section calculation "Calculation" {:per member}
                (formula-slot weight "Weight" (* base 2) {:op :info})
                (line result "Result" (+ weight 1))))
            """.trimIndent(),
        )
        val defaults = Mantra.calculate(
            application,
            case("(case c {:schema \"app/weights\"} (inputs {:base {:A 2 :B 3}}))"),
        )
        assertTrue(defaults.succeeded, defaults.diagnostics.toString())
        assertDecimal("4", defaults.decimal("weight", "A"))
        assertDecimal("7", defaults.decimal("result", "B"))

        val customized = Mantra.calculate(
            application,
            case("(case c {:schema \"app/weights\"} (inputs {:base {:A 2 :B 3}}) (bind weight (* base 3)))"),
        )
        assertTrue(customized.succeeded, customized.diagnostics.toString())
        assertDecimal("6", customized.decimal("weight", "A"))
        assertDecimal("10", customized.decimal("result", "B"))

        val closed = assertFailsWith<MantraException> {
            Mantra.calculate(application, case("(case c (inputs {:base {:A 2 :B 3}}) (bind result 10))"))
        }
        assertTrue(closed.diagnostics.any { it.code == "MANTRA-CASE-BIND-UNKNOWN" })

        assertFailsWith<MantraException> {
            Mantra.calculate(application, case("(case c (inputs {:base {:A 2 :B 3}}) (bind weight \"wrong type\"))"))
        }
        val cycle = assertFailsWith<MantraException> {
            Mantra.calculate(application, case("(case c (inputs {:base {:A 2 :B 3}}) (bind weight (+ result 1)))"))
        }
        assertTrue(cycle.diagnostics.any { it.code.contains("CYCLE") }, cycle.message)

        val restricted = schema(
            """
            (schema app/restricted {}
              (input base :decimal)
              (input unrelated :decimal)
              (formula-slot weight "Weight" base {:uses [base]}))
            """.trimIndent(),
        )
        val forbidden = assertFailsWith<MantraException> {
            Mantra.calculate(
                restricted,
                case("(case c (inputs {:base 2 :unrelated 3}) (bind weight (+ base unrelated)))"),
            )
        }
        assertTrue(forbidden.diagnostics.any { it.code == "MANTRA-FORMULA-SLOT-REFERENCE" })
    }

    @Test
    fun `running staffel totals, transparent groups and minus operators`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema t/staffel {:title "Staffel"}
                  (param pauschbetrag 1230)
                  (section einkommen "Einkommen"
                    (field lohn "Arbeitslohn")
                    (line wk "Werbungskosten" (max wk-ist pauschbetrag) {:op :minus})
                    (total einkuenfte "Einkünfte")
                    (section abzuege "Abzüge" {:op :minus}
                      (field spende "Spende")
                      (line pauschale "Pauschale" 36))
                    (line info "nur Info" 99 {:op :info})
                    (total gesamt "Gesamtbetrag"))
                  (input wk-ist :decimal))
                """.trimIndent(),
            ),
            case("(case c1 (inputs {:lohn 50000 :wk-ist 800 :spende 100}))"),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertDecimal("1230", result.decimal("wk"))
        assertDecimal("48770", result.decimal("einkuenfte"))
        assertDecimal("48634", result.decimal("gesamt"))
        val sum = assertIs<NodeTrace.Sum>(result.node("gesamt").trace())
        assertEquals(listOf("einkuenfte" to 1, "spende" to -1, "pauschale" to -1), sum.parts.map { it.id to it.sign })
    }

    @Test
    fun `dimensions align, cross-foot and expose full maps through all`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema t/dims {}
                  (input zusammen :boolean {:default true})
                  (dimension person {:members [{:key :A :label "Person A"} {:key :B :label "Person B" :when zusammen}]})
                  (section je-person "Je Person" {:per person}
                    (field lohn "Lohn")
                    (line anteil "Anteil" (decimal/divide lohn (dim/sum all.lohn) 4) {:op :info :format :percent})
                    (line label-len "Label" (count person.label) {:op :info :type :integer})
                    (total einkuenfte "Einkünfte"))
                  (total gesamt "Summe der Einkünfte (automatisch querfußend)")
                  (section info "Info"
                    (line summe "Summe über dim/sum" (dim/sum einkuenfte) {:op :info})
                    (line max-person "Höchste Einkünfte" (apply max (vals einkuenfte)) {:op :info})))
                """.trimIndent(),
            ),
            case("(case c (inputs {:lohn {:A 60000 :B 40000}}))"),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(listOf("A", "B"), result.members.getValue("person").map { it.key })
        assertDecimal("0.6", result.decimal("anteil", "A"))
        assertDecimal("100000", result.decimal("summe"))
        assertDecimal("60000", result.decimal("max-person"))
        assertDecimal("8", result.decimal("label-len", "B"))

        val single = Mantra.calculate(
            result.schema,
            case("(case c (inputs {:zusammen false :lohn {:A 60000 :B 40000}}))"),
        )
        assertEquals(listOf("A"), single.members.getValue("person").map { it.key })
        assertDecimal("60000", single.decimal("gesamt"))
    }

    @Test
    fun `section condition follows matching members when a child has fewer dimensions`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema t/guards {}
                  (dimension person {:members [:A :B]})
                  (dimension year {:members [:Y1 :Y2]})
                  (section selected "Selected" {:per [person year]
                                                :when (and (= person.key :A) (= year.key :Y1))}
                    (line by-person "By person" 10 {:per person :op :info})
                    (line by-year "By year" 20 {:per year :op :info})
                    (line by-both "By both" 30 {:op :info})))
                """.trimIndent(),
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertTrue(result.node("by-person").isActive(listOf("A")))
        assertFalse(result.node("by-person").isActive(listOf("B")))
        assertTrue(result.node("by-year").isActive(listOf("Y1")))
        assertFalse(result.node("by-year").isActive(listOf("Y2")))
        assertTrue(result.node("by-both").isActive(listOf("A", "Y1")))
        assertFalse(result.node("by-both").isActive(listOf("A", "Y2")))

        val contradictory = Mantra.calculate(
            schema(
                """
                (schema t/nested-guards {}
                  (dimension person {:members [:A :B]})
                  (dimension year {:members [:Y1 :Y2]})
                  (section outer "Outer" {:per [person year] :when (= year.key :Y1)}
                    (section inner "Inner" {:when (= year.key :Y2)}
                      (line by-person "By person" 10 {:per person :op :info}))))
                """.trimIndent(),
            ),
        )
        assertTrue(contradictory.succeeded, contradictory.diagnostics.toString())
        assertFalse(contradictory.node("by-person").isActive(listOf("A")))
        assertFalse(contradictory.node("by-person").isActive(listOf("B")))
    }

    @Test
    fun `spread allocation, choice, guards, rounding, tables, slots and defn`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema t/alloc {}
                  (defn halbe [^Decimal x] (/ x 2))
                  (input units :table {:columns {:id :keyword :name :text :ca :decimal :life :integer}})
                  (input central :decimal)
                  (input viu :decimal {:per unit})
                  (input fvlcd :decimal {:per unit :optional true})
                  (dimension unit {:from units :key :id :title :name})
                  (section alloc "Allocation" {:per unit}
                    (line ca "Carrying amount" unit.ca {:op :info})
                    (line weighted "Weighted" (* ca unit.life) {:op :info})
                    (line share "Share" (alloc/pro-rata central all.weighted 0) {:spread true :op :info})
                    (line after "After allocation" (+ ca share) {:op :info})
                    (choice ra "Recoverable amount" {:rule :max :op :info}
                      (option :viu "Value in use" viu)
                      (option :fvlcd "FVLCD" fvlcd {:when (some? fvlcd)}))
                    (line loss "Impairment" (max 0 (- after ra)) {:op :info :round [0 :floor]})
                    (line half "Half" (halbe loss) {:op :info :when (pos? loss)}))
                  (section extra "Extras"
                    (slot user "User lines")
                    (total extras "Extras total")))
                """.trimIndent(),
            ),
            case(
                """
                (case c
                  (inputs {:central 150
                           :units [{:id :A :name "Unit A" :ca 100 :life 1}
                                   {:id :B :name "Unit B" :ca 150 :life 2}
                                   {:id :C :name "Unit C" :ca 200 :life 2}]
                           :viu {:A 199 :B 164 :C 271}
                           :fvlcd {:C 280}})
                  (extend user
                    (line bonus "Bonus" (* 2 central))
                    (line fee "Fee" 25.5 {:op :minus})))
                """.trimIndent(),
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(
            listOf("19", "56", "75"),
            listOf("A", "B", "C").map {
                result.decimal("share", it).toPlainString()
            },
        )
        assertDecimal("206", result.decimal("after", "B"))
        assertDecimal("42", result.decimal("loss", "B"))
        assertDecimal("280", result.decimal("ra", "C"))
        assertEquals("fvlcd", (result.node("ra").trace(listOf("C")) as NodeTrace.Choice).selected)
        assertEquals("viu", (result.node("ra").trace(listOf("B")) as NodeTrace.Choice).selected)
        assertDecimal("0", result.decimal("loss", "C"))
        assertFalse(result.node("half").isActive(listOf("A")))
        assertDecimal("21", result.decimal("half", "B"))
        assertDecimal("274.5", result.decimal("extras"))
        assertTrue(result.tree.children.any { it is com.xqiou.mantra.core.view.ViewSection && it.id == "extra" })
    }

    @Test
    fun `cycles, unknown references and reserved names are reported with locations`() {
        val cycle = runCatching {
            Mantra.plan(schema("(schema t/c {} (line a \"A\" (+ b 1)) (line b \"B\" (* a 2)))"))
        }.exceptionOrNull() as? MantraException ?: fail("expected a cycle diagnostic")
        assertTrue(
            cycle.diagnostics.any {
                it.code == "MANTRA-CYCLE" && "a → b → a" in it.message ||
                    "b → a → b" in it.message
            },
            cycle.diagnostics.toString(),
        )

        val unknown = runCatching {
            Mantra.plan(schema("(schema t/u {}\n  (line a \"A\"\n    (+ missing 1)))"))
        }.exceptionOrNull() as MantraException
        val diagnostic = unknown.diagnostics.first { it.code == "MANTRA-FORMULA" }
        assertEquals(3, diagnostic.location?.line, diagnostic.toString())

        val reserved = runCatching {
            Mantra.plan(schema("(schema t/r {} (line if \"S\" 1))"))
        }.exceptionOrNull() as MantraException
        assertTrue(reserved.diagnostics.any { it.code == "MANTRA-ID-RESERVED" })
    }

    @Test
    fun `identifiers may shadow functions and are referenced as mantra-qualified names`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema t/ns {}
                  (input amount :decimal {:default 40})
                  (line sum "Sum" (+ mantra/amount 2))
                  (line both "Built-in amount and qualified node" (+ (amount "1.250,50") mantra/sum mantra/amount)))
                """.trimIndent(),
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(
            setOf("amount", "sum"),
            result.diagnostics.filter {
                it.code == "MANTRA-ID-SHADOWED"
            }.mapNotNull { it.nodeId }.toSet(),
        )
        assertDecimal("42", result.decimal("sum"))
        assertDecimal("1332.50", result.decimal("both"))
    }

    @Test
    fun `variable metadata is validated`() {
        val schema = schema(
            """
            (schema t/meta {}
              (input kinder :integer {:min 0 :max 20})
              (input bezeichnung :text {:required true})
              (input art :keyword {:options [:a :b]})
              (line x "X" (* kinder 2)))
            """.trimIndent(),
        )
        val bad = Mantra.calculate(schema, case("(case c (inputs {:kinder 25 :art \"b\"}))"))
        assertEquals(setOf("MANTRA-INPUT-RANGE", "MANTRA-INPUT-REQUIRED"), bad.diagnostics.map { it.code }.toSet())
        assertEquals(Value.Kw("b"), bad.value("art")) // text from data sources becomes a keyword
    }

    @Test
    fun `input findings point to the supplied case value with exact offsets`() {
        val application =
            schema(
                "(schema t/locations {} (input amount :integer {:min 0}) (section result \"Result\" (field amount \"Amount\")))",
            )
        val source = "(case c\n  (inputs {:amount -1}))"
        val result = Mantra.calculate(application, case(source))
        val finding = result.diagnostics.single { it.code == "MANTRA-INPUT-RANGE" }
        assertEquals("amount", finding.nodeId)
        assertEquals("case.mantra", finding.location?.source)
        assertEquals(2, finding.location?.line)
        assertEquals("-1", source.substring(finding.location!!.startOffset!!, finding.location!!.endOffset!!))
    }

    @Test
    fun `evaluation failures keep the rest of the calculation`() {
        val result = Mantra.calculate(schema("(schema t/f {} (line a \"A\" (/ 1 0)) (line b \"B\" 5))"))
        assertFalse(result.succeeded)
        assertTrue(result.diagnostics.any { it.code == "MANTRA-EVALUATION" && it.nodeId == "a" })
        assertDecimal("5", result.decimal("b"))
        assertEquals(Value.Nil, result.value("a"))
        assertIs<NodeTrace.Failed>(result.node("a").trace())
    }

    @Test
    fun `data literals follow the kernel numeric grammar and limits`() {
        val application = schema("(schema t/literals {} (input principal :decimal) (line result \"Result\" principal))")
        val result = Mantra.calculate(application, case("(case c (inputs {:principal .5}))"))
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertDecimal("0.5", result.decimal("result"))

        val oversized = "0." + "0".repeat(1_000) + "1"
        val failure = assertFailsWith<MantraException> { case("(case c (inputs {:principal $oversized}))") }
        assertTrue(
            failure.diagnostics.any {
                it.code == "MANTRA-READ-LITERAL" && "DSL-VALUE-NUMERIC-SCALE-LIMIT" in it.message
            },
        )
    }

    @Test
    fun `controlled value failures become diagnostics during evaluation`() {
        val application = schema("(schema t/limit {} (input principal :decimal) (line result \"Result\" principal))")
        val supplied = case("(case c)").copy(inputs = mapOf("principal" to Value.Num(BigDecimal("1E-1001"))))
        val result = Mantra.calculate(application, supplied)
        assertFalse(result.succeeded)
        assertTrue(
            result.diagnostics.any {
                it.code == "MANTRA-VALUE-LIMIT" && "DSL-VALUE-NUMERIC-SCALE-LIMIT" in it.message
            },
            result.diagnostics.toString(),
        )
    }
}
