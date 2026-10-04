package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.engine.MantraKernel
import com.xqiou.mantra.core.engine.MantraLibrary
import com.xqiou.mantra.core.view.frozenList

/** Documentation for one built-in function; no executable handler is exposed. */
data class LibraryFunction(val name: String, val summary: String)

/** Read-only discovery API for the Mantra calculation library and its composed environment. */
object FunctionCatalog {
    /** Identifier of the domain-neutral Normein library supplied by Mantra. */
    val libraryId: String get() = MantraLibrary.LIBRARY_ID

    /** Semantics version of that library, independent of the Mantra artifact version. */
    val semanticsVersion: String get() = MantraLibrary.SEMANTICS_VERSION

    /** Functions provided by Mantra, in their documented registration order. */
    val functions: List<LibraryFunction> by lazy {
        frozenList(
            MantraLibrary.functions.filterNot {
                it.name.startsWith("mantra-internal/")
            }.map { LibraryFunction(it.name, it.documentation.summary) },
        )
    }

    /** Number of callable functions after composing Mantra and the Normein standard libraries. */
    val callableCount: Int get() = MantraKernel.environment.registry.functions.size
}
