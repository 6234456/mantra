package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.view.ParameterLayer

/** Rebinds facts and same-typed parameter values without rebuilding checked expressions. */
internal object PlanRebinding {
    fun compatible(plan: CalculationPlan, case: CaseData): Boolean =
        plan.case.id == case.id && plan.case.schemaId == case.schemaId &&
            plan.case.functions == case.functions && plan.case.extensions == case.extensions &&
            plan.case.formulaBindings == case.formulaBindings

    fun bind(plan: CalculationPlan, case: CaseData, sets: List<ParameterSet>, sink: DiagnosticSink): CalculationPlan? {
        val schema = plan.schema
        SchemaVersions.validate(schema, case, sink)
        val inputIds = schema.inputs.map { it.id }.toSet()
        case.inputs.keys.filter { it !in inputIds }.forEach {
            sink.error("MANTRA-CASE-INPUT-UNKNOWN", "Case supplies unknown input :$it")
        }
        val paramIds = schema.params.map { it.id }.toSet()
        case.params.keys.filter { it !in paramIds }.forEach {
            sink.error("MANTRA-CASE-PARAM-UNKNOWN", "Case overrides unknown parameter :$it")
        }
        sets.forEach { set ->
            set.forSchema?.takeIf { it != schema.id }?.let {
                sink.warning(
                    "MANTRA-PARAMETERS-SCHEMA",
                    "Parameter set ${set.id} is intended for schema $it, not ${schema.id}",
                    set.location,
                )
            }
            set.values.keys.filter { it !in paramIds }.forEach {
                sink.error(
                    "MANTRA-PARAMETERS-UNKNOWN",
                    "Parameter set ${set.id} sets unknown parameter :$it",
                    set.location,
                )
            }
        }
        sink.throwIfErrors()
        val parameters = schema.params.associate { decl ->
            val fromSet = sets.lastOrNull { decl.id in it.values }
            val (value, source) = when {
                decl.id in case.params -> case.params.getValue(decl.id) to "case"
                fromSet != null -> fromSet.values.getValue(decl.id) to fromSet.id
                else -> decl.value to "schema"
            }
            val layers = buildList {
                add(ParameterLayer("schema", decl.value, reference = decl.presentation.reference))
                sets.forEach { set ->
                    set.values[decl.id]?.let { add(ParameterLayer("parameters", it, set.id, set.references[decl.id])) }
                }
                add(ParameterLayer("case", case.params[decl.id], declared = decl.id in case.params))
            }
            decl.id to ParamVertex(decl, value, source, layers)
        }
        if (parameters.any { (id, vertex) -> vertex.type != plan.valueVertices.getValue(id).type }) return null
        val vertices = plan.vertices.mapValues { (id, vertex) -> parameters[id] ?: vertex }
        return CalculationPlan(
            schema, case, sets, plan.dimensions, vertices, plan.order.map { vertices.getValue(it.id) },
            plan.tree, plan.definitions, plan.typeSchema, plan.types,
        )
    }

    fun raw(case: CaseData, id: String, coord: List<String>): Value? {
        var value = case.inputs[id]
        coord.forEach { key ->
            val entries = (value as? Value.MapV)?.entries
            value = entries?.get(Value.Kw(key)) ?: entries?.get(Value.Text(key))
        }
        return value
    }
}
