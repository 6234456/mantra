package com.xqiou.mantra.packages

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackageCatalogTest {
    @TempDir lateinit var temporary: Path
    private val engine = SemanticVersion.parse("0.4.0-SNAPSHOT")

    @Test
    fun `cross package references need both an explicit host mount and exact declared dependency`() {
        val sourceFixture = PackageFixture()
        val source =
            trustedDirectory(
                sourceFixture.directory(
                    temporary.resolve("source"),
                    sourceFixture.manifest().replace("fictional.demo", "fictional.source"),
                ),
                engine,
                sourceFixture.limits,
            )
        val rootFixture = PackageFixture()
        rootFixture.dependencies = """[{"id":"fictional.source","version":"1.0.0"}]"""
        val root =
            trustedDirectory(
                rootFixture.directory(
                    temporary.resolve("root"),
                    rootFixture.manifest().replace("fictional.demo", "fictional.root"),
                ),
                engine,
                rootFixture.limits,
            )
        val catalog = PackageCatalog()
        catalog.register("apps/root", root)
        assertFailsWith<PackageException> {
            catalog.resolveCase("../../source/cases/zero.mantra", "apps/root/cases/zero.mantra")
        }
        catalog.register("apps/source", source)
        val reference = catalog.resolveCase("../../source/cases/zero.mantra", "apps/root/cases/zero.mantra")
        assertEquals("apps/source/cases/zero.mantra", reference.canonicalPath)
        assertEquals(source.revision, reference.snapshot.revision)
        assertFailsWith<PackageException> { catalog.register("apps", source) }
        assertFailsWith<PackageException> {
            catalog.resolveCase("../../../outside.mantra", "apps/root/cases/zero.mantra")
        }
        val unauthorized = PackageCatalog()
        val noDependency = PackageFixture()
        unauthorized.register(
            "apps/root",
            trustedDirectory(noDependency.directory(temporary.resolve("unauthorized")), engine, noDependency.limits),
        )
        unauthorized.register("apps/source", source)
        assertEquals(
            "MANTRA-PACKAGE-DEPENDENCY",
            assertFailsWith<PackageException> {
                unauthorized.resolveCase("../../source/cases/zero.mantra", "apps/root/cases/zero.mantra")
            }.diagnostic.code,
        )
    }

    @Test
    fun `imports require manifest DATA role and receive defensive captured bytes`() {
        val fixture = PackageFixture()
        fixture.files["data/facts.csv"] = "key;value\nbase-value;0\n"
        fixture.files["cases/zero.mantra"] = fixture.files.getValue("cases/zero.mantra").dropLast(1) +
            """ (sources (csv {:path "../data/facts.csv" :delimiter ";" :decimal "." :grouping ""})))"""
        val snapshot = trustedDirectory(fixture.directory(temporary.resolve("imports")), engine, fixture.limits)
        val data = snapshot.dataSources("zero").single()
        assertEquals("data/facts.csv", data.resource.path)
        val bytes = data.bytes()
        bytes.fill(0)
        assertEquals("key;value\nbase-value;0\n", data.text())
        fixture.files.remove("data/facts.csv")
        val omitted = trustedDirectory(fixture.directory(temporary.resolve("omitted")), engine, fixture.limits)
        assertEquals(
            "MANTRA-PACKAGE-UNLISTED",
            assertFailsWith<PackageException> {
                omitted.case("zero")
            }.diagnostic.code,
        )
    }
}
