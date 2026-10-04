package com.xqiou.mantra.consumer

import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.workbench.CasePackageLoader
import com.xqiou.mantra.workbench.WorkspaceProblem

// This source set declares only mantra-workbench. LayoutSpec must be compile-reachable from its POM.
fun publicLayout(binding: CasePackageLoader.Binding?): LayoutSpec? = binding?.layout

fun main() {
    check(publicLayout(null) == null)
    check(SourceText("typed", "data").name == "typed")
    check(WorkspaceProblem.REQUEST.name == "REQUEST")
    check(Class.forName("com.xqiou.mantra.render.layout.LayoutSpec").simpleName == "LayoutSpec")
    println("MANTRA_KOTLIN_WORKBENCH_CONSUMER_OK")
}
