import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import type { Address, CaseSummary, Compare, Explain, Paper, Panel, Run, Structure } from '../types'
import type { WorkbenchData } from '../data'
import { configuredData, WorkbenchReadError } from '../data'
import { addressFromPath, addressKey, addressToPath, casePath, provenancePath } from '../address'
import { chooseLanguage, language, t } from '../i18n'
import { auditForCell, nodeValue } from '../viewModel'
import { DiagnosticsPage, ParametersPage } from './ReadOnlyPages'
import { ExportPage } from './ExportPage'
import { InputsPage } from './InputsPage'
import { SourcesPage } from './SourcesPage'
import { ExplainDetails } from './ExplainDetails'
import { CaseChainEvidence } from './CaseChainEvidence'
import { ChoiceComparison } from './ChoiceComparison'
import { AggregateEvidence } from './AggregateEvidence'
import { ValidationEvidence } from './ValidationEvidence'
import type { Validation } from './ValidationEvidence'
const ExtensionsPage = lazy(() => import('./AuthoringPages').then((module) => ({ default: module.ExtensionsPage })))
const FormulaSlotCard = lazy(() => import('./AuthoringPages').then((module) => ({ default: module.FormulaSlotCard })))

type Route = {
  caseId?: string
  page:
    | 'overview'
    | 'panel'
    | 'provenance'
    | 'inputs'
    | 'parameters'
    | 'sources'
    | 'diagnostics'
    | 'extensions'
    | 'export'
    | 'other'
  panelId?: string
  groupId?: string
  address?: Address
  expectedRevision?: string
  compare?: string
}
const lang = language()

function route(): Route {
  const parts = location.pathname.split('/').filter(Boolean)
  if (parts[0] !== 'cases' || !parts[1]) return { page: 'overview' }
  let caseId: string
  try {
    caseId = decodeURIComponent(parts[1])
  } catch {
    return { page: 'overview' }
  }
  if (parts[2] === 'panels' && parts[3])
    return {
      caseId,
      page: 'panel',
      panelId: decodeURIComponent(parts[3]),
      address:
        addressFromPath(
          new URLSearchParams(location.search).get('cell') ?? '',
          new URLSearchParams(location.search).get('case'),
        ) ?? undefined,
    }
  if (parts[2] === 'provenance' && parts[3])
    return {
      caseId,
      page: 'provenance',
      address:
        addressFromPath(
          decodeURIComponent(parts.slice(3).join('/')),
          new URLSearchParams(location.search).get('case'),
        ) ?? undefined,
      expectedRevision: new URLSearchParams(location.search).get('expectedRevision') ?? undefined,
    }
  if (parts[2] === 'overview') return { caseId, page: 'overview' }
  if (parts[2] === 'inputs')
    return {
      caseId,
      page: 'inputs',
      groupId: parts[3] ? decodeURIComponent(parts[3]) : undefined,
      address: addressFromPath(new URLSearchParams(location.search).get('cell') ?? '') ?? undefined,
    }
  if (parts[2] === 'parameters')
    return { caseId, page: 'parameters', compare: new URLSearchParams(location.search).get('compare') ?? undefined }
  if (parts[2] === 'sources') return { caseId, page: 'sources' }
  if (parts[2] === 'diagnostics') return { caseId, page: 'diagnostics' }
  if (parts[2] === 'extensions') return { caseId, page: 'extensions' }
  if (parts[2] === 'export') return { caseId, page: 'export' }
  return { caseId, page: 'other' }
}

function useRoute() {
  const [current, setCurrent] = useState(route)
  useEffect(() => {
    const onPop = () => setCurrent(route())
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])
  const navigate = (path: string) => {
    history.pushState(null, '', path)
    setCurrent(route())
  }
  const replace = (path: string) => {
    history.replaceState(null, '', path)
    setCurrent(route())
  }
  return [current, navigate, replace] as const
}

function useLoad<T>(
  load: (signal: AbortSignal) => Promise<T>,
  keys: unknown[],
): { data?: T; error?: Error; loading: boolean } {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true })
  useEffect(() => {
    const controller = new AbortController()
    setState({ loading: true })
    load(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setState({ data, loading: false })
      })
      .catch((error) => {
        if (!controller.signal.aborted) setState({ error, loading: false })
      })
    return () => controller.abort()
    // The caller supplies primitive dependency keys for stable reloads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, keys)
  return state
}

function headlineNode(structure: Structure) {
  return structure.headline ?? structure.mainline.at(-1)?.result
}
function panelLink(caseId: string, id: string) {
  return `${casePath(caseId)}/panels/${encodeURIComponent(id)}`
}

