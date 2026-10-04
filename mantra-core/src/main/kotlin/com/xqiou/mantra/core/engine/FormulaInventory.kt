package com.xqiou.mantra.core.engine

import java.util.Collections
import java.util.IdentityHashMap

/** Every host formula, including guards and validation expressions, in stable declaration order. */
internal object FormulaInventory {
    fun of(plan: CalculationPlan, scan: () -> Unit = {}): List<CompiledFormula> {
        val seen = Collections.newSetFromMap(IdentityHashMap<CompiledFormula, Boolean>())
        val result = mutableListOf<CompiledFormula>()
        fun add(formula: CompiledFormula?) {
            if (formula != null) {
                scan()
                if (seen.add(formula)) result += formula
            }
        }
        plan.vertices.values.forEach { vertex ->
            scan()
            if (vertex is ValueVertex) add(vertex.ownCondition)
            when (vertex) {
                is DimensionVertex -> vertex.memberConditions.values.forEach(::add)
                is ConditionVertex -> add(vertex.compiled)
                is LineVertex -> add(vertex.compiled)
                is ChoiceVertex -> vertex.options.forEach {
                    add(it.condition)
                    add(it.formula)
                }
                is CheckVertex -> add(vertex.compiled)
                is ReconcileVertex -> {
                    add(vertex.left)
                    add(vertex.right)
                }
                is InputValidationVertex -> {
                    add(vertex.required)
                    vertex.columns.values.forEach(::add)
                }
                else -> Unit
            }
        }
        return result
    }
}
