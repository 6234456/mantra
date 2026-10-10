package com.xqiou.mantra.excel

import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import org.apache.poi.ss.usermodel.SheetVisibility

data class ExcelPrevious(val first: Boolean, val value: X?)

internal fun ExcelWorkbookBuilder.periodKeyValues(dimension: String): X.Vec? {
    if (view.dimensions[dimension]?.periods == null) return null
    return X.Vec(members[dimension].orEmpty().map { Ex.keyword(it.key) })
}

internal fun ExcelWorkbookBuilder.previousReference(id: String, dims: List<String>, coord: Coord): ExcelPrevious {
    val target = view.nodes[id] ?: throw Untranslatable("prev target $id is unknown")
    val axes = target.dims.filter { it in dims && view.dimensions[it]?.periods != null }
    if (axes.size != 1) throw Untranslatable("prev target $id needs one shared continuous period dimension")
    val axis = axes.single()
    val index = dims.indexOf(axis)
    val key = coord[index]
    val ordered = members[axis].orEmpty().map { it.key }
    val offset = ordered.indexOf(key)
    if (offset < 0) throw Untranslatable("prev context $key is not a member of $axis")
    if (offset == 0) return ExcelPrevious(true, null)
    val shifted = coord.toMutableList().also { it[index] = ordered[offset - 1] }
    fun preserveNil(value: X): X = when (value) {
        is X.Scalar -> Ex.iff(Ex.cmp("=", value, Ex.EMPTY), Ex.EMPTY, value)
        is X.MapX -> X.MapX(value.keys, value.values.map(::preserveNil), value.liveKeys)
        is X.Range -> X.MapX(value.keys, value.cells.map(::preserveNil))
        else -> value
    }
    return ExcelPrevious(false, reference(id, dims, shifted)?.let(::preserveNil))
}

/** Bounds generated formulas without silently substituting static engine values. */
internal fun ExcelWorkbookBuilder.materializeExpression(value: X.Scalar): X.Scalar {
    val existing = expressionSlots[value]
    fun reference(slot: Slot) = ref(slot, value.kind).copy(
        numericOrNil = value.numericOrNil,
        booleanOrNil = value.booleanOrNil,
    )
    if (existing != null) return reference(existing)
    val scratch = expressionSheet ?: sheet("Formula intermediates").also {
        expressionSheet = it
        wb.setSheetVisibility(wb.getSheetIndex(it), SheetVisibility.HIDDEN)
    }
    val slot = Slot(scratch, expressionSlots.size, 0)
    Ex.validateFormula(value.text)
    cell(scratch, slot.row, slot.col).cellFormula = value.text
    formulaCells++
    expressionSlots[value] = slot
    return reference(slot)
}
