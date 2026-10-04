package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.packages.CapturedPackageData
import com.xqiou.mantra.packages.PackageDataImporter

/** CSV, JSON and XLSX use the same literal import adapters as workspace files, without file reads. */
object PackageImports : PackageDataImporter {
    override fun import(
        schema: Schema,
        case: CaseData,
        sources: List<CapturedPackageData>,
        control: CaseLoadControl,
    ): CaseData = BoundSources.loadCaptured(
        case,
        schema,
        sources,
        onRow = { control.chargeInputRows() },
        checkpoint = control::checkpoint,
    ).case
}
