import { useEffect, useMemo, useRef, useState } from 'react'
import type { ClipboardEvent, KeyboardEvent } from 'react'
import type { PaperTable as Table } from '../types'
import { cellAppearance } from '../ui/PaperTable'
import type { SourceOwner } from './service'

export interface GridPosition {
  row: number
  column: number
}

export interface AuthoringGridProps {
  table: Table
  selected?: GridPosition
  onSelect: (position: GridPosition) => void
  ownerFor: (row: number, column: number) => SourceOwner | undefined
  onEdit: (owner: SourceOwner, initialText?: string, composition?: boolean) => void
  showFormulas?: boolean
  formulaFor?: (row: number, column: number) => string | undefined
  invalidHandles?: ReadonlySet<string>
  errorPreview?: 'previous-valid' | 'current-runtime' | 'previous-runtime'
  onToggleFormulas?: () => void
  onCopy?: (positions: GridPosition[]) => void
  onPaste?: (text: string, owner: SourceOwner) => void
  onRangeChange?: (positions: GridPosition[]) => void
}

function nearestPosition(table: Table, position: GridPosition): GridPosition {
  const row = Math.max(0, Math.min(position.row, table.rows.length - 1))
  const column = Math.max(0, Math.min(position.column, (table.rows[row]?.cells.length ?? 1) - 1))
  return { row, column }
}

function positionsInRange(table: Table, first: GridPosition, last: GridPosition): GridPosition[] {
  const minRow = Math.min(first.row, last.row)
  const maxRow = Math.max(first.row, last.row)
  const minColumn = Math.min(first.column, last.column)
  const maxColumn = Math.max(first.column, last.column)
  return table.rows.flatMap((row, rowIndex) =>
    rowIndex < minRow || rowIndex > maxRow
      ? []
      : row.cells.flatMap((_, column) =>
          column >= minColumn && column <= maxColumn ? [{ row: rowIndex, column }] : [],
        ),
  )
}

function escapeHtml(text: string) {
  return text.replace(/[&<>"']/g, (character) => {
    const escaped: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
    return escaped[character]
  })
}

