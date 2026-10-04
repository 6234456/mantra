package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Per-import bounds; never changes Apache POI's process-wide ZIP policy. */
internal object XlsxContainerPreflight {
    const val MAX_COMPRESSED_BYTES = 10 * 1024 * 1024
    private const val MAX_ENTRIES = 1_000
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024

    fun capture(input: InputStream, checkpoint: () -> Unit): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkpoint()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (result.size().toLong() + count > MAX_COMPRESSED_BYTES) limit("Compressed workbook exceeds 10 MiB")
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    fun verify(bytes: ByteArray, checkpoint: () -> Unit) {
        checkpoint()
        if (bytes.isEmpty()) invalid("Empty workbook container")
        if (bytes.size > MAX_COMPRESSED_BYTES) limit("Compressed workbook exceeds 10 MiB")
        var entries = 0
        var total = 0L
        val contents = linkedMapOf<String, EntryContent>()
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    checkpoint()
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) limit("Workbook container exceeds 1000 entries")
                    if (entry.name in contents) invalid("Duplicate workbook ZIP entry: ${entry.name}")
                    if (entry.size > MAX_ENTRY_BYTES) limit("Expanded workbook ZIP entry exceeds 16 MiB")
                    var expanded = 0L
                    val sha = MessageDigest.getInstance("SHA-256")
                    while (true) {
                        checkpoint()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        expanded += count
                        total += count
                        if (expanded > MAX_ENTRY_BYTES) limit("Expanded workbook ZIP entry exceeds 16 MiB")
                        if (total > MAX_EXPANDED_BYTES) limit("Expanded workbook container exceeds 64 MiB")
                        sha.update(buffer, 0, count)
                    }
                    contents[entry.name] = EntryContent(expanded, sha.digest().toList())
                    zip.closeEntry()
                }
            }
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            invalid("Workbook ZIP container could not be read: ${error.message}")
        }
        if (entries == 0 || "[Content_Types].xml" !in contents || "xl/workbook.xml" !in contents) {
            invalid("Workbook ZIP container lacks required OOXML entries")
        }
        // The pinned POI InputStream constructor reads local headers. Also verify the central
        // directory's actual streams, and require both views to select the same bounded bytes.
        if (contents != centralContents(bytes, checkpoint)) invalid("Workbook ZIP local and central entries disagree")
        checkpoint()
    }

    private data class EntryContent(val expanded: Long, val sha256: List<Byte>)

    private fun centralContents(bytes: ByteArray, checkpoint: () -> Unit): Map<String, EntryContent> {
        val contents = linkedMapOf<String, EntryContent>()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        try {
            SeekableInMemoryByteChannel(bytes).use { channel ->
                ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                    val entries = zip.entries
                    while (entries.hasMoreElements()) {
                        checkpoint()
                        val entry = entries.nextElement()
                        if (contents.size == MAX_ENTRIES) limit("Workbook container exceeds 1000 entries")
                        if (entry.name in contents) invalid("Duplicate workbook ZIP central entry: ${entry.name}")
                        if (!zip.canReadEntryData(entry)) invalid("Unsupported workbook ZIP entry: ${entry.name}")
                        if (entry.size > MAX_ENTRY_BYTES) limit("Expanded workbook ZIP entry exceeds 16 MiB")
                        var expanded = 0L
                        val sha = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                checkpoint()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                expanded += count
                                total += count
                                if (expanded > MAX_ENTRY_BYTES) limit("Expanded workbook ZIP entry exceeds 16 MiB")
                                if (total > MAX_EXPANDED_BYTES) limit("Expanded workbook container exceeds 64 MiB")
                                sha.update(buffer, 0, count)
                            }
                        }
                        contents[entry.name] = EntryContent(expanded, sha.digest().toList())
                    }
                }
            }
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: IOException) {
            invalid("Workbook ZIP central directory could not be read: ${error.message}")
        }
        return contents
    }

    private fun limit(message: String): Nothing = throw MantraException(
        listOf(Diagnostic(Severity.ERROR, "MANTRA-DATA-XLSX-LIMIT", message)),
    )

    private fun invalid(message: String): Nothing = throw MantraException(
        listOf(Diagnostic(Severity.ERROR, "MANTRA-DATA-XLSX-CONTAINER", message)),
    )
}