function Link({
  href,
  navigate,
  children,
  className,
  current,
}: {
  href: string
  navigate: (path: string) => void
  children: React.ReactNode
  className?: string
  current?: boolean
}) {
  return (
    <a
      href={href}
      className={className}
      aria-current={current ? 'page' : undefined}
      onClick={(event) => {
        if (event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
          event.preventDefault()
          navigate(href)
        }
      }}
    >
      {children}
    </a>
  )
}

export function App() {
  const [current, navigate, replace] = useRoute()
  const [refresh, setRefresh] = useState(0)
  const [editEffect, setEditEffect] = useState<Compare | undefined>()
  useEffect(() => setEditEffect(undefined), [current.caseId, current.groupId])
  const data = useMemo(configuredData, [])
  const [streamError, setStreamError] = useState<string>()
  useEffect(() => {
    if (import.meta.env.VITE_WORKBENCH_MODE !== 'live') return
    const source = new EventSource('/api/v1/events')
    let revision: string | undefined
    let hadError = false
    const onRevision = (event: MessageEvent) => {
      const next = (JSON.parse(event.data) as { revision: string }).revision
      if (hadError || (revision && revision !== next)) setRefresh((value) => value + 1)
      setStreamError(undefined)
      revision = next
      hadError = false
    }
    const onChanged = (event: MessageEvent) => {
      revision = (JSON.parse(event.data) as { revision: string }).revision
      setStreamError(undefined)
      hadError = false
      setRefresh((value) => value + 1)
    }
    const onWorkspaceError = (event: MessageEvent) => {
      const error = JSON.parse(event.data) as { message: string }
      hadError = true
      setStreamError(error.message)
      setRefresh((value) => value + 1)
    }
    source.addEventListener('revision', onRevision)
    source.addEventListener('documentChanged', onChanged)
    source.addEventListener('workspaceError', onWorkspaceError)
    return () => source.close()
  }, [])
  const workspace = useLoad((signal) => data.workspace(signal), [data, refresh])
  const caseId = current.caseId ?? workspace.data?.cases[0]?.id
  const structure = useLoad(
    (signal) => (caseId ? data.structure(caseId, signal) : Promise.reject(new Error('No case'))),
    [data, caseId, refresh],
  )
  const run = useLoad(
    (signal) => (caseId ? data.run(caseId, signal) : Promise.reject(new Error('No case'))),
    [data, caseId, refresh],
  )
  const paper = useLoad(
    (signal) =>
      caseId
        ? data.paper(caseId, current.page === 'panel' ? current.panelId : undefined, signal)
        : Promise.reject(new Error('No case')),
    [data, caseId, current.page, current.panelId, refresh],
  )
  const selectedCase = workspace.data?.cases.find((item) => item.id === caseId)
  const failure =
    workspace.error ?? structure.error ?? run.error ?? paper.error ?? (streamError ? new Error(streamError) : undefined)
  const ready = structure.data && run.data
  if (!workspace.data && workspace.loading)
    return (
      <div className="loading-shell" role="status">
        Mantra · {t('paper', lang)}…
      </div>
    )
  if (!workspace.data?.cases.length)
    return (
      <div className="empty-start">
        <Brand />
        <h1>{t('noFixtures', lang)}</h1>
        <p>{workspace.error?.message}</p>
        <button onClick={() => location.reload()}>{t('retry', lang)}</button>
      </div>
    )
  return (
    <div className="app-shell">
      <AppBar
        cases={workspace.data.cases}
        selectedCase={selectedCase}
        structure={structure.data?.data}
        run={run.data?.data}
        paper={paper.data?.data}
        revision={run.data?.revision}
        navigate={navigate}
        caseId={caseId!}
      />
      <div className="body-shell">
        {ready && (
          <Sidebar
            structure={structure.data!.data}
            run={run.data!.data}
            caseId={caseId!}
            activePanel={current.panelId}
            activePage={current.page}
            navigate={navigate}
          />
        )}
        <main className="content" key={refresh}>
          {failure && (
            <div className="error-banner" role="alert">
              <strong>{failure.message}</strong>
              <Link href={`${casePath(caseId!)}/sources`} navigate={navigate}>
                {t('sources', lang)}
              </Link>
              <button onClick={() => location.reload()}>{t('retry', lang)}</button>
            </div>
          )}
          {current.page === 'sources' ? (
            <SourcesPage
              caseId={caseId!}
              structure={structure.data?.data}
              revision={run.data?.revision}
              data={data}
              onSaved={() => setRefresh((value) => value + 1)}
            />
          ) : !ready ? (
            <div className="skeleton" role="status" aria-label="Loading" />
          ) : current.page === 'overview' ? (
            <Overview
              structure={structure.data!.data}
              run={run.data!.data}
              paper={paper.data?.data}
              caseId={caseId!}
              navigate={navigate}
            />
          ) : current.page === 'panel' ? (
            <PanelPage
              structure={structure.data!.data}
              run={run.data!.data}
              paper={paper.data?.data}
              panelId={current.panelId}
              selected={current.address}
              caseId={caseId!}
              revision={run.data!.revision}
              data={data}
              onSaved={() => setRefresh((value) => value + 1)}
              navigate={navigate}
              replaceRoute={replace}
            />
          ) : current.page === 'provenance' && current.address ? (
            <ProvenancePage
              address={current.address}
              expectedRevision={current.expectedRevision}
              structure={structure.data!.data}
              run={run.data!.data}
              data={data}
              caseId={caseId!}
              navigate={navigate}
            />
          ) : current.page === 'inputs' ? (
            <InputsPage
              caseId={caseId!}
              groupId={current.groupId}
              selectedAddress={current.address}
              structure={structure.data!.data}
              run={run.data!.data}
              revision={run.data!.revision}
              data={data}
              effect={editEffect}
              onSaved={(difference) => {
                setEditEffect(difference)
                setRefresh((value) => value + 1)
              }}
              navigate={navigate}
            />
          ) : current.page === 'parameters' ? (
            <ParametersPage
              caseId={caseId!}
              structure={structure.data!.data}
              workspace={workspace.data}
              data={data}
              compareSet={current.compare}
              refresh={refresh}
              revision={run.data!.revision}
              onSaved={() => setRefresh((value) => value + 1)}
              navigate={navigate}
            />
          ) : current.page === 'diagnostics' ? (
            <DiagnosticsPage caseId={caseId!} structure={structure.data!.data} data={data} navigate={navigate} />
          ) : current.page === 'extensions' ? (
            <Suspense fallback={<div className="skeleton" role="status" aria-label="Loading editor" />}>
              <ExtensionsPage
                caseId={caseId!}
                structure={structure.data!.data}
                revision={run.data!.revision}
                data={data}
                onSaved={() => setRefresh((value) => value + 1)}
              />
            </Suspense>
          ) : current.page === 'export' ? (
            <ExportPage caseId={caseId!} data={data} layouts={workspace.data?.layouts} />
          ) : (
            <section className="sheet empty-view">
              <h1>{t('unavailable', lang)}</h1>
            </section>
          )}
        </main>
      </div>
    </div>
  )
}

