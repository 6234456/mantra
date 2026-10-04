package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.NodeTrace
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exact diagnostics and real failure amounts; no catch-and-skip or numeric-zero convenience getter. */
object AcceptanceAssertions {
    private fun token(value: Value?): String = when (value) {
        is Value.Text -> value.value
        is Value.Kw -> value.name
        is Value.Num -> value.value.toPlainString()
        else -> ""
    }

    private fun key(diagnostic: Diagnostic): String = listOf(
        diagnostic.code,
        diagnostic.nodeId.orEmpty(),
        diagnostic.coord.joinToString("/"),
        diagnostic.severity.name.lowercase(),
        diagnostic.rowIndex?.toString().orEmpty(),
        diagnostic.column.orEmpty(),
    ).joinToString("|")

    private fun expectedKey(item: Value.MapV): String {
        fun field(name: String) = item.entries[Value.Kw(name)]
        return listOf(
            token(field("code")),
            token(field("node")),
            (field("coord") as? Value.Vec)?.items.orEmpty().joinToString("/") { token(it) },
            token(field("severity")).ifEmpty { "error" },
            token(field("row-index")),
            token(field("column")),
        ).joinToString("|")
    }

    private fun expected(case: CaseData, field: String): List<Value.MapV> {
        val value = case.meta[field] ?: return emptyList()
        require(value is Value.Vec) { ":$field must contain a vector" }
        return value.items.map { item ->
            require(item is Value.MapV) { ":$field must contain maps" }
            item
        }
    }

    fun ordinary(
        binding: AcceptanceCaseBinding,
        execution: AcceptanceExecution,
        sourcePaths: AcceptanceSourcePathResolver,
    ) {
        assertIdentity(binding, execution)
        assertGraphStatuses(execution)
        assertTrue(execution.succeeded, "${binding.declaration.path}: genuine technical failure")
        assertTrue(execution.result.succeeded, "Root calculation must genuinely succeed")
        val case = binding.declaration.case
        val localExpected = expected(case, "expected-business").map(::expectedKey).sorted()
        val localActual = execution.localDiagnostics.filter { it.category == DiagnosticCategory.BUSINESS }
            .map(::key).sorted()
        assertEquals(localExpected, localActual, "${case.id}: local business findings")
        val sourceExpected = expected(case, "expected-source-business").map { item ->
            fun field(name: String) = item.entries[Value.Kw(name)]
            val path = sourcePaths.resolve(binding.declaration.path, token(field("case")))
            val id = token(field("schema"))
            val version = token(field("schema-version"))
            require(id.isNotBlank() && version.isNotBlank()) { "Source finding requires exact schema identity" }
            listOf(path.toString(), id, version, expectedKey(item)).joinToString("|")
        }.sorted()
        val sourceActual = execution.inheritedDiagnostics.filter {
            it.diagnostic.category == DiagnosticCategory.BUSINESS
        }.map { item ->
            require(!item.source.schema.version.isNullOrBlank()) { "Linked source lost exact schema version" }
            listOf(
                item.source.casePath.toString(),
                item.source.schema.id,
                item.source.schema.version,
                key(item.diagnostic),
            )
                .joinToString("|")
        }.sorted()
        assertEquals(sourceExpected, sourceActual, "${case.id}: inherited business findings with ownership")
        assertEquals(
            binding.declaration.linkedTargets.sortedBy { it.node + it.coord.joinToString("/") },
            execution.links.map { it.to }.sortedBy { it.node + it.coord.joinToString("/") },
            "All declared link targets must be supplied once by genuine links",
        )
        if (binding.declaration.hasLinks) assertTrue(execution.sources.isNotEmpty())
        assertLinkedInputs(execution)
    }

    fun technicalFailure(binding: AcceptanceCaseBinding, execution: AcceptanceExecution) {
        assertIdentity(binding, execution)
        assertGraphStatuses(execution)
        assertFalse(execution.succeeded, "A failure fixture must really fail")
        assertFalse(execution.result.succeeded, "A declared evaluation failure must fail its root calculation")
        val code = binding.declaration.case.text("expected-evaluation-code")
        require(!code.isNullOrBlank()) { "Failure fixture requires :expected-evaluation-code" }
        assertTrue(
            execution.localDiagnostics.any {
                it.code == code && it.category == DiagnosticCategory.EVALUATION && it.severity == Severity.ERROR
            },
            "Expected actual evaluation error $code; got ${execution.localDiagnostics}",
        )
        val values = expected(binding.declaration.case, "expected-failed-values")
        require(values.isNotEmpty()) { "Failure fixture must name undefined result addresses" }
        values.forEach { item ->
            val id = token(item.entries[Value.Kw("node")])
            val coord = (item.entries[Value.Kw("coord")] as? Value.Vec)?.items.orEmpty().map(::token)
            val node = assertNotNull(execution.result.nodes[id], "Failed formula must exist: $id")
            assertEquals(node.dims.size, coord.size, "Failed value needs its full coordinate")
            assertEquals(Value.Nil, node.value(coord), "Failure cannot return an approximation or substitute zero")
            assertTrue(node.trace(coord) is NodeTrace.Failed, "Expected real failed evaluation trace at $id$coord")
        }
    }

