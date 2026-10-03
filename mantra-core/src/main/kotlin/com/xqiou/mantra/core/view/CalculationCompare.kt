package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.Panel
import java.math.BigDecimal

/** Exact difference between two evaluated coordinates. Missing and explicit nil remain distinct. */
data class ValueChange(
    val node: String,
    val coord: List<String>,
    val base: Value?,
    val variant: Value?,
    val basePresent: Boolean,
    val variantPresent: Boolean,
    val delta: BigDecimal?,
)

data class PanelChanges(val step: Int?, val panel: String?, val items: List<ValueChange>)
data class MainlineChange(val step: Int, val panel: String, val value: ValueChange)
data class ParameterChange(val value: ValueChange, val baseSource: String, val variantSource: String)
data class CalculationComparison(
    val mainline: List<MainlineChange>,
    val changes: List<PanelChanges>,
    val parameterChanges: List<ParameterChange>,
)

/** Compares values from the same schema without rounding through a renderer or JSON number. */
object CalculationCompare {
    fun between(base: CalculationView, variant: CalculationView): CalculationComparison {
        require(base.schema.id == variant.schema.id) {
            "Cannot compare different schemas: ${base.schema.id} and ${variant.schema.id}"
        }
        val allIds = (base.nodes.keys + variant.nodes.keys).distinct()
        val differences = allIds.associateWith { id ->
            val left = base.nodes[id]
            val right = variant.nodes[id]
            val coordinates = (left?.values?.keys.orEmpty() + right?.values?.keys.orEmpty()).distinct()
            coordinates.mapNotNull { coord ->
                val aPresent = left?.values?.containsKey(coord) == true
                val bPresent = right?.values?.containsKey(coord) == true
                val a = left?.values?.get(coord)
                val b = right?.values?.get(coord)
                if (aPresent == bPresent && equivalent(a, b)) {
                    null
                } else {
                    ValueChange(
                        id,
                        coord.toList(),
                        a,
                        b,
                        aPresent,
                        bPresent,
                        if (aPresent && bPresent && a is Value.Num && b is Value.Num) b.value - a.value else null,
                    )
                }
            }
        }
        val params = allIds.filter {
            base.nodes[it]?.kind == NodeKind.PARAM || variant.nodes[it]?.kind == NodeKind.PARAM
        }
        val parameterChanges = params.flatMap { id ->
            differences.getValue(id).map { change ->
                ParameterChange(
                    change,
                    base.nodes[id]?.parameterSource ?: "absent",
                    variant.nodes[id]?.parameterSource ?: "absent",
                )
            }
        }
        val mainline = base.structure.mainline.flatMapIndexed { index, panelId ->
            val result = base.structure.panel(panelId).resultId
            differences[result].orEmpty().map { MainlineChange(index + 1, panelId, it) }
        }
        val groups = linkedMapOf<String?, MutableList<ValueChange>>()
        allIds.filter { it !in params }.forEach { id ->
            val panel = base.structure.panelOf(id) ?: variant.structure.panelOf(id)
            val items = differences.getValue(id)
            if (items.isNotEmpty()) groups.getOrPut(panel?.id) { mutableListOf() }.addAll(items)
        }
        val ordered = groups.entries.sortedWith(
            compareBy<Map.Entry<String?, MutableList<ValueChange>>> { entry ->
                panel(base, variant, entry.key)?.position?.step ?: Int.MAX_VALUE
            }.thenBy { entry -> panel(base, variant, entry.key)?.order ?: Int.MAX_VALUE },
        )
        return CalculationComparison(
            mainline,
            ordered.map { (id, items) ->
                PanelChanges(panel(base, variant, id)?.position?.step, id, items)
            },
            parameterChanges,
        )
    }

    private fun panel(base: CalculationView, variant: CalculationView, id: String?): Panel? =
        base.structure.panels.firstOrNull { it.id == id } ?: variant.structure.panels.firstOrNull { it.id == id }

    private fun equivalent(a: Value?, b: Value?): Boolean = when {
        a is Value.Num && b is Value.Num -> a.value.compareTo(b.value) == 0
        else -> a == b
    }
}
