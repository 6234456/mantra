import { useEffect, useMemo, useState } from 'react'
import type { WorkbenchData } from '../data'
import type { ExportPreview } from '../types'
import { language, t } from '../i18n'

const lang = language()
type Format = 'xlsx' | 'html' | 'txt'

function columnName(index: number): string {
  let value = index + 1
  let name = ''
  while (value > 0) {
    value--
    name = String.fromCharCode(65 + (value % 26)) + name
    value = Math.floor(value / 26)
  }
  return name
}

export function ExportPage({
  caseId,
  data,
  layouts = [],
}: {
  caseId: string
  data: WorkbenchData
  layouts?: Array<{ id: string; path: string }>
}) {
  const [sheet, setSheet] = useState<string>()
  const [layout, setLayout] = useState('')
  const [format, setFormat] = useState<Format>('xlsx')
  const [selectedCell, setSelectedCell] = useState<string>()
  const [state, setState] = useState<{ document?: ExportPreview; loading: boolean; error?: Error }>({ loading: true })

  useEffect(() => {
    setSheet(undefined)
    setSelectedCell(undefined)
    setLayout('')
  }, [caseId])
  useEffect(() => {
    const controller = new AbortController()
    setState({ loading: true })
    data
      .exportPreview(caseId, sheet, layout || undefined, controller.signal)
      .then((response) => {
        if (!controller.signal.aborted) setState({ document: response.data, loading: false })
      })
      .catch((error) => {
        if (!controller.signal.aborted) setState({ loading: false, error })
      })
    return () => controller.abort()
  }, [caseId, data, sheet, layout])

  const document = state.document
  const cells = useMemo(() => document?.preview.cells ?? [], [document])
  const byAddress = useMemo(() => new Map(cells.map((cell) => [cell.address, cell])), [cells])
  const focused = byAddress.get(selectedCell ?? '') ?? cells.find((cell) => cell.formula) ?? cells[0]
  const rowCount = Math.min(document?.preview.rows ?? 0, 50)
  const columnCount = Math.min(document?.preview.columns ?? 0, 20)
  const download = data.exportUrl(caseId, format, layout || undefined)

  return (
    <div className="export-page">
      <div className="page-heading">
        <span className="eyebrow">{t('export', lang)}</span>
        <h1>{t('workbook', lang)}</h1>
        <p>
          {t('preview', lang)} · {t('fidelity', lang)}
        </p>
      </div>
      <div className="export-controls sheet">
        <label>
          {t('layout', lang)}{' '}
          <select
            value={layout}
            onChange={(event) => {
              setLayout(event.target.value)
              setSheet(undefined)
              setSelectedCell(undefined)
            }}
          >
            <option value="">{t('currentLayout', lang)}</option>
            {layouts.map((item) => (
              <option key={item.id} value={item.id}>
                {item.id}
              </option>
            ))}
          </select>
        </label>
        <fieldset>
          <legend className="sr-only">{t('export', lang)}</legend>
          {(['xlsx', 'html', 'txt'] as const).map((item) => (
            <label key={item} className={format === item ? 'selected' : ''}>
              <input
                type="radio"
                name="export-format"
                value={item}
                checked={format === item}
                onChange={() => setFormat(item)}
              />
              {item === 'xlsx' ? 'Excel (.xlsx)' : item === 'html' ? 'HTML' : 'Text'}
            </label>
          ))}
        </fieldset>
        {download ? (
          <a className="primary-button" href={download} download={`mantra-export.${format}`}>
            {t('download', lang)}
          </a>
        ) : (
          <button className="primary-button" disabled title={t('downloadLive', lang)}>
            {t('download', lang)}
          </button>
        )}
      </div>
      {state.loading && (
        <section className="sheet export-message" role="status">
          {t('preview', lang)}…
        </section>
      )}
      {state.error && (
        <section className="sheet export-message" role="alert">
          {state.error.message}
        </section>
      )}
      {document && !state.loading && (
        <>
          <div className="export-grid">
            <section className="sheet export-sheets">
              <div className="section-heading">
                <div>
                  <span className="eyebrow">{t('workbook', lang)}</span>
                  <h2>
                    {document.sheets.length} {t('worksheets', lang)}
                  </h2>
                </div>
              </div>
              <ol>
                {document.sheets.map((item, index) => (
                  <li key={item.name}>
                    <button
                      type="button"
                      className={item.name === document.selectedSheet ? 'active' : ''}
                      aria-current={item.name === document.selectedSheet ? 'true' : undefined}
                      onClick={() => {
                        setSheet(item.name)
                        setSelectedCell(undefined)
                      }}
                    >
                      <span>{String(index + 1).padStart(2, '0')}</span>
                      <b>{item.name}</b>
                      <small>
                        {item.rows} × {item.columns}
                      </small>
                    </button>
                  </li>
                ))}
              </ol>
            </section>
            <section className="sheet export-preview">
              <div className="section-heading">
                <div>
                  <span className="eyebrow">{t('preview', lang)}</span>
                  <h2>{document.selectedSheet}</h2>
                </div>
              </div>
              <div className="export-formula">
                <span className="mono">{focused?.address ?? '—'}</span>
                <span className="mono">fx</span>
                <code>{focused?.formula ? `=${focused.formula}` : (focused?.value ?? '—')}</code>
              </div>
              <div className="export-table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th scope="col" aria-label="Row" />
                      {Array.from({ length: columnCount }, (_, col) => (
                        <th key={col} scope="col">
                          {columnName(col)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {Array.from({ length: rowCount }, (_, row) => (
                      <tr key={row}>
                        <th scope="row">{row + 1}</th>
                        {Array.from({ length: columnCount }, (_, col) => {
                          const address = `${columnName(col)}${row + 1}`
                          const cell = byAddress.get(address)
                          return (
                            <td key={address}>
                              <button
                                type="button"
                                className={`${cell?.kind === 'formula' ? 'formula' : ''} ${focused?.address === address ? 'focused' : ''}`}
                                onClick={() => setSelectedCell(address)}
                                aria-label={`${address}: ${cell?.value ?? ''}`}
                                title={cell?.formula ? `=${cell.formula}` : undefined}
                              >
                                {cell?.value ?? ''}
                              </button>
                            </td>
                          )
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {document.preview.truncated && <p className="muted">{t('truncatedPreview', lang)}</p>}
            </section>
          </div>
          <section className="sheet export-fidelity">
            <div className="section-heading">
              <div>
                <span className="eyebrow">Excel</span>
                <h2>{t('fidelity', lang)}</h2>
              </div>
            </div>
            <div className="export-metrics">
              <div>
                <strong>{document.report.formulaCells}</strong>
                <span>{t('formulaCells', lang)}</span>
              </div>
              <div>
                <strong>{document.report.inputCells}</strong>
                <span>{t('inputCells', lang)}</span>
              </div>
              <div>
                <strong>{document.report.fallbacks.length}</strong>
                <span>{t('fallbackCells', lang)}</span>
              </div>
              <div>
                <strong>{document.report.names}</strong>
                <span>{t('namedRanges', lang)}</span>
              </div>
            </div>
            {document.report.fallbacks.length > 0 && (
              <details>
                <summary>
                  {t('fallbackCells', lang)} · {document.report.fallbacks.length}
                </summary>
                <ul>
                  {document.report.fallbacks.map((item) => (
                    <li key={`${item.sheet}-${item.cell}-${item.nodeId}`}>
                      <span className="mono">
                        {item.sheet}!{item.cell}
                      </span>{' '}
                      · {item.nodeId} · {item.reason}
                    </li>
                  ))}
                </ul>
              </details>
            )}
            {document.report.evaluationErrors.length > 0 && (
              <details>
                <summary>
                  {t('evaluationErrors', lang)} · {document.report.evaluationErrors.length}
                </summary>
                <ul>
                  {document.report.evaluationErrors.map((item, index) => (
                    <li key={index}>{item}</li>
                  ))}
                </ul>
              </details>
            )}
            <details>
              <summary>
                {t('namedRanges', lang)} · {document.names.length}
              </summary>
              <ul>
                {document.names.map((item, index) => (
                  <li key={`${item.name}-${index}`}>
                    <span className="mono">{item.name}</span> → {item.refersTo}
                  </li>
                ))}
              </ul>
            </details>
          </section>
        </>
      )}
    </div>
  )
}
