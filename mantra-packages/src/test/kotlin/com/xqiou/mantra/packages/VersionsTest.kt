package com.xqiou.mantra.packages

import com.xqiou.mantra.core.model.SchemaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VersionsTest {
    @Test
    fun `strict SemVer orders prereleases and arbitrary size numbers without rewriting identity`() {
        val sequence =
            listOf(
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-alpha.beta",
                "1.0.0-beta",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0-rc.1",
                "1.0.0",
            )
        sequence.zipWithNext().forEach { (a, b) -> assertTrue(SemanticVersion.parse(a) < SemanticVersion.parse(b)) }
        assertTrue(SemanticVersion.parse("999999999999999999999.0.0") > SemanticVersion.parse("10.0.0"))
        val a = SemanticVersion.parse("1.2.3+one")
        val b = SemanticVersion.parse("1.2.3+two")
        assertEquals(0, a.compareTo(b))
        assertNotEquals(a, b)
        listOf("1", "0.1", "2025.2", "01.2.3", "1.2.3-01", "1.2.3-", "1.2.3+", "v1.2.3", " 1.2.3").forEach {
            assertFailsWith<PackageException> { SemanticVersion.parse(it) }
        }
    }

    @Test
    fun `comparator intersection has explicit prerelease semantics and rejects unsupported or empty ranges`() {
        val range = VersionRange.parse(">=0.4.0-0 <0.5.0")
        assertTrue(range.contains(SemanticVersion.parse("0.4.0-SNAPSHOT")))
        assertTrue(range.contains(SemanticVersion.parse("0.4.9")))
        assertFalse(range.contains(SemanticVersion.parse("0.5.0")))
        assertFalse(VersionRange.parse(">=0.4.0").contains(SemanticVersion.parse("0.4.0-SNAPSHOT")))
        assertTrue(VersionRange.parse(">0.1.0 >=1.0.0 <=1.0.0").contains(SemanticVersion.parse("1.0.0")))
        listOf(
            "latest",
            "^1.0.0",
            "~1.0.0",
            "1.*",
            ">=1.0.0 || <2.0.0",
            ">2.0.0 <1.0.0",
            ">1.0.0 <=1.0.0",
            "=1.0.0 =2.0.0",
        ).forEach {
            assertFailsWith<PackageException> { VersionRange.parse(it) }
        }
    }

    @Test
    fun `legacy exact permits raw historic and null versions but never coerces them into SemVer`() {
        listOf("2025.2", "2025.3", "0.1", "1", "01", null).forEach { version ->
            val binding = PackageSchemaBinding(SchemaIdentity("demo", version), SchemaVersionMode.LEGACY_EXACT)
            assertEquals(version, binding.identity.version)
            assertFailsWith<PackageException> { PackageSchemaBinding(binding.identity, SchemaVersionMode.SEMVER) }
        }
        assertNotEquals(
            PackageSchemaBinding(SchemaIdentity("demo", "01"), SchemaVersionMode.LEGACY_EXACT),
            PackageSchemaBinding(SchemaIdentity("demo", "1"), SchemaVersionMode.LEGACY_EXACT),
        )
    }
}
