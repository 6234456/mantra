package com.xqiou.mantra.packages

import com.xqiou.mantra.core.read.SourceText
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Explicit host capability. A source package/JAR is never inferred to be a writable case store. */
class FileMigrationStore(
    writableRoot: Path,
    private val caseBytes: Long,
    policy: DirectoryPolicy = DirectoryPolicy.STRICT_HANDLES,
) : MigrationStore {
    private val root = try {
        writableRoot.toRealPath()
    } catch (error: java.io.IOException) {
        fail("MANTRA-MIGRATION-STORE", "Explicit writable host root is missing or unreadable", error)
    }

    init {
        require(caseBytes in 1..Int.MAX_VALUE.toLong())
    }

    private val trusted = if (policy ==
        DirectoryPolicy.TRUSTED_LOCAL
    ) {
        TrustedLocalMigrationStore(root, caseBytes)
    } else {
        null
    }

    override fun read(casePath: String): SourceText = trusted?.read(casePath)
        ?: withParent(casePath) { directory, file ->
            SourceText(casePath, utf8(readBytes(directory, file)), casePath)
        }

    override fun commit(
        casePath: String,
        sourceSha256: String,
        candidate: SourceText,
        authorize: (write: () -> Unit) -> Unit,
    ) {
        trusted?.let {
            it.commit(casePath, sourceSha256, candidate, authorize)
            return
        }
        val candidateBytes = candidate.text.toByteArray(Charsets.UTF_8)
        if (candidateBytes.size.toLong() >
            caseBytes
        ) {
            fail("MANTRA-MIGRATION-LIMIT", "Migrated source exceeds its byte limit")
        }
        val localLock = lockFor(root.resolve(logicalPath(casePath)))
        localLock.withLock {
            withParent(casePath) { directory, file ->
                val lockPath = Path.of(".${file.fileName}.mantra-migration.lock")
                directory.newByteChannel(
                    lockPath,
                    setOf(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                ).use { channel ->
                    val lockChannel =
                        channel as? FileChannel
                            ?: fail("MANTRA-MIGRATION-COMMIT", "Host filesystem does not expose file locking")
                    lockChannel.lock().use {
                        fun checkOriginal() {
                            if (digest(readBytes(directory, file)) !=
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
                            val temporary = Path.of(".${file.fileName}.migration-${UUID.randomUUID()}.tmp")
                            var created = false
                            try {
                                directory.newByteChannel(
                                    temporary,
                                    setOf(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS,
                                    ),
                                ).use { output ->
                                    created = true
                                    val buffer = ByteBuffer.wrap(candidateBytes)
                                    while (buffer.hasRemaining()) output.write(buffer)
                                    (output as? FileChannel)?.force(true)
                                }
                                // Directory-relative move is atomic; no unsafe non-atomic fallback is attempted.
                                directory.move(temporary, directory, file)
                                created = false
                                invoked = true
                            } catch (error: java.io.IOException) {
                                fail("MANTRA-MIGRATION-COMMIT", "Atomic migration replacement failed", error)
                            } finally {
                                if (created) directory.deleteFile(temporary)
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
            }
        }
    }

    private fun readBytes(directory: SecureDirectoryStream<Path>, file: Path): ByteArray = try {
        directory.newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() > caseBytes) fail("MANTRA-MIGRATION-LIMIT", "Case exceeds its byte limit")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count >
                    caseBytes
                ) {
                    fail("MANTRA-MIGRATION-LIMIT", "Case exceeds its byte limit")
                }
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
            output.toByteArray()
        }
    } catch (error: java.io.IOException) {
        fail("MANTRA-MIGRATION-STORE", "Cannot read confined case (symlinks are rejected)", error)
    }

    private fun <T> withParent(casePath: String, action: (SecureDirectoryStream<Path>, Path) -> T): T {
        val segments = logicalPath(casePath).split('/')
        return secureRoot(root, "MANTRA-MIGRATION-STORE") { secure ->
            fun descend(directory: SecureDirectoryStream<Path>, index: Int): T = if (index == segments.lastIndex) {
                action(directory, Path.of(segments[index]))
            } else {
                directory.newDirectoryStream(Path.of(segments[index]), LinkOption.NOFOLLOW_LINKS).use {
                    descend(
                        it,
                        index + 1,
                    )
                }
            }
            try {
                descend(secure, 0)
            } catch (error: java.io.IOException) {
                fail("MANTRA-MIGRATION-STORE", "Cannot access confined writable case path", error)
            }
        }
    }

    companion object {
        private val locks = Array(256) { ReentrantLock() }
        internal fun lockFor(path: Path): ReentrantLock = locks[Math.floorMod(path.hashCode(), locks.size)]
    }
}
