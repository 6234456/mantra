import type { Compare, PaperTable as Table, Value } from '../types'
import { cellAppearance } from '../ui/PaperTable'
import type { TemplatePreview } from './client'
import './CandidateComparison.css'

type Change = Compare['changes'][number]['items'][number]

/** Preserve the engine's text and exact wire values; no comparison is recomputed in the browser. */
function valueText(value: Value, display: string | null, present: boolean): string {
  if (!present) return 'Absent'
  if (display !== null) return display
  if (value === null) return 'null'
  if (typeof value === 'boolean' || typeof value === 'string') return String(value)
  if (!Array.isArray(value)) {
    if ('n' in value) return value.n
    if ('date' in value) return value.date
    if ('kw' in value) return value.kw
  }
  return JSON.stringify(value)
}

function presence(change: Change, comparisonOnly: boolean) {
  if (!change.basePresent && change.variantPresent) return 'Added'
  if (change.basePresent && !change.variantPresent) return 'Removed'
  return change.basePresent || change.variantPresent ? (comparisonOnly ? 'Compared' : 'Changed') : 'Absent'
}

/** Saved cells are read-only evidence; they cannot select or replace the draft's Explain address. */
function SavedTable({ table }: { table: Table }) {
  return (
    <div className="table-scroll">
      <table className="paper-table" aria-label={`Saved template: ${table.title}`}>
        <caption>{table.title}</caption>
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
              className={`row-${row.kind.toLowerCase()} ${row.flags?.map((flag) => `flag-${flag.toLowerCase()}`).join(' ') ?? ''}`}
            >
              {row.cells.map((cell, columnIndex) => (
                <td
                  key={columnIndex}
                  className={`tone-${cell.style?.tone ?? 'default'} fill-${cell.style?.fill ?? 'none'}`}
                  style={{
                    paddingLeft: columnIndex === 0 ? `${12 + Math.min(row.depth, 6) * 12}px` : undefined,
                    ...cellAppearance(cell),
                  }}
                >
                  {cell.text}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ChangeGroup({
  title,
  changes,
  candidate,
  comparisonOnly = false,
}: {
  title: string
  changes: Change[]
  candidate: TemplatePreview
  comparisonOnly?: boolean
}) {
  if (!changes.length) return null
  return (
    <tbody>
      <tr>
        <th colSpan={5} scope="rowgroup">
          {title}
        </th>
      </tr>
      {changes.map((change, index) => (
        <tr key={`${change.node}:${change.coord.join('/')}:${index}`}>
          <th scope="row">
            {typeof candidate.structure.nodes?.[change.node]?.label === 'string'
              ? candidate.structure.nodes[change.node].label
              : change.node}
            <code>
              {change.node}
              {change.coord.length ? ` @ ${change.coord.join(' / ')}` : ''}
            </code>
            {(change.baseSource !== undefined || change.variantSource !== undefined) && (
              <small>
                {change.baseSource ?? '—'} → {change.variantSource ?? '—'}
              </small>
            )}
          </th>
          <td>{valueText(change.base, change.display.base, change.basePresent)}</td>
          <td>{valueText(change.variant, change.display.variant, change.variantPresent)}</td>
          <td>{change.display.delta ?? change.delta?.n ?? '—'}</td>
          <td>{presence(change, comparisonOnly)}</td>
        </tr>
      ))}
    </tbody>
  )
}

export function CandidateComparison({
  candidate,
  baseline,
  current,
}: {
  candidate?: TemplatePreview
  baseline?: TemplatePreview
  current: boolean
}) {
  const difference = candidate?.difference
  const hasRows =
    !!difference &&
    (difference.mainline.length > 0 ||
      difference.changes.some((group) => group.items.length > 0) ||
      difference.parameterChanges.length > 0)
  return (
    <div className="template-candidate-comparison">
      <section className="sheet" aria-label="Draft comparison">
        <h2>Changes from saved template</h2>
        {!candidate || !difference ? (
          <p>
            {candidate
              ? 'Comparison is not available for this preview.'
              : 'Preview a draft to compare its values with the saved template.'}
          </p>
        ) : (
          <>
            <p className="muted">
              {current
                ? 'Current draft comparison'
                : 'Previous draft comparison — preview the current draft to update it.'}
            </p>
            {!hasRows ? (
              <p>No value changes reported for this draft.</p>
            ) : (
              <div className="table-scroll">
                <table className="template-change-table" aria-label="Changes from saved template">
                  <thead>
                    <tr>
                      <th scope="col">Field</th>
                      <th scope="col">Saved template</th>
                      <th scope="col">Draft</th>
                      <th scope="col">Δ</th>
                      <th scope="col">Change</th>
                    </tr>
                  </thead>
                  <ChangeGroup
                    title="Mainline results"
                    changes={difference.mainline}
                    candidate={candidate}
                    comparisonOnly
                  />
                  {difference.changes.map((group, index) => (
                    <ChangeGroup
                      key={`${group.panel ?? 'general'}:${group.step ?? 'none'}:${index}`}
                      title={
                        group.panel
                          ? `Other values and inputs · ${candidate.structure.panels.find((panel) => panel.id === group.panel)?.title ?? group.panel}`
                          : 'Other values and inputs'
                      }
                      changes={group.items}
                      candidate={candidate}
                    />
                  ))}
                  <ChangeGroup title="Parameters" changes={difference.parameterChanges} candidate={candidate} />
                </table>
              </div>
            )}
          </>
        )}
      </section>
      <section className="sheet" aria-label="Saved template">
        <h2>Saved template</h2>
        {!baseline ? (
          <p>Saved template preview is not available yet.</p>
        ) : (
          <>
            <p>{baseline.paper.title}</p>
            {!baseline.succeeded && <p role="status">Saved template preview contains runtime failures.</p>}
            {!baseline.validationPassed && <p role="status">Saved template business checks have not passed.</p>}
            {baseline.paper.tables.length ? (
              baseline.paper.tables.map((table) => <SavedTable key={table.id} table={table} />)
            ) : (
              <p>No saved table for this panel.</p>
            )}
          </>
        )}
      </section>
    </div>
  )
}
