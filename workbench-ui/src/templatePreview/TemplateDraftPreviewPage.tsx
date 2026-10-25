import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Address, CaseSummary, Diagnostic, Envelope, Panel } from '../types'
import { addressKey } from '../address'
import { ExplainDetails } from '../ui/ExplainDetails'
import { PaperTable } from '../ui/PaperTable'
import { ThemeControl } from '../ui/ThemeControl'
import { explainDiagnostic } from '../diagnosticMessages'
import { CandidateComparison } from './CandidateComparison'
import { LayoutSettingsPanel } from './LayoutSettingsPanel'
import type { LayoutSettingsClient, LayoutSettingsRequest } from './layoutSettingsClient'
import {
  LiveTemplatePreviewClient,
  sameTemplateRevisions,
  TemplatePreviewError,
  type TemplatePreview,
  type TemplatePreviewClient,
  type TemplateSources,
} from './client'
import './templatePreview.css'

/** A source-first real-engine workbench. The fixture visual prototype remains a separate route. */
export default function TemplateDraftPreviewPage({
  client: supplied,
  settingsClient,
}: { client?: TemplatePreviewClient; settingsClient?: LayoutSettingsClient } = {}) {
  const client = useMemo(() => supplied ?? new LiveTemplatePreviewClient(), [supplied])
  const [cases, setCases] = useState<CaseSummary[]>([])
  const [caseId, setCaseId] = useState(() => new URLSearchParams(location.search).get('case') ?? '')
  const [snapshot, setSnapshot] = useState<Envelope<TemplateSources>>()
  const [documents, setDocuments] = useState<Record<string, string>>({})
  const [sourceHandle, setSourceHandle] = useState('')
  const [inputs, setInputs] = useState<Record<string, string>>({})
  const [result, setResult] = useState<Envelope<TemplatePreview>>()
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([])
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [reload, setReload] = useState(0)
  const [includeZero, setIncludeZero] = useState(false)
  const [panels, setPanels] = useState<Panel[]>([])
  const [panel, setPanel] = useState('')
  const [compareSaved, setCompareSaved] = useState(false)
  const [baseline, setBaseline] = useState<Envelope<TemplatePreview>>()
  const [baselineBusy, setBaselineBusy] = useState(false)
  const [baselineError, setBaselineError] = useState('')
  const [baselineRetry, setBaselineRetry] = useState(0)
  const [selected, setSelected] = useState<Address>()
  const [sequence, setSequence] = useState(0)
  const [settingsPending, setSettingsPending] = useState(false)
  const context = useRef(0)
  const draftSequence = useRef(0)
  const pending = useRef<AbortController | null>(null)
  const baselineContext = useRef(0)
  const baselineSequence = useRef(0)
  const baselinePending = useRef<AbortController | null>(null)
  const candidatePanelsReceived = useRef(false)
  const available = !!supplied || !!document.querySelector('meta[name="mantra-session-token"]')
  const packageHost = !!document.querySelector('meta[name="mantra-package-workspace"][content="on"]')
  const latestDraft = useRef({ caseId, snapshot, documents, conflict })
  useLayoutEffect(() => {
    latestDraft.current = { caseId, snapshot, documents, conflict }
  })

  const invalidateBaseline = useCallback(() => {
    baselineContext.current++
    baselinePending.current?.abort()
    setBaseline(undefined)
    setBaselineBusy(false)
    setBaselineError('')
  }, [])

  const markConflict = useCallback(() => {
    context.current++
    pending.current?.abort()
    setBusy(false)
    baselineContext.current++
    baselinePending.current?.abort()
    setBaselineBusy(false)
    setConflict(true)
  }, [])

  function invalidate() {
    context.current++
    draftSequence.current++
    setSequence(draftSequence.current)
    pending.current?.abort()
    setBusy(false)
    setError('')
    setDiagnostics([])
  }

  useEffect(() => {
    if (!available || packageHost) return
    const controller = new AbortController()
    void client
      .workspace(controller.signal)
      .then((workspace) => {
        if (controller.signal.aborted) return
        setCases(workspace.cases)
        setCaseId((id) => id || workspace.cases[0]?.id || '')
      })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : String(failure))
      })
    return () => controller.abort()
  }, [client, available, packageHost])

  useEffect(() => {
    if (!caseId || !available || packageHost) return
    const controller = new AbortController()
    const generation = ++context.current
    pending.current?.abort()
    invalidateBaseline()
    setLoading(true)
    setBusy(false)
    setSnapshot(undefined)
    setResult(undefined)
    setDiagnostics([])
    setSelected(undefined)
    setPanels([])
    candidatePanelsReceived.current = false
    setPanel('')
    setError('')
    setConflict(false)
    setInputs({})
    setDocuments({})
    void client
      .sources(caseId, controller.signal)
      .then((next) => {
        if (controller.signal.aborted || context.current !== generation) return
        setSnapshot(next)
        setDocuments(Object.fromEntries(next.data.documents.map((item) => [item.handle, item.text])))
        setSourceHandle(
          next.data.documents.find((item) => item.editable)?.handle ?? next.data.documents[0]?.handle ?? '',
        )
        setLoading(false)
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted || context.current !== generation) return
        setError(failure instanceof Error ? failure.message : String(failure))
        setLoading(false)
      })
    return () => controller.abort()
  }, [client, caseId, reload, available, packageHost, invalidateBaseline])

  useEffect(() => {
    if (!compareSaved || !snapshot || snapshot.data.document !== caseId || conflict || !available || packageHost) return
    const controller = new AbortController()
    baselinePending.current = controller
    const generation = ++baselineContext.current
    const nextSequence = ++baselineSequence.current
    setBaseline(undefined)
    setBaselineBusy(true)
    setBaselineError('')
    void client
      .preview(
        caseId,
        {
          baseRevision: snapshot.revision,
          baseRevisions: snapshot.data.baseRevisions,
          draftSequence: nextSequence,
          documents: snapshot.data.documents
            .filter((item) => item.editable)
            .map((item) => ({ handle: item.handle, text: item.text })),
          inputs: [],
          includeZero,
          ...(panel ? { panel } : {}),
        },
        controller.signal,
      )
      .then((next) => {
        if (controller.signal.aborted || baselineContext.current !== generation) return
        setBaseline(next)
        setPanels((previous) => (candidatePanelsReceived.current ? previous : next.data.structure.panels))
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted || baselineContext.current !== generation) return
        setBaselineError(failure instanceof Error ? failure.message : String(failure))
        if (failure instanceof TemplatePreviewError && failure.status === 409) markConflict()
      })
      .finally(() => {
        if (!controller.signal.aborted && baselineContext.current === generation) setBaselineBusy(false)
      })
    return () => controller.abort()
  }, [
    client,
    caseId,
    snapshot,
    panel,
    includeZero,
    compareSaved,
    baselineRetry,
    conflict,
    available,
    packageHost,
    markConflict,
  ])

  useEffect(
    () => () => {
      context.current++
      pending.current?.abort()
      baselineContext.current++
      baselinePending.current?.abort()
    },
    [],
  )

  const changed =
    !!snapshot &&
    (snapshot.data.documents.some((item) => documents[item.handle] !== item.text) ||
      Object.keys(inputs).length > 0 ||
      settingsPending)
  useEffect(() => {
    if (!changed) return
    function leaving(event: BeforeUnloadEvent) {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', leaving)
    return () => window.removeEventListener('beforeunload', leaving)
  }, [changed])

  async function preview() {
    if (!snapshot || busy || conflict || settingsPending) return
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    const generation = ++context.current
    const nextSequence = ++draftSequence.current
    setSequence(nextSequence)
    setBusy(true)
    setError('')
    setDiagnostics([])
    try {
      const next = await client.preview(
        caseId,
        {
          baseRevision: snapshot.revision,
          baseRevisions: snapshot.data.baseRevisions,
          draftSequence: nextSequence,
          documents: snapshot.data.documents
            .filter((item) => item.editable)
            .map((item) => ({ handle: item.handle, text: documents[item.handle] ?? item.text })),
          inputs: Object.entries(inputs).map(([node, text]) => ({ node, text })),
          includeZero,
          ...(panel ? { panel } : {}),
          ...(selected?.node
            ? { explain: { node: selected.node, ...(selected.coord ? { coord: selected.coord } : {}) } }
            : {}),
        },
        controller.signal,
      )
      if (controller.signal.aborted || context.current !== generation) return
      setResult(next)
      candidatePanelsReceived.current = true
      setPanels(next.data.structure.panels)
      setDiagnostics(next.data.diagnostics)
    } catch (failure) {
      if (controller.signal.aborted || context.current !== generation) return
      setError(failure instanceof Error ? failure.message : String(failure))
      if (failure instanceof TemplatePreviewError) {
        setDiagnostics(failure.diagnostics)
        if (failure.status === 409) markConflict()
      }
    } finally {
      if (!controller.signal.aborted && context.current === generation) setBusy(false)
    }
  }

  const source = snapshot?.data.documents.find((item) => item.handle === sourceHandle)
  const layoutSource = snapshot?.data.documents.find((item) => item.role === 'layout')
  function applyLayoutSettings(expectedCase: string, request: LayoutSettingsRequest, nextText: string): boolean {
    const latest = latestDraft.current
    const layout = latest.snapshot?.data.documents.find((item) => item.handle === request.handle)
    if (
      latest.conflict ||
      latest.caseId !== expectedCase ||
      latest.snapshot?.data.document !== expectedCase ||
      latest.snapshot.revision !== request.baseRevision ||
      !sameTemplateRevisions(latest.snapshot.data.baseRevisions, request.baseRevisions) ||
      layout?.role !== 'layout' ||
      !layout.editable ||
      latest.documents[request.handle] !== request.text
    )
      return false
    if (nextText !== request.text) {
      invalidate()
      setDocuments((previous) => ({ ...previous, [request.handle]: nextText }))
    }
    return true
  }
  const current = result?.data.draftSequence === sequence && !conflict && !settingsPending
  const explain =
    selected && result?.data.explain && addressKey(selected) === addressKey(result.data.explain.address)
      ? result.data.explain
      : undefined
  const scalarInputs = Object.entries(result?.data.structure.nodes ?? {}).filter(
    ([, node]) => ['input', 'field'].includes(node.kind) && !node.dims?.length && node.type !== 'table',
  )

  return (
    <main className="template-preview-page">
      <header className="template-preview-header">
        <div>
          <span className="eyebrow">Real Mantra engine · draft preview</span>
          <h1>Template draft preview</h1>
          <p>Edit source and example inputs, then preview. This page keeps files unchanged.</p>
        </div>
        <ThemeControl lang="en" />
        <a href={caseId ? `/cases/${encodeURIComponent(caseId)}/overview` : '/'}>Return to workbench</a>
      </header>
      {!available || packageHost ? (
        <p role="alert">
          {packageHost
            ? 'Template draft preview requires a file workspace. Package workspaces do not provide this endpoint.'
            : 'Start the live Mantra workbench to preview template drafts.'}
        </p>
      ) : (
        <>
          <div className="template-preview-toolbar" role="group" aria-label="Preview settings">
            <label>
              Example case
              <select
                value={caseId}
                onChange={(event) => {
                  if (
                    changed &&
                    event.target.value !== caseId &&
                    !window.confirm('Discard this in-memory draft and switch example cases?')
                  )
                    return
                  invalidate()
                  invalidateBaseline()
                  setCaseId(event.target.value)
                }}
              >
                {cases.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title || item.id}
                  </option>
                ))}
              </select>
            </label>
            <button
              disabled={!snapshot || busy || conflict || settingsPending}
              aria-describedby={settingsPending ? 'template-settings-preview-reason' : undefined}
              onClick={() => void preview()}
            >
              Preview draft
            </button>
            {settingsPending && (
              <span id="template-settings-preview-reason">
                Apply or cancel the layout setting changes before previewing.
              </span>
            )}
            <button
              disabled={loading}
              onClick={() => {
                if (changed && !window.confirm('Discard this in-memory draft and capture the current saved source?'))
                  return
                invalidate()
                invalidateBaseline()
                setReload((value) => value + 1)
              }}
            >
              Reload saved source
            </button>
            <label>
              Preview panel
              <select
                value={panel}
                disabled={!panels.length}
                onChange={(event) => {
                  invalidate()
                  invalidateBaseline()
                  setSelected(undefined)
                  setPanel(event.target.value)
                }}
              >
                <option value="">All panels</option>
                {panels.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title || item.id}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <input
                type="checkbox"
                checked={includeZero}
                onChange={(event) => {
                  invalidate()
                  invalidateBaseline()
                  setIncludeZero(event.target.checked)
                }}
              />
              Show zero rows
            </label>
            <label>
              <input
                type="checkbox"
                checked={compareSaved}
                onChange={(event) => {
                  invalidateBaseline()
                  setCompareSaved(event.target.checked)
                }}
              />
              Compare with saved template
            </label>
          </div>
          {loading && <p role="status">Capturing template sources…</p>}
          {snapshot && (
            <p className="muted">
              Captured source revision <code>{snapshot.revision}</code>
            </p>
          )}
          {error && <p role="alert">{error}</p>}
          {conflict && (
            <p role="status">
              Sources changed outside this page. Your draft is retained; reload to capture a new base.
            </p>
          )}
          <div className="template-preview-columns">
            <section className="sheet template-preview-source">
              <h2>Source</h2>
              <label>
                Participating document
                <select value={sourceHandle} onChange={(event) => setSourceHandle(event.target.value)}>
                  {snapshot?.data.documents.map((item) => (
                    <option key={item.handle} value={item.handle}>
                      {item.document} · {item.role}
                    </option>
                  ))}
                </select>
              </label>
              {source && (
                <>
                  {!source.editable && <p className="muted">{source.reason}</p>}
                  <textarea
                    aria-label={`Draft source ${source.document}`}
                    spellCheck={false}
                    readOnly={!source.editable}
                    value={documents[source.handle] ?? source.text}
                    onChange={(event) => {
                      invalidate()
                      setDocuments((value) => ({ ...value, [source.handle]: event.target.value }))
                    }}
                  />
                </>
              )}
            </section>
            <section className="sheet" aria-label="Draft preview">
              <h2>Engine preview</h2>
              <p role="status" data-testid="template-preview-status">
                {busy
                  ? 'Calculating draft…'
                  : result
                    ? `${current ? 'Current' : 'Previous'} engine preview · draft #${result.data.draftSequence}${result.data.succeeded ? '' : ' · runtime failure'}`
                    : 'Preview a draft to see its tables and input fields.'}
              </p>
              {result && <h3>{result.data.paper.title}</h3>}
              {result && !result.data.paper.tables.some((table) => table.rows.length > 0) && (
                <p data-testid="template-empty-preview">
                  No visible rows in this preview. Review layout visibility, the selected panel or the zero-row setting.
                </p>
              )}
              {result?.data.paper.tables.map((table) => (
                <div key={table.id}>
                  <h3>{table.title}</h3>
                  <PaperTable
                    table={table}
                    browsing={result.data.paper.browsing}
                    selected={selected}
                    onIncludeZero={(value) => {
                      invalidate()
                      invalidateBaseline()
                      setIncludeZero(value)
                    }}
                    onSelect={(address) => {
                      invalidate()
                      setSelected(address)
                    }}
                  />
                </div>
              ))}
            </section>
          </div>
          {snapshot?.data.document === caseId && (
            <details className="template-layout-settings-disclosure" key={`${caseId}:${snapshot.revision}`}>
              <summary>Advanced layout settings</summary>
              <LayoutSettingsPanel
                caseId={caseId}
                snapshot={snapshot}
                source={layoutSource}
                text={layoutSource ? (documents[layoutSource.handle] ?? layoutSource.text) : undefined}
                conflict={conflict}
                onApply={applyLayoutSettings}
                onConflict={markConflict}
                onPendingChange={setSettingsPending}
                client={settingsClient}
              />
            </details>
          )}
          {compareSaved && (
            <section className="sheet template-preview-comparison" aria-label="Saved-template comparison">
              <h2>Compare with saved template</h2>
              <p role="status" data-testid="template-baseline-status">
                {baselineBusy
                  ? 'Calculating saved-template preview…'
                  : baseline
                    ? `${conflict ? 'Previous saved-template' : 'Saved-template'} preview · captured source and saved example inputs`
                    : 'No saved-template preview for this context.'}
              </p>
              {baselineError && <p role="alert">{baselineError}</p>}
              {baselineError && !conflict && (
                <button onClick={() => setBaselineRetry((value) => value + 1)}>Retry saved-template preview</button>
              )}
              <CandidateComparison candidate={result?.data} baseline={baseline?.data} current={current} />
            </section>
          )}
          {scalarInputs.length > 0 && (
            <section className="sheet template-preview-inputs">
              <h2>Example input overrides</h2>
              <p>Checked fields are sent as raw text and parsed by the candidate template.</p>
              {scalarInputs.map(([id, node]) => (
                <label key={id}>
                  <input
                    type="checkbox"
                    checked={Object.hasOwn(inputs, id)}
                    onChange={(event) => {
                      invalidate()
                      setInputs((previous) => {
                        const next = { ...previous }
                        if (event.target.checked) next[id] = ''
                        else delete next[id]
                        return next
                      })
                    }}
                  />
                  {node.label || id}
                  <input
                    aria-label={`Example input ${id}`}
                    disabled={!Object.hasOwn(inputs, id)}
                    value={inputs[id] ?? ''}
                    onChange={(event) => {
                      invalidate()
                      setInputs((previous) => ({ ...previous, [id]: event.target.value }))
                    }}
                  />
                </label>
              ))}
            </section>
          )}
          {diagnostics.length > 0 && (
            <section className="sheet">
              <h2>Diagnostics</h2>
              <ul>
                {diagnostics.map((item, index) => (
                  <li key={index}>
                    <strong>{item.code}</strong> {explainDiagnostic(item, 'en').summary}
                    <details>
                      <summary>Original diagnostic</summary>
                      {item.message}
                    </details>
                    {item.location && (
                      <small>
                        {' '}
                        · {item.location.document}:{item.location.line}:{item.location.column}
                      </small>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {explain && (
            <section className="sheet">
              <h2>Draft Explain {current ? '' : '(previous preview)'}</h2>
              <ExplainDetails explain={explain} />
            </section>
          )}
        </>
      )}
    </main>
  )
}
