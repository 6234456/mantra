import { useEffect, useMemo, useState } from 'react'
import type { WorkbenchData } from '../data'
import type { ImportInspection, InputField, SourceBinding, Structure } from '../types'
import './SourcesPage.css'

const words = {
  de: { eyebrow: 'Datenzugang', title: 'Datenquellen', intro: 'Dateien werden im Fall gebunden. Manuelle Eingaben haben Vorrang.',
    current: 'Gebundene Quellen', empty: 'Noch keine Datei gebunden.', remove: 'Bindung entfernen', select: '1 · Datei wählen', inspect: '2 · Erkennen', map: '3 · Zuordnen', check: '4 · Prüfen und anwenden',
    file: 'Datei', format: 'Format', mode: 'CSV-Struktur', pairs: 'Eingabe / Wert', wide: 'Spalten als Eingaben', member: 'Mitgliedsspalte', source: 'Quellspalte', sample: 'Beispiel', target: 'Zielwert', skip: 'Nicht importieren', rows: 'Datenzeilen', apply: 'Quelle übernehmen', examining: 'Datei lesen…', applying: 'Übernehmen…', status: 'Zuordnung', choose: 'Datei auswählen', reload: 'Neu laden' },
  en: { eyebrow: 'Data access', title: 'Data sources', intro: 'Files are bound to the case. Manual inputs take precedence.',
    current: 'Bound sources', empty: 'No file is bound yet.', remove: 'Remove binding', select: '1 · Choose file', inspect: '2 · Inspect', map: '3 · Map', check: '4 · Check and apply',
    file: 'File', format: 'Format', mode: 'CSV structure', pairs: 'Input / value', wide: 'Columns as inputs', member: 'Member column', source: 'Source column', sample: 'Sample', target: 'Target input', skip: 'Do not import', rows: 'data rows', apply: 'Apply source', examining: 'Reading file…', applying: 'Applying…', status: 'Mapping', choose: 'Choose a file', reload: 'Reload' },
}

function formatFromName(name: string): 'csv' | 'json' | 'xlsx' {
  const extension = name.split('.').at(-1)?.toLowerCase()
  return extension === 'json' || extension === 'xlsx' ? extension : 'csv'
}

function base64(bytes: Uint8Array): string {
  let binary = ''
  for (let index = 0; index < bytes.length; index += 32768)
    binary += String.fromCharCode(...bytes.subarray(index, index + 32768))
  return btoa(binary)
}

function inputFields(structure: Structure): InputField[] {
  const items = [...structure.generalInputs, ...structure.panels.flatMap(panel => panel.fields)]
  return Array.from(new Map(items.map(item => {
    const field = typeof item === 'string' ? { id: item, label: structure.nodes?.[item]?.label } : item
    return [field.id, field] as const
  })).values()).filter(field => field.type !== 'table')
}

