package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.packages.MigrationOperation
import com.xqiou.mantra.packages.MigrationPlan
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem

/** Retain review intent, never FULL before/after calculation snapshots or their audit graphs. */
internal class PackageMigrationWorkflow(
    private val maxEntries: Int = 32,
    private val maxPayloadBytes: Long = 4L * 1024 * 1024,
) {
    private data class Pending(val plan: MigrationPlan, val payloadBytes: Long)
    private val pending = linkedMapOf<String, Pending>()
    private var payloadBytes = 0L

    init {
        require(maxEntries > 0 && maxPayloadBytes > 0)
    }

    fun retain(plan: MigrationPlan, token: String) {
        require(token.matches(Regex("[0-9a-f]{64}")))
        val size = payloadSize(plan)
        if (size > maxPayloadBytes) {
            throw WorkspaceException(
                WorkspaceProblem.TOO_LARGE,
                "Migration review intent exceeds its retained-source byte limit",
            )
        }
        remove(token)
        while (pending.size >= maxEntries || payloadBytes > maxPayloadBytes - size) remove(pending.keys.first())
        pending[token] = Pending(plan, size)
        payloadBytes += size
    }

    fun plan(case: String, token: String): MigrationPlan {
        val found = pending[token] ?: throw WorkspaceException(
            WorkspaceProblem.CONFLICT,
            "Reviewed migration preview expired or was not found",
        )
        if (found.plan.casePath != case) {
            throw WorkspaceException(
                WorkspaceProblem.REQUEST,
                "Review token belongs to a different case",
            )
        }
        return found.plan
    }

    fun remove(token: String) {
        pending.remove(token)?.let { payloadBytes -= it.payloadBytes }
    }

    /** UTF-8 intent payload bound, not a claim of exact JVM heap accounting. */
    private fun payloadSize(plan: MigrationPlan): Long {
        var bytes = 64L // Review token.
        fun add(value: String?) {
            if (value == null) return
            val size = value.toByteArray(Charsets.UTF_8).size.toLong()
            if (bytes > maxPayloadBytes - minOf(size, maxPayloadBytes)) {
                throw WorkspaceException(
                    WorkspaceProblem.TOO_LARGE,
                    "Migration review intent exceeds its retained-source byte limit",
                )
            }
            bytes += size
        }
        fun binding(binding: com.xqiou.mantra.packages.PackageSchemaBinding) {
            add(binding.identity.id)
            add(binding.identity.version)
            add(binding.mode.name)
        }
        add(plan.casePath)
        add(plan.baseRevision)
        binding(plan.source)
        binding(plan.target.schema)
        add(plan.target.packageIdentity.id)
        add(plan.target.packageIdentity.version.text)
        add(plan.target.packageRevision)
        add(plan.target.parameterSelectionRevision)
        plan.operations.forEach { operation ->
            when (operation) {
                is MigrationOperation.PinSchema -> binding(operation.schema)
                is MigrationOperation.BindParameters -> operation.ids.forEach(::add)
                is MigrationOperation.BindLayout -> add(operation.id)
                is MigrationOperation.ReplaceText -> {
                    add(operation.offset.toString())
                    add(operation.expected)
                    add(operation.replacement)
                }
            }
        }
        return bytes
    }
}
