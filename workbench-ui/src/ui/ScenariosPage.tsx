import { useEffect, useMemo, useState } from 'react'
import { FixtureData } from '../data'
import type { WorkbenchData } from '../data'
import type { Compare, Envelope, Structure, Workspace } from '../types'
import { casePath } from '../address'
import { language, t } from '../i18n'
import './ScenariosPage.css'

const lang = language()
const MAX_SCENARIOS = 8
const PAGE_SIZE = 50
const CONCURRENCY = 2
type Change = Compare['mainline'][number]
type Result = { loading: boolean; response?: Envelope<Compare>; error?: string }

export function scenariosPath(caseId: string, selected: string[], effectiveDate?: string) {
  const query = new URLSearchParams()
  selected.forEach((id) => query.append('scenario', id))
  if (effectiveDate) query.set('effectiveDate', effectiveDate)
  return `${casePath(caseId)}/scenarios${query.size ? `?${query}` : ''}`
}

/** Validate the authored comparison date without choosing a date on the user's behalf. */
export function isComparisonDate(value?: string): boolean {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function changeKey(change: Pick<Change, 'node' | 'coord'>) {
  return JSON.stringify([change.node, change.coord])
}

function useComparisons(
  data: WorkbenchData,
  caseId: string,
  selectedKey: string,
  comparable: boolean,
  revision: string,
  refresh: number,
  retry: number,
  effectiveDate?: string,
) {
  const batchKey = JSON.stringify([caseId, selectedKey, comparable, revision, refresh, retry, effectiveDate])
  const [state, setState] = useState<{ key: string; results: Record<string, Result> }>({ key: '', results: {} })
  useEffect(() => {
    const controller = new AbortController()
    const selected = JSON.parse(selectedKey) as string[]
    setState({ key: batchKey, results: Object.fromEntries(selected.map((id) => [id, { loading: comparable }])) })
    if (!comparable) return () => controller.abort()
    let next = 0
    async function worker() {
      while (!controller.signal.aborted && next < selected.length) {
        const id = selected[next++]
        let result: Result
        try {
          const response = effectiveDate
            ? await data.compare(caseId, [id], controller.signal, effectiveDate)
            : await data.compare(caseId, [id], controller.signal)
          if (
            response.data.variant.parameters.length !== 1 ||
            response.data.variant.parameters[0] !== id ||
            (response.data.variant.case && response.data.variant.case !== caseId)
          ) {
            throw new Error(
              lang === 'de' ? 'Die Antwort gehört zu einem anderen Szenario.' : 'Scenario response mismatch.',
            )
          }
          result = { loading: false, response }
        } catch (error) {
          result = { loading: false, error: error instanceof Error ? error.message : String(error) }
        }
        if (!controller.signal.aborted)
          setState((current) => ({ key: batchKey, results: { ...current.results, [id]: result } }))
      }
    }
    for (let index = 0; index < CONCURRENCY; index++) void worker()
    return () => controller.abort()
  }, [data, caseId, selectedKey, comparable, batchKey, effectiveDate])
  return state.key === batchKey ? state.results : {}
}

export function ScenariosPage({
  caseId,
  structure,
  workspace,
  data,
  selected,
  effectiveDate,
  revision,
  refresh = 0,
  navigate,
}: {
  caseId: string
  structure: Structure
  workspace: Workspace
  data: WorkbenchData
  selected: string[]
  effectiveDate?: string
  revision: string
  refresh?: number
  navigate: (path: string) => void
}) {
  const comparable = (data.canCompareScenarios?.(caseId) ?? data.canCompareParameters?.(caseId)) !== false
  const dateRequired = data.parameterComparisonDateRequired?.() === true
  const dateValid = !dateRequired || isComparisonDate(effectiveDate)
  const options = workspace.parameters ?? []
  const known = new Set(options.map((option) => option.id))
  const unique = [...new Set(selected)]
  const invalid = unique.filter((id) => !known.has(id))
  const overLimit = selected.length > MAX_SCENARIOS
  const valid = unique.filter((id) => known.has(id)).slice(0, MAX_SCENARIOS)
  const selectedKey = JSON.stringify(overLimit ? [] : valid)
  const [retry, setRetry] = useState(0)
  const results = useComparisons(
    data,
    caseId,
    selectedKey,
    comparable && dateValid,
    revision,
    refresh,
    retry,
    dateRequired ? effectiveDate : undefined,
  )
  const ready = valid.filter((id) => results[id]?.response)
  const rows = useMemo(() => {
    const found = new Map<string, Change>()
    valid.forEach((id) => {
      results[id]?.response?.data.mainline.forEach((change) => {
        const key = JSON.stringify([change.panel, changeKey(change)])
        if (!found.has(key)) found.set(key, change)
      })
    })
    return [...found.values()]
    // Selection order comes from the canonical key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedKey, results])
  const remove = (id: string) =>
    navigate(
      scenariosPath(
        caseId,
        unique.filter((item) => item !== id),
        effectiveDate,
      ),
    )
  const busy = valid.some((id) => results[id]?.loading)
  return (
    <>
      <div className="page-heading">
        <span className="eyebrow">{t('workspace', lang)}</span>
        <h1>{lang === 'de' ? 'Szenarien' : 'Scenarios'}</h1>
        <p>{structure.title}</p>
      </div>
      <section className="sheet scenarios-picker">
        <h2>{lang === 'de' ? 'Parametersätze vergleichen' : 'Compare parameter sets'}</h2>
        <p>
          {lang === 'de'
            ? 'Jede Spalte vergleicht einen Parametersatz mit dem aktuellen Fall. Der Satz ersetzt die gebundenen Parametersätze; Fallüberschreibungen bleiben wirksam. Es werden keine Dateien geändert.'
            : 'Each column compares one parameter set with the current case. The set replaces the bound parameter sets; case overrides remain effective. No files are changed.'}
        </p>
        {!comparable ? (
          <p role="status">
            {lang === 'de'
              ? 'Der Parametervergleich ist in dieser Ansicht nicht verfügbar.'
              : 'Parameter comparison is unavailable in this view.'}
          </p>
        ) : (
          <>
            {data instanceof FixtureData && (
              <p className="muted" role="note">
                {lang === 'de'
                  ? 'Fixture-Modus zeigt nur gespeicherte Compare-Ergebnisse; fehlende Kombinationen können hier nicht berechnet werden.'
                  : 'Fixture mode shows stored Compare results only; missing combinations cannot be calculated here.'}
              </p>
            )}
            {dateRequired && (
              <div className="scenario-date">
                <label>
                  {lang === 'de' ? 'Vergleichsdatum' : 'Comparison date'}
                  <input
                    type="date"
                    required
                    value={isComparisonDate(effectiveDate) ? effectiveDate : ''}
                    aria-describedby="scenario-date-hint"
                    aria-invalid={!!effectiveDate && !dateValid}
                    onChange={(event) => navigate(scenariosPath(caseId, unique, event.target.value))}
                  />
                </label>
                <p id="scenario-date-hint" className="muted">
                  {lang === 'de'
                    ? 'Das Datum wird ausdrücklich gewählt; die Gültigkeit der Parametersätze prüft der Server.'
                    : 'Choose the date explicitly; the server checks whether each parameter set is valid for it.'}
                </p>
                {!dateValid && (
                  <p role={effectiveDate ? 'alert' : 'status'}>
                    {lang === 'de'
                      ? 'Ein gültiges Vergleichsdatum wählen, um die Szenarien zu berechnen.'
                      : 'Choose a valid comparison date to calculate the scenarios.'}
                  </p>
                )}
              </div>
            )}
            <label className="scenario-add">
              {lang === 'de' ? 'Parametersatz hinzufügen' : 'Add parameter set'}
              <select
                value=""
                disabled={unique.length >= MAX_SCENARIOS || !options.length}
                onChange={(event) => {
                  const id = event.target.value
                  if (known.has(id) && unique.length < MAX_SCENARIOS && !unique.includes(id))
                    navigate(scenariosPath(caseId, [...unique, id], effectiveDate))
                }}
              >
                <option value="">{lang === 'de' ? 'Parametersatz wählen' : 'Choose parameter set'}</option>
                {options
                  .filter((option) => !unique.includes(option.id))
                  .map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.id}
                    </option>
                  ))}
              </select>
            </label>
            <p className="muted">
              {lang === 'de'
                ? 'Bis zu 8 Szenarien, jeweils 2 parallele Anfragen.'
                : 'Up to 8 scenarios, with 2 requests at a time.'}
            </p>
            {!options.length && (
              <p role="status">
                {lang === 'de'
                  ? 'Keine Parametersätze im Arbeitsbereich verfügbar.'
                  : 'No parameter sets are available in this workspace.'}
              </p>
            )}
            <div className="scenario-selections">
              {unique.slice(0, MAX_SCENARIOS).map((id) => (
                <button key={id} type="button" onClick={() => remove(id)}>
                  {id} <span aria-hidden="true">×</span>
                  <span className="sr-only">{lang === 'de' ? ' entfernen' : ' remove'}</span>
                </button>
              ))}
              {!!unique.length && (
                <button type="button" onClick={() => navigate(scenariosPath(caseId, [], effectiveDate))}>
                  {lang === 'de' ? 'Auswahl löschen' : 'Clear selection'}
                </button>
              )}
            </div>
            {!!invalid.length && (
              <p role="alert">
                {lang === 'de'
                  ? 'Unbekannte Parametersätze werden nicht angefragt: '
                  : 'Unknown parameter sets will not be requested: '}
                {invalid.slice(0, MAX_SCENARIOS).join(', ')}
              </p>
            )}
            {overLimit && (
              <p role="alert">
                {lang === 'de'
                  ? 'Bitte höchstens 8 Szenarien auswählen. Es wurde keine Anfrage gesendet.'
                  : 'Select at most 8 scenarios. No requests were sent.'}
              </p>
            )}
            {!valid.length && !unique.length && (
              <p className="muted">
                {lang === 'de'
                  ? 'Einen Parametersatz auswählen, um seine Wirkung zu prüfen.'
                  : 'Select a parameter set to inspect its effects.'}
              </p>
            )}
          </>
        )}
      </section>
      {comparable && dateValid && !overLimit && !!valid.length && (
        <section className="sheet scenario-results">
          <div className="section-heading">
            <h2>{lang === 'de' ? 'Wirkung auf die Hauptlinie' : 'Mainline effects'}</h2>
            <button type="button" disabled={busy} onClick={() => setRetry((value) => value + 1)}>
              {lang === 'de' ? 'Szenarien neu laden' : 'Reload scenarios'}
            </button>
          </div>
          <p className="muted">
            {dateRequired
              ? lang === 'de'
                ? 'Die Vergleiche sind unabhängige Momentaufnahmen. Speichern in der Workbench aktualisiert die Ergebnisse. Nach externen Änderungen an einer Falldatei die Szenarien oder diese Seite neu laden. Zum Aktualisieren erfasster Paketressourcen den Server neu starten.'
                : 'Comparisons are independent snapshots. Saving in the workbench refreshes the results. After editing a case file outside the workbench, reload the scenarios or this page. Restart the server to refresh captured package resources.'
              : lang === 'de'
                ? 'Die Vergleiche sind unabhängige Momentaufnahmen. Änderungen im Arbeitsbereich laden die Ergebnisse neu.'
                : 'Comparisons are independent snapshots. Workspace changes reload the results.'}
          </p>
          <div className="scenario-status-list" aria-live="polite">
            {valid.map((id) => (
              <div key={id}>
                <strong>{id}</strong>
                {results[id]?.loading && <span role="status">{lang === 'de' ? 'Wird verglichen…' : 'Comparing…'}</span>}
                {results[id]?.error && <span role="alert">{results[id].error}</span>}
                {results[id]?.response && (
                  <small>
                    {lang === 'de' ? 'Vergleichsrevision: ' : 'Comparison revision: '}
                    <code>{results[id].response!.revision}</code>
                  </small>
                )}
              </div>
            ))}
          </div>
          {!!rows.length && (
            <PagedRows key={`${caseId}-${selectedKey}-${refresh}-${retry}-${effectiveDate ?? ''}`} rows={rows}>
              {(pageRows) => (
                <div
                  className="scenario-table-scroll"
                  tabIndex={0}
                  role="region"
                  aria-label={lang === 'de' ? 'Szenariovergleich' : 'Scenario comparison'}
                >
                  <table className="scenario-table">
                    <caption>
                      {lang === 'de'
                        ? 'Werte und Differenzen aus dem Compare-Dokument'
                        : 'Values and differences from the Compare document'}
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">{lang === 'de' ? 'Ergebnis' : 'Result'}</th>
                        {valid.map((id) => (
                          <th key={id} scope="col">
                            {id}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {pageRows.map((row) => (
                        <tr key={JSON.stringify([row.panel, changeKey(row)])}>
                          <th scope="row">
                            {structure.panels.find((panel) => panel.id === row.panel)?.title ?? row.panel}
                            <small>
                              {structure.nodes?.[row.node]?.label ?? row.node}
                              {row.coord.length ? ` @ ${row.coord.join(' / ')}` : ''}
                            </small>
                          </th>
                          {valid.map((id) => {
                            const change = results[id]?.response?.data.mainline.find(
                              (item) => item.panel === row.panel && changeKey(item) === changeKey(row),
                            )
                            return <td key={id}>{change ? <ChangeValues change={change} /> : '—'}</td>
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </PagedRows>
          )}
          {!!ready.length && !rows.length && (
            <p>
              {lang === 'de'
                ? 'Die Compare-Dokumente enthalten keine Hauptlinienergebnisse.'
                : 'The Compare documents report no mainline results.'}
            </p>
          )}
        </section>
      )}
      {comparable &&
        dateValid &&
        !overLimit &&
        ready.map((id) => (
          <ScenarioDetails
            key={`${id}-${results[id].response!.revision}`}
            id={id}
            compare={results[id].response!.data}
            structure={structure}
          />
        ))}
    </>
  )
}

function ChangeValues({
  change,
}: {
  change: Compare['parameterChanges'][number] | Change | Compare['changes'][number]['items'][number]
}) {
  const absent = lang === 'de' ? 'Nicht vorhanden' : 'Absent'
  return (
    <dl className="scenario-values">
      <div>
        <dt>{lang === 'de' ? 'Basis' : 'Base'}</dt>
        <dd>{change.basePresent ? (change.display.base ?? '—') : absent}</dd>
      </div>
      <div>
        <dt>{lang === 'de' ? 'Szenario' : 'Scenario'}</dt>
        <dd>{change.variantPresent ? (change.display.variant ?? '—') : absent}</dd>
      </div>
      <div>
        <dt>Δ</dt>
        <dd>{change.display.delta ?? '—'}</dd>
      </div>
    </dl>
  )
}

function PagedRows<T>({ rows, children }: { rows: T[]; children: (rows: T[]) => React.ReactNode }) {
  const [page, setPage] = useState(0)
  const pages = Math.ceil(rows.length / PAGE_SIZE)
  const current = Math.min(page, Math.max(0, pages - 1))
  return (
    <>
      {children(rows.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE))}
      {pages > 1 && (
        <div className="scenario-pagination">
          <button type="button" disabled={current === 0} onClick={() => setPage(current - 1)}>
            {lang === 'de' ? 'Zurück' : 'Previous'}
          </button>
          <span>
            {lang === 'de' ? 'Seite' : 'Page'} {current + 1} / {pages}
          </span>
          <button type="button" disabled={current + 1 >= pages} onClick={() => setPage(current + 1)}>
            {lang === 'de' ? 'Weiter' : 'Next'}
          </button>
        </div>
      )}
    </>
  )
}

function ScenarioDetails({ id, compare, structure }: { id: string; compare: Compare; structure: Structure }) {
  const rows = [
    ...compare.parameterChanges.map((change) => ({
      kind: lang === 'de' ? 'Parameter' : 'Parameter',
      change,
      source: `${change.baseSource} → ${change.variantSource}`,
    })),
    ...compare.changes.flatMap((group) =>
      group.items.map((change) => ({
        kind: group.panel
          ? (structure.panels.find((panel) => panel.id === group.panel)?.title ?? group.panel)
          : lang === 'de'
            ? 'Allgemeine Eingaben'
            : 'General inputs',
        change,
        source: '',
      })),
    ),
  ]
  return (
    <details className="sheet scenario-details">
      <summary>
        {id} · {lang === 'de' ? 'Parameter und weitere Änderungen' : 'Parameters and other changes'} ({rows.length})
      </summary>
      {!rows.length && <p>{lang === 'de' ? 'Keine weiteren wirksamen Änderungen.' : 'No other effective changes.'}</p>}
      {!!rows.length && (
        <PagedRows rows={rows}>
          {(pageRows) => (
            <div className="scenario-detail-rows">
              {pageRows.map(({ kind, change, source }, index) => (
                <article key={`${kind}-${changeKey(change)}-${index}`}>
                  <div>
                    <span className="eyebrow">{kind}</span>
                    <h3>{structure.nodes?.[change.node]?.label ?? change.node}</h3>
                    <code>
                      {change.node}
                      {change.coord.length ? ` @ ${change.coord.join(' / ')}` : ''}
                    </code>
                    {source && <p>{source}</p>}
                  </div>
                  <ChangeValues change={change} />
                </article>
              ))}
            </div>
          )}
        </PagedRows>
      )}
    </details>
  )
}
