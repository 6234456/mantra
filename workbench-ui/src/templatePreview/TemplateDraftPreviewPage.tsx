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
import { SchemaPropertiesPanel } from './SchemaPropertiesPanel'
import type { SchemaPropertiesClient, SchemaPropertiesRequest, SchemaPropertyOwner } from './schemaPropertiesClient'
import { textareaSourceRange, type SourceRange } from './sourceNavigation'
import { StyleClassesPanel } from './StyleClassesPanel'
import type { StyleClassesClient } from './styleClassesClient'
import { emptySourceHistory, recordSourceChange, stepSourceHistory, type SourceHistory } from './sourceHistory'
import {
  emptyRecoveryPending,
  matchBackup,
  restoreDocuments,
  type RecoveryDraft,
  type RecoveryPending,
} from './recovery'
import { recoveryContextChanged, useTemplateDraftRecovery, type RecoveryContext } from './useTemplateDraftRecovery'
import { TemplateRecoveryPanel } from './TemplateRecoveryPanel'
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
  styleClassesClient,
  schemaPropertiesClient,
  recoveryStorage,
}: {
  client?: TemplatePreviewClient
  settingsClient?: LayoutSettingsClient
  styleClassesClient?: StyleClassesClient
  schemaPropertiesClient?: SchemaPropertiesClient
  recoveryStorage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
} = {}) {
  const client = useMemo(() => supplied ?? new LiveTemplatePreviewClient(), [supplied])
  const [cases, setCases] = useState<CaseSummary[]>([])
  const [workspaceId, setWorkspaceId] = useState<string>()
  const [caseId, setCaseId] = useState(() => new URLSearchParams(location.search).get('case') ?? '')
  const [snapshot, setSnapshot] = useState<Envelope<TemplateSources>>()
  const [documents, setDocuments] = useState<Record<string, string>>({})
  const [history, setHistory] = useState<SourceHistory>(emptySourceHistory)
  const [historyNotice, setHistoryNotice] = useState('')
  const [sourceHandle, setSourceHandle] = useState('')
  const [sourceLocation, setSourceLocation] = useState<{ handle: string; text: string; range: SourceRange }>()
  const sourceEditor = useRef<HTMLTextAreaElement | null>(null)
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
  const [styleClassesPending, setStyleClassesPending] = useState(false)
  const [recoveryPending, setRecoveryPending] = useState(emptyRecoveryPending)
  const [recoveryEpoch, setRecoveryEpoch] = useState(0)
  const [restored, setRestored] = useState(false)
  const [layoutDisclosureOpen, setLayoutDisclosureOpen] = useState(false)
  const [styleDisclosureOpen, setStyleDisclosureOpen] = useState(false)
  const [schemaDisclosureOpen, setSchemaDisclosureOpen] = useState(false)
  const authoringPending =
    settingsPending ||
    styleClassesPending ||
    recoveryPending.layoutSettings.length > 0 ||
    recoveryPending.styleClass !== null ||
    !!recoveryPending.schemaProperty
  const context = useRef(0)
  const draftSequence = useRef(0)
  const pending = useRef<AbortController | null>(null)
  const baselineContext = useRef(0)
  const baselineSequence = useRef(0)
  const baselinePending = useRef<AbortController | null>(null)
  const candidatePanelsReceived = useRef(false)
  const available = !!supplied || !!document.querySelector('meta[name="mantra-session-token"]')
  const packageHost = !!document.querySelector('meta[name="mantra-package-workspace"][content="on"]')
  const latestDraft = useRef({
    caseId,
    workspaceId: snapshot?.data.workspaceId ?? workspaceId,
    snapshot,
    documents,
    inputs,
    recoveryPending,
    conflict,
    history,
    authoringPending,
    loading,
  })
  useLayoutEffect(() => {
    latestDraft.current = {
      caseId,
      workspaceId: snapshot?.data.workspaceId ?? workspaceId,
      snapshot,
      documents,
      inputs,
      recoveryPending,
      conflict,
      history,
      authoringPending,
      loading,
    }
  })
  useLayoutEffect(() => {
    if (!sourceLocation || sourceLocation.handle !== sourceHandle) return
    const latest = latestDraft.current
    const editor = sourceEditor.current
    if (!editor || latest.documents[sourceLocation.handle] !== sourceLocation.text || latest.conflict) return
    editor.focus()
    editor.setSelectionRange(sourceLocation.range.startOffset, sourceLocation.range.endOffset)
    editor.scrollIntoView?.({ block: 'center' })
    setSourceLocation(undefined)
  }, [sourceLocation, sourceHandle])
  const publishLayoutDraft = useCallback((layoutSettings: RecoveryPending['layoutSettings']) => {
    const pending = { ...latestDraft.current.recoveryPending, layoutSettings }
    latestDraft.current = {
      ...latestDraft.current,
      recoveryPending: pending,
      authoringPending: pending.layoutSettings.length > 0 || pending.styleClass !== null || !!pending.schemaProperty,
    }
    setRecoveryPending(pending)
  }, [])
  const publishStyleDraft = useCallback((styleClass: RecoveryPending['styleClass']) => {
    const pending = { ...latestDraft.current.recoveryPending, styleClass }
    latestDraft.current = {
      ...latestDraft.current,
      recoveryPending: pending,
      authoringPending: pending.layoutSettings.length > 0 || pending.styleClass !== null || !!pending.schemaProperty,
    }
    setRecoveryPending(pending)
  }, [])

  const publishSchemaDraft = useCallback((schemaProperty: RecoveryPending['schemaProperty']) => {
    const pending = { ...latestDraft.current.recoveryPending, schemaProperty }
    latestDraft.current = {
      ...latestDraft.current,
      recoveryPending: pending,
      authoringPending: pending.layoutSettings.length > 0 || pending.styleClass !== null || !!schemaProperty,
    }
    setRecoveryPending(pending)
  }, [])

  const invalidateBaseline = useCallback(() => {
    baselineContext.current++
    baselinePending.current?.abort()
    setBaseline(undefined)
    setBaselineBusy(false)
    setBaselineError('')
  }, [])

  const markConflict = useCallback(() => {
    latestDraft.current = { ...latestDraft.current, conflict: true }
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
        setWorkspaceId(workspace.workspaceId)
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
    setSourceLocation(undefined)
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
    setHistory(emptySourceHistory())
    setHistoryNotice('')
    setRecoveryPending(emptyRecoveryPending())
    setSettingsPending(false)
    setStyleClassesPending(false)
    setRecoveryEpoch((value) => value + 1)
    setRestored(false)
    setLayoutDisclosureOpen(false)
    setStyleDisclosureOpen(false)
    setSchemaDisclosureOpen(false)
    void client
      .sources(caseId, controller.signal)
      .then((next) => {
        if (controller.signal.aborted || context.current !== generation) return
        setSnapshot(next)
        setWorkspaceId(next.data.workspaceId)
        setDocuments(Object.fromEntries(next.data.documents.map((item) => [item.handle, item.text])))
        setHistory(emptySourceHistory())
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
      authoringPending)
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
    if (!snapshot || busy || conflict || authoringPending) return
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
      if (!latestDraft.current.authoringPending) setRestored(false)
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
  const schemaSource = snapshot?.data.documents.find((item) => item.role === 'schema')
  const layoutSource = snapshot?.data.documents.find((item) => item.role === 'layout')
  function acceptSourceChange(handle: string, before: string, after: string, label: string): boolean {
    const latest = latestDraft.current
    const document = latest.snapshot?.data.documents.find((item) => item.handle === handle)
    if (
      latest.loading ||
      latest.snapshot?.data.document !== latest.caseId ||
      !document?.editable ||
      !['schema', 'layout'].includes(document.role) ||
      latest.documents[handle] !== before
    )
      return false
    if (before === after) return true
    const nextDocuments = { ...latest.documents, [handle]: after }
    const nextHistory = recordSourceChange(latest.history, { handle, before, after, label })
    // Reserve both source and history before React commits so concurrent editors see the accepted text.
    latestDraft.current = { ...latest, documents: nextDocuments, history: nextHistory }
    invalidate()
    setDocuments(nextDocuments)
    setHistory(nextHistory)
    setHistoryNotice('')
    return true
  }
  function applyPropertySource(
    expectedCase: string,
    request: Pick<LayoutSettingsRequest, 'baseRevision' | 'baseRevisions' | 'handle' | 'text'>,
    nextText: string,
    label: string,
    role: 'schema' | 'layout' = 'layout',
  ): boolean {
    const latest = latestDraft.current
    const layout = latest.snapshot?.data.documents.find((item) => item.handle === request.handle)
    if (
      latest.conflict ||
      latest.caseId !== expectedCase ||
      latest.snapshot?.data.document !== expectedCase ||
      latest.snapshot.revision !== request.baseRevision ||
      !sameTemplateRevisions(latest.snapshot.data.baseRevisions, request.baseRevisions) ||
      layout?.role !== role ||
      !layout.editable ||
      latest.documents[request.handle] !== request.text
    )
      return false
    return acceptSourceChange(request.handle, request.text, nextText, label)
  }
  function locateSchemaProperty(
    expectedCase: string,
    request: SchemaPropertiesRequest,
    owner: SchemaPropertyOwner,
  ): boolean {
    const latest = latestDraft.current
    const root = latest.snapshot?.data.documents.find((item) => item.handle === request.handle)
    const target = latest.snapshot?.data.documents.find((item) => item.document === owner.document)
    if (
      latest.loading ||
      latest.conflict ||
      latest.caseId !== expectedCase ||
      latest.snapshot?.data.document !== expectedCase ||
      latest.snapshot.revision !== request.baseRevision ||
      !sameTemplateRevisions(latest.snapshot.data.baseRevisions, request.baseRevisions) ||
      root?.role !== 'schema' ||
      !root.editable ||
      latest.documents[request.handle] !== request.text ||
      !target ||
      (!target.editable && latest.documents[target.handle] !== target.text)
    )
      return false
    const text = latest.documents[target.handle]
    if (typeof text !== 'string') return false
    const range = textareaSourceRange(text, owner.range ?? owner.declarationRange)
    if (!range) return false
    setSourceHandle(target.handle)
    setSourceLocation({ handle: target.handle, text, range })
    return true
  }
  function historyReason(direction: 'undo' | 'redo'): string | undefined {
    if (loading || !snapshot || snapshot.data.document !== caseId)
      return 'Capture template sources before using source history.'
    if (conflict) return 'Resolve the source revision conflict before using source history.'
    if (authoringPending) return 'Apply or cancel the property changes before using source history.'
    const transaction = (direction === 'undo' ? history.past : history.future).at(-1)
    if (!transaction) return direction === 'undo' ? 'No source changes to undo.' : 'No source changes to redo.'
    if (documents[transaction.handle] !== transaction[direction === 'undo' ? 'after' : 'before'])
      return 'The source no longer matches this history entry.'
  }
  function moveHistory(direction: 'undo' | 'redo') {
    const latest = latestDraft.current
    if (
      latest.loading ||
      latest.conflict ||
      latest.authoringPending ||
      latest.snapshot?.data.document !== latest.caseId
    )
      return
    const next = stepSourceHistory(latest.history, direction, latest.documents)
    if (
      !next ||
      !latest.snapshot?.data.documents.some(
        (item) => item.handle === next.transaction.handle && item.editable && ['schema', 'layout'].includes(item.role),
      )
    )
      return
    latestDraft.current = { ...latest, documents: next.documents, history: next.history }
    invalidate()
    setDocuments(next.documents)
    setHistory(next.history)
    setSourceHandle(next.transaction.handle)
    setHistoryNotice(
      `${direction === 'undo' ? 'Undid' : 'Redid'} source change: ${next.transaction.label}. Preview the draft to update its results.`,
    )
  }
  const getRecoveryContext = useCallback((): RecoveryContext => {
    const latest = latestDraft.current
    return {
      workspaceId: latest.workspaceId,
      caseId: latest.caseId,
      snapshot: latest.snapshot,
      documents: latest.documents,
      inputs: latest.inputs,
      pending: latest.recoveryPending,
      loading: latest.loading,
      conflict: latest.conflict,
    }
  }, [])
  function restoreDraft(draft: RecoveryDraft, mode: 'local' | 'import'): boolean {
    const latest = latestDraft.current
    if (latest.loading || latest.conflict || !latest.snapshot || latest.snapshot.data.document !== latest.caseId)
      return false
    if (!matchBackup(draft, latest.snapshot, mode).compatible) return false
    const restoredDocuments = restoreDocuments(draft, latest.snapshot, mode)
    if (!restoredDocuments) return false
    if (
      recoveryContextChanged(getRecoveryContext()) &&
      !window.confirm(
        'Replace this page’s current source, inputs and unapplied fields with the selected recovery copy?',
      )
    )
      return false
    const nextInputs = Object.fromEntries(draft.inputs.map(({ node, text }) => [node, text]))
    const pendingFields = structuredClone(draft.pending)
    const nextHistory = emptySourceHistory()
    latestDraft.current = {
      ...latest,
      documents: restoredDocuments,
      inputs: nextInputs,
      recoveryPending: pendingFields,
      history: nextHistory,
      authoringPending:
        pendingFields.layoutSettings.length > 0 || pendingFields.styleClass !== null || !!pendingFields.schemaProperty,
    }
    invalidate()
    invalidateBaseline()
    setResult(undefined)
    setSelected(undefined)
    setPanels([])
    candidatePanelsReceived.current = false
    setPanel('')
    setIncludeZero(false)
    setCompareSaved(false)
    setDocuments(restoredDocuments)
    setInputs(nextInputs)
    setRecoveryPending(pendingFields)
    setSettingsPending(pendingFields.layoutSettings.length > 0)
    setStyleClassesPending(pendingFields.styleClass !== null)
    setHistory(nextHistory)
    setHistoryNotice('')
    setRecoveryEpoch((value) => value + 1)
    setSourceHandle(
      draft.documents.find((item) => item.text !== item.baseText)?.handle ??
        latest.snapshot.data.documents.find((item) => item.editable)?.handle ??
        '',
    )
    setLayoutDisclosureOpen(pendingFields.layoutSettings.length > 0)
    setStyleDisclosureOpen(pendingFields.styleClass !== null)
    setSchemaDisclosureOpen(!!pendingFields.schemaProperty)
    setRestored(true)
    return true
  }
  const recovery = useTemplateDraftRecovery({
    context: {
      workspaceId: snapshot?.data.workspaceId ?? workspaceId,
      caseId,
      snapshot,
      documents,
      inputs,
      pending: recoveryPending,
      loading,
      conflict,
    },
    getCurrent: getRecoveryContext,
    onRestore: restoreDraft,
    storage: recoveryStorage,
  })
  const undoReason = historyReason('undo')
  const redoReason = historyReason('redo')
  const current = result?.data.draftSequence === sequence && !conflict && !authoringPending
  const explain =
    selected && result?.data.explain && addressKey(selected) === addressKey(result.data.explain.address)
      ? result.data.explain
      : undefined
  const scalarInputs = Object.entries(result?.data.structure.nodes ?? {}).filter(
    ([, node]) => ['input', 'field'].includes(node.kind) && !node.dims?.length && node.type !== 'table',
  )
  const inputIds = [...new Set([...scalarInputs.map(([id]) => id), ...Object.keys(inputs)])]

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
                    !window.confirm(
                      'Leave this in-memory draft and switch example cases? Any existing browser backup remains available.',
                    )
                  )
                    return
                  recovery.flushNow()
                  recovery.leaveContext()
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
              disabled={!snapshot || busy || conflict || authoringPending}
              aria-describedby={authoringPending ? 'template-settings-preview-reason' : undefined}
              onClick={() => void preview()}
            >
              Preview draft
            </button>
            <button
              disabled={!!undoReason}
              aria-describedby={undoReason ? 'template-source-undo-reason' : undefined}
              onClick={() => moveHistory('undo')}
            >
              Undo source
            </button>
            {undoReason && (
              <span id="template-source-undo-reason" className="muted">
                {undoReason}
              </span>
            )}
            <button
              disabled={!!redoReason}
              aria-describedby={redoReason ? 'template-source-redo-reason' : undefined}
              onClick={() => moveHistory('redo')}
            >
              Redo source
            </button>
            {redoReason && (
              <span id="template-source-redo-reason" className="muted">
                {redoReason}
              </span>
            )}
            {authoringPending && (
              <span id="template-settings-preview-reason">
                {recoveryPending.schemaProperty
                  ? 'Apply or cancel the schema property changes before previewing.'
                  : settingsPending && styleClassesPending
                    ? 'Apply or cancel the layout setting and style class changes before previewing.'
                    : styleClassesPending
                      ? 'Apply or cancel the style class changes before previewing.'
                      : 'Apply or cancel the layout setting changes before previewing.'}
              </span>
            )}
            <button
              disabled={loading}
              onClick={() => {
                if (
                  (changed || recovery.local) &&
                  !window.confirm(
                    'Discard this in-memory draft and its browser recovery copy, then capture the current saved source?',
                  )
                )
                  return
                recovery.prepareReload()
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
          {historyNotice && <p role="status">{historyNotice}</p>}
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
          <TemplateRecoveryPanel
            key={`recovery:${workspaceId ?? snapshot?.data.workspaceId ?? ''}:${caseId}`}
            recovery={recovery}
            restored={restored}
          />
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
                    ref={sourceEditor}
                    aria-label={`Draft source ${source.document}`}
                    spellCheck={false}
                    readOnly={!source.editable}
                    value={documents[source.handle] ?? source.text}
                    onChange={(event) => {
                      acceptSourceChange(
                        source.handle,
                        documents[source.handle] ?? source.text,
                        event.target.value,
                        `Edit ${source.document}`,
                      )
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
            <details
              className="template-layout-settings-disclosure"
              key={`${caseId}:${snapshot.revision}:${recoveryEpoch}`}
              open={layoutDisclosureOpen}
              onToggle={(event) => setLayoutDisclosureOpen(event.currentTarget.open)}
            >
              <summary>Advanced layout settings</summary>
              <LayoutSettingsPanel
                caseId={caseId}
                snapshot={snapshot}
                source={layoutSource}
                text={layoutSource ? (documents[layoutSource.handle] ?? layoutSource.text) : undefined}
                conflict={conflict}
                onApply={(expectedCase, request, nextText) =>
                  applyPropertySource(expectedCase, request, nextText, 'Apply layout settings')
                }
                onConflict={markConflict}
                onPendingChange={setSettingsPending}
                restoredDraft={recoveryPending.layoutSettings}
                onDraftChange={publishLayoutDraft}
                client={settingsClient}
              />
            </details>
          )}
          {snapshot?.data.document === caseId && (
            <details
              className="template-style-classes-disclosure"
              key={`style:${caseId}:${snapshot.revision}:${recoveryEpoch}`}
              open={styleDisclosureOpen}
              onToggle={(event) => setStyleDisclosureOpen(event.currentTarget.open)}
            >
              <summary>Style classes</summary>
              <StyleClassesPanel
                caseId={caseId}
                snapshot={snapshot}
                source={layoutSource}
                text={layoutSource ? (documents[layoutSource.handle] ?? layoutSource.text) : undefined}
                conflict={conflict}
                onApply={(expectedCase, request, nextText) =>
                  applyPropertySource(expectedCase, request, nextText, 'Apply style classes')
                }
                onConflict={markConflict}
                onPendingChange={setStyleClassesPending}
                restoredDraft={recoveryPending.styleClass}
                onDraftChange={publishStyleDraft}
                client={styleClassesClient}
              />
            </details>
          )}
          {snapshot?.data.document === caseId && (
            <details
              className="template-schema-properties-disclosure"
              key={`schema:${caseId}:${snapshot.revision}:${recoveryEpoch}`}
              open={schemaDisclosureOpen}
              onToggle={(event) => setSchemaDisclosureOpen(event.currentTarget.open)}
            >
              <summary>Schema presentation properties</summary>
              <SchemaPropertiesPanel
                caseId={caseId}
                snapshot={snapshot}
                source={schemaSource}
                text={schemaSource ? (documents[schemaSource.handle] ?? schemaSource.text) : undefined}
                conflict={conflict}
                onApply={(expectedCase, request, nextText) =>
                  applyPropertySource(expectedCase, request, nextText, 'Apply schema property', 'schema')
                }
                onConflict={markConflict}
                onDraftChange={publishSchemaDraft}
                restoredDraft={recoveryPending.schemaProperty}
                onLocate={locateSchemaProperty}
                client={schemaPropertiesClient}
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
          {inputIds.length > 0 && (
            <section className="sheet template-preview-inputs">
              <h2>Example input overrides</h2>
              <p>Checked fields are sent as raw text and parsed by the candidate template.</p>
              {inputIds.map((id) => (
                <label key={id}>
                  <input
                    type="checkbox"
                    checked={Object.hasOwn(inputs, id)}
                    onChange={(event) => {
                      invalidate()
                      const next = { ...latestDraft.current.inputs }
                      if (event.target.checked) next[id] = ''
                      else delete next[id]
                      latestDraft.current = { ...latestDraft.current, inputs: next }
                      setInputs(next)
                    }}
                  />
                  {scalarInputs.find(([nodeId]) => nodeId === id)?.[1].label || id}
                  {!scalarInputs.some(([nodeId]) => nodeId === id) && (
                    <small>Unvalidated restored input override</small>
                  )}
                  <input
                    aria-label={`Example input ${id}`}
                    disabled={!Object.hasOwn(inputs, id)}
                    value={inputs[id] ?? ''}
                    onChange={(event) => {
                      invalidate()
                      const next = { ...latestDraft.current.inputs, [id]: event.target.value }
                      latestDraft.current = { ...latestDraft.current, inputs: next }
                      setInputs(next)
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
