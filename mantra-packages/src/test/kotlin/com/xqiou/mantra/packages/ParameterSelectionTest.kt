package com.xqiou.mantra.packages

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ParameterSelectionTest {
    @TempDir lateinit var temporary: Path
    private fun snapshot(fixture: PackageFixture, name: String = "source") = trustedDirectory(
        fixture.directory(temporary.resolve(name)),
        SemanticVersion.parse("0.4.0-SNAPSHOT"),
        fixture.limits,
    )

    @Test
    fun `half open boundaries select keys independently and carry actual references and hashes`() {
        val fixture = PackageFixture().parameter("old", "2", "2026-01-01", "2027-01-01")
            .parameter("new", "3", "2027-01-01", "2028-01-01").parameter("cap", "99", ceiling = "80")
        val captured = snapshot(fixture)
        // cap also supplies rate, so it is only a candidate for the explicitly requested ceiling.
        val old = ParameterSelector.effectiveDate(
            captured,
            fixture.binding,
            LocalDate.parse("2026-12-31"),
            listOf("old", "new"),
            setOf("rate"),
        )
        val next = ParameterSelector.effectiveDate(
            captured,
            fixture.binding,
            LocalDate.parse("2027-01-01"),
            listOf("old", "new"),
            setOf("rate"),
        )
        val cap = ParameterSelector.effectiveDate(
            captured,
            fixture.binding,
            LocalDate.parse("2027-01-01"),
            listOf("cap"),
            setOf("ceiling"),
        )
        assertEquals(Value.num(2), old.parameters.single().values["rate"])
        assertEquals(Value.num(3), next.parameters.single().values["rate"])
        assertEquals(setOf("ceiling"), cap.parameters.single().values.keys)
        val evidence = next.provenance.single()
        assertEquals("new", evidence.setId)
        assertEquals("Fictional source new", evidence.reference)
        assertEquals(captured.descriptor("parameters/new.mantra").sha256, evidence.resourceSha256)
        assertTrue(evidence.validForDate)
        assertNotEquals(old.revision, next.revision)
        val followingDay = ParameterSelector.effectiveDate(
            captured,
            fixture.binding,
            LocalDate.parse("2027-01-02"),
            listOf("old", "new"),
            setOf("rate"),
        )
        assertNotEquals(next.revision, followingDay.revision)
    }

    @Test
    fun `missing endpoint remains unbounded overlaps and gaps do not infer a successor or default`() {
        val fixture = PackageFixture().parameter("unbounded", "2", "2026-01-01")
            .parameter("next", "3", "2027-01-01", "2028-01-01")
        val captured = snapshot(fixture)
        assertEquals(
            "MANTRA-PACKAGE-DATE-OVERLAP",
            assertFailsWith<PackageException> {
                ParameterSelector.effectiveDate(
                    captured,
                    fixture.binding,
                    LocalDate.parse("2027-01-01"),
                    listOf("unbounded", "next"),
                    setOf("rate"),
                )
            }.diagnostic.code,
        )
        assertEquals(
            "MANTRA-PACKAGE-DATE-GAP",
            assertFailsWith<PackageException> {
                ParameterSelector.effectiveDate(
                    captured,
                    fixture.binding,
                    LocalDate.parse("2025-12-31"),
                    listOf("unbounded", "next"),
                    setOf("rate"),
                )
            }.diagnostic.code,
        )
        assertEquals(
            "MANTRA-PACKAGE-DATE-GAP",
            assertFailsWith<PackageException> {
                ParameterSelector.effectiveDate(
                    captured,
                    fixture.binding,
                    LocalDate.parse("2027-01-01"),
                    listOf("next"),
                    setOf("ceiling"),
                )
            }.diagnostic.code,
        )
    }

    @Test
    fun `explicit what if uses ordered actual values outside interval without claiming date validity`() {
        val fixture = PackageFixture().parameter("old", "2", "2026-01-01", "2027-01-01")
            .parameter("scenario", "0", "2030-01-01", "2031-01-01")
        val captured = snapshot(fixture)
        val selected = ParameterSelector.whatIf(
            captured,
            fixture.binding,
            LocalDate.parse("2026-06-01"),
            listOf("old", "scenario"),
            setOf("rate"),
        )
        assertEquals(listOf("old", "scenario"), selected.parameters.map { it.id })
        assertEquals(ParameterSelectionMode.WHAT_IF, selected.provenance.single().mode)
        assertEquals("scenario", selected.provenance.single().setId)
        assertFalse(selected.provenance.single().validForDate)
        val result = Mantra.calculate(captured.schema(fixture.binding), captured.case("zero"), selected.parameters)
        assertTrue(result.succeeded)
        assertEquals(Value.num(0), result.value("answer"))
        assertFailsWith<PackageException> {
            ParameterSelector.whatIf(
                captured,
                fixture.binding,
                LocalDate.parse("2026-06-01"),
                listOf("old", "old"),
                setOf("rate"),
            )
        }
        assertFailsWith<PackageException> {
            ParameterSelector.whatIf(
                captured,
                fixture.binding,
                LocalDate.parse("2026-06-01"),
                listOf("old"),
                setOf("unknown"),
            )
        }
    }

    @Test
    fun `invalid dates and reversed intervals are rejected even for explicit what if`() {
        listOf(
            "2026-02-30" to "2027-01-01",
            "2026-01-01" to "2026-01-01",
            "2027-01-01" to "2026-01-01",
        ).forEachIndexed {
                index,
                (from, until),
            ->
            val fixture = PackageFixture().parameter("invalid", "2", from, until)
            val captured = snapshot(fixture, "invalid$index")
            assertEquals(
                "MANTRA-PACKAGE-DATE",
                assertFailsWith<PackageException> {
                    ParameterSelector.whatIf(
                        captured,
                        fixture.binding,
                        LocalDate.parse("2026-01-01"),
                        listOf("invalid"),
                        setOf("rate"),
                    )
                }.diagnostic.code,
            )
        }
    }
}
