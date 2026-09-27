import { useEffect, useMemo, useState } from 'react'
import type { Address, CaseSummary, Explain, Paper, Panel, Run, Structure } from '../types'
import type { WorkbenchData } from '../data'
import { configuredData } from '../data'
import { addressFromPath, addressKey, addressToPath, casePath } from '../address'
import { chooseLanguage, language, t } from '../i18n'
import { auditForCell, nodeValue } from '../viewModel'
import { DiagnosticsPage, ParametersPage } from './ReadOnlyPages'
import { ExportPage } from './ExportPage'

type Route = { caseId?: string; page: 'overview' | 'panel' | 'provenance' | 'parameters' | 'diagnostics' | 'export' | 'other'; panelId?: string; address?: Address; compare?: string }
const lang = language()

function route(): Route {
  const parts = location.pathname.split('/').filter(Boolean)
  if (parts[0] !== 'cases' || !parts[1]) return { page: 'overview' }
  let caseId: string
  try { caseId = decodeURIComponent(parts[1]) } catch { return { page: 'overview' } }
  if (parts[2] === 'panels' && parts[3]) return { caseId, page: 'panel', panelId: decodeURIComponent(parts[3]), address: addressFromPath(new URLSearchParams(location.search).get('cell') ?? '') ?? undefined }
  if (parts[2] === 'provenance' && parts[3]) return { caseId, page: 'provenance', address: addressFromPath(decodeURIComponent(parts.slice(3).join('/'))) ?? undefined }
  if (parts[2] === 'overview') return { caseId, page: 'overview' }
  if (parts[2] === 'parameters') return { caseId, page: 'parameters', compare: new URLSearchParams(location.search).get('compare') ?? undefined }
  if (parts[2] === 'diagnostics') return { caseId, page: 'diagnostics' }
  if (parts[2] === 'export') return { caseId, page: 'export' }
  return { caseId, page: 'other' }
}

function useRoute() {
  const [current, setCurrent] = useState(route)
  useEffect(() => { const onPop = () => setCurrent(route()); window.addEventListener('popstate', onPop); return () => window.removeEventListener('popstate', onPop) }, [])
  const navigate = (path: string) => { history.pushState(null, '', path); setCurrent(route()) }
  return [current, navigate] as const
}

