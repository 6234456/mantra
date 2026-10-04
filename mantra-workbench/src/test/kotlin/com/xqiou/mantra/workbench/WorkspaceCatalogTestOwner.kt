package com.xqiou.mantra.workbench

import org.junit.jupiter.api.AfterEach
import java.nio.file.Path

/** Each JUnit test owns its catalogs, including any catalogs opened inside a scenario loop. */
abstract class WorkspaceCatalogTestOwner {
    private val catalogs = mutableListOf<WorkspaceCatalog>()

    protected fun workspaceCatalog(root: Path): WorkspaceCatalog = WorkspaceCatalog(root).also(catalogs::add)

    @AfterEach
    fun closeCatalogs() {
        val owned = catalogs.asReversed().toList()
        catalogs.clear()
        var failure: Throwable? = null
        owned.forEach { catalog ->
            try {
                catalog.close()
                check(catalog.sessions.isTerminated) { "A test-owned workspace session thread was not released" }
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
}