function Brand() {
  return (
    <span className="brand">
      <svg width="30" height="18" viewBox="0 0 30 18" aria-hidden="true">
        <path d="M1 11h28" stroke="currentColor" strokeWidth="2.4" />
        <path d="M15 2.5v5" stroke="var(--siena)" strokeWidth="2" />
        <circle cx="6" cy="11" r="3.4" fill="currentColor" />
        <circle cx="15" cy="11" r="3.4" fill="var(--bar)" stroke="currentColor" strokeWidth="2" />
        <circle cx="24" cy="11" r="3.4" fill="currentColor" />
      </svg>
      <span>Mantra</span>
    </span>
  )
}

function AppBar({
  cases,
  selectedCase,
  structure,
  run,
  paper,
  revision,
  navigate,
  caseId,
}: {
  cases: CaseSummary[]
  selectedCase?: CaseSummary
  structure?: Structure
  run?: Run
  paper?: Paper
  revision?: string
  navigate: (path: string) => void
  caseId: string
}) {
  const errors =
    run?.diagnostics.filter((item) => item.severity === 'error' && item.category !== 'business').length ?? 0
  const businessErrors =
    run?.diagnostics.filter((item) => item.severity === 'error' && item.category === 'business').length ?? 0
  return (
    <header className="app-bar">
      <Link href={`${casePath(caseId)}/overview`} navigate={navigate} className="brand-link">
        <Brand />
      </Link>
      <span className="bar-divider" />
      <label className="case-switch">
        <span className="sr-only">{t('chooseCase', lang)}</span>
        <select value={caseId} onChange={(event) => navigate(`${casePath(event.target.value)}/overview`)}>
          {cases.map((item) => (
            <option key={item.id} value={item.id}>
              {item.title || item.id}
            </option>
          ))}
        </select>
        <small>{selectedCase?.period ?? structure?.period ?? selectedCase?.id}</small>
      </label>
      <div className="bar-tags">
        <span className="tag blue-tag">
          {structure?.title ?? structure?.schema} {structure?.schemaVersion ? `· v${structure.schemaVersion}` : ''}
        </span>
      </div>
      <div className="bar-spacer" />
      <label className="language-switch">
        <span className="sr-only">Language</span>
        <select value={lang} onChange={(event) => chooseLanguage(event.target.value as 'de' | 'en')}>
          <option value="de">DE</option>
          <option value="en">EN</option>
        </select>
      </label>
      <span className="bar-status" title={revision}>
        {run
          ? `${run.succeeded ? t('statusReady', lang) : lang === 'de' ? 'Berechnungsfehler' : 'Calculation error'} · ${errors} ${t('statusError', lang)}`
          : '…'}
        {businessErrors > 0 && (
          <small>
            {' '}
            · {businessErrors} {lang === 'de' ? 'fachliche Befunde' : 'business findings'}
          </small>
        )}
      </span>
      <span className="bar-result">
        {paper?.headline?.value ?? (run && structure ? nodeValue(run, headlineNode(structure)) : '')}
      </span>
      <Link href={`${casePath(caseId)}/export`} navigate={navigate} className="primary-button">
        {t('export', lang)}
      </Link>
    </header>
  )
}

