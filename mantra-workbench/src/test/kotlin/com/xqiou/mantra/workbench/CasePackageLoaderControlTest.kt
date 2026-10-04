package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.RunCancellation
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CasePackageLoaderControlTest {
    @TempDir lateinit var root: Path

    @Test fun `workspace index checkpoints directories and non Mantra files before sorting`() {
        val text = "(case demo {:schema \"test/index\"})"
        Files.writeString(root.resolve("case.mantra"), text)
        repeat(300) { Files.createDirectory(root.resolve("unrelated-$it")) }
        var checkpoints = 0
        var bytes = 0L
        val stop = IllegalStateException("index traversal cancelled")
        val control = object : CaseLoadControl {
            override val deadline = Instant.MAX
            override val cancellation = RunCancellation.NONE
            override fun checkpoint() {
                if (++checkpoints == 100) throw stop
            }
            override fun chargeParticipatingBytes(amount: Long) {
                bytes += amount
            }
            override fun chargeInputRows(amount: Long) = Unit
        }
        assertEquals(
            stop,
            assertFailsWith<IllegalStateException> {
                CasePackageLoader(root).load(CanonicalCaseKey("case.mantra"), control)
            },
        )
        assertEquals(100, checkpoints)
        assertEquals(
            text.toByteArray().size.toLong(),
            bytes,
            "Traversal stopped before document parsing and additional reads",
        )
    }
}
