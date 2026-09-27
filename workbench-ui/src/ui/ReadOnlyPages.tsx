import { useEffect, useMemo, useState } from 'react'
import type { WorkbenchData } from '../data'
import type { Compare, Diagnostic, Diagnostics, Envelope, Structure, Value, Workspace } from '../types'
import { addressToPath, casePath } from '../address'
import { language, t } from '../i18n'

const lang = language()

function useDocument<T>(load: (signal: AbortSignal) => Promise<Envelope<T>>, keys: unknown[]) {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true })
  useEffect(() => {
    const controller = new AbortController()
    setState({ loading: true })
    load(controller.signal).then(result => { if (!controller.signal.aborted) setState({ data: result.data, loading: false }) })
      .catch(error => { if (!controller.signal.aborted) setState({ error, loading: false }) })
    return () => controller.abort()
    // The caller supplies primitive dependency keys.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, keys)
  return state
}

function valueText(value: Value): string {
  if (value === null) return 'nil'
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (Array.isArray(value)) return value.map(valueText).join(', ')
  if ('n' in value) return value.n
  if ('kw' in value) return value.kw
  if ('date' in value) return value.date
  return JSON.stringify(value)
}

function layerTitle(layer: { layer: string; set?: string }): string {
  if (layer.layer === 'schema') return lang === 'de' ? 'Schema-Standard' : 'Schema default'
  if (layer.layer === 'case') return lang === 'de' ? 'Fallüberschreibung' : 'Case override'
  return layer.set ?? (lang === 'de' ? 'Parametersatz' : 'Parameter set')
}

function stateMessage(error?: Error, loading?: boolean) {
  if (loading) return <div className="skeleton" role="status" aria-label="Loading" />
  if (error) return <div className="error-banner" role="alert">{error.message}</div>
  return null
}

export function ParametersPage({ caseId, structure, workspace, data, compareSet, navigate }: { caseId: string; structure: Structure; workspace: Workspace; data: WorkbenchData; compareSet?: string; navigate: (path: string) => void }) {
  const parameters = useDocument(signal => data.parameters(caseId, signal), [data, caseId])
  const options = workspace.parameters ?? []
  const selectedSet = compareSet ?? ''
  const comparison = useDocument(signal => selectedSet ? data.compare(caseId, [selectedSet], signal) : Promise.reject(new Error('No selection')), [data, caseId, selectedSet])
  const names = useMemo(() => new Map(parameters.data?.parameters.map(item => [item.id, item.label]) ?? []), [parameters.data])
  return <>
    <div className="page-heading"><span className="eyebrow">{t('workspace', lang)}</span><h1>{t('parameters', lang)}</h1><p>{structure.title}</p></div>
    {stateMessage(parameters.error, parameters.loading)}
    {parameters.data && <section className="sheet parameter-sheet"><div className="section-heading"><div><span className="eyebrow">{lang === 'de' ? 'Priorität' : 'Priority'}</span><h2>{lang === 'de' ? 'Parameter nach Ebene' : 'Parameter by layer'}</h2></div><span className="muted">{parameters.data.parameters.length}</span></div>
      {!parameters.data.parameters.length && <p className="muted">{lang === 'de' ? 'Dieses Schema deklariert keine Parameter.' : 'This schema declares no parameters.'}</p>}
      <div className="parameter-list">{parameters.data.parameters.map(parameter => <article className="parameter-item" key={parameter.id}>
        <div className="parameter-heading"><div><h3>{parameter.label}</h3><code>{parameter.id}</code>{parameter.reference && <p>{parameter.reference}</p>}</div><div className="effective-value"><span className="eyebrow">{lang === 'de' ? 'Wirksam' : 'Effective'}</span><strong>{valueText(parameter.effective.value)}</strong><small>{layerTitle(parameter.effective)}</small></div></div>
        <div className="parameter-layers">{parameter.layers.map((layer, index) => {
          const effective = layer.declared && layer.layer === parameter.effective.layer && layer.set === parameter.effective.set && !parameter.layers.slice(index + 1).some(next => next.declared && next.layer === layer.layer && next.set === layer.set)
          return <div className={`parameter-layer ${effective ? 'is-effective' : ''}`} key={`${layer.layer}-${layer.set ?? ''}-${index}`}><span className="eyebrow">{layerTitle(layer)}</span><strong>{layer.declared ? valueText(layer.value) : '—'}</strong><small>{layer.declared ? layer.reference ?? (effective ? (lang === 'de' ? 'Wirksamer Wert' : 'Effective value') : '') : (lang === 'de' ? 'Keine Überschreibung' : 'No override')}</small></div>
        })}</div>
      </article>)}</div>
    </section>}
    <section className="sheet comparison-sheet"><div className="section-heading"><div><span className="eyebrow">{t('comparison', lang)}</span><h2>{lang === 'de' ? 'Wirkung eines Parametersatzes' : 'Effect of a parameter set'}</h2></div><label className="compare-picker">{lang === 'de' ? 'Vergleichen mit' : 'Compare with'} <select value={selectedSet} onChange={event => { const set = event.target.value; navigate(`${casePath(caseId)}/parameters${set ? `?compare=${encodeURIComponent(set)}` : ''}`) }}><option value="">{lang === 'de' ? 'Parametersatz wählen' : 'Choose parameter set'}</option>{options.map(option => <option key={option.id} value={option.id}>{option.id}</option>)}</select></label></div>
      {!options.length && <p className="muted">{lang === 'de' ? 'Keine Parametersätze im Arbeitsbereich verfügbar.' : 'No parameter sets are available in this workspace.'}</p>}
      {selectedSet && stateMessage(comparison.error, comparison.loading)}
      {selectedSet && comparison.data && <ComparisonResult compare={comparison.data} structure={structure} names={names} />}
    </section>
  </>
}

function ComparisonResult({ compare, structure, names }: { compare: Compare; structure: Structure; names: Map<string, string> }) {
  return <div className="comparison-result"><div className="comparison-columns"><div><span className="eyebrow">{t('effect', lang)}</span>{!compare.mainline.length && <p className="muted">{lang === 'de' ? 'Keine Änderung der Hauptlinie.' : 'No mainline change.'}</p>}{compare.mainline.map(change => <div className="comparison-row" key={`${change.panel}-${change.node}-${change.coord.join('/')}`}><span>{structure.panels.find(panel => panel.id === change.panel)?.title ?? change.node}</span><span>{change.display.base ?? '—'} → {change.display.variant ?? '—'}</span><strong>{change.display.delta ?? '—'}</strong></div>)}</div><div><span className="eyebrow">{lang === 'de' ? 'Geänderte Parameter' : 'Changed parameters'}</span>{!compare.parameterChanges.length && <p className="muted">{lang === 'de' ? 'Keine wirksame Parameteränderung.' : 'No effective parameter change.'}</p>}{compare.parameterChanges.map(change => <div className="comparison-row" key={`${change.node}-${change.coord.join('/')}`}><span>{names.get(change.node) ?? change.node}<small>{change.baseSource} → {change.variantSource}</small></span><span>{change.display.base ?? '—'} → {change.display.variant ?? '—'}</span><strong>{change.display.delta ?? '—'}</strong></div>)}</div></div>
    <div className="comparison-changes"><span className="eyebrow">{lang === 'de' ? 'Weitere Änderungen nach Bereich' : 'Other changes by panel'}</span>{!compare.changes.length && <p className="muted">{lang === 'de' ? 'Keine weiteren Wertänderungen.' : 'No other value changes.'}</p>}{compare.changes.map((group, index) => <section className="comparison-group" key={`${group.step ?? 'aux'}-${group.panel ?? 'general'}-${index}`}><h3>{group.step != null && <small>{t('step', lang)} {group.step} · </small>}{group.panel ? structure.panels.find(panel => panel.id === group.panel)?.title ?? group.panel : (lang === 'de' ? 'Allgemeine Eingaben' : 'General inputs')}</h3>{group.items.map((change, itemIndex) => <div className="comparison-row" key={`${change.node}-${change.coord.join('/')}-${itemIndex}`}><span>{structure.nodes?.[change.node]?.label ?? change.node}<small>{change.node}{change.coord.length ? ` @ ${change.coord.join(' / ')}` : ''}</small></span><span>{change.display.base ?? '—'} → {change.display.variant ?? '—'}</span><strong>{change.display.delta ?? '—'}</strong></div>)}</section>)}</div>
  </div>
}

function locationText(location: NonNullable<Diagnostic['location']>) { return `${location.document}:${location.line}:${location.column}` }

export function DiagnosticsPage({ caseId, structure, data, navigate }: { caseId: string; structure: Structure; data: WorkbenchData; navigate: (path: string) => void }) {
  const document = useDocument<Diagnostics>(signal => data.diagnostics(caseId, signal), [data, caseId])
  const [severity, setSeverity] = useState('all')
  const [selected, setSelected] = useState(0)
  useEffect(() => { setSeverity('all'); setSelected(0) }, [caseId])
  const findings = document.data?.diagnostics ?? []
  const filtered = findings.map((finding, index) => ({ finding, index })).filter(item => severity === 'all' || item.finding.severity === severity)
  const active = findings[selected] && filtered.some(item => item.index === selected) ? findings[selected] : filtered[0]?.finding
  const jump = active?.address && structure.panels.find(panel => panel.nodes.includes(active.address!.node) || panel.result === active.address!.node)
  const jumpPath = jump && active?.address ? `${casePath(caseId)}/panels/${encodeURIComponent(jump.id)}?cell=${encodeURIComponent(addressToPath(active.address))}` : null
  return <>
    <div className="page-heading"><span className="eyebrow">{t('workspace', lang)}</span><h1>{t('diagnostics', lang)}</h1><p>{structure.title}</p></div>
    {stateMessage(document.error, document.loading)}
    {document.data && <div className="diagnostics-layout"><section className="sheet findings-sheet"><div className="section-heading"><div><span className="eyebrow">{findings.length} {lang === 'de' ? 'Befunde' : 'findings'}</span><h2>{lang === 'de' ? 'Prüfliste' : 'Findings'}</h2></div><label>{lang === 'de' ? 'Schweregrad' : 'Severity'} <select aria-label={lang === 'de' ? 'Schweregrad' : 'Severity'} value={severity} onChange={event => setSeverity(event.target.value)}><option value="all">{lang === 'de' ? 'Alle' : 'All'}</option><option value="error">Error</option><option value="warning">Warning</option><option value="info">Info</option></select></label></div>
      {!filtered.length && <p className="muted">{findings.length ? (lang === 'de' ? 'Keine Befunde für diesen Schweregrad.' : 'No findings at this severity.') : (lang === 'de' ? 'Keine Befunde.' : 'No findings.')}</p>}
      <div className="findings-list">{filtered.map(({ finding, index }) => <button key={`${finding.code}-${index}`} type="button" className={`finding-row ${active === finding ? 'selected' : ''}`} onClick={() => setSelected(index)} aria-pressed={active === finding}><span className={`severity-mark ${finding.severity}`}>{finding.severity}</span><span><strong>{finding.code}</strong><small>{finding.message}</small>{finding.location && <em>{locationText(finding.location)}</em>}</span></button>)}</div></section>
      <aside className="sheet finding-detail">{active ? <><span className={`severity-mark ${active.severity}`}>{active.severity}</span><h2>{active.code}</h2><p>{active.message}</p>{active.location && <div className="finding-location"><span className="eyebrow">{lang === 'de' ? 'Dokumentposition' : 'Document location'}</span><code>{locationText(active.location)}</code>{active.location.startOffset !== undefined && active.location.endOffset !== undefined && <small>{lang === 'de' ? 'Zeichen' : 'Characters'} {active.location.startOffset}–{active.location.endOffset}</small>}</div>}{!!active.related.length && <div className="finding-related"><span className="eyebrow">{lang === 'de' ? 'Weitere Positionen' : 'Related locations'}</span>{active.related.map((location, index) => <code key={index}>{locationText(location)}</code>)}</div>}{jumpPath && <a className="text-link" href={jumpPath} onClick={event => { event.preventDefault(); navigate(jumpPath) }}>{lang === 'de' ? 'Zum betroffenen Wert' : 'Go to affected value'} ↗</a>}</> : <p className="muted">{lang === 'de' ? 'Befund auswählen.' : 'Select a finding.'}</p>}</aside></div>}
  </>
}