function Sidebar({
  structure,
  run,
  caseId,
  activePanel,
  activePage,
  navigate,
}: {
  structure: Structure
  run: Run
  caseId: string
  activePanel?: string
  activePage: Route['page']
  navigate: (path: string) => void
}) {
  const panels = structure.panels
  const branches = panels.filter((panel) => panel.role === 'branch')
  const auxiliary = panels.filter((panel) => panel.role === 'auxiliary')
  const sideLinks: Array<[string, string]> = [
    [t('inputs', lang), 'inputs'],
    [t('parameters', lang), 'parameters'],
    [t('sources', lang), 'sources'],
    [t('extensions', lang), 'extensions'],
    [t('diagnostics', lang), 'diagnostics'],
    [t('export', lang), 'export'],
  ]
  return (
    <nav className="sidebar" aria-label="Case navigation">
      <Link
        href={`${casePath(caseId)}/overview`}
        navigate={navigate}
        className={`sidebar-overview ${activePage === 'overview' ? 'active' : ''}`}
      >
        {t('overview', lang)}
      </Link>
      <div className="nav-group">
        <h2>{t('mainline', lang)}</h2>
        <div className="rail-list">
          {structure.mainline.map((step) => {
            const related = branches.filter(
              (panel) =>
                panel.entries.some((entry) => entry.step === step.step) &&
                Math.min(...panel.entries.map((entry) => entry.step)) === step.step,
            )
            return (
              <div key={step.panel}>
                <Link
                  href={panelLink(caseId, step.panel)}
                  navigate={navigate}
                  current={activePanel === step.panel}
                  className="rail-step"
                >
                  <span className="step-dot">{step.step}</span>
                  <span>{step.title}</span>
                  <b>{nodeValue(run, step.result)}</b>
                </Link>
                {related.map((panel) => (
                  <Link
                    key={panel.id}
                    href={panelLink(caseId, panel.id)}
                    navigate={navigate}
                    current={activePanel === panel.id}
                    className="rail-branch"
                  >
                    <span className="branch-glyph">└</span>
                    <span>{panel.title}</span>
                    <b>
                      {panel.entries.length > 1
                        ? `→ ${panel.entries.map((entry) => entry.step).join(' · ')}`
                        : nodeValue(run, panel.result)}
                    </b>
                  </Link>
                ))}
              </div>
            )
          })}
        </div>
      </div>
      {!!auxiliary.length && (
        <div className="nav-group">
          <h2>{t('auxiliary', lang)}</h2>
          {auxiliary.map((panel) => (
            <Link
              key={panel.id}
              href={panelLink(caseId, panel.id)}
              navigate={navigate}
              current={activePanel === panel.id}
              className="rail-aux"
            >
              <span className="aux-dot" />
              <span>{panel.title}</span>
              <b>{nodeValue(run, panel.result)}</b>
            </Link>
          ))}
        </div>
      )}
      <div className="nav-group workspace-nav">
        <h2>{t('workspace', lang)}</h2>
        {sideLinks.map(([label, suffix]) => (
          <Link
            key={suffix}
            href={`${casePath(caseId)}/${suffix}`}
            navigate={navigate}
            current={activePage === suffix}
            className="workspace-link"
          >
            {label}
          </Link>
        ))}
      </div>
      <div className="sidebar-foot mono">
        {structure.schema}
        {structure.schemaVersion ? ` · v${structure.schemaVersion}` : ''}
      </div>
    </nav>
  )
}

