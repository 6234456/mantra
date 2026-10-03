package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.FunctionCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionDocumentationTest {
    @Test
    fun `DSL reference lists every public calculation function`() {
        val reference = Files.readString(Path.of("docs/dsl-reference.md"))
            .substringAfter("### 1.4").substringBefore("## 2.")
        val documented = Regex("\\((alloc|calc|dim|fin|table)/[a-z-]+")
            .findAll(reference).map { it.value.removePrefix("(") }.toSet()
        assertEquals(FunctionCatalog.functions.map { it.name }.toSet(), documented)
    }
}
