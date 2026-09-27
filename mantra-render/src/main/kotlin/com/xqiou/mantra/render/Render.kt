package com.xqiou.mantra.render

import com.xqiou.mantra.core.engine.CalculationResult
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.html.HtmlRenderer
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.render.paper.WorkingPaper
import com.xqiou.mantra.render.paper.WorkingPaperBuilder
import com.xqiou.mantra.render.text.TextRenderer
import java.nio.file.Files
import java.nio.file.Path

/** Public entry point of the presentation layer. */
object Render {
    /** Resolves the layout: explicit layout document, schema `:preset` metadata, or the German Staffel preset. */
    fun defaultLayout(result: CalculationResult): LayoutSpec {
        val preset = (result.schema.meta.attributes["preset"] as? com.xqiou.mantra.core.model.Value.Kw)?.name
        return preset?.let(Presets::of) ?: Presets.DE_STAFFEL_4
    }

    fun loadLayout(path: Path): LayoutSpec {
        val absolute = path.toAbsolutePath().normalize()
        return LayoutReader.read(SourceText(absolute.fileName.toString(), Files.readString(absolute), absolute.parent?.toString()))
    }

    fun paper(result: CalculationResult, layout: LayoutSpec = defaultLayout(result)): WorkingPaper =
        WorkingPaperBuilder(result, layout).build()

    /** Paper with every row of every table, independent of current values (basis for spreadsheet export). */
    fun completePaper(result: CalculationResult, layout: LayoutSpec = defaultLayout(result)): WorkingPaper =
        WorkingPaperBuilder(result, layout.copy(hideZero = false, showInactive = true, expandMembers = false), includeAll = true).build()

    fun html(result: CalculationResult, layout: LayoutSpec = defaultLayout(result)): String = HtmlRenderer.render(paper(result, layout))

    fun text(result: CalculationResult, layout: LayoutSpec = defaultLayout(result), includeAudit: Boolean = false): String =
        TextRenderer.render(paper(result, layout), includeAudit)
}