function MainlineMap({
  structure,
  run,
  caseId,
  navigate,
  highlighted = [],
}: {
  structure: Structure
  run: Run
  caseId: string
  navigate: (path: string) => void
  highlighted?: number[]
}) {
  const branches = structure.panels.filter((panel) => panel.role === 'branch')
  const auxiliary = structure.panels.filter((panel) => panel.role === 'auxiliary')
  return (
    <div className="mainline-map">
      <div className="map-steps">
        {structure.mainline.map((step) => (
          <div className="map-station" key={step.panel}>
            <Link
              href={panelLink(caseId, step.panel)}
              navigate={navigate}
              className={`station-dot ${highlighted.includes(step.step) ? 'emphasized' : ''}`}
            >
              {step.step}
            </Link>
            <div className="eyebrow">
              {t('step', lang)} {step.step}
            </div>
            <Link href={panelLink(caseId, step.panel)} navigate={navigate} className="station-title">
              {step.title}
            </Link>
            <strong className="station-value">{nodeValue(run, step.result) ?? t('noResult', lang)}</strong>
          </div>
        ))}
      </div>
      <div className="map-branches">
        {branches.map((panel) => (
          <Link key={panel.id} href={panelLink(caseId, panel.id)} navigate={navigate} className="branch-card">
            <span className="branch-mark">↳</span>
            <span>
              {panel.title}
              <small>→ {panel.entries.map((entry) => entry.step).join(' · ')}</small>
            </span>
            <b>{nodeValue(run, panel.result)}</b>
          </Link>
        ))}
      </div>
      {!!auxiliary.length && (
        <div className="map-aux">
          {auxiliary.map((panel) => (
            <Link key={panel.id} href={panelLink(caseId, panel.id)} navigate={navigate} className="aux-card">
              <span className="aux-dot" />
              {panel.title}
              <b>{nodeValue(run, panel.result)}</b>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}

function Overview({
  structure,
  run,
  paper,
  caseId,
  navigate,
}: {
  structure: Structure
  run: Run
  paper?: Paper
  caseId: string
  navigate: (path: string) => void
}) {
  const headline = headlineNode(structure)
  const candidate = paper?.headline
  const paperHeadline = candidate?.node === headline ? candidate : null
  return (
    <>
      <div className="page-heading">
        <span className="eyebrow">{t('overview', lang)}</span>
        <h1>{structure.title}</h1>
        <p>{structure.period ?? structure.schema}</p>
      </div>
      <section className="sheet map-sheet">
        <div className="section-heading">
          <div>
            <span className="eyebrow">{t('mainline', lang)}</span>
            <h2>{t('overview', lang)}</h2>
          </div>
          <span className="muted">
            {structure.mainline.length} {t('step', lang).toLowerCase()}
          </span>
        </div>
        <MainlineMap structure={structure} run={run} caseId={caseId} navigate={navigate} />
      </section>
      <CaseChainEvidence graph={run.caseGraph} caseId={caseId} navigate={navigate} />
      <div className="overview-cards">
        <section className="sheet result-card">
          <span className="eyebrow">{t('result', lang)}</span>
          <h2>
            {paperHeadline?.label ?? structure.nodes?.[headline ?? '']?.label ?? structure.mainline.at(-1)?.title}
          </h2>
          <strong>{paperHeadline?.value ?? nodeValue(run, headline) ?? '—'}</strong>
        </section>
        <section className="sheet info-card">
          <span className="eyebrow">{t('diagnostics', lang)}</span>
          <h2>
            {run.succeeded
              ? t('statusReady', lang)
              : lang === 'de'
                ? 'Berechnung fehlgeschlagen'
                : 'Calculation failed'}
          </h2>
          {!run.validationPassed && (
            <p>{lang === 'de' ? 'Fachliche Prüfungen offen' : 'Business checks need attention'}</p>
          )}
          <p>
            {run.diagnostics.filter((item) => item.severity === 'error').length} {t('statusError', lang)}
          </p>
        </section>
        <section className="sheet info-card">
          <span className="eyebrow">{t('paper', lang)}</span>
          <h2>{structure.schema}</h2>
          <p>{structure.schemaVersion ? `v${structure.schemaVersion}` : ''}</p>
        </section>
      </div>
    </>
  )
}

function Compass({
  structure,
  run,
  panel,
  caseId,
  navigate,
}: {
  structure: Structure
  run: Run
  panel: Panel
  caseId: string
  navigate: (path: string) => void
}) {
  const active = panel.entries.map((entry) => entry.step)
  return (
    <section className="sheet compass" aria-label={t('mainline', lang)}>
      <div>
        <span className="eyebrow">{t('mainline', lang)}</span>
        <p>{panel.role === 'mainline' ? t('step', lang) : t('flowsInto', lang)}</p>
      </div>
      <div className="compass-steps">
        {structure.mainline.map((step) => (
          <Link
            key={step.step}
            href={panelLink(caseId, step.panel)}
            navigate={navigate}
            className={`compass-step ${active.includes(step.step) ? 'on-path' : ''}`}
          >
            <span className="step-dot">{step.step}</span>
            <span>
              {step.title}
              <small>{nodeValue(run, step.result)}</small>
            </span>
          </Link>
        ))}
      </div>
      {panel.entries.length > 0 && (
        <div className="entry-chip">
          <span className="eyebrow">{t('flowsInto', lang)}</span>
          {panel.entries.map((entry) => (
            <span key={`${entry.step}-${entry.via}`}>
              {t('step', lang)} {entry.step} · {entry.viaLabel}
            </span>
          ))}
        </div>
      )}
    </section>
  )
}

function Breadcrumb({ panel, caseId, navigate }: { panel: Panel; caseId: string; navigate: (path: string) => void }) {
  return (
    <nav className="breadcrumb" aria-label="Breadcrumb">
      {panel.breadcrumb.map((crumb, index) => (
        <span key={index}>
          {index > 0 && <span aria-hidden="true">›</span>}
          {crumb.panel ? (
            <Link href={panelLink(caseId, crumb.panel)} navigate={navigate}>
              {crumb.label}
            </Link>
          ) : crumb.kind === 'mainline' ? (
            <Link href={`${casePath(caseId)}/overview`} navigate={navigate}>
              {t('mainline', lang)}
            </Link>
          ) : (
            <span>{crumb.label}</span>
          )}
        </span>
      ))}
    </nav>
  )
}

function PanelPage({
  structure,
  run,
  paper,
  panelId,
  selected,
  caseId,
  revision,
  data,
  onSaved,
  navigate,
  replaceRoute,
}: {
  structure: Structure
  run: Run
  paper?: Paper
  panelId?: string
  selected?: Address
  caseId: string
  revision: string
  data: WorkbenchData
  onSaved: () => void
  navigate: (path: string) => void
  replaceRoute: (path: string) => void
}) {
  const panel = structure.panels.find((item) => item.id === panelId)
  const focused = selected
  const explanation = useLoad(
    (signal) => (focused ? data.explain(caseId, focused, signal) : Promise.reject(new Error('No selection'))),
    [data, caseId, focused && addressKey(focused)],
  )
  const choiceNode =
    panel?.nodes.find((id) => structure.nodes?.[id]?.kind === 'choice') ??
    (panel?.result && structure.nodes?.[panel.result]?.kind === 'choice' ? panel.result : undefined)
  const choice = useLoad(
    (signal) =>
      choiceNode
        ? data.explain(caseId, { case: null, node: choiceNode }, signal)
        : Promise.reject(new Error('No choice')),
    [data, caseId, choiceNode],
  )
  if (!panel) return <section className="sheet empty-view">{t('unavailable', lang)}</section>
  const table = paper?.tables.find((item) => item.id === panel.id)
  const audit = auditForCell(paper, table?.rows, focused)
  function select(address: Address) {
    const url = new URL(location.href)
    url.searchParams.set('cell', addressToPath(address))
    if (address.case) url.searchParams.set('case', address.case)
    else url.searchParams.delete('case')
    url.searchParams.delete('expectedRevision')
    replaceRoute(`${url.pathname}${url.search}${url.hash}`)
  }
  return (
    <>
      <Compass structure={structure} run={run} panel={panel} caseId={caseId} navigate={navigate} />
      <Breadcrumb panel={panel} caseId={caseId} navigate={navigate} />
      <div className="panel-heading">
        <div>
          <span className={`role-badge ${panel.role}`}>{panel.role}</span>
          <h1>{panel.title}</h1>
        </div>
        <strong>{nodeValue(run, panel.result)}</strong>
      </div>
      {(!!choice.data?.data.options.length ||
        !!table?.rows.some((row) => row.kind.toLowerCase() === 'option' && row.node === choiceNode)) && (
        <ChoiceComparison
          explain={choice.data?.data}
          table={table}
          choiceNode={choiceNode}
          panel={panel}
          structure={structure}
          run={run}
        />
      )}
      {structure.formulaSlots
        ?.filter((slot) => slot.panel === panel.id)
        .map((slot) => (
          <Suspense key={slot.id} fallback={<div className="skeleton" role="status" aria-label="Loading editor" />}>
            <FormulaSlotCard slot={slot} caseId={caseId} revision={revision} data={data} onSaved={onSaved} />
          </Suspense>
        ))}
      <div className="panel-columns">
        <section className="sheet table-sheet">
          <div className="section-heading">
            <div>
              <span className="eyebrow">{t('paper', lang)}</span>
              <h2>{table?.title ?? panel.title}</h2>
            </div>
          </div>
          {table ? (
            <PanelTable table={table} selected={focused} onSelect={select} />
          ) : (
            <p className="muted">{t('noPaper', lang)}</p>
          )}
        </section>
        <Inspector
          selected={focused}
          explain={explanation.data?.data}
          audit={audit}
          validation={focused ? run.values[focused.node]?.[focused.coord?.join('/') ?? '']?.validation : undefined}
          error={explanation.error}
          caseId={caseId}
          navigate={navigate}
        />
      </div>
      {!!panel.exports.length && (
        <section className="sheet used-in">
          <span className="eyebrow">{t('usedIn', lang)}</span>
          <div>
            {panel.exports.map((flow, i) => (
              <Link
                key={`${flow.toPanel}-${flow.toNode}-${i}`}
                href={panelLink(caseId, flow.toPanel)}
                navigate={navigate}
              >
                {structure.panels.find((item) => item.id === flow.toPanel)?.title ?? flow.toPanel}
                <span>→</span>
                {structure.nodes?.[flow.toNode]?.label ?? flow.toNode}
              </Link>
            ))}
          </div>
        </section>
      )}
    </>
  )
}

function PanelTable({
  table,
  selected,
  onSelect,
}: {
  table: Paper['tables'][number]
  selected?: Address
  onSelect: (address: Address) => void
}) {
  const visible = table.rows
  const cells = visible.flatMap((row, rowIndex) =>
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
    const target = event.currentTarget.querySelector<HTMLButtonElement>(
      `button[data-row="${next.rowIndex}"][data-col="${next.columnIndex}"]`,
    )
    target?.focus()
    if (next.address) onSelect(next.address)
  }
  return (
    <div className="table-scroll">
      <table className="paper-table" onKeyDown={onKey}>
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
          {visible.map((row, rowIndex) => (
            <tr
              key={row.anchor ?? rowIndex}
              className={`row-${row.kind.toLowerCase()} ${row.flags?.map((flag) => `flag-${flag.toLowerCase()}`).join(' ') ?? ''}`}
            >
              {row.cells.map((cell, columnIndex) => (
                <td
                  key={columnIndex}
                  className={`${cell.editable ? 'editable-cell' : ''} tone-${cell.style?.tone ?? 'default'} fill-${cell.style?.fill ?? 'none'}`}
                  style={{
                    paddingLeft: columnIndex === 0 ? `${12 + Math.min(row.depth, 6) * 12}px` : undefined,
                    fontWeight: cell.style?.weight === 'bold' ? 600 : undefined,
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
  )
}

function Inspector({
  selected,
  explain,
  audit,
  validation,
  error,
  caseId,
  navigate,
}: {
  selected?: Address
  explain?: Explain
  audit?: Paper['audit'][number]
  validation?: Validation | null
  error?: Error
  caseId: string
  navigate: (path: string) => void
}) {
  return (
    <aside className="sheet inspector">
      <span className="eyebrow">{t('calculation', lang)}</span>
      {!selected ? (
        <p className="muted">{t('inspect', lang)}</p>
      ) : (
        <>
          <h2>{explain?.label ?? audit?.label ?? selected.node}</h2>
          <strong className="inspector-value">{explain?.result.display ?? audit?.result}</strong>
          {explain?.formula?.text || audit?.formula ? <pre>{explain?.formula?.text ?? audit?.formula}</pre> : null}
          {audit?.working && !explain && <p>{audit.working}</p>}
          {validation && <ValidationEvidence validation={validation} />}
          {explain && <ExplainDetails explain={explain} rootCaseId={caseId} navigate={navigate} />}
          {!explain && audit?.aggregate && <AggregateEvidence aggregate={audit.aggregate} />}
          {explain?.references.map((ref, i) => (
            <div className="reference-item" key={i}>
              <Link href={provenancePath(caseId, ref.address, ref.revision)} navigate={navigate}>
                {ref.label}
              </Link>
              <b>{ref.display}</b>
            </div>
          ))}
          {error && !audit && <p className="muted">{error.message}</p>}
          <Link href={provenancePath(caseId, selected)} navigate={navigate} className="text-link">
            {t('provenance', lang)} ↗
          </Link>
        </>
      )}
    </aside>
  )
}

function ProvenancePage({
  address,
  expectedRevision,
  structure,
  run,
  data,
  caseId,
  navigate,
}: {
  address: Address
  expectedRevision?: string
  structure: Structure
  run: Run
  data: WorkbenchData
  caseId: string
  navigate: (path: string) => void
}) {
  const root = useLoad(
    (signal) => data.explain(caseId, address, signal, expectedRevision),
    [data, caseId, addressKey(address), expectedRevision],
  )
  const local = !address.case || address.case === caseId || address.case === run.caseGraph?.root
  const revisions = Object.fromEntries(run.caseGraph?.cases.map((entry) => [entry.case, entry.revision]) ?? [])
  const stale = root.error instanceof WorkbenchReadError && root.error.status === 409
  const currentRevision =
    (address.case ? revisions[address.case] : undefined) ??
    (root.error instanceof WorkbenchReadError ? root.error.currentRevision : undefined)
  const panel = local
    ? structure.panels.find((item) => item.result === address.node || item.nodes.includes(address.node))
    : undefined
  return (
    <>
      <Breadcrumb
        panel={panel ?? ({ breadcrumb: [{ kind: 'mainline' }, { label: address.node }] } as Panel)}
        caseId={caseId}
        navigate={navigate}
      />
      <div className="page-heading">
        <span className="eyebrow">{t('provenance', lang)}</span>
        <h1>{root.data?.data.label ?? (local ? structure.nodes?.[address.node]?.label : undefined) ?? address.node}</h1>
        <p>
          {root.data?.data.result.display ??
            (local ? nodeValue(run, address.node, address.coord?.join('/') ?? '') : '—')}
        </p>
      </div>
      <div className="provenance-layout">
        <section className="sheet">
          <h2>{t('sourceTree', lang)}</h2>
          <ProvenanceNode
            address={address}
            expectedRevision={expectedRevision}
            revisions={revisions}
            caseId={caseId}
            data={data}
            depth={0}
          />
        </section>
        <aside className="sheet">
          <h2>{t('intermediate', lang)}</h2>
          {local && run.values[address.node]?.[address.coord?.join('/') ?? '']?.validation && (
            <ValidationEvidence validation={run.values[address.node][address.coord?.join('/') ?? ''].validation!} />
          )}
          {root.data && <ExplainDetails explain={root.data.data} rootCaseId={caseId} navigate={navigate} />}
          {root.error && (
            <div role="alert">
              <p>{stale ? t('sourceChanged', lang) : root.error.message}</p>
              {stale && (
                <button onClick={() => navigate(provenancePath(caseId, address, currentRevision))}>
                  {t('refreshSource', lang)}
                </button>
              )}
            </div>
          )}
        </aside>
      </div>
    </>
  )
}

function ProvenanceNode({
  address,
  expectedRevision,
  revisions,
  caseId,
  data,
  depth,
}: {
  address: Address
  expectedRevision?: string
  revisions: Record<string, string>
  caseId: string
  data: WorkbenchData
  depth: number
}) {
  const [open, setOpen] = useState(depth === 0)
  const [continued, setContinued] = useState(false)
  const explain = useLoad(
    (signal) => (open ? data.explain(caseId, address, signal, expectedRevision) : Promise.reject(new Error('Closed'))),
    [data, caseId, addressKey(address), expectedRevision, open],
  )
  const children = Array.from(
    new Map(
      [...(explain.data?.data.references ?? []), ...(explain.data?.data.parts ?? [])].map((reference) => {
        const childAddress = { ...reference.address, case: reference.address.case ?? address.case }
        const revision =
          ('revision' in reference ? reference.revision : undefined) ??
          (childAddress.case === address.case
            ? expectedRevision
            : childAddress.case
              ? revisions[childAddress.case]
              : undefined)
        return [
          addressKey(childAddress) + '|' + (revision ?? ''),
          { address: childAddress, expectedRevision: revision },
        ] as const
      }),
    ).values(),
  )
  return (
    <div className="tree-node" style={{ marginLeft: Math.min(depth, 5) * 18 }}>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open}>
        {open ? '▾' : '▸'} {explain.data?.data.label ?? address.node}
        <strong>{explain.data?.data.result.display}</strong>
      </button>
      {open && explain.error && <p className="muted">{t('unavailable', lang)}</p>}
      {open && depth >= 5 && children.length > 0 && !continued && (
        <button type="button" className="continue-tree" onClick={() => setContinued(true)}>
          {t('continueTree', lang)}
        </button>
      )}
      {open &&
        (depth < 5 || continued) &&
        children.map((child) => (
          <ProvenanceNode
            key={addressKey(child.address) + (child.expectedRevision ?? '')}
            address={child.address}
            expectedRevision={child.expectedRevision}
            revisions={revisions}
            caseId={caseId}
            data={data}
            depth={depth + 1}
          />
        ))}
      {open && explain.data?.data.truncated && <p className="muted">{t('traceTruncated', lang)}</p>}
    </div>
  )
}
