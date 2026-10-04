package com.xqiou.mantra.core.api

import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import com.xqiou.normein.dsl.runtime.DslBudgetLimits
import java.util.Collections
import java.util.EnumMap

/**
 * One request's controls. Formula overrides only lower complete kernel defaults.
 * A different normalized formula profile requires rebuilding a reusable execution runtime.
 */
class CalculationOptions(
    val limits: RunLimits = RunLimits(),
    val control: RunControl = RunControl(),
    formulaLimits: Map<DslBudgetCounter, Long> = emptyMap(),
) {
    /** Complete, detached profile: absent overrides do not disable any kernel counter. */
    val formulaLimits: Map<DslBudgetCounter, Long>

    init {
        val defaults = DslBudgetLimits.defaults()
        val merged = EnumMap<DslBudgetCounter, Long>(DslBudgetCounter::class.java).apply { putAll(defaults) }
        formulaLimits.forEach { (counter, maximum) ->
            require(maximum >= 0 && maximum <= defaults.getValue(counter)) {
                "Formula limit $counter must be within the kernel default ceiling"
            }
            merged[counter] = maximum
        }
        this.formulaLimits = Collections.unmodifiableMap(merged)
    }
}
