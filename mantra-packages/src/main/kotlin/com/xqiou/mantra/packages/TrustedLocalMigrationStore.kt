package com.xqiou.mantra.packages

import com.xqiou.mantra.core.read.SourceText
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.concurrent.withLock

/**
 * Explicit cooperative host policy. Host epoch authorization, source-byte CAS, a bounded JVM lock
 * and an OS sidecar lock are retained. Atomic move is required. Java NOFOLLOW/path identity checks
 * are not a substitute for native directory handles against same-permission malicious rename.
 * All cooperating writers must use the host epoch lock and the same sidecar lock protocol.
 */
internal class TrustedLocalMigrationStore(root: Path, private val caseBytes: Long) : MigrationStore {
    private val directory = LocalDirectoryRoot(root, "MANTRA-MIGRATION-STORE")

    override fun read(casePath: String): SourceText = SourceText(casePath, utf8(capture(casePath)), casePath)

    override fun commit(
        casePath: String,
        sourceSha256: String,
        candidate: SourceText,
        authorize: (write: () -> Unit) -> Unit,
    ) {
        val candidateBytes = candidate.text.toByteArray(Charsets.UTF_8)
        if (candidateBytes.size.toLong() >
            caseBytes
        ) {
            fail("MANTRA-MIGRATION-LIMIT", "Migrated source exceeds its byte limit")
        }
        val (parent, file) = directory.parent(casePath)
        FileMigrationStore.lockFor(file).withLock {
            val parentBefore = directory.inspectDirectory(parent)
            val lockPath = parent.resolve(".${file.fileName}.mantra-migration.lock")
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) directory.inspectFile(lockPath)
            try {
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { lock ->
                    directory.inspectFile(lockPath)
                    lock.lock().use {
                        fun checkOriginal() {
                            directory.parent(casePath)
                            if (!parentBefore.sameIdentity(
                                    directory.inspectDirectory(parent),
                                )
                            ) {
                                fail("MANTRA-MIGRATION-STALE", "Writable parent identity changed")
                            }
                            if (digest(capture(casePath)) !=
                                sourceSha256
                            ) {
                                fail("MANTRA-MIGRATION-STALE", "Case bytes changed after preview")
                            }
                        }
                        checkOriginal()
                        var invoked = false
                        authorize {
                            if (invoked) fail("MANTRA-MIGRATION-COMMIT", "Host invoked migration commit more than once")
                            checkOriginal()
                            val temporary = parent.resolve(".${file.fileName}.migration-${UUID.randomUUID()}.tmp")
                            var created = false
                            try {
                                FileChannel.open(
                                    temporary,
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS,
                                ).use { output ->
                                    created = true
                                    val buffer = ByteBuffer.wrap(candidateBytes)
                                    while (buffer.hasRemaining()) output.write(buffer)
                                    output.force(true)
                                }
                                checkOriginal()
                                // No non-atomic replacement fallback. Both names are in the same checked parent.
                                Files.move(
                                    temporary,
                                    file,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING,
                                )
                                created = false
                                invoked = true
                            } finally {
                                if (created) Files.deleteIfExists(temporary)
                            }
                        }
                        if (!invoked) {
                            fail(
                                "MANTRA-MIGRATION-COMMIT",
                                "Host did not authorize the explicit migration write",
                            )
                        }
                    }
                }
            } catch (error: java.io.IOException) {
                fail("MANTRA-MIGRATION-COMMIT", "Atomic trusted-local migration replacement failed", error)
            }
        }
    }

    private fun capture(casePath: String): ByteArray {
        // A fresh read transaction avoids treating our own lock/temp creation as source mutations.
        val access = DirectorySources.trustedLocal(directory.path)
        return try {
            val bytes = access.read(casePath, caseBytes)
            access.verifyUnchanged()
            bytes
        } catch (error: PackageException) {
            val code = if (error.diagnostic.code ==
                "MANTRA-PACKAGE-LIMIT"
            ) {
                "MANTRA-MIGRATION-LIMIT"
            } else {
                "MANTRA-MIGRATION-STORE"
            }
            fail(code, "Cannot capture confined writable case", error)
        }
    }
}
