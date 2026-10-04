package com.xqiou.mantra.excel

import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import org.apache.poi.ss.usermodel.SheetVisibility

/** Cell-backed bounded unfolding, with no workbook iterative calculation setting. */
internal fun ExcelWorkbookBuilder.unfoldConvergence(
    init: X.Scalar,
    iterations: X.Scalar,
    tolerance: X.Scalar,
    enabled: X.Scalar,
    callback: (previous: X.Scalar, run: X.Scalar) -> X.Scalar,
): X.Scalar {
    fun numeric(value: X.Scalar, name: String) {
        if (value.kind == XKind.DATE || !value.numericOrNil) {
            throw Untranslatable("calc/converge $name needs a scalar number")
        }
    }
    numeric(init, "init")
    numeric(iterations, "iterations")
    numeric(tolerance, "tolerance")
    val literalLimit = iterations.text.toBigDecimalOrNull()?.let { value ->
        try {
            value.intValueExact().takeIf { it in 1..1000 }
        } catch (_: ArithmeticException) {
            null
        }
    }
    // Dynamic limits remain live and get the documented ceiling, never a frozen parameter value.
    val capacity = literalLimit ?: 1000
    val attemptedSteps = convergenceSteps + capacity.toLong()
    if (attemptedSteps > options.maxConvergenceSteps.toLong()) {
        throw ExcelExportLimitException("Convergence expansion exceeds ${options.maxConvergenceSteps} callback steps")
    }
    val blockCells = 21L + capacity * 7L
    if (createdCells.toLong() + blockCells > options.maxCells.toLong()) {
        throw ExcelExportLimitException("Convergence needs at least $blockCells additional cells")
    }
    val start = convergenceNextRow
    if (start.toLong() + capacity + 4L > 1_048_576L) {
        throw ExcelExportLimitException("Convergence table exceeds the worksheet row limit")
    }
    val table = convergenceSheet ?: sheet("Convergence").also {
        convergenceSheet = it
        wb.setSheetVisibility(wb.getSheetIndex(it), SheetVisibility.HIDDEN)
    }
    // Reserve before translating callbacks, because nested convergence allocates on the same sheet.
    convergenceNextRow = start + capacity + 4
    convergenceSteps = attemptedSteps
    val parameters = start + 1
    val initial = start + 2
    fun reference(row: Int, col: Int, kind: XKind = XKind.NUM) = ref(Slot(table, row, col), kind)
    fun write(row: Int, col: Int, value: X.Scalar) {
        Ex.validateFormula(value.text)
        cell(table, row, col).cellFormula = value.text
        formulaCells++
    }
    fun not(value: X.Scalar) = Ex.fn("NOT", value, kind = XKind.BOOL)
    fun error(value: X.Scalar) = Ex.fn("ISERROR", value, kind = XKind.BOOL)
    fun number(value: X.Scalar) = Ex.fn("ISNUMBER", value, kind = XKind.BOOL)
    fun and(vararg values: X.Scalar) = Ex.fn("AND", *values, kind = XKind.BOOL)
    val invalidNumber = Ex.fn("VALUE", Ex.text("MANTRA-CALC-NUMBER"))
    val invalidBounds = Ex.fn("VALUE", Ex.text("MANTRA-CALC-INVALID-BOUND"))
    val exhausted = Ex.fn("NA")
    val headers = listOf("Iteration", "Current", "Candidate", "Adjacent delta", "Stopped", "Run", "Callback error")
    headers.forEachIndexed { col, label ->
        cell(table, start, col).setCellValue(label)
    }
    write(parameters, 1, Ex.iff(enabled, init, Ex.EMPTY))
    write(parameters, 2, Ex.iff(enabled, iterations, Ex.EMPTY))
    write(parameters, 3, Ex.iff(enabled, tolerance, Ex.EMPTY))
    val seed = reference(parameters, 1)
    val limit = reference(parameters, 2)
    val epsilon = reference(parameters, 3)
    val limitValid = and(
        Ex.cmp("=", limit, Ex.fn("TRUNC", limit)),
        Ex.cmp(">=", limit, Ex.num(1)),
        Ex.cmp("<=", limit, Ex.num(1000)),
    )
    // Preserve upstream errors and kernel argument validation order. ISNUMBER alone hides errors.
    val valid = Ex.iff(
        error(seed),
        seed,
        Ex.iff(
            error(limit),
            limit,
            Ex.iff(
                error(epsilon),
                epsilon,
                Ex.iff(
                    number(seed),
                    Ex.iff(
                        number(limit),
                        Ex.iff(
                            number(epsilon),
                            and(limitValid, Ex.cmp(">=", epsilon, Ex.ZERO)),
                            Ex.FALSE,
                        ),
                        Ex.FALSE,
                    ),
                    Ex.FALSE,
                ),
            ),
        ),
    )
    write(parameters, 4, Ex.iff(enabled, valid, Ex.FALSE))
    val validRef = reference(parameters, 4, XKind.BOOL)
    write(initial, 0, Ex.ZERO)
    write(initial, 1, seed)
    write(initial, 2, Ex.EMPTY)
    write(initial, 3, Ex.EMPTY)
    write(initial, 4, Ex.FALSE)
    write(initial, 5, Ex.FALSE)
    write(initial, 6, Ex.FALSE)
    for (iteration in 1..capacity) {
        reader.chargeCoordinateVisits()
        val row = initial + iteration
        val previous = reference(row - 1, 1)
        val priorStop = reference(row - 1, 4, XKind.BOOL)
        val run = reference(row, 5, XKind.BOOL)
        write(row, 0, Ex.num(iteration.toLong()))
        write(
            row,
            5,
            Ex.iff(
                enabled,
                Ex.iff(
                    validRef,
                    and(not(priorStop), Ex.cmp("<=", Ex.num(iteration.toLong()), limit)),
                    Ex.FALSE,
                ),
                Ex.FALSE,
            ),
        )
        // This run reference is also the callback materialization guard, including all helpers.
        val next = callback(previous, run)
        numeric(next, "callback result")
        write(row, 2, Ex.iff(run, next, Ex.EMPTY))
        val candidate = reference(row, 2)
        val strictNumber = Ex.iff(error(candidate), candidate, Ex.iff(number(candidate), candidate, invalidNumber))
        write(row, 1, Ex.iff(run, strictNumber, previous))
        val current = reference(row, 1)
        write(row, 3, Ex.iff(run, Ex.fn("ABS", Ex.sub(current, previous)), Ex.EMPTY))
        write(
            row,
            4,
            Ex.iff(
                priorStop,
                Ex.TRUE,
                Ex.iff(
                    run,
                    Ex.cmp("<=", reference(row, 3), epsilon),
                    Ex.FALSE,
                ),
            ),
        )
        write(row, 6, error(current))
    }
    val last = initial + capacity
    fun area(column: Int): X.Scalar {
        val first = reference(initial + 1, column).text
        val letter = org.apache.poi.ss.util.CellReference.convertNumToColString(column)
        return Ex.atom("$first:\$$letter\$${last + 1}", XKind.ANY)
    }
    fun firstTrue(column: Int): X.Scalar {
        val match = Ex.fn("MATCH", Ex.TRUE, area(column), Ex.ZERO)
        return Ex.iff(Ex.fn("ISNA", match, kind = XKind.BOOL), Ex.ZERO, match)
    }
    // Sequential MATCH scans populate the preceding row cache. A direct reference to the
    // final row would make a visible formula recurse through a dynamic 1000-row chain.
    write(parameters, 6, Ex.iff(enabled, Ex.iff(validRef, firstTrue(6), Ex.ZERO), Ex.ZERO))
    write(parameters, 7, Ex.iff(enabled, Ex.iff(validRef, firstTrue(4), Ex.ZERO), Ex.ZERO))
    val errorRow = reference(parameters, 6)
    val stopRow = reference(parameters, 7)
    val hasError = Ex.cmp(">", errorRow, Ex.ZERO)
    val hasStopped = Ex.cmp(">", stopRow, Ex.ZERO)
    val failedValue = Ex.fn("INDEX", area(1), errorRow)
    val stoppedValue = Ex.fn("INDEX", area(1), stopRow)
    // A separate live status describes the worksheet unfolding, not a new Normein audit trace.
    val status = Ex.iff(
        enabled,
        Ex.iff(
            error(validRef),
            Ex.text("input-error"),
            Ex.iff(
                validRef,
                Ex.iff(
                    hasError,
                    Ex.text("callback-error"),
                    Ex.iff(hasStopped, Ex.text("converged"), Ex.text("not-converged")),
                ),
                Ex.text("invalid-bound"),
            ),
        ),
        Ex.text("skipped"),
    )
    write(parameters, 5, status)
    val answer = Ex.iff(
        enabled,
        Ex.iff(
            error(validRef),
            validRef,
            Ex.iff(
                validRef,
                Ex.iff(
                    hasError,
                    failedValue,
                    Ex.iff(hasStopped, stoppedValue, exhausted),
                ),
                invalidBounds,
            ),
        ),
        Ex.EMPTY,
    )
    return answer.copy(kind = XKind.NUM, numericOrNil = true, booleanOrNil = false)
}
