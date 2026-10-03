package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.data.CsvSource
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream

/** A bounded, read-only look at a browser-selected file before it is bound to a case. */
object ImportFiles {
    const val MAX_BYTES = 10 * 1024 * 1024

    fun inspect(name: String, format: String, bytes: ByteArray): Map<String, Any?> {
        if (name.isBlank() || name.length > 255 || '/' in name || '\\' in name || '\u0000' in name) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid import filename")
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Import file must be between 1 byte and 10 MiB")
        }
        val columns: List<Map<String, Any?>>
        val rowCount: Int
        var delimiter: String? = null
        var decimal: String? = null
        var grouping: String? = null
        var numericAmbiguous = false
        try {
            when (format) {
                "csv" -> {
                    val content = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                    val first = content.lineSequence().firstOrNull().orEmpty()
                    val separator = listOf(';', ',', '\t').maxBy { char -> first.count { it == char } }
                    delimiter = separator.toString()
                    val rows = CsvSource.parseRows(content, separator)
                    if (rows.isEmpty() || rows.first().all(String::isBlank)) {
                        throw WorkspaceException(WorkspaceProblem.INVALID, "CSV header is required")
                    }
                    rowCount = rows.drop(1).count { row -> row.any(String::isNotBlank) }
                    val numberSamples = rows.drop(1).take(50).flatMap { it }.map(String::trim)
                    var dot = 0
                    var comma = 0
                    var uncertain = 0
                    numberSamples.forEach { sample ->
                        val match = Regex("""^-?(\d+)([.,])(\d+)$""").matchEntire(sample)
                        when {
                            sample.contains('.') && sample.contains(',') ->
                                if (sample.lastIndexOf('.') > sample.lastIndexOf(',')) dot++ else comma++
                            match != null -> {
                                val whole = match.groupValues[1]
                                val mark = match.groupValues[2]
                                val fraction = match.groupValues[3]
                                // 1.234 / 1,234 can be either a decimal or a grouped integer.
                                if (fraction.length == 3 && whole != "0" && whole.length <= 3 &&
                                    mark != separator.toString()
                                ) {
                                    uncertain++
                                } else if (mark == ".") {
                                    dot++
                                } else {
                                    comma++
                                }
                            }
                        }
                    }
                    numericAmbiguous = uncertain > 0 && dot == 0 && comma == 0
                    if (!numericAmbiguous) {
                        decimal = if (dot > comma) "." else ","
                        grouping = if (decimal == ".") "," else "."
                    }
                    columns = rows.first().mapIndexed { index, header ->
                        mapOf(
                            "name" to header.trim(),
                            "sample" to rows.drop(1).take(3).map { it.getOrElse(index) { "" } },
                        )
                    }
                }
                "json" -> {
                    val value = Json.parse(bytes.toString(Charsets.UTF_8))
                    val entries = (value as? Value.MapV)?.entries
                        ?: throw WorkspaceException(WorkspaceProblem.INVALID, "JSON import must contain an object")
                    columns =
                        entries.keys.map { key ->
                            mapOf(
                                "name" to (key as? Value.Kw)?.name.orEmpty(),
                                "sample" to emptyList<String>(),
                            )
                        }
                    rowCount = 1
                }
                "xlsx" -> {
                    ByteArrayInputStream(bytes).use { input ->
                        XSSFWorkbook(input).use { workbook ->
                            columns =
                                workbook.allNames.map { name ->
                                    mapOf(
                                        "name" to name.nameName,
                                        "sample" to emptyList<String>(),
                                    )
                                }
                            rowCount = workbook.numberOfSheets
                        }
                    }
                }
                else -> throw WorkspaceException(WorkspaceProblem.REQUEST, "Unsupported import format")
            }
        } catch (error: WorkspaceException) {
            throw error
        } catch (_: Exception) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Import file could not be read")
        }
        return linkedMapOf(
            "name" to name,
            "format" to format,
            "columns" to columns,
            "rowCount" to rowCount,
        ).apply {
            if (delimiter != null) put("delimiter", delimiter)
            if (decimal != null) put("decimal", decimal)
            if (grouping != null) put("grouping", grouping)
            if (numericAmbiguous) put("numericAmbiguous", true)
        }
    }
}
