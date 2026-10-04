package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.api.CompilationStatistics
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunStage
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.view.frozenList
import com.xqiou.mantra.core.view.frozenMap
import com.xqiou.mantra.core.view.snapshot
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.value.DslValueConstructionException
import com.xqiou.normein.dsl.value.DslValueTypes

/** Checked structure and immutable kernel plans; no evaluator, control or case facts are retained. */
internal class CompiledTemplate private constructor(
    val plan: CalculationPlan,
    val kernels: KernelPlans,
    val compilation: CompilationStatistics,
    val diagnostics: List<Diagnostic>,
    val parameters: List<ParameterSet>,
    private val rootTypes: Map<String, DslType>,
    val parameterTypes: Map<String, ValueType>,
) {
    fun bind(case: CaseData, sets: List<ParameterSet>, sink: DiagnosticSink, context: RunContext): CalculationPlan =
        context.at(RunStage.BINDING) {
            val scan = { context.charge(RunCounter.HOST_SCANS) }
            val facts = case.snapshot(scan)
            val parameters = sets.snapshotParameters(scan)
            LiteralInputRows.account(plan.schema, facts, context)
            if (facts.schemaId != null && facts.schemaId != plan.schema.id) {
                sink.error("MANTRA-COMPILE-INCOMPATIBLE", "Case ${facts.id} targets a different schema")
            }
            if (facts.functions != plan.case.functions || facts.extensions != plan.case.extensions ||
                facts.formulaBindings != plan.case.formulaBindings
            ) {
                sink.error(
                    "MANTRA-COMPILE-INCOMPATIBLE",
                    "Functions, extensions and formula bindings must match the compiled template",
                )
            }
            sink.throwIfErrors()
            val bound = PlanRebinding.bind(plan, facts, parameters, sink)
            if (bound == null) {
                sink.error("MANTRA-COMPILE-INCOMPATIBLE", "Parameter root types differ from the compiled template")
            }
            sink.throwIfErrors()
            checkNotNull(bound).valueVertices.values.filterIsInstance<ParamVertex>().forEach { vertex ->
                val location = facts.paramLocations[vertex.id]
                    ?: parameters.lastOrNull { it.id == vertex.source }?.location ?: vertex.location
                try {
                    if (!DslValueTypes.isAssignable(
                            Values.toDslControlled(vertex.value, scan),
                            rootTypes.getValue(vertex.id),
                            plan.typeSchema,
                        )
                    ) {
                        sink.error(
                            "MANTRA-COMPILE-INCOMPATIBLE",
                            "Parameter ${vertex.id} is incompatible with its compiled root type",
                            location,
                            vertex.id,
                        )
                    }
                } catch (invalid: DslValueConstructionException) {
                    sink.error(
                        "MANTRA-COMPILE-INPUT",
                        "Parameter ${vertex.id} cannot be passed to the kernel: ${invalid.message}".take(2048),
                        location,
                        vertex.id,
                        category = DiagnosticCategory.EVALUATION,
                    )
                }
                context.checkpoint()
            }
            sink.throwIfErrors()
            sink.addAll(diagnostics)
            context.checkpoint()
            checkNotNull(bound)
        }

    companion object {
        fun create(
            schema: Schema,
            bindings: CaseData,
            sets: List<ParameterSet>,
            context: RunContext,
        ): CompiledTemplate = context.at(RunStage.PLANNING) {
            val scan = { context.charge(RunCounter.HOST_SCANS) }
            val declared = schema.snapshot(scan)
            // Retain only authored compilation shape and parameter type examples. Large input
            // tables, link receipts and import declarations belong to each actual execution.
            val prototype = bindings.copy(
                id = "<compiled>",
                meta = bindings.meta.filterKeys { it == "schema-version" },
                inputs = emptyMap(), inputLocations = emptyMap(), inputOrigins = emptyMap(),
                inputCells = emptyMap(), sources = emptyList(), links = emptyList(), linkInputs = emptyMap(),
            ).snapshot(scan)
            val parameters = sets.snapshotParameters(scan)
            val sink = DiagnosticSink()
            val meter = CompilationMeter(context)
            val plan = Planner(sink, context, requireMaterializedLinks = false, compiling = meter)
                .plan(declared, prototype, parameters)
            sink.throwIfErrors()
            val checked = checkNotNull(plan)
            val formulas = FormulaInventory.of(checked, scan)
            val kernels = KernelPlans.create(formulas, meter)
            context.checkpoint()
            return@at CompiledTemplate(
                checked,
                kernels,
                meter.snapshot(formulas.size.toLong()),
                // Parameter ownership is re-established on every bind, including mismatched
                // parameter-set warnings. Other compiler warnings describe immutable authorship.
                frozenList(sink.all.filter { it.code != "MANTRA-PARAMETERS-SCHEMA" }),
                parameters,
                frozenMap(
                    checked.valueVertices.values.filterIsInstance<ParamVertex>().associate {
                        it.id to CompilationTypes.infer(it.value, scan)
                    },
                ),
                frozenMap(
                    checked.valueVertices.values.filterIsInstance<ParamVertex>().associate {
                        scan()
                        it.id to it.type
                    },
                ),
            )
        }
    }
}

internal fun List<ParameterSet>.snapshotParameters(scan: () -> Unit): List<ParameterSet> = frozenList(
    map { set ->
        scan()
        set.copy(
            meta = frozenMap(set.meta.mapValues { (_, value) -> value.snapshot(scan) }),
            values = frozenMap(set.values.mapValues { (_, value) -> value.snapshot(scan) }),
            references = frozenMap(set.references),
        )
    },
)
