package com.xqiou.mantra.core

import com.xqiou.mantra.core.engine.MantraLibrary
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class MantraLibraryTest {
    private fun kw(name: String): DslValue = DslValues.keyword(null, name)
    private fun weights(vararg pairs: Pair<String, String>) = pairs.map { (k, v) -> kw(k) to BigDecimal(v) }
    private fun plain(result: List<Pair<DslValue, BigDecimal>>) = result.map {
        ((it.first as DslValue.KeywordValue).name) to
            it.second.toPlainString()
    }

    @Test
    fun `pro rata allocation foots exactly with largest remainders`() {
        assertEquals(
            listOf("A" to "19", "B" to "56", "C" to "75"),
            plain(MantraLibrary.proRata(BigDecimal(150), weights("A" to "100", "B" to "300", "C" to "400"), 0)),
        )
        // 100 / 3 at cent precision: the extra cent goes to the first key on equal remainders.
        assertEquals(
            listOf("A" to "33.34", "B" to "33.33", "C" to "33.33"),
            plain(MantraLibrary.proRata(BigDecimal(100), weights("A" to "1", "B" to "1", "C" to "1"), 2)),
        )
    }

    @Test
    fun `capped allocation re-allocates excess above caps`() {
        // 90 over equal weights would give 30 each, but A may absorb at most 10.
        assertEquals(
            listOf("A" to "10", "B" to "40", "C" to "40"),
            plain(
                MantraLibrary.capped(
                    BigDecimal(90),
                    weights("A" to "1", "B" to "1", "C" to "1"),
                    weights("A" to "10"),
                    0,
                ),
            ),
        )
        // Caps below demand: everything that fits is allocated, the rest is dropped.
        assertEquals(
            listOf("A" to "5", "B" to "5"),
            plain(
                MantraLibrary.capped(
                    BigDecimal(50),
                    weights("A" to "1", "B" to "3"),
                    weights("A" to "5", "B" to "5"),
                    0,
                ),
            ),
        )
    }

    @Test
    fun `waterfall fills capacities in order`() {
        // Loss 100: goodwill (cap 30) first, then other assets (cap 50), rest to the last bucket.
        assertEquals(
            listOf("goodwill" to "30", "assets" to "50", "rest" to "20"),
            MantraLibrary.waterfall(
                BigDecimal(100),
                listOf(
                    kw("goodwill") to BigDecimal(30),
                    kw("assets") to BigDecimal(50),
                    kw("rest") to null,
                ),
            )
                .map { ((it.first as DslValue.KeywordValue).name) to it.second.toPlainString() },
        )
    }

    @Test
    fun `band lookup, annuity payment`() {
        val rows = DslValues.vector(
            listOf(2023 to "0.14", 2024 to "0.136", 2025 to "0.132").map { (year, rate) ->
                DslValues.vector(listOf(DslValues.decimal(BigDecimal(year)), DslValues.decimal(BigDecimal(rate))))
            },
        )
        assertEquals(BigDecimal("0.136"), MantraLibrary.band(BigDecimal(2024), rows, null))
        assertEquals(BigDecimal("0.132"), MantraLibrary.band(BigDecimal(2030), rows, null))
        assertEquals(null, MantraLibrary.band(BigDecimal(2000), rows, null))
        // 100,000 over 10 years at 5 %: 12,950.46 per year.
        assertEquals(BigDecimal("12950.46"), MantraLibrary.pmt(BigDecimal("0.05"), 10, BigDecimal(100000), 2))
        assertEquals(BigDecimal("10000.00"), MantraLibrary.pmt(BigDecimal.ZERO, 10, BigDecimal(100000), 2))
    }

    @Test
    fun `stepwise bands apply each rate to its slice only`() {
        val bands = DslValues.vector(
            listOf(
                DslValues.vector(listOf(DslValues.decimal(BigDecimal(15340)), DslValues.decimal(BigDecimal("0.05")))),
                DslValues.vector(listOf(DslValues.decimal(BigDecimal(51130)), DslValues.decimal(BigDecimal("0.06")))),
                DslValues.vector(listOf(DslValue.Nil, DslValues.decimal(BigDecimal("0.07")))),
            ),
        )
        // 15,340 x 5% + 35,790 x 6% + 8,870 x 7% = 767 + 2,147.40 + 620.90
        assertEquals(0, BigDecimal("3535.30").compareTo(MantraLibrary.stepwise(BigDecimal(60000), bands)))
        assertEquals(0, BigDecimal("500.00").compareTo(MantraLibrary.stepwise(BigDecimal(10000), bands)))
    }
}