export function SourcesPage({ caseId, structure, revision, data, onSaved }: {
  caseId: string; structure: Structure; revision: string; data: WorkbenchData; onSaved: () => void
}) {
  const lang = document.documentElement.lang.startsWith('en') ? 'en' : 'de'
  const w = words[lang]
  const [sources, setSources] = useState<SourceBinding[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [fileName, setFileName] = useState('')
  const [content, setContent] = useState('')
  const [format, setFormat] = useState<'csv' | 'json' | 'xlsx'>('csv')
  const [inspection, setInspection] = useState<ImportInspection>()
  const [mode, setMode] = useState<'pairs' | 'wide'>('pairs')
  const [memberColumn, setMemberColumn] = useState('')
  const [mapping, setMapping] = useState<Record<string, string>>({})
  const inputs = useMemo(() => inputFields(structure), [structure])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    data.sources(caseId, controller.signal).then(response => {
      if (!controller.signal.aborted) { setSources(response.data.sources); setLoading(false); setError('') }
    }).catch(reason => { if (!controller.signal.aborted) { setLoading(false); setError(String(reason)) } })
    return () => controller.abort()
  }, [caseId, data, revision])

  async function choose(file?: File) {
    if (!file) return
    setError(''); setInspection(undefined); setMapping({}); setFileName(file.name)
    setFormat(formatFromName(file.name))
    if (file.size > 10 * 1024 * 1024) { setError('10 MiB maximum'); setContent(''); return }
    setContent(base64(new Uint8Array(await file.arrayBuffer())))
  }

  async function inspect() {
    if (!content || !fileName) return
    setBusy(true); setError('')
    try {
      const response = await data.importInspect(caseId, fileName, format, content)
      setInspection(response.data)
      setMapping(Object.fromEntries(response.data.columns.map(column => [column.name,
        inputs.find(input => input.id === column.name)?.id ?? ''])))
      setMode(format === 'csv' && response.data.columns.some(column => ['input', 'eingabe', 'id'].includes(column.name.toLowerCase())) ? 'pairs' : 'wide')
    } catch (reason) { setError(String(reason)) } finally { setBusy(false) }
  }

  function options(): Record<string, unknown> {
    if (format === 'xlsx') return {}
    if (format === 'json') return { mapping: Object.fromEntries(Object.entries(mapping).filter(([, target]) => target).map(([source, target]) => [target, source])) }
    if (mode === 'pairs') return { delimiter: inspection?.delimiter ?? ';' }
    return { mode: 'wide', delimiter: inspection?.delimiter ?? ';', ...(memberColumn ? { 'member-column': memberColumn } : {}),
      columns: Object.fromEntries(Object.entries(mapping).filter(([source, target]) => target && source !== memberColumn)) }
  }

  async function apply() {
    if (!content || !inspection) return
    setBusy(true); setError('')
    try {
      await data.importApply(caseId, fileName, format, content, revision, options())
      setContent(''); setFileName(''); setInspection(undefined); setMapping({}); onSaved()
    } catch (reason) { setError(String(reason)) } finally { setBusy(false) }
  }

  async function remove(index: number) {
    setBusy(true); setError('')
    try { await data.edit(caseId, revision, [{ op: 'removeSource', index }]); onSaved() }
    catch (reason) { setError(String(reason)) } finally { setBusy(false) }
  }

  const needsMember = format === 'csv' && mode === 'wide' && Object.values(mapping).some(target =>
    inputs.find(input => input.id === target)?.dims?.length)
  const mapped = Object.values(mapping).filter(Boolean).length
  const ready = !!inspection && (format === 'xlsx' || format === 'csv' && mode === 'pairs' || mapped > 0) && (!needsMember || !!memberColumn)
  return <div className="sources-page">
    <div className="page-heading"><span className="eyebrow">{w.eyebrow}</span><h1>{w.title}</h1><p>{w.intro}</p></div>
    <div className="sources-layout">
      <section className="sheet sources-bound"><div className="section-heading"><div><span className="eyebrow">{w.current}</span><h2>{w.current}</h2></div><span className="mono">{sources.length}</span></div>
        {loading ? <p role="status">…</p> : sources.length === 0 ? <p className="muted">{w.empty}</p> :
          <ol className="source-list">{sources.map(source => <li key={`${source.index}-${source.path}`}><span className="source-kind">{source.kind.toUpperCase()}</span><div><strong>{source.path}</strong><small>{Object.keys(source.options).filter(key => key !== 'path').join(' · ')}</small></div><button type="button" disabled={busy} onClick={() => remove(source.index)}>{w.remove}</button></li>)}</ol>}
      </section>
      <section className="sheet sources-import"><div className="section-heading"><div><span className="eyebrow">Import</span><h2>{w.select}</h2></div></div>
        <div className="sources-file-row"><label>{w.file}<input type="file" accept=".csv,.json,.xlsx,text/csv,application/json" onChange={event => void choose(event.target.files?.[0])} /></label><label>{w.format}<select value={format} onChange={event => { setFormat(event.target.value as typeof format); setInspection(undefined) }}><option value="csv">CSV</option><option value="json">JSON</option><option value="xlsx">XLSX</option></select></label><button type="button" disabled={!content || busy} onClick={() => void inspect()}>{busy ? w.examining : w.inspect}</button></div>
        {fileName && <p className="sources-filename mono">{fileName}</p>}
        {inspection && <><div className="sources-step"><span className="eyebrow">{w.inspect}</span><strong>{inspection.rowCount} {w.rows}</strong><span>{inspection.columns.length} Spalten</span></div>
          <div className="sources-step"><span className="eyebrow">{w.map}</span><h3>{w.status}</h3>
            {format === 'csv' && <div className="sources-mode"><label>{w.mode}<select value={mode} onChange={event => setMode(event.target.value as typeof mode)}><option value="pairs">{w.pairs}</option><option value="wide">{w.wide}</option></select></label>{mode === 'wide' && <label>{w.member}<select value={memberColumn} onChange={event => setMemberColumn(event.target.value)}><option value="">—</option>{inspection.columns.map(column => <option key={column.name}>{column.name}</option>)}</select></label>}</div>}
            {(format === 'json' || format === 'csv' && mode === 'wide') && <div className="sources-table-wrap"><table><thead><tr><th>{w.source}</th><th>{w.sample}</th><th>{w.target}</th></tr></thead><tbody>{inspection.columns.map(column => <tr key={column.name}><td>{column.name}</td><td className="mono">{column.sample.join(' · ') || '—'}</td><td><select aria-label={`${w.target}: ${column.name}`} value={mapping[column.name] ?? ''} disabled={column.name === memberColumn && format === 'csv'} onChange={event => setMapping(previous => ({ ...previous, [column.name]: event.target.value }))}><option value="">{w.skip}</option>{inputs.map(input => <option key={input.id} value={input.id}>{input.label ?? input.id} · {input.id}</option>)}</select></td></tr>)}</tbody></table></div>}
          </div><div className="sources-step sources-apply"><span className="eyebrow">{w.check}</span><p>{format === 'xlsx' ? 'Named input cells' : `${mapped} ${w.target.toLowerCase()}`}</p><button className="primary-button" type="button" disabled={!ready || busy} onClick={() => void apply()}>{busy ? w.applying : w.apply}</button></div></>}
        {error && <p role="alert" className="input-error">{error}</p>}
      </section>
    </div>
  </div>
}
