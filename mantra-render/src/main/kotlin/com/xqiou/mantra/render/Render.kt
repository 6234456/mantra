package com.xqiou.mantra.render

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationView
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
    fun defaultLayout(result: CalculationResult): LayoutSpec = defaultLayout(CalculationView.of(result))

    fun defaultLayout(view: CalculationView): LayoutSpec {
        val preset = (view.schema.attributes["preset"] as? com.xqiou.mantra.core.model.Value.Kw)?.name
        return preset?.let(Presets::of) ?: Presets.DE_STAFFEL_4
    }

    fun loadLayout(path: Path): LayoutSpec {
        val absolute = path.toAbsolutePath().normalize()
        return LayoutReader.read(
            SourceText(absolute.fileName.toString(), Files.readString(absolute), absolute.parent?.toString()),
        )
    }

    fun paper(result: CalculationResult, layout: LayoutSpec = defaultLayout(result)): WorkingPaper =
        paper(CalculationView.of(result), layout)

    fun paper(
        view: CalculationView,
        layout: LayoutSpec = defaultLayout(view),
        options: CalculationOptions = CalculationOptions(),
    ): WorkingPaper =
        view.openReader(options).use { reader -> WorkingPaperBuilder(view, layout, reader = reader).build() }

    /** Shares a caller-owned read epoch; the caller closes [reader]. */
    fun paper(view: CalculationView, layout: LayoutSpec, reader: CalculationReader): WorkingPaper =
        WorkingPaperBuilder(view, layout, reader = reader).build()

    /** Paper with every row of every table, independent of current values (basis for spreadsheet export). */
    fun completePaper(result: CalculationResult, layout: LayoutSpec = defaultLayout(result)): WorkingPaper =
        completePaper(CalculationView.of(result), layout)

    fun completePaper(
        view: CalculationView,
        layout: LayoutSpec = defaultLayout(view),
        options: CalculationOptions = CalculationOptions(),
    ): WorkingPaper = view.openReader(options).use { reader -> completePaper(view, layout, reader) }

    /** Shares the export's read epoch, including all tables and reductions. */
    fun completePaper(view: CalculationView, layout: LayoutSpec, reader: CalculationReader): WorkingPaper =
        WorkingPaperBuilder(
            view,
            layout.copy(hideZero = false, showInactive = true, expandMembers = false),
            includeAll = true,
            reader = reader,
        ).build()

    fun html(result: CalculationResult, layout: LayoutSpec = defaultLayout(result)): String =
        HtmlRenderer.render(paper(result, layout))
    fun html(view: CalculationView, layout: LayoutSpec = defaultLayout(view)): String =
        HtmlRenderer.render(paper(view, layout))

    fun text(
        result: CalculationResult,
        layout: LayoutSpec = defaultLayout(result),
        includeAudit: Boolean = false,
    ): String = TextRenderer.render(paper(result, layout), includeAudit)

    fun text(view: CalculationView, layout: LayoutSpec = defaultLayout(view), includeAudit: Boolean = false): String =
        TextRenderer.render(paper(view, layout), includeAudit)
}
