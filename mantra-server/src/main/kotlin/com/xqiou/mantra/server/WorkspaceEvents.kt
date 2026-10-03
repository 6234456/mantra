package com.xqiou.mantra.server

import com.sun.net.httpserver.HttpExchange
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.io.IOException
import java.util.concurrent.Semaphore

/** Streams workspace changes using a shared, bounded content scanner. */
internal class WorkspaceEvents(private val catalog: WorkspaceCatalog, private val running: () -> Boolean) {
    private val eventSlots = Semaphore(2)
    private val stampLock = Any()
    private var sharedStamp: WorkspaceCatalog.WorkspaceStamp? = null
    private var stampError: WorkspaceException? = null
    private var stampCheckedAt = 0L

    private fun eventStamp(): WorkspaceCatalog.WorkspaceStamp = synchronized(stampLock) {
        val now = System.nanoTime()
        val delay = if (stampError == null) 1_000_000_000L else 5_000_000_000L
        if (stampCheckedAt != 0L && now - stampCheckedAt < delay) {
            stampError?.let { throw it }
            sharedStamp?.let { return@synchronized it }
        }
        stampCheckedAt = now
        try {
            catalog.workspaceStamp(sharedStamp).also {
                sharedStamp = it
                stampError = null
            }
        } catch (problem: WorkspaceException) {
            stampError = problem
            throw problem
        }
    }

    fun stream(exchange: HttpExchange) {
        if (!eventSlots.tryAcquire()) {
            return error(exchange, 503, "MANTRA-WORKBENCH-BUSY", "Too many event streams")
        }
        try {
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("X-Accel-Buffering", "no")
            exchange.sendResponseHeaders(200, 0)
            val output = exchange.responseBody
            fun sendEvent(name: String, data: Map<String, Any?>) {
                output.write("event: $name\ndata: ${WorkbenchJson.write(data)}\n\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
            var previous: WorkspaceCatalog.WorkspaceStamp? = null
            var scanError: String? = null
            while (running() && !Thread.currentThread().isInterrupted) {
                val current = try {
                    eventStamp()
                } catch (problem: WorkspaceException) {
                    val code = when (problem.problem) {
                        WorkspaceProblem.TOO_LARGE -> "MANTRA-WORKBENCH-TOO-LARGE"
                        else -> "MANTRA-WORKBENCH-DOCUMENT"
                    }
                    val signature = "$code:${problem.message}"
                    if (scanError != signature) {
                        sendEvent("workspaceError", mapOf("code" to code, "message" to problem.message))
                        scanError = signature
                    }
                    Thread.sleep(5_000)
                    continue
                }
                if (previous == null || scanError != null) {
                    sendEvent("revision", mapOf("revision" to current.revision))
                } else if (current.revision != previous.revision) {
                    val changed = (previous.files.keys + current.files.keys).filter {
                        previous.files[it] !=
                            current.files[it]
                    }.sorted()
                    sendEvent("documentChanged", mapOf("revision" to current.revision, "paths" to changed))
                } else {
                    output.write(": keepalive\n\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                previous = current
                scanError = null
                Thread.sleep(1_000)
            }
        } catch (_: IOException) {
            // A closed browser tab is observed when the next event or heartbeat is written.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            eventSlots.release()
        }
    }
}
