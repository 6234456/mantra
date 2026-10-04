package com.xqiou.mantra.packages

import com.xqiou.mantra.core.read.SourceText
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

// Tests opt in explicitly; production Path/classpath/store defaults stay STRICT_HANDLES.
internal fun trustedDirectory(root: Path, engine: SemanticVersion, limits: PackageLimits): PackageSnapshot =
    PackageLoader.directory(root, engine, limits, DirectoryPolicy.TRUSTED_LOCAL)

internal fun trustedStore(root: Path, caseBytes: Long): FileMigrationStore =
    FileMigrationStore(root, caseBytes, DirectoryPolicy.TRUSTED_LOCAL)

class PortableDirectoryTest {
    @TempDir lateinit var temporary: Path
    private val engine = SemanticVersion.parse("0.4.0-SNAPSHOT")

    @Test
    fun `default directory and migration store never silently fall back on unsupported providers`() {
        val fixture = PackageFixture()
        val root = fixture.directory(temporary.resolve("package"))
        val supportsHandles = Files.newDirectoryStream(root.toRealPath().root).use { it is SecureDirectoryStream<*> }
        if (supportsHandles) {
            assertEquals(
                fixture.binding.identity,
                PackageLoader.directory(root, engine, fixture.limits).schema(fixture.binding).identity,
            )
            assertEquals(
                fixture.files.getValue("cases/zero.mantra"),
                FileMigrationStore(root, 64_000).read("cases/zero.mantra").text,
            )
        } else {
            assertEquals(
                "MANTRA-PACKAGE-SECURE-READ",
                assertFailsWith<PackageException> {
                    PackageLoader.directory(root, engine, fixture.limits)
                }.diagnostic.code,
            )
            assertEquals(
                "MANTRA-MIGRATION-STORE",
                assertFailsWith<PackageException> {
                    FileMigrationStore(root, 64_000).read("cases/zero.mantra")
                }.diagnostic.code,
            )
        }
        assertEquals(
            fixture.binding.identity,
            trustedDirectory(root, engine, fixture.limits).schema(fixture.binding).identity,
        )
    }

    @Test
    fun `host port buffers are copied before final verification and snapshot remains immutable`() {
        val fixture = PackageFixture()
        val delegate = DirectorySources.trustedLocal(fixture.directory(temporary.resolve("buffers")))
        val retained = mutableListOf<ByteArray>()
        val port = object : DirectoryAccess {
            override fun read(path: String, maximumBytes: Long): ByteArray = delegate.read(path, maximumBytes).also {
                retained +=
                    it
            }
            override fun verifyUnchanged() {
                delegate.verifyUnchanged()
                retained.forEach { it.fill(0) }
            }
        }
        val snapshot = PackageLoader.directory(port, engine, fixture.limits)
        assertEquals(fixture.manifest(), String(snapshot.manifestBytes(), Charsets.UTF_8))
        assertEquals(fixture.binding.identity, snapshot.schema(fixture.binding).identity)
        val exposed = snapshot.bytes("schema.mantra")
        exposed.fill(0)
        assertEquals(fixture.files.getValue("schema.mantra"), String(snapshot.bytes("schema.mantra"), Charsets.UTF_8))
    }

    @Test
    fun `ordinary changes before resource read fail declared SHA while changes after read fail capture verification`() {
        listOf(false, true).forEach { after ->
            val fixture = PackageFixture()
            val root = fixture.directory(temporary.resolve(if (after) "after" else "before"))
            val delegate = DirectorySources.trustedLocal(root)
            val port = object : DirectoryAccess {
                override fun read(path: String, maximumBytes: Long): ByteArray {
                    val bytes = delegate.read(path, maximumBytes)
                    if (!after &&
                        path == "manifest.json"
                    ) {
                        Files.writeString(root.resolve("schema.mantra"), "changed before capture")
                    }
                    return bytes
                }
                override fun verifyUnchanged() {
                    if (after) Files.writeString(root.resolve("schema.mantra"), "changed after capture")
                    delegate.verifyUnchanged()
                }
            }
            val error = assertFailsWith<PackageException> { PackageLoader.directory(port, engine, fixture.limits) }
            assertEquals(
                if (after) "MANTRA-PACKAGE-SOURCE-CHANGED" else "MANTRA-PACKAGE-INTEGRITY",
                error.diagnostic.code,
            )
        }
    }

    @Test
    fun `equal byte replacement during capture is rejected by identity checks`() {
        val fixture = PackageFixture()
        val root = fixture.directory(temporary.resolve("replacement"))
        val delegate = DirectorySources.trustedLocal(root)
        val port = object : DirectoryAccess {
            override fun read(path: String, maximumBytes: Long): ByteArray = delegate.read(path, maximumBytes)
            override fun verifyUnchanged() {
                val replacement = root.resolve("replacement.mantra")
                Files.writeString(replacement, fixture.files.getValue("schema.mantra"))
                Files.move(replacement, root.resolve("schema.mantra"), StandardCopyOption.REPLACE_EXISTING)
                delegate.verifyUnchanged()
            }
        }
        assertEquals(
            "MANTRA-PACKAGE-SOURCE-CHANGED",
            assertFailsWith<PackageException> {
                PackageLoader.directory(port, engine, fixture.limits)
            }.diagnostic.code,
        )
    }

    @Test
    fun `file classpath needs explicit trusted policy and retains the same captured manifest`() {
        val fixture = PackageFixture()
        val root = fixture.directory(temporary.resolve("classpath/bundle"))
        URLClassLoader(arrayOf(root.parent.toUri().toURL()), null).use { loader ->
            val snapshot = PackageLoader.classpath(
                "bundle",
                loader,
                engine,
                fixture.limits,
                DirectoryPolicy.TRUSTED_LOCAL,
            )
            assertEquals(fixture.binding.identity, snapshot.schema(fixture.binding).identity)
        }
    }

    @Test
    fun `trusted atomic store retains byte CAS host authorization and cleanup and rejects sidecar symlinks`() {
        val fixture = PackageFixture()
        val root = fixture.directory(temporary.resolve("store"))
        val path = "cases/zero.mantra"
        val original = fixture.files.getValue(path)
        val store = trustedStore(root, 64_000)
        val candidate = SourceText(path, original + "\n;; reviewed migration", path)
        var authorized = false
        assertEquals(
            "MANTRA-MIGRATION-STALE",
            assertFailsWith<PackageException> {
                store.commit(path, "0".repeat(64), candidate) {
                    authorized = true
                    it()
                }
            }.diagnostic.code,
        )
        assertFalse(authorized)
        assertEquals(
            "MANTRA-MIGRATION-STALE",
            assertFailsWith<PackageException> {
                store.commit(path, digest(original.toByteArray()), candidate) { write ->
                    Files.writeString(root.resolve(path), original + "\n;; concurrent edit")
                    write()
                }
            }.diagnostic.code,
        )
        val edited = Files.readString(root.resolve(path))
        store.commit(path, digest(edited.toByteArray()), candidate) { it() }
        assertEquals(candidate.text, store.read(path).text)
        Files.list(root.resolve("cases")).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
        val lock = root.resolve("cases/.zero.mantra.mantra-migration.lock")
        Files.delete(lock)
        val outside = temporary.resolve("outside.lock")
        Files.writeString(outside, "external sentinel")
        Files.createSymbolicLink(lock, outside)
        assertFailsWith<PackageException> {
            store.commit(path, digest(candidate.text.toByteArray()), candidate) { it() }
        }
        assertEquals("external sentinel", Files.readString(outside))
    }
}
