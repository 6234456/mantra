package com.xqiou.mantra.packages

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackageLoaderTest {
    @TempDir lateinit var temporary: Path
    private val engine = SemanticVersion.parse("0.4.0-SNAPSHOT")

    @Test
    fun `directory and fixed JAR captures run the same zero false case and retain byte identity`() {
        val fixture = PackageFixture().parameter("year", "2", "2026-01-01", "2027-01-01")
        val directory = trustedDirectory(fixture.directory(temporary.resolve("source")), engine, fixture.limits)
        val jar = temporary.resolve("bundle.jar")
        fixture.jar(jar)
        URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
            val snapshot = PackageLoader.classpath("bundle", loader, engine, fixture.limits)
            assertEquals(directory.revision, snapshot.revision)
            listOf(directory, snapshot).forEach { captured ->
                val result = Mantra.calculate(
                    captured.schema(fixture.binding),
                    captured.case("zero"),
                    listOf(captured.parameters("year")),
                )
                assertTrue(result.succeeded)
                assertEquals(Value.num(0), result.value("answer"))
                assertEquals(Value.Bool(false), result.value("enabled"))
            }
        }
        Files.writeString(temporary.resolve("source/schema.mantra"), "corrupted after capture")
        val bytes = directory.bytes("schema.mantra")
        bytes.fill(0)
        assertEquals(fixture.binding.identity, directory.schema(fixture.binding).identity)
    }

    @Test
    fun `duplicate JSON fields trailing tokens unknown fields and duplicate paths are rejected`() {
        val fixture = PackageFixture()
        val valid = fixture.manifest()
        listOf(
            valid.replace("\"format\":", "\"id\":\"duplicate\",\"format\":"),
            "$valid {}",
            valid.dropLast(1) + ",\"unknown\":true}",
            valid.replace("\"cases/zero.mantra\",\"role\":\"case\"", "\"schema.mantra\",\"role\":\"case\""),
        ).forEachIndexed {
                index,
                manifest,
            ->
            val root = fixture.directory(temporary.resolve("invalid$index"), manifest)
            assertFailsWith<PackageException> { trustedDirectory(root, engine, fixture.limits) }
        }
    }

    @Test
    fun `integrity budgets UTF8 and engine compatibility fail before document execution`() {
        val fixture = PackageFixture()
        val root = fixture.directory(temporary.resolve("source"))
        Files.writeString(root.resolve("cases/zero.mantra"), "modified")
        assertEquals(
            "MANTRA-PACKAGE-INTEGRITY",
            assertFailsWith<PackageException> {
                trustedDirectory(root, engine, fixture.limits)
            }.diagnostic.code,
        )
        fixture.directory(root)
        assertFailsWith<PackageException> { trustedDirectory(root, engine, fixture.limits.copy(totalBytes = 20)) }
        assertFailsWith<PackageException> { trustedDirectory(root, engine, fixture.limits.copy(resources = 1)) }
        assertFailsWith<PackageException> { trustedDirectory(root, engine, fixture.limits.copy(jsonDepth = 1)) }
        assertFailsWith<PackageException> { trustedDirectory(root, SemanticVersion.parse("0.3.0"), fixture.limits) }
        Files.write(root.resolve("manifest.json"), byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(
            "MANTRA-PACKAGE-UTF8",
            assertFailsWith<PackageException> {
                trustedDirectory(root, engine, fixture.limits)
            }.diagnostic.code,
        )
    }

    @Test
    fun `escaping paths and directory symlinks never grant external reads`() {
        val fixture = PackageFixture()
        listOf(
            "../outside.mantra",
            "/outside.mantra",
            "dir/../schema.mantra",
            "C:\\outside.mantra",
            "a//b",
            "./schema.mantra",
        ).forEachIndexed {
                index,
                path,
            ->
            val manifest = fixture.manifest().replace(
                "\"path\":\"schema.mantra\"",
                "\"path\":\"${path.replace("\\", "\\\\")}\"",
            )
            assertFailsWith<PackageException> {
                trustedDirectory(fixture.directory(temporary.resolve("path$index"), manifest), engine, fixture.limits)
            }
        }
        val root = fixture.directory(temporary.resolve("symlink"))
        val external = temporary.resolve("external.mantra")
        Files.writeString(external, fixture.files.getValue("schema.mantra"))
        Files.delete(root.resolve("schema.mantra"))
        Files.createSymbolicLink(root.resolve("schema.mantra"), external)
        assertFailsWith<PackageException> { trustedDirectory(root, engine, fixture.limits) }
        Files.delete(root.resolve("schema.mantra"))
        Files.writeString(root.resolve("schema.mantra"), fixture.files.getValue("schema.mantra"))
        Files.move(root.resolve("cases"), temporary.resolve("external-cases"))
        Files.createSymbolicLink(root.resolve("cases"), temporary.resolve("external-cases"))
        assertFailsWith<PackageException> { trustedDirectory(root, engine, fixture.limits) }
    }

    @Test
    fun `manifest listed includes use captured bytes and unlisted includes imports and wrong roles reject`() {
        val fixture = PackageFixture()
        fixture.files["schema.mantra"] =
            fixture.files.getValue("schema.mantra").replace("(param rate 1)", "(include \"fragments/rate.mantra\")")
        fixture.files["fragments/rate.mantra"] = "(fragment (param rate 1))"
        val root = fixture.directory(temporary.resolve("included"))
        val snapshot = trustedDirectory(root, engine, fixture.limits)
        Files.delete(root.resolve("fragments/rate.mantra"))
        assertEquals(fixture.binding.identity, snapshot.schema(fixture.binding).identity)
        assertFailsWith<PackageException> { snapshot.bytes("unlisted.csv", PackageResourceRole.DATA) }
        assertFailsWith<PackageException> { snapshot.bytes("cases/zero.mantra", PackageResourceRole.DATA) }
        fixture.files.remove("fragments/rate.mantra")
        val omitted = trustedDirectory(fixture.directory(temporary.resolve("omitted")), engine, fixture.limits)
        assertEquals(
            "MANTRA-PACKAGE-UNLISTED",
            assertFailsWith<PackageException> {
                omitted.schema(fixture.binding)
            }.diagnostic.code,
        )
    }

    @Test
    fun `classpath shadow resources do not replace the pinned container and duplicate manifests reject`() {
        val fixture = PackageFixture()
        val jar = temporary.resolve("correct.jar")
        fixture.jar(jar)
        val shadow = temporary.resolve("shadow")
        Files.createDirectories(shadow.resolve("bundle"))
        Files.writeString(shadow.resolve("bundle/schema.mantra"), "shadow resource")
        URLClassLoader(arrayOf(shadow.toUri().toURL(), jar.toUri().toURL()), null).use { loader ->
            assertEquals(
                fixture.binding.identity,
                PackageLoader.classpath("bundle", loader, engine, fixture.limits).schema(fixture.binding).identity,
            )
        }
        val other = temporary.resolve("other.jar")
        fixture.jar(other)
        URLClassLoader(arrayOf(jar.toUri().toURL(), other.toUri().toURL()), null).use { loader ->
            assertEquals(
                "MANTRA-PACKAGE-CONTAINER",
                assertFailsWith<PackageException> {
                    PackageLoader.classpath("bundle", loader, engine, fixture.limits)
                }.diagnostic.code,
            )
        }
    }

    @Test
    fun `historic omitted case version is explicitly manifest bound while new versions must pin exactly`() {
        val legacy = PackageFixture("2025.2", SchemaVersionMode.LEGACY_EXACT)
        legacy.files["cases/zero.mantra"] =
            legacy.files.getValue("cases/zero.mantra").replace(":schema-version \"2025.2\"", "")
        val captured = trustedDirectory(legacy.directory(temporary.resolve("legacy")), engine, legacy.limits)
        assertEquals("2025.2", captured.schema(legacy.binding).version)
        assertEquals(null, captured.case("zero").schemaVersion)
        val modern = PackageFixture()
        modern.files["cases/zero.mantra"] =
            modern.files.getValue("cases/zero.mantra").replace(":schema-version \"1.0.0\"", "")
        val mismatch = trustedDirectory(modern.directory(temporary.resolve("modern")), engine, modern.limits)
        assertFailsWith<PackageException> { mismatch.case("zero") }
    }

    @Test
    fun `duplicate JAR entries scan limits and remote containers are rejected without extraction or network access`() {
        val fixture = PackageFixture()
        fixture.files["schema.mantrx"] = fixture.files.getValue("schema.mantra")
        val jar = temporary.resolve("duplicate.jar")
        fixture.jar(jar)
        // Equal-length ZIP entry name mutation creates a real duplicate without reflection or extraction.
        val bytes = Files.readAllBytes(jar)
        val before = "bundle/schema.mantrx".toByteArray()
        val after = "bundle/schema.mantra".toByteArray()
        var replacements = 0
        for (offset in 0..bytes.size - before.size) {
            if (before.indices.all { bytes[offset + it] == before[it] }) {
                after.copyInto(bytes, offset)
                replacements++
            }
        }
        assertEquals(2, replacements) // Local header and central directory, not resource content.
        Files.write(jar, bytes)
        URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
            val error =
                assertFailsWith<PackageException> { PackageLoader.classpath("bundle", loader, engine, fixture.limits) }
            assertEquals("MANTRA-PACKAGE-DUPLICATE", error.diagnostic.code)
            assertTrue(error.message.orEmpty().contains("Duplicate JAR entry"))
        }
        fixture.jar(jar)
        URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
            assertEquals(
                "MANTRA-PACKAGE-LIMIT",
                assertFailsWith<PackageException> {
                    PackageLoader.classpath("bundle", loader, engine, fixture.limits.copy(containerEntries = 1))
                }.diagnostic.code,
            )
        }
        val remote = object : ClassLoader(null) {
            override fun getResources(name: String) =
                Collections.enumeration(listOf(URL("https://example.invalid/$name")))
        }
        assertEquals(
            "MANTRA-PACKAGE-CONTAINER",
            assertFailsWith<PackageException> {
                PackageLoader.classpath("bundle", remote, engine, fixture.limits)
            }.diagnostic.code,
        )
    }
}
