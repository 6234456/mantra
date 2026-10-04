package com.xqiou.mantra.consumer

import com.xqiou.mantra.server.WorkbenchServer
import com.xqiou.mantra.workbench.WorkspaceCatalog

fun publicCatalog(catalog: WorkspaceCatalog): String = catalog.root.toString()
fun publicPort(server: WorkbenchServer): Int = server.localPort

fun main() {
    check(Class.forName("com.xqiou.mantra.workbench.WorkspaceCatalog").simpleName == "WorkspaceCatalog")
    check(Class.forName("com.fasterxml.jackson.databind.ObjectMapper").getDeclaredConstructor().newInstance() != null)
    println("MANTRA_KOTLIN_SERVER_CONSUMER_OK")
}
