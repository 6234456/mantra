import { useEffect, useMemo, useRef, useState } from 'react'
import { addressKey } from '../address'
import { language } from '../i18n'
import type { CSSProperties } from 'react'
import type { Address, Cell, Paper, PaperTable as Table } from '../types'
import './PaperTable.css'

const lang = language()

function cellAppearance(cell: Cell): CSSProperties {
  const overrides = cell.styleOverrides
  if (!overrides) return { fontWeight: cell.style?.weight === 'bold' ? 600 : undefined }
  const tones = { default: 'var(--ink)', muted: 'var(--ink-3)', accent: 'var(--blue-strong)' }
  const fills = { none: 'transparent', subtle: 'var(--rail)', accent: 'var(--blue-tint)' }
  return {
    fontWeight: overrides.weight === undefined ? undefined : overrides.weight === 'bold' ? 600 : 400,
    color: overrides.tone === undefined ? undefined : tones[overrides.tone],
    backgroundColor: overrides.fill === undefined ? undefined : fills[overrides.fill],
  }
}

/** Find text in renderer-produced rows without changing their visibility, order or values. */
export function paperMatches(table: Table, query: string): number[] {
  const text = query.trim().toLocaleLowerCase()
  if (!text) return []
  return table.rows.flatMap((row, index) =>
    row.cells.some((cell) => cell.text.toLocaleLowerCase().includes(text)) ? [index] : [],
  )
}

export function PaperTable({
  table,
  browsing,
  onIncludeZero,
  selected,
  onSelect,
}: {
  table: Table
  browsing?: Paper['browsing']
  onIncludeZero: (value: boolean) => void
  selected?: Address
  onSelect: (address: Address) => void
}) {
  const [query, setQuery] = useState('')
  const [matchIndex, setMatchIndex] = useState(0)
  const matches = useMemo(() => paperMatches(table, query), [table, query])
  const matchedRows = useMemo(() => new Set(matches), [matches])
  const activeIndex = matches.length ? matchIndex % matches.length : 0
  const activeRow = matches[activeIndex]
  const tableElement = useRef<HTMLTableElement>(null)
  useEffect(() => {
    if (activeRow === undefined) return
    tableElement.current?.querySelector(`[data-paper-row="${activeRow}"]`)?.scrollIntoView?.({ block: 'nearest' })
  }, [activeRow, table])
  const cells = table.rows.flatMap((row, rowIndex) =>
    row.cells
      .map((cell, columnIndex) => ({ rowIndex, columnIndex, address: cell.address }))
      .filter((item) => !!item.address),
  )
  function onKey(event: React.KeyboardEvent<HTMLTableElement>) {
    if (!['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return
    const active = event.target as HTMLElement
    const row = Number(active.dataset.row),
      col = Number(active.dataset.col)
    const next =
      event.key === 'ArrowDown'
        ? cells.find((item) => item.columnIndex === col && item.rowIndex > row)
        : event.key === 'ArrowUp'
          ? cells.findLast((item) => item.columnIndex === col && item.rowIndex < row)
          : event.key === 'ArrowRight'
            ? cells.find((item) => item.rowIndex === row && item.columnIndex > col)
            : [...cells].reverse().find((item) => item.rowIndex === row && item.columnIndex < col)
    if (!next) return
    event.preventDefault()
    event.currentTarget
      .querySelector<HTMLButtonElement>(`button[data-row="${next.rowIndex}"][data-col="${next.columnIndex}"]`)
      ?.focus()
    if (next.address) onSelect(next.address)
  }
  function moveMatch(direction: number) {
    if (matches.length) setMatchIndex((activeIndex + direction + matches.length) % matches.length)
  }
  return (
    <>
      <div className="paper-controls">
        {(browsing?.hideZero || !browsing) && (
          <label className="paper-zero-control">
            <input
              type="checkbox"
              checked={browsing?.includeZero ?? false}
              disabled={!browsing}
              onChange={(event) => onIncludeZero(event.target.checked)}
            />
            {lang === 'de' ? 'Nullzeilen anzeigen' : 'Show zero rows'}
            {!browsing && <small>{lang === 'de' ? 'Live-Server erforderlich' : 'Live server required'}</small>}
          </label>
        )}
        <div className="paper-search" role="search">
          <label>
            {lang === 'de' ? 'In der Tabelle suchen' : 'Find in table'}
            <input
              type="search"
              value={query}
              onChange={(event) => {
                setQuery(event.target.value)
                setMatchIndex(0)
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  moveMatch(event.shiftKey ? -1 : 1)
                }
              }}
            />
          </label>
          <button
            type="button"
            disabled={!matches.length}
            onClick={() => moveMatch(-1)}
            aria-label={lang === 'de' ? 'Vorheriger Treffer' : 'Previous match'}
          >
            ↑
          </button>
          <button
            type="button"
            disabled={!matches.length}
            onClick={() => moveMatch(1)}
            aria-label={lang === 'de' ? 'Nächster Treffer' : 'Next match'}
          >
            ↓
          </button>
          <span role="status" aria-live="polite">
            {query.trim()
              ? matches.length
                ? `${activeIndex + 1} / ${matches.length}`
                : lang === 'de'
                  ? 'Keine Treffer'
                  : 'No matches'
              : ''}
          </span>
        </div>
      </div>
      <div className="table-scroll">
        <table className="paper-table" ref={tableElement} onKeyDown={onKey}>
          <thead>
            <tr>
              {table.columns.map((column) => (
                <th key={column.id} scope="col">
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {table.rows.map((row, rowIndex) => (
              <tr
                key={row.anchor ?? rowIndex}
                data-paper-row={rowIndex}
                className={`row-${row.kind.toLowerCase()} ${row.flags?.map((flag) => `flag-${flag.toLowerCase()}`).join(' ') ?? ''} ${matchedRows.has(rowIndex) ? 'paper-match' : ''} ${activeRow === rowIndex ? 'paper-match-active' : ''}`}
              >
                {row.cells.map((cell, columnIndex) => (
                  <td
                    key={columnIndex}
                    className={`${cell.editable ? 'editable-cell' : ''} tone-${cell.style?.tone ?? 'default'} fill-${cell.style?.fill ?? 'none'}`}
                    style={{
                      paddingLeft: columnIndex === 0 ? `${12 + Math.min(row.depth, 6) * 12}px` : undefined,
                      ...cellAppearance(cell),
                    }}
                  >
                    {cell.address ? (
                      <button
                        type="button"
                        data-row={rowIndex}
                        data-col={columnIndex}
                        className={`cell-button ${selected && addressKey(selected) === addressKey(cell.address) ? 'selected' : ''}`}
                        onClick={() => onSelect(cell.address!)}
                        aria-label={`${table.columns[columnIndex]?.header ?? ''}: ${cell.text}`}
                      >
                        {cell.text || ' '}
                      </button>
                    ) : (
                      cell.text
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
