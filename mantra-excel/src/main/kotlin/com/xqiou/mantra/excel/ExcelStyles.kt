package com.xqiou.mantra.excel

import com.xqiou.mantra.render.layout.NegativeStyle
import com.xqiou.mantra.render.layout.NumberStyle
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/** Visual role of a cell; combined into cached POI styles. */
enum class Fill(val rgb: Int?) {
    NONE(null),
    HEADER(0xE7EAEE),
    INPUT(0xFFF2CC),
    PARAM(0xDDEBF7),
    FALLBACK(0xFCE4D6),
    STEP(0xEEF3FA),
}

data class StyleKey(
    val format: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val muted: Boolean = false,
    val size: Short = 10,
    val fill: Fill = Fill.NONE,
    val topRule: Boolean = false,
    val doubleBottom: Boolean = false,
    val headerRule: Boolean = false,
    val align: HorizontalAlignment = HorizontalAlignment.GENERAL,
    val link: Boolean = false,
    val wrap: Boolean = false,
    val indent: Short = 0,
    val tone: StyleTone? = null,
)

/** Apply the output-neutral rule after built-in row styling. */
fun StyleKey.applyRule(rule: StyleSpec): StyleKey = copy(
    bold = rule.weight?.let { it == StyleWeight.BOLD } ?: bold,
    fill = when (rule.fill) {
        StyleFill.NONE -> Fill.NONE
        StyleFill.SUBTLE -> Fill.HEADER
        StyleFill.ACCENT -> Fill.STEP
        null -> fill
    },
    tone = rule.tone ?: tone,
)

/** Style cache (POI limits the number of distinct cell styles per workbook). */
class ExcelStyles(private val workbook: XSSFWorkbook, private val numbers: NumberStyle) {
    private val cache = hashMapOf<StyleKey, XSSFCellStyle>()
    private val fonts = hashMapOf<List<Any>, XSSFFont>()

    fun get(key: StyleKey): XSSFCellStyle = cache.getOrPut(key) {
        workbook.createCellStyle().apply {
            key.format?.let { dataFormat = workbook.createDataFormat().getFormat(it) }
            setFont(font(key))
            key.fill.rgb?.let { rgb ->
                setFillForegroundColor(
                    XSSFColor(
                        byteArrayOf(
                            (rgb shr 16).toByte(),
                            (rgb shr 8 and 0xFF).toByte(),
                            (
                                rgb and
                                    0xFF
                                ).toByte(),
                        ),
                        null,
                    ),
                )
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            if (key.topRule) borderTop = BorderStyle.THIN
            if (key.doubleBottom) borderBottom = BorderStyle.DOUBLE
            if (key.headerRule) borderBottom = BorderStyle.THIN
            alignment = key.align
            verticalAlignment = VerticalAlignment.TOP
            wrapText = key.wrap
            indention = key.indent
        }
    }

    private fun font(key: StyleKey): XSSFFont = fonts.getOrPut(
        listOf(
            key.bold,
            key.italic,
            key.muted,
            key.size,
            key.link,
            key.tone ?: StyleTone.DEFAULT,
        ),
    ) {
        workbook.createFont().apply {
            fontName = "Calibri"
            fontHeightInPoints = key.size
            bold = key.bold
            italic = key.italic
            when {
                key.link -> {
                    setColor(XSSFColor(byteArrayOf(0x1F, 0x5F, 0xBF.toByte()), null))
                    underline = XSSFFont.U_SINGLE
                }
                key.tone == StyleTone.ACCENT -> setColor(XSSFColor(byteArrayOf(0x1F, 0x5F, 0xBF.toByte()), null))
                key.tone == StyleTone.MUTED || (key.tone == null && key.muted) -> setColor(
                    XSSFColor(byteArrayOf(0x6B, 0x72, 0x80.toByte()), null),
                )
            }
        }
    }

    /** Excel number format for an amount; [deduction] shows positive values as a deduction "(x)". */
    fun amountFormat(precision: Int, deduction: Boolean = false): String {
        val body = "#,##0" + if (precision > 0) "." + "0".repeat(precision) else ""
        val zero = numbers.zero?.let { "\"" + it.replace("\"", "") + "\"" } ?: body
        return when {
            deduction -> "($body);$body;$zero"
            numbers.negative == NegativeStyle.PARENTHESES -> "$body;($body);$zero"
            else -> "$body;-$body;$zero"
        }
    }

    fun percentFormat(precision: Int): String = "0" + (if (precision > 0) "." + "0".repeat(precision) else "") + "%"
}
