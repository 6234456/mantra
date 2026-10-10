package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.api.CalculationSession
import com.xqiou.mantra.core.api.RecalculationStats
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.read.ParameterSet
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Bounded, catalog-owned sessions. Their dedicated executor owns creation, reuse and disposal;
 * callers on HTTP threads receive detached results, never thread-confined SDK runtimes.
 */
internal class WorkspaceSessions(private val limit: Int = 16) : AutoCloseable {
    private class Entry(val schemaFingerprint: String, val session: CalculationSession) {
        var auditCase: CaseData? = null
        var auditParameters: List<ParameterSet>? = null
        var audit: CalculationResult? = null
    }
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val submissions = Any()

    @Volatile private var ownerThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mantra-workspace-sessions").apply { isDaemon = true }.also { ownerThread = it }
    }

    @Volatile
    private var closed = false
    private var closeTask: Future<Unit>? = null
    internal val isTerminated: Boolean get() = executor.isTerminated && ownerThread?.isAlive != true

    private var activeResolver: com.xqiou.mantra.core.api.CasePackageResolver? = null
    private var graphRunner: com.xqiou.mantra.core.api.CaseGraphRunner? = null
    private var lastGraph: com.xqiou.mantra.core.api.CaseRunResult? = null

    fun graph(
        resolver: com.xqiou.mantra.core.api.CasePackageResolver,
        request: com.xqiou.mantra.core.api.CaseRunRequest,
        fresh: Boolean = false,
    ): com.xqiou.mantra.core.api.CaseRunResult = onOwner {
        check(activeResolver == null) { "Workspace graph requests cannot be nested" }
        if (fresh) {
            return@onOwner com.xqiou.mantra.core.api.CaseGraphRunner(resolver, 0).use { it.run(request) }
        }
        val runner = graphRunner ?: com.xqiou.mantra.core.api.CaseGraphRunner(
            object : com.xqiou.mantra.core.api.CasePackageResolver {
                override fun identify(
                    reference: com.xqiou.mantra.core.api.CaseReference,
                    control: com.xqiou.mantra.core.api.CaseLoadControl,
                ) = checkNotNull(activeResolver).identify(reference, control)
                override fun load(
                    key: com.xqiou.mantra.core.api.CanonicalCaseKey,
                    control: com.xqiou.mantra.core.api.CaseLoadControl,
                ) = checkNotNull(activeResolver).load(key, control)
            },
            limit,
        ).also { graphRunner = it }
        activeResolver = resolver
        try {
            runner.run(request).also { lastGraph = it }
        } finally {
            activeResolver = null
        }
    }

    fun calculate(
        caseId: String,
        schemaFingerprint: String,
        schema: Schema,
        case: CaseData,
        parameters: List<ParameterSet>,
        audit: Boolean,
    ): CalculationResult = onOwner {
        var entry = entries[caseId]
        val result = if (entry == null || entry.schemaFingerprint != schemaFingerprint) {
            entries.remove(caseId)?.session?.close()
            entry = Entry(schemaFingerprint, Mantra.openSession(schema, case, parameters))
            entries[caseId] = entry
            while (entries.size > limit) {
                val oldest = entries.entries.iterator().next()
                entries.remove(oldest.key)
                oldest.value.session.close()
            }
            entry.session.result
        } else {
            entry.session.recalculate(case, parameters)
        }
        if (!audit) return@onOwner result
        if (entry.audit == null || entry.auditCase != case || entry.auditParameters != parameters) {
            // FULL evidence is collected only when requested; VALUE_ONLY results never invent steps.
            entry.audit = Mantra.calculateForAudit(schema, case, parameters)
            entry.auditCase = case
            entry.auditParameters = parameters.toList()
        }
        requireNotNull(entry.audit)
    }

    fun stats(caseId: String): RecalculationStats? {
        if (Thread.currentThread() === ownerThread) return graphStats(caseId) ?: entries[caseId]?.session?.lastRun
        val task = synchronized(submissions) {
            if (closed) return null
            executor.submit(Callable { graphStats(caseId) ?: entries[caseId]?.session?.lastRun })
        }
        return await(task)
    }

    private fun graphStats(caseId: String) =
        lastGraph?.cases?.get(com.xqiou.mantra.core.api.CanonicalCaseKey(caseId))?.recalculation

    override fun close() {
        // The owner never waits for a task queued behind itself.
        if (Thread.currentThread() === ownerThread) {
            synchronized(submissions) {
                if (!closed) {
                    closed = true
                    try {
                        closeEntries()
                        closeTask = CompletableFuture.completedFuture(Unit)
                    } finally {
                        executor.shutdown()
                    }
                }
            }
            return
        }
        val task = synchronized(submissions) {
            closeTask ?: executor.submit(Callable { closeEntries() }).also {
                closed = true
                closeTask = it
                executor.shutdown()
            }
        }
        waitForClose(task)
    }

    private fun waitForClose(task: Future<Unit>) {
        var interrupted = false
        fun <T> uninterrupted(wait: () -> T): T {
            while (true) {
                try {
                    return wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        try {
            try {
                uninterrupted { task.get() }
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
            check(uninterrupted { executor.awaitTermination(30, TimeUnit.SECONDS) }) {
                "Workspace session executor did not terminate"
            }
            ownerThread?.let { owner ->
                uninterrupted { owner.join(TimeUnit.SECONDS.toMillis(30)) }
                check(!owner.isAlive) { "Workspace session owner thread did not stop" }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun closeEntries() {
        var first: Throwable? = null
        fun close(resource: AutoCloseable) {
            try {
                resource.close()
            } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure)
            }
        }
        try {
            entries.values.forEach { close(it.session) }
            graphRunner?.let(::close)
        } finally {
            entries.clear()
            graphRunner = null
            activeResolver = null
            lastGraph = null
        }
        first?.let { throw it }
    }

    private fun <T> onOwner(operation: () -> T): T {
        if (Thread.currentThread() === ownerThread) {
            check(!closed) { "Workspace calculation sessions are closed" }
            return operation()
        }
        val task = synchronized(submissions) {
            check(!closed) { "Workspace calculation sessions are closed" }
            executor.submit(Callable { operation() })
        }
        return await(task)
    }

    private fun <T> await(task: Future<T>): T = try {
        task.get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while waiting for workspace calculation", failure)
    }
}

/** Hash the bytes actually parsed, so a file changing during a request cannot poison a cache key. */
internal fun schemaFingerprint(sources: Map<String, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    sources.toSortedMap().forEach { (name, source) ->
        listOf(name, source).forEach { item ->
            val bytes = item.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
