package com.xqiou.mantra.consumer

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.normein.dsl.runtime.DslBudgetCounter

fun main() {
    val schema = Mantra.loadSchema(
        SourceText("schema.mantra", """(schema consumer/core (param multiplier 2) (input base :decimal) (line answer "Answer" (* base multiplier)))"""),
        SourceResolver { _, _ -> null },
    )
    val parameter = ParameterSet("current", emptyMap(), mapOf("multiplier" to Value.num(3)), emptyMap(),
        com.xqiou.mantra.core.SourceLocation("params", 1, 1))
    val template = Mantra.compile(schema, parameters = listOf(parameter))
    val options = CalculationOptions(formulaLimits = mapOf(DslBudgetCounter.FUNCTION_CALLS to 10L))
    template.openSession(options).use { worker ->
        val first = worker.calculate(CaseData.empty("kotlin-a").copy(inputs = mapOf("base" to Value.num(4))))
        val second = worker.calculate(CaseData.empty("kotlin-b").copy(inputs = mapOf("base" to Value.num(7))))
        check(first.case.id == "kotlin-a" && first.decimal("answer").toInt() == 12)
        check(second.case.id == "kotlin-b" && second.decimal("answer").toInt() == 21)
        check(worker.statistics.sessionOpens == 1L && worker.statistics.executionPlanCompilations == 0L)
    }
    println("MANTRA_KOTLIN_CORE_CONSUMER_OK")
}