function useLoad<T>(load: (signal: AbortSignal) => Promise<T>, keys: unknown[]): { data?: T; error?: Error; loading: boolean } {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true })
  useEffect(() => {
    const controller = new AbortController()
    setState({ loading: true })
    load(controller.signal).then(data => { if (!controller.signal.aborted) setState({ data, loading: false }) }).catch(error => { if (!controller.signal.aborted) setState({ error, loading: false }) })
    return () => controller.abort()
    // The caller supplies primitive dependency keys for stable reloads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, keys)
  return state
}

function headlineNode(structure: Structure) { return structure.headline ?? structure.mainline.at(-1)?.result }
function panelLink(caseId: string, id: string) { return `${casePath(caseId)}/panels/${encodeURIComponent(id)}` }

function Link({ href, navigate, children, className, current }: { href: string; navigate: (path: string) => void; children: React.ReactNode; className?: string; current?: boolean }) {
  return <a href={href} className={className} aria-current={current ? 'page' : undefined} onClick={event => { if (event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) { event.preventDefault(); navigate(href) } }}>{children}</a>
}

export function App() {
  const [current, navigate] = useRoute()
  const data = useMemo(configuredData, [])
  const workspace = useLoad(signal => data.workspace(signal), [data])
  const caseId = current.caseId ?? workspace.data?.cases[0]?.id
  const structure = useLoad(signal => caseId ? data.structure(caseId, signal) : Promise.reject(new Error('No case')), [data, caseId])
  const run = useLoad(signal => caseId ? data.run(caseId, signal) : Promise.reject(new Error('No case')), [data, caseId])
  const paper = useLoad(signal => caseId ? data.paper(caseId, current.page === 'panel' ? current.panelId : undefined, signal) : Promise.reject(new Error('No case')), [data, caseId, current.page, current.panelId])
  const selectedCase = workspace.data?.cases.find(item => item.id === caseId)
  const failure = workspace.error ?? structure.error ?? run.error ?? paper.error
  const ready = structure.data && run.data
  if (!workspace.data && workspace.loading) return <div className="loading-shell" role="status">Mantra · {t('paper', lang)}…</div>
  if (!workspace.data?.cases.length) return <div className="empty-start"><Brand /><h1>{t('noFixtures', lang)}</h1><p>{workspace.error?.message}</p><button onClick={() => location.reload()}>{t('retry', lang)}</button></div>
  return <div className="app-shell">
    <AppBar cases={workspace.data.cases} selectedCase={selectedCase} structure={structure.data?.data} run={run.data?.data} paper={paper.data?.data} revision={run.data?.revision} navigate={navigate} caseId={caseId!} />
    <div className="body-shell">
      {ready && <Sidebar structure={structure.data!.data} run={run.data!.data} caseId={caseId!} activePanel={current.panelId} activePage={current.page} navigate={navigate} />}
      <main className="content">
        {failure && <div className="error-banner" role="alert"><strong>{failure.message}</strong><button onClick={() => location.reload()}>{t('retry', lang)}</button></div>}
        {!ready ? <div className="skeleton" role="status" aria-label="Loading" /> : current.page === 'overview' ?
          <Overview structure={structure.data!.data} run={run.data!.data} paper={paper.data?.data} caseId={caseId!} navigate={navigate} /> : current.page === 'panel' ?
          <PanelPage structure={structure.data!.data} run={run.data!.data} paper={paper.data?.data} panelId={current.panelId} selected={current.address} caseId={caseId!} data={data} navigate={navigate} /> : current.page === 'provenance' && current.address ?
          <ProvenancePage address={current.address} structure={structure.data!.data} run={run.data!.data} data={data} caseId={caseId!} navigate={navigate} /> :
          current.page === 'parameters' ? <ParametersPage caseId={caseId!} structure={structure.data!.data} workspace={workspace.data} data={data} compareSet={current.compare} navigate={navigate} /> :
          current.page === 'diagnostics' ? <DiagnosticsPage caseId={caseId!} structure={structure.data!.data} data={data} navigate={navigate} /> :
          current.page === 'export' ? <ExportPage caseId={caseId!} data={data} layouts={workspace.data?.layouts} /> :
          <section className="sheet empty-view"><h1>{t('unavailable', lang)}</h1></section>}
      </main>
    </div>
  </div>
}

function Brand() { return <span className="brand"><svg width="30" height="18" viewBox="0 0 30 18" aria-hidden="true"><path d="M1 11h28" stroke="currentColor" strokeWidth="2.4"/><path d="M15 2.5v5" stroke="var(--siena)" strokeWidth="2"/><circle cx="6" cy="11" r="3.4" fill="currentColor"/><circle cx="15" cy="11" r="3.4" fill="var(--bar)" stroke="currentColor" strokeWidth="2"/><circle cx="24" cy="11" r="3.4" fill="currentColor"/></svg><span>Mantra</span></span> }

function AppBar({ cases, selectedCase, structure, run, paper, revision, navigate, caseId }: { cases: CaseSummary[]; selectedCase?: CaseSummary; structure?: Structure; run?: Run; paper?: Paper; revision?: string; navigate: (path: string) => void; caseId: string }) {
  const errors = run?.diagnostics.filter(item => item.severity === 'error').length ?? 0
  return <header className="app-bar"><Link href={`${casePath(caseId)}/overview`} navigate={navigate} className="brand-link"><Brand /></Link><span className="bar-divider" />
    <label className="case-switch"><span className="sr-only">{t('chooseCase', lang)}</span><select value={caseId} onChange={event => navigate(`${casePath(event.target.value)}/overview`)}>{cases.map(item => <option key={item.id} value={item.id}>{item.title || item.id}</option>)}</select><small>{selectedCase?.period ?? structure?.period ?? selectedCase?.id}</small></label>
    <div className="bar-tags"><span className="tag blue-tag">{structure?.title ?? structure?.schema} {structure?.schemaVersion ? `· v${structure.schemaVersion}` : ''}</span></div>
    <div className="bar-spacer" /><label className="language-switch"><span className="sr-only">Language</span><select value={lang} onChange={event => chooseLanguage(event.target.value as 'de' | 'en')}><option value="de">DE</option><option value="en">EN</option></select></label><span className="bar-status" title={revision}>{run ? `${t('statusReady', lang)} · ${errors} ${t('statusError', lang)}` : '…'}</span>
    <span className="bar-result">{paper?.headline?.value ?? (run && structure ? nodeValue(run, headlineNode(structure)) : '')}</span>
    <Link href={`${casePath(caseId)}/export`} navigate={navigate} className="primary-button">{t('export', lang)}</Link>
  </header>
}

function Sidebar({ structure, run, caseId, activePanel, activePage, navigate }: { structure: Structure; run: Run; caseId: string; activePanel?: string; activePage: Route['page']; navigate: (path: string) => void }) {
  const panels = structure.panels
  const branches = panels.filter(panel => panel.role === 'branch')
  const auxiliary = panels.filter(panel => panel.role === 'auxiliary')
  const sideLinks: Array<[string, string]> = [
    [t('inputs', lang), 'inputs'], [t('parameters', lang), 'parameters'], [t('sources', lang), 'sources'], [t('extensions', lang), 'extensions'], [t('diagnostics', lang), 'diagnostics'], [t('export', lang), 'export'],
  ]
  return <nav className="sidebar" aria-label="Case navigation">
    <Link href={`${casePath(caseId)}/overview`} navigate={navigate} className={`sidebar-overview ${activePage === 'overview' ? 'active' : ''}`}>{t('overview', lang)}</Link>
    <div className="nav-group"><h2>{t('mainline', lang)}</h2><div className="rail-list">{structure.mainline.map(step => {
      const related = branches.filter(panel => panel.entries.some(entry => entry.step === step.step) && Math.min(...panel.entries.map(entry => entry.step)) === step.step)
      return <div key={step.panel}><Link href={panelLink(caseId, step.panel)} navigate={navigate} current={activePanel === step.panel} className="rail-step"><span className="step-dot">{step.step}</span><span>{step.title}</span><b>{nodeValue(run, step.result)}</b></Link>{related.map(panel => <Link key={panel.id} href={panelLink(caseId, panel.id)} navigate={navigate} current={activePanel === panel.id} className="rail-branch"><span className="branch-glyph">└</span><span>{panel.title}</span><b>{panel.entries.length > 1 ? `→ ${panel.entries.map(entry => entry.step).join(' · ')}` : nodeValue(run, panel.result)}</b></Link>)}</div>
    })}</div></div>
    {!!auxiliary.length && <div className="nav-group"><h2>{t('auxiliary', lang)}</h2>{auxiliary.map(panel => <Link key={panel.id} href={panelLink(caseId, panel.id)} navigate={navigate} current={activePanel === panel.id} className="rail-aux"><span className="aux-dot" /><span>{panel.title}</span><b>{nodeValue(run, panel.result)}</b></Link>)}</div>}
    <div className="nav-group workspace-nav"><h2>{t('workspace', lang)}</h2>{sideLinks.map(([label, suffix]) => <Link key={suffix} href={`${casePath(caseId)}/${suffix}`} navigate={navigate} current={activePage === suffix} className="workspace-link">{label}</Link>)}</div>
    <div className="sidebar-foot mono">{structure.schema}{structure.schemaVersion ? ` · v${structure.schemaVersion}` : ''}</div>
  </nav>
}

function MainlineMap({ structure, run, caseId, navigate, highlighted = [] }: { structure: Structure; run: Run; caseId: string; navigate: (path: string) => void; highlighted?: number[] }) {
  const branches = structure.panels.filter(panel => panel.role === 'branch')
  const auxiliary = structure.panels.filter(panel => panel.role === 'auxiliary')
  return <div className="mainline-map"><div className="map-steps">{structure.mainline.map(step => <div className="map-station" key={step.panel}><Link href={panelLink(caseId, step.panel)} navigate={navigate} className={`station-dot ${highlighted.includes(step.step) ? 'emphasized' : ''}`}>{step.step}</Link><div className="eyebrow">{t('step', lang)} {step.step}</div><Link href={panelLink(caseId, step.panel)} navigate={navigate} className="station-title">{step.title}</Link><strong className="station-value">{nodeValue(run, step.result) ?? t('noResult', lang)}</strong></div>)}</div>
    <div className="map-branches">{branches.map(panel => <Link key={panel.id} href={panelLink(caseId, panel.id)} navigate={navigate} className="branch-card"><span className="branch-mark">↳</span><span>{panel.title}<small>→ {panel.entries.map(entry => entry.step).join(' · ')}</small></span><b>{nodeValue(run, panel.result)}</b></Link>)}</div>
    {!!auxiliary.length && <div className="map-aux">{auxiliary.map(panel => <Link key={panel.id} href={panelLink(caseId, panel.id)} navigate={navigate} className="aux-card"><span className="aux-dot" />{panel.title}<b>{nodeValue(run, panel.result)}</b></Link>)}</div>}
  </div>
}

function Overview({ structure, run, paper, caseId, navigate }: { structure: Structure; run: Run; paper?: Paper; caseId: string; navigate: (path: string) => void }) {
  const headline = headlineNode(structure)
  const candidate = paper?.headline
  const paperHeadline = candidate?.node === headline ? candidate : null
  return <><div className="page-heading"><span className="eyebrow">{t('overview', lang)}</span><h1>{structure.title}</h1><p>{structure.period ?? structure.schema}</p></div>
    <section className="sheet map-sheet"><div className="section-heading"><div><span className="eyebrow">{t('mainline', lang)}</span><h2>{t('overview', lang)}</h2></div><span className="muted">{structure.mainline.length} {t('step', lang).toLowerCase()}</span></div><MainlineMap structure={structure} run={run} caseId={caseId} navigate={navigate} /></section>
    <div className="overview-cards"><section className="sheet result-card"><span className="eyebrow">{t('result', lang)}</span><h2>{paperHeadline?.label ?? structure.nodes?.[headline ?? '']?.label ?? structure.mainline.at(-1)?.title}</h2><strong>{paperHeadline?.value ?? nodeValue(run, headline) ?? '—'}</strong></section><section className="sheet info-card"><span className="eyebrow">{t('diagnostics', lang)}</span><h2>{t('statusReady', lang)}</h2><p>{run.diagnostics.filter(item => item.severity === 'error').length} {t('statusError', lang)}</p></section><section className="sheet info-card"><span className="eyebrow">{t('paper', lang)}</span><h2>{structure.schema}</h2><p>{structure.schemaVersion ? `v${structure.schemaVersion}` : ''}</p></section></div>
  </>
}

function Compass({ structure, run, panel, caseId, navigate }: { structure: Structure; run: Run; panel: Panel; caseId: string; navigate: (path: string) => void }) {
  const active = panel.entries.map(entry => entry.step)
  return <section className="sheet compass" aria-label={t('mainline', lang)}><div><span className="eyebrow">{t('mainline', lang)}</span><p>{panel.role === 'mainline' ? t('step', lang) : t('flowsInto', lang)}</p></div><div className="compass-steps">{structure.mainline.map(step => <Link key={step.step} href={panelLink(caseId, step.panel)} navigate={navigate} className={`compass-step ${active.includes(step.step) ? 'on-path' : ''}`}><span className="step-dot">{step.step}</span><span>{step.title}<small>{nodeValue(run, step.result)}</small></span></Link>)}</div>{panel.entries.length > 0 && <div className="entry-chip"><span className="eyebrow">{t('flowsInto', lang)}</span>{panel.entries.map(entry => <span key={`${entry.step}-${entry.via}`}>{t('step', lang)} {entry.step} · {entry.viaLabel}</span>)}</div>}</section>
}

function Breadcrumb({ panel, caseId, navigate }: { panel: Panel; caseId: string; navigate: (path: string) => void }) { return <nav className="breadcrumb" aria-label="Breadcrumb">{panel.breadcrumb.map((crumb, index) => <span key={index}>{index > 0 && <span aria-hidden="true">›</span>}{crumb.panel ? <Link href={panelLink(caseId, crumb.panel)} navigate={navigate}>{crumb.label}</Link> : crumb.kind === 'mainline' ? <Link href={`${casePath(caseId)}/overview`} navigate={navigate}>{t('mainline', lang)}</Link> : <span>{crumb.label}</span>}</span>)}</nav> }

function PanelPage({ structure, run, paper, panelId, selected, caseId, data, navigate }: { structure: Structure; run: Run; paper?: Paper; panelId?: string; selected?: Address; caseId: string; data: WorkbenchData; navigate: (path: string) => void }) {
  const panel = structure.panels.find(item => item.id === panelId)
  const [focused, setFocused] = useState<Address | undefined>(selected)
  useEffect(() => setFocused(selected), [selected?.node, JSON.stringify(selected?.coord), JSON.stringify(selected?.cell)])
  const explanation = useLoad(signal => focused ? data.explain(caseId, focused, signal) : Promise.reject(new Error('No selection')), [data, caseId, focused && addressKey(focused)])
  const choiceNode = panel?.nodes.find(id => structure.nodes?.[id]?.kind === 'choice') ?? (panel?.result && structure.nodes?.[panel.result]?.kind === 'choice' ? panel.result : undefined)
  const choice = useLoad(signal => choiceNode ? data.explain(caseId, { node: choiceNode }, signal) : Promise.reject(new Error('No choice')), [data, caseId, choiceNode])
  if (!panel) return <section className="sheet empty-view">{t('unavailable', lang)}</section>
  const table = paper?.tables.find(item => item.id === panel.id)
  const audit = auditForCell(paper, table?.rows, focused)
  function select(address: Address) { setFocused(address); const url = new URL(location.href); url.searchParams.set('cell', addressToPath(address)); history.replaceState(null, '', url) }
  return <><Compass structure={structure} run={run} panel={panel} caseId={caseId} navigate={navigate} /><Breadcrumb panel={panel} caseId={caseId} navigate={navigate} />
    <div className="panel-heading"><div><span className={`role-badge ${panel.role}`}>{panel.role}</span><h1>{panel.title}</h1></div><strong>{nodeValue(run, panel.result)}</strong></div>
    {(!!choice.data?.data.options.length || !!table?.rows.some(row => row.kind.toLowerCase() === 'option' && row.node === choiceNode)) && <ChoiceComparison explain={choice.data?.data} table={table} choiceNode={choiceNode} panel={panel} structure={structure} run={run} />}
    <div className="panel-columns"><section className="sheet table-sheet"><div className="section-heading"><div><span className="eyebrow">{t('paper', lang)}</span><h2>{table?.title ?? panel.title}</h2></div></div>
      {table ? <PanelTable table={table} selected={focused} onSelect={select} /> : <p className="muted">{t('noPaper', lang)}</p>}</section>
      <Inspector selected={focused} explain={explanation.data?.data} audit={audit} error={explanation.error} caseId={caseId} navigate={navigate} /></div>
    {!!panel.exports.length && <section className="sheet used-in"><span className="eyebrow">{t('usedIn', lang)}</span><div>{panel.exports.map((flow, i) => <Link key={`${flow.toPanel}-${flow.toNode}-${i}`} href={panelLink(caseId, flow.toPanel)} navigate={navigate}>{structure.panels.find(item => item.id === flow.toPanel)?.title ?? flow.toPanel}<span>→</span>{structure.nodes?.[flow.toNode]?.label ?? flow.toNode}</Link>)}</div></section>}
  </>
}

function PanelTable({ table, selected, onSelect }: { table: Paper['tables'][number]; selected?: Address; onSelect: (address: Address) => void }) {
  const visible = table.rows
  const cells = visible.flatMap((row, rowIndex) => row.cells.map((cell, columnIndex) => ({ rowIndex, columnIndex, address: cell.address })).filter(item => !!item.address))
  function onKey(event: React.KeyboardEvent<HTMLTableElement>) {
    if (!['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return
    const active = event.target as HTMLElement
    const row = Number(active.dataset.row), col = Number(active.dataset.col)
    const next = event.key === 'ArrowDown' ? cells.find(item => item.columnIndex === col && item.rowIndex > row) : event.key === 'ArrowUp' ? cells.findLast(item => item.columnIndex === col && item.rowIndex < row) : event.key === 'ArrowRight' ? cells.find(item => item.rowIndex === row && item.columnIndex > col) : [...cells].reverse().find(item => item.rowIndex === row && item.columnIndex < col)
    if (!next) return
    event.preventDefault()
    const target = event.currentTarget.querySelector<HTMLButtonElement>(`button[data-row="${next.rowIndex}"][data-col="${next.columnIndex}"]`)
    target?.focus(); if (next.address) onSelect(next.address)
  }
  return <div className="table-scroll"><table className="paper-table" onKeyDown={onKey}><thead><tr>{table.columns.map(column => <th key={column.id} scope="col">{column.header}</th>)}</tr></thead><tbody>{visible.map((row, rowIndex) => <tr key={row.anchor ?? rowIndex} className={`row-${row.kind.toLowerCase()} ${row.flags?.map(flag => `flag-${flag.toLowerCase()}`).join(' ') ?? ''}`}>{row.cells.map((cell, columnIndex) => <td key={columnIndex} className={`${cell.editable ? 'editable-cell' : ''} tone-${cell.style?.tone ?? 'default'} fill-${cell.style?.fill ?? 'none'}`} style={{ paddingLeft: columnIndex === 0 ? `${12 + Math.min(row.depth, 6) * 12}px` : undefined, fontWeight: cell.style?.weight === 'bold' ? 600 : undefined }}>{cell.address ? <button type="button" data-row={rowIndex} data-col={columnIndex} className={`cell-button ${selected && addressKey(selected) === addressKey(cell.address) ? 'selected' : ''}`} onClick={() => onSelect(cell.address!)} aria-label={`${table.columns[columnIndex]?.header ?? ''}: ${cell.text}`}>{cell.text || ' '}</button> : cell.text}</td>)}</tr>)}</tbody></table></div>
}

function Inspector({ selected, explain, audit, error, caseId, navigate }: { selected?: Address; explain?: Explain; audit?: Paper['audit'][number]; error?: Error; caseId: string; navigate: (path: string) => void }) {
  return <aside className="sheet inspector"><span className="eyebrow">{t('calculation', lang)}</span>{!selected ? <p className="muted">{t('inspect', lang)}</p> : <><h2>{explain?.label ?? audit?.label ?? selected.node}</h2><strong className="inspector-value">{explain?.result.display ?? audit?.result}</strong>{explain?.formula?.text || audit?.formula ? <pre>{explain?.formula?.text ?? audit?.formula}</pre> : null}{audit?.working && !explain && <p>{audit.working}</p>}{explain?.steps.map((step, i) => <div className="calculation-step" key={i}><code>{step.text}</code><b>{step.display}</b></div>)}{explain?.references.map((ref, i) => <div className="reference-item" key={i}><span>{ref.label}</span><b>{ref.display}</b></div>)}{error && !audit && <p className="muted">{error.message}</p>}<Link href={`${casePath(caseId)}/provenance/${encodeURIComponent(addressToPath(selected))}`} navigate={navigate} className="text-link">{t('provenance', lang)} ↗</Link></>}</aside>
}

function ChoiceComparison({ explain, table, choiceNode, panel, structure, run }: { explain?: Explain; table?: Paper['tables'][number]; choiceNode?: string; panel: Panel; structure: Structure; run: Run }) {
  const options = explain?.options.length ? explain.options : (table?.rows.filter(row => row.kind.toLowerCase() === 'option' && row.node === choiceNode).map(row => {
    const cells = row.cells.map(cell => cell.text.trim()).filter(Boolean)
    const labelIndex = table.columns.findIndex(column => column.id === 'label')
    return { key: row.optionKey, label: labelIndex >= 0 ? row.cells[labelIndex]?.text : cells[0], display: row.cells.find(cell => cell.address && cell.text.trim())?.text ?? cells.at(-1), selected: row.flags?.some(flag => flag.toLowerCase() === 'selected') }
  }) ?? [])
  return <section className="sheet choice"><h2>{t('comparison', lang)}</h2><div className="choice-options">{options.map((option, i) => <div className={`choice-option ${option.selected ? 'chosen' : ''}`} key={option.key ?? i}><span>{option.selected ? '✓ ' : ''}{option.label ?? option.key}</span><strong>{option.display}</strong>{'differenceDisplay' in option && option.differenceDisplay && <small>{option.differenceDisplay}</small>}</div>)}</div><h3>{t('effect', lang)}</h3><div className="choice-effects">{panel.entries.map(entry => <span key={`${entry.step}-${entry.via}`}>{t('step', lang)} {entry.step} · {entry.viaLabel} <b>{nodeValue(run, entry.via) ?? structure.mainline.find(step => step.step === entry.step)?.title}</b></span>)}</div></section>
}

function ProvenancePage({ address, structure, run, data, caseId, navigate }: { address: Address; structure: Structure; run: Run; data: WorkbenchData; caseId: string; navigate: (path: string) => void }) {
  const root = useLoad(signal => data.explain(caseId, address, signal), [data, caseId, addressKey(address)])
  const panel = structure.panels.find(item => item.result === address.node || item.nodes.includes(address.node))
  return <><Breadcrumb panel={panel ?? { breadcrumb: [{ kind: 'mainline' }, { label: address.node }] } as Panel} caseId={caseId} navigate={navigate} /><div className="page-heading"><span className="eyebrow">{t('provenance', lang)}</span><h1>{root.data?.data.label ?? structure.nodes?.[address.node]?.label ?? address.node}</h1><p>{nodeValue(run, address.node, address.coord?.join('/') ?? '')}</p></div><div className="provenance-layout"><section className="sheet"><h2>{t('sourceTree', lang)}</h2><ProvenanceNode address={address} caseId={caseId} data={data} depth={0} /></section><aside className="sheet"><h2>{t('intermediate', lang)}</h2>{root.data?.data.steps.map((step, i) => <div className="calculation-step" key={i}><code>{step.text}</code><b>{step.display}</b></div>)}{root.error && <p>{root.error.message}</p>}</aside></div></>
}

function ProvenanceNode({ address, caseId, data, depth }: { address: Address; caseId: string; data: WorkbenchData; depth: number }) {
  const [open, setOpen] = useState(depth === 0)
  const [continued, setContinued] = useState(false)
  const explain = useLoad(signal => open ? data.explain(caseId, address, signal) : Promise.reject(new Error('Closed')), [data, caseId, addressKey(address), open])
  const references = explain.data?.data.references ?? []
  return <div className="tree-node" style={{ marginLeft: Math.min(depth, 5) * 18 }}><button type="button" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? '▾' : '▸'} {explain.data?.data.label ?? address.node}<strong>{explain.data?.data.result.display}</strong></button>{open && explain.error && <p className="muted">{t('unavailable', lang)}</p>}{open && depth >= 5 && references.length > 0 && !continued && <button type="button" className="continue-tree" onClick={() => setContinued(true)}>{t('continueTree', lang)}</button>}{open && (depth < 5 || continued) && references.map((ref, i) => <ProvenanceNode key={`${addressKey(ref.address)}-${i}`} address={ref.address} caseId={caseId} data={data} depth={depth + 1} />)}{open && explain.data?.data.truncated && <p className="muted">{t('traceTruncated', lang)}</p>}</div>
}
