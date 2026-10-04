package com.xqiou.mantra.render.pdf

/** Bounded A4 output; landscape is selected when a working-paper table has more than six columns. */
data class PdfOptions(
    val includeAudit: Boolean = true,
    val landscape: Boolean? = null,
    val maxPages: Int = 500,
    val maxRows: Int = 100_000,
    val maxOutputBytes: Int = 20 * 1024 * 1024,
) {
    init {
        require(maxPages > 0 && maxRows > 0 && maxOutputBytes > 0)
    }
}

class PdfRenderException(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)
