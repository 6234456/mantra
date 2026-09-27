package com.xqiou.mantra.excel

/**
 * Naming convention for workbook names. Export and import ([XlsxSource]) share it:
 * `<id>` for scalar nodes, `<id>__<member>` for member cells, `<table>__<key>__<column>` for table
 * input cells, `guard__…` and `active__…` for conditions. Separators are double underscores because
 * dotted names such as `w.a` are read as column ranges by spreadsheet formula parsers.
 */
object ExcelNames {
    const val SEPARATOR: String = "__"

    fun sanitize(raw: String): String {
        var name = raw.map { ch ->
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> ch
                ch == '_' -> ch
                ch == '?' -> 'Q'
                else -> '_'
            }
        }.joinToString("")
        if (name.isEmpty() || !(name[0].isLetter() || name[0] == '_')) name = "_$name"
        val looksLikeCell = Regex("[A-Za-z]{1,3}[0-9]{1,7}").matches(name) || Regex("[RrCc]|[Rr][0-9]*[Cc][0-9]*").matches(name)
        if (looksLikeCell || name.equals("true", true) || name.equals("false", true)) name = "${name}_"
        return name
    }

    fun member(id: String, key: String): String = sanitize(id) + SEPARATOR + sanitize(key)

    fun record(table: String, key: String, column: String): String = sanitize(table) + SEPARATOR + sanitize(key) + SEPARATOR + sanitize(column)
}
