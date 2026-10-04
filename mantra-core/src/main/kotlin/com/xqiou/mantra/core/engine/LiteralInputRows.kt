package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunStage
import com.xqiou.mantra.core.data.DataSources
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType

/** Imported rows are charged by their loader. Literal tables are accounted before runtime use. */
internal object LiteralInputRows {
    fun account(schema: Schema, case: CaseData, context: RunContext) {
        val dimensions = DataSources.inputDimensions(schema)
        DataSources.inputs(schema).values.filter { it.type == ValueType.TABLE }.forEach { input ->
            context.at(RunStage.IMPORTING, context.nodeAddress(input.id)) {
                fun walk(value: Value?, coord: List<String>, depth: Int) {
                    context.charge(RunCounter.HOST_SCANS)
                    if (depth == 0) {
                        if (case.inputOrigins[input.id]?.get(coord.joinToString("/")) == null && value is Value.Vec) {
                            context.charge(RunCounter.INPUT_ROWS, value.items.size.toLong())
                        }
                    } else {
                        (value as? Value.MapV)?.entries?.forEach { (key, next) ->
                            val member = when (key) {
                                is Value.Kw -> key.name
                                is Value.Text -> key.value
                                is Value.Num -> key.value.toPlainString()
                                else -> return@forEach
                            }
                            walk(next, coord + member, depth - 1)
                        }
                    }
                }
                walk(case.inputs[input.id], emptyList(), dimensions[input.id].orEmpty().size)
            }
        }
    }
}