/** Selects renderer-produced cells and delegates all edits to semantic source owners. */
export function AuthoringGrid({
  table,
  selected,
  onSelect,
  ownerFor,
  onEdit,
  showFormulas,
  formulaFor,
  invalidHandles,
  errorPreview = 'previous-valid',
  onToggleFormulas,
  onCopy,
  onPaste,
  onRangeChange,
}: AuthoringGridProps) {
  const tableElement = useRef<HTMLTableElement>(null)
  const compositionTransfer = useRef(false)
  const [anchor, setAnchor] = useState<GridPosition | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const active = nearestPosition(table, selected ?? { row: 0, column: 0 })
  const { row: activeRow, column: activeColumn } = active
  const positions = useMemo(
    () =>
      positionsInRange(table, anchor ?? { row: activeRow, column: activeColumn }, {
        row: activeRow,
        column: activeColumn,
      }),
    // The coordinate values prevent an unrelated parent rerender from resetting a selection.
    [table, anchor, activeRow, activeColumn],
  )
  useEffect(() => {
    onRangeChange?.(positions)
  }, [positions, onRangeChange])

  function select(position: GridPosition, extend: boolean, focus = true) {
    const next = nearestPosition(table, position)
    setAnchor(extend ? (anchor ?? active) : null)
    onSelect(next)
    if (focus)
      tableElement.current
        ?.querySelector<HTMLElement>(`[data-row="${next.row}"][data-column="${next.column}"]`)
        ?.focus()
  }

  function edit(position: GridPosition, initialText?: string, composition = false) {
    const owner = ownerFor(position.row, position.column)
    if (!owner?.editable) {
      setAnnouncement(owner?.reason ?? 'This cell has no editable template property.')
      return
    }
    setAnnouncement('')
    if (composition) onEdit(owner, initialText, true)
    else onEdit(owner, initialText)
  }

  function onKey(event: KeyboardEvent<HTMLTableElement>) {
    const target = (event.target as HTMLElement).closest<HTMLElement>('[role="gridcell"]')
    if (!target || !event.currentTarget.contains(target)) return
    const position = { row: Number(target.dataset.row), column: Number(target.dataset.column) }
    if (event.nativeEvent.keyCode === 229) {
      if (!compositionTransfer.current) {
        compositionTransfer.current = true
        // The parent must focus and select the original text synchronously, before IME composition.
        // Keep the native event untouched so the browser can place that composition in the editor.
        edit(position, undefined, true)
      }
      return
    }
    if (event.nativeEvent.isComposing || compositionTransfer.current) return
    let next: GridPosition | undefined
    if (event.key === 'ArrowDown') next = { ...position, row: position.row + 1 }
    if (event.key === 'ArrowUp') next = { ...position, row: position.row - 1 }
    if (event.key === 'ArrowRight') next = { ...position, column: position.column + 1 }
    if (event.key === 'ArrowLeft') next = { ...position, column: position.column - 1 }
    if (event.key === 'Home') next = { row: event.ctrlKey || event.metaKey ? 0 : position.row, column: 0 }
    if (event.key === 'End') {
      const row = event.ctrlKey || event.metaKey ? table.rows.length - 1 : position.row
      next = { row, column: (table.rows[row]?.cells.length ?? 1) - 1 }
    }
    if (next) {
      event.preventDefault()
      select(next, event.shiftKey)
      return
    }
    if (event.key === 'Enter' || event.key === 'F2') {
      event.preventDefault()
      edit(position)
    } else if (event.key === 'Escape') {
      if (positions.length > 1) {
        event.preventDefault()
        setAnchor(null)
      }
    } else if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault()
      setAnnouncement('Labels and formulas cannot be cleared here. Press Enter to edit.')
    } else if ((event.ctrlKey || event.metaKey) && event.key === '`') {
      event.preventDefault()
      onToggleFormulas?.()
    } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault()
      edit(position, event.key)
    }
    // Tab and clipboard shortcuts keep native browser behavior.
  }

  function copy(event: ClipboardEvent<HTMLTableElement>, cut = false) {
    if (!positions.length) return
    event.preventDefault()
    const rows = [...new Set(positions.map((position) => position.row))].map((row) =>
      positions.filter((position) => position.row === row).map((position) => table.rows[row].cells[position.column]),
    )
    event.clipboardData.setData('text/plain', rows.map((row) => row.map((cell) => cell.text).join('\t')).join('\n'))
    event.clipboardData.setData(
      'text/html',
      `<table>${rows.map((row) => `<tr>${row.map((cell) => `<td>${escapeHtml(cell.text)}</td>`).join('')}</tr>`).join('')}</table>`,
    )
    event.clipboardData.setData(
      'application/vnd.mantra.semantic-addresses+json',
      JSON.stringify(rows.flatMap((row) => row.flatMap((cell) => (cell.address ? [cell.address] : [])))),
    )
    onCopy?.(positions)
    if (cut) setAnnouncement('Cut is not available for template definitions.')
  }

  return (
    <div className="authoring-grid-scroll">
      <table
        className="paper-table authoring-grid"
        role="grid"
        aria-label={table.title}
        aria-rowcount={table.rows.length + 1}
        aria-colcount={table.columns.length}
        tabIndex={table.rows.length ? undefined : 0}
        ref={tableElement}
        onKeyDown={onKey}
        onCompositionEnd={() => {
          compositionTransfer.current = false
        }}
        onCopy={(event) => copy(event)}
        onCut={(event) => copy(event, true)}
        onPaste={(event) => {
          event.preventDefault()
          if (positions.length > 1) {
            setAnnouncement('Paste into one property at a time in this version.')
            return
          }
          const owner = ownerFor(active.row, active.column)
          if (!owner?.editable) {
            setAnnouncement(owner?.reason ?? 'This cell has no editable template property.')
            return
          }
          const text = event.clipboardData.getData('text/plain')
          if (onPaste) onPaste(text, owner)
          else onEdit(owner, text)
        }}
      >
        <thead>
          <tr role="row">
            {table.columns.map((column) => (
              <th role="columnheader" key={column.id} scope="col">
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row, rowIndex) => (
            <tr
              key={row.anchor ?? rowIndex}
              role="row"
              data-paper-row={rowIndex}
              className={`row-${row.kind.toLowerCase()} ${row.flags?.map((flag) => `flag-${flag.toLowerCase()}`).join(' ') ?? ''}`}
            >
              {row.cells.map((cell, column) => {
                const owner = ownerFor(rowIndex, column)
                const formula = formulaFor?.(rowIndex, column)
                const invalid = !!owner && !!invalidHandles?.has(owner.handle)
                return (
                  <td
                    role="gridcell"
                    key={column}
                    data-row={rowIndex}
                    data-column={column}
                    tabIndex={active.row === rowIndex && active.column === column ? 0 : -1}
                    aria-readonly={!owner?.editable}
                    aria-selected={positions.some(
                      (position) => position.row === rowIndex && position.column === column,
                    )}
                    aria-invalid={invalid || undefined}
                    aria-label={`${table.columns[column]?.header ?? ''}: ${cell.text}`}
                    className={`tone-${cell.style?.tone ?? 'default'} fill-${cell.style?.fill ?? 'none'} ${invalid ? 'author-definition-invalid' : ''}`}
                    style={{
                      paddingLeft: column === 0 ? `${12 + Math.min(row.depth, 6) * 12}px` : undefined,
                      ...cellAppearance(cell),
                    }}
                    onFocus={() => {
                      compositionTransfer.current = false
                      onSelect({ row: rowIndex, column })
                    }}
                    onClick={(event) => select({ row: rowIndex, column }, event.shiftKey)}
                    onDoubleClick={() => edit({ row: rowIndex, column })}
                  >
                    <span data-authoring-value>
                      {showFormulas && formula !== undefined ? <code>{formula}</code> : cell.text || '\u00a0'}
                    </span>
                    {owner && (
                      <span
                        className="author-definition-mark"
                        aria-label={
                          owner.editable
                            ? 'Template definition; press Enter to edit.'
                            : `Read-only definition: ${owner.reason ?? 'This definition cannot be edited.'}`
                        }
                        title={owner.editable ? 'Template definition' : (owner.reason ?? 'Read-only definition')}
                      >
                        <span aria-hidden="true">{owner.editable ? 'ƒ' : '🔒'}</span>
                      </span>
                    )}
                    {invalid && (
                      <span
                        className="author-definition-mark"
                        aria-label={
                          errorPreview === 'current-runtime'
                            ? 'Definition has a runtime failure; displayed values come from the current preview.'
                            : errorPreview === 'previous-runtime'
                              ? 'Definition has a runtime failure; displayed values are based on previous source revisions.'
                              : 'Definition has an error; displayed values come from the last valid preview.'
                        }
                      >
                        !
                      </span>
                    )}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="authoring-grid-announcement" role="status" aria-live="polite">
        {announcement}
      </p>
    </div>
  )
}