    fun failureDocument(binding: AcceptanceCaseBinding, run: Map<String, Any?>) {
        assertEquals(false, run["succeeded"], "Workbench must expose actual failure")
        val code = requireNotNull(binding.declaration.case.text("expected-evaluation-code"))
        val diagnostics = run["diagnostics"] as? List<*> ?: error("Workbench run lacks diagnostics")
        assertTrue(
            diagnostics.filterIsInstance<Map<*, *>>().any {
                it["code"] == code && it["category"] == "evaluation" && it["severity"] == "error"
            },
            "Workbench must expose the genuine technical diagnostic",
        )
        val values = run["values"] as? Map<*, *> ?: error("Workbench run lacks values")
        expected(binding.declaration.case, "expected-failed-values").forEach { item ->
            val id = token(item.entries[Value.Kw("node")])
            val coord = (item.entries[Value.Kw("coord")] as? Value.Vec)?.items.orEmpty().joinToString("/") { token(it) }
            val memberValues = values[id] as? Map<*, *> ?: error("Workbench lacks failed formula $id")
            val cell = memberValues[coord] as? Map<*, *> ?: error("Workbench lacks failed coordinate $id@$coord")
            assertEquals(null, cell["value"], "Workbench cannot present a stale/approximate/zero failure result")
        }
    }

    private fun assertIdentity(binding: AcceptanceCaseBinding, execution: AcceptanceExecution) {
        assertEquals(binding.declaration.path, execution.identity.casePath)
        assertEquals(binding.schemaId, execution.identity.schema)
        assertEquals(binding.schema.id, execution.result.schema.id)
        assertEquals(binding.schema.meta.text("version"), execution.result.schema.meta.text("version"))
        assertEquals(binding.declaration.case.id, execution.result.case.id)
        execution.sources.forEach { source ->
            assertEquals(source.identity.schema.id, source.result.schema.id)
            assertEquals(source.identity.schema.version, source.result.schema.meta.text("version"))
        }
        val identities = execution.sources.map { it.identity.casePath }
        assertEquals(identities.size, identities.toSet().size, "A graph source should be retained once")
        execution.inheritedDiagnostics.forEach { finding ->
            val source = assertNotNull(execution.sources.singleOrNull { it.identity == finding.source })
            assertTrue(
                finding.diagnostic in source.localDiagnostics,
                "Source finding must retain its actual direct owner",
            )
            assertTrue(
                finding.diagnostic in source.result.diagnostics,
                "Source diagnostic must come from its actual public result",
            )
        }
    }

    private fun assertGraphStatuses(execution: AcceptanceExecution) {
        val inheritedBusiness = execution.inheritedDiagnostics.filter {
            it.diagnostic.category ==
                DiagnosticCategory.BUSINESS
        }
        val actualSourceBusiness = execution.sources.flatMap { source ->
            source.localDiagnostics.filter { it.category == DiagnosticCategory.BUSINESS }.map {
                AcceptanceInheritedDiagnostic(source.identity, it)
            }
        }
        assertEquals(
            actualSourceBusiness.toSet(),
            inheritedBusiness.toSet(),
            "Every source BUSINESS finding retains its direct owner",
        )
        assertEquals(
            actualSourceBusiness.size,
            inheritedBusiness.size,
            "Source BUSINESS findings are retained exactly once",
        )
        val diagnostics = execution.localDiagnostics + execution.inheritedDiagnostics.map { it.diagnostic } +
            execution.sources.flatMap { it.localDiagnostics }
        assertEquals(
            diagnostics.none {
                it.severity == Severity.ERROR && it.category != DiagnosticCategory.BUSINESS
            } && execution.result.succeeded && execution.sources.all { it.result.succeeded },
            execution.succeeded,
            "Graph success must retain source technical errors",
        )
        assertEquals(
            diagnostics.none {
                it.severity == Severity.ERROR && it.category == DiagnosticCategory.BUSINESS
            },
            execution.validationPassed,
            "Graph validation must retain source business errors",
        )
    }

    private fun assertLinkedInputs(execution: AcceptanceExecution) {
        execution.links.forEach { link ->
            val source = assertNotNull(execution.sources.singleOrNull { it.identity == link.source })
            assertTrue(source.result.succeeded, "A technically failed source cannot supply a linked value")
            val sourceNode = assertNotNull(source.result.nodes[link.from.node])
            assertEquals(sourceNode.dims.size, link.from.coord.size)
            assertTrue(sourceNode.isActive(link.from.coord), "Inactive source is not a provided zero")
            val supplied = assertNotNull(sourceNode.values[link.from.coord], "Missing source cannot default zero")
            assertTrue(supplied != Value.Nil, "Nil source cannot default zero")
            assertScalarEquals(supplied, link.value)
            val target = assertNotNull(execution.result.nodes[link.to.node])
            assertEquals(target.dims.size, link.to.coord.size)
            assertTrue(target.isActive(link.to.coord), "Link target must exist and be active")
            assertScalarEquals(link.value, assertNotNull(target.values[link.to.coord]))
            val origin = assertNotNull(target.trace(link.to.coord) as? NodeTrace.Input)
            assertEquals("LINK", origin.origin.name, "Legal zero/false must retain genuine Link origin")
        }
    }

    private fun assertScalarEquals(expected: Value, actual: Value) {
        if (expected is Value.Num && actual is Value.Num) {
            assertEquals(0, expected.value.compareTo(actual.value))
        } else {
            assertEquals(expected, actual)
        }
    }
}
