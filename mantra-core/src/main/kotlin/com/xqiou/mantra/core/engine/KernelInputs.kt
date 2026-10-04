package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValueConstructionResult
import com.xqiou.normein.dsl.value.DslValues

/** Imports node values and declared records into the current plan's checked kernel types. */
internal class KernelInputs(
    private val plan: () -> CalculationPlan,
    private val sink: DiagnosticSink,
    private val context: () -> RunContext,
) {
    private fun scan() = context().charge(RunCounter.HOST_SCANS)
    private fun literal(value: Value) = Values.toDslControlled(value, ::scan)

    fun toDsl(vertex: ValueVertex, value: Value, depth: Int): DslValue {
        scan()
        if (depth > 0 && value is Value.MapV) {
            return DslValues.map(
                value.entries.map { (key, item) ->
                    literal(key) to toDsl(vertex, item, depth - 1)
                },
            )
        }
        return when {
            vertex.type == ValueType.TABLE && value is Value.Vec -> structured(
                value.items.map { row ->
                    scan()
                    hostRecord(
                        (row as Value.MapV).entries.entries.associate { (key, item) ->
                            (key as Value.Kw).name to item
                        },
                        rowTypes(vertex),
                    )
                },
                DslTypes.vector(DslTypes.ref(plan().types.tableRow.getValue(vertex.id))),
                vertex.id,
            )
            vertex.type == ValueType.INTEGER && value is Value.Num -> Values.toIntegerDsl(value)
            else -> literal(value)
        }
    }

    fun hostRecord(fields: Map<String, Value>, types: Map<String, ValueType>): Map<String, Any?> = fields.filterKeys {
        scan()
        it in types
    }.mapValues { (name, value) ->
        when {
            value == Value.Nil -> null
            types[name] == ValueType.INTEGER && value is Value.Num -> Values.toIntegerDsl(value)
            else -> literal(value)
        }
    }

    val recordTypes: Map<String, Map<String, ValueType>> by lazy {
        val current = plan()
        val base = mapOf("key" to ValueType.KEYWORD, "label" to ValueType.TEXT, "index" to ValueType.INTEGER)
        current.dimensions.values.associate { dimension ->
            scan()
            val columns = dimension.fromTable?.let { table ->
                (current.valueVertices[table] as? InputVertex)?.decl?.columns
            }.orEmpty()
            val period = if (dimension.periods != null) {
                mapOf(
                    "start" to ValueType.DATE,
                    "end-exclusive" to ValueType.DATE,
                    "previous-key" to ValueType.KEYWORD,
                    "parent-key" to ValueType.KEYWORD,
                )
            } else {
                emptyMap()
            }
            dimension.id to (
                columns.associate {
                    scan()
                    it.name to it.type
                } + base + period
                )
        }
    }

    fun rowTypes(vertex: ValueVertex): Map<String, ValueType> =
        (vertex as? InputVertex)?.decl?.columns.orEmpty().associate {
            scan()
            it.name to it.type
        }

    fun structured(host: Any?, type: DslType, what: String): DslValue =
        context().at(com.xqiou.mantra.core.api.RunStage.IMPORTING) {
            when (val result = DslValues.importStructuredHost(host, type, plan().typeSchema)) {
                is DslValueConstructionResult.Success -> result.value.also { context().checkpoint() }
                is DslValueConstructionResult.Failure -> {
                    sink.error("MANTRA-RECORD", "Cannot pass $what to the kernel: ${result.violation}")
                    DslValue.Nil
                }
            }
        }
}
