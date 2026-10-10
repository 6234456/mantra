import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { Envelope, Paper, PreviewPaper } from '../types'
import { ThemeControl } from '../ui/ThemeControl'
import { paperMatches } from '../ui/PaperTable'
import { AuthoringGrid, type GridPosition } from './AuthoringGrid'
import { AuthoringCodeEditor } from './AuthoringCodeEditor'
import { AuthoringInspector } from './AuthoringInspector'
import { AuthoringDrawer } from './AuthoringDrawer'
import { AuthoringOutline } from './AuthoringOutline'
import { flushSync } from 'react-dom'
import { EditorView } from '@codemirror/view'
import { PrototypeDialog, SourceDocumentDiff, SourcePane, originalDocuments, ownerText } from './AuthoringPanels'
import {
  authoringReducer,
  canRedo,
  canSave,
  canUndo,
  createAuthoringState,
  currentPreview,
  diagnosticsAreStale,
  isDirty,
  previewStatus,
  sameDocuments,
  sameRevisions,
  saveCapsule,
  saveReason,
} from './model'
import { clearRecovery, readRecovery, type RecoveryDraft } from './recovery'
import { useDraftRecovery } from './useDraftRecovery'
import { AuthoringBackupPanel, recoveryStatusMessage } from './AuthoringBackupPanel'
import type {
  AuthoringRecording,
  AuthoringService,
  ExampleInputResult,
  OpenResult,
  SemanticOperation,
  SourceOwner,
} from './service'
import { createRecordedService, sourceDigest } from './simulated/recordedService'
import { ownerForDiagnostic } from './simulated/sourceOwners'
import rawRecording from './recording/recording.json'
import config from './config.json'
import './authoring.css'

const recording: AuthoringRecording = rawRecording
const baseDocuments = originalDocuments(recording.base)

function useViewport(query: string) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const media = window.matchMedia(query)
    const changed = () => setMatches(media.matches)
    media.addEventListener('change', changed)
    return () => media.removeEventListener('change', changed)
  }, [query])
  return matches
}

function recoveryTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? 'an earlier session'
    : new Intl.DateTimeFormat(undefined, {
        dateStyle: 'medium',
        timeStyle: 'short',
      }).format(date)
}

/** Fixture-only review surface. Saves, owners, forks and conflicts are explicitly simulated. */
export function AuthoringPrototype({ service: suppliedService }: { service?: AuthoringService } = {}) {
  const [session, setSession] = useState(() => suppliedService ?? createRecordedService(recording))
  const [opened, setOpened] = useState<OpenResult>()
  const [error, setError] = useState('')
  const [stage, setStage] = useState<'entry' | 'fork' | 'editor'>('entry')
  const [recovery, setRecovery] = useState<RecoveryDraft>()
  const [restored, setRestored] = useState<RecoveryDraft>()
  const [generation, setGeneration] = useState(0)
  useEffect(() => {
    let active = true
    void session
      .open()
      .then((result) => {
        if (active) {
          setOpened(result)
        }
      })
      .catch((failure: unknown) => {
        if (active) setError(failure instanceof Error ? failure.message : String(failure))
      })
    return () => {
      active = false
    }
  }, [session])
  useEffect(() => {
    let active = true
    void readRecovery(recording).then((draft) => {
      if (active && draft) {
        setRecovery(draft)
        setStage('editor')
      }
    })
    return () => {
      active = false
    }
  }, [])
  async function restoreDraft(draft: RecoveryDraft) {
    try {
      const next = createRecordedService(recording, { savedDocuments: draft.baseDocuments })
      const result = await next.open()
      setSession(next)
      setOpened(result)
      setRestored(draft)
      setRecovery(undefined)
      setGeneration((value) => value + 1)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    }
  }
  async function reset() {
    clearRecovery(recording)
    const next = createRecordedService(recording)
    const result = await next.open()
    setSession(next)
    setOpened(result)
    setRecovery(undefined)
    setRestored(undefined)
    setGeneration((value) => value + 1)
  }
  if (stage === 'editor' && opened)
    return (
      <AuthoringEditor
        key={generation}
        service={session}
        opened={opened}
        recovery={recovery}
        restored={restored}
        onRestore={(draft) => void restoreDraft(draft)}
        onDiscardRecovery={() => {
          clearRecovery(recording)
          setRecovery(undefined)
        }}
        onReset={() => void reset()}
      />
    )
  return (
    <main className="authoring">
      <header className="author-identity">
        <span className="author-badge">Prototype · recorded engine data</span>
        <ThemeControl lang="en" />
      </header>
      <h1>Author a Mantra template</h1>
      <p>Edit the original DSL, inspect an engine preview, and review source changes.</p>
      <p>Owner handles, source patches, forks, saving and conflict events are simulated in this prototype.</p>
      {error && <p role="alert">{error}</p>}
      <h2>Workspace templates</h2>
      <p>No live authoring workspace is connected. Create a simulated copy from a recorded pattern.</p>
      <h2>Start from a pattern</h2>
      <div className="author-patterns">
        {config.patterns.map((pattern) => (
          <section className="author-card" key={pattern.title}>
            <h3>{pattern.title}</h3>
            <p>{pattern.description}</p>
            <p className="author-reference">
              {pattern.available ? 'Recorded engine states · independent pattern expectations' : 'Not recorded'}
            </p>
            <button
              disabled={!pattern.available || !opened}
              onClick={() => setStage('fork')}
              aria-label={pattern.available ? 'Create editable copy…' : `Create editable copy of ${pattern.title}`}
            >
              Create editable copy…
            </button>
          </section>
        ))}
      </div>
      <h2>Captured packages</h2>
      <p>No captured package is connected. Captured definitions are read-only.</p>
      {stage === 'fork' && opened && (
        <PrototypeDialog title="Create a workspace copy (simulated)" onClose={() => setStage('entry')}>
          <p>The original stays unchanged. The copy is a new workspace template.</p>
          <p>Target: in-memory prototype session. No files will be written.</p>
          <h3>Dependency closure</h3>
          {opened.dependencies.map((dependency) => (
            <p key={dependency.document}>
              <strong>{dependency.document}</strong> {dependency.editable ? 'Editable' : 'Read-only'}
              <br />
              <code>{dependency.sha256}</code>
            </p>
          ))}
          <p>Includes and dependency identity are preserved in the recorded source set.</p>
          <button className="primary-button" onClick={() => setStage('editor')}>
            Confirm editable copy
          </button>{' '}
          <button onClick={() => setStage('entry')}>Cancel</button>
        </PrototypeDialog>
      )}
    </main>
  )
}

function AuthoringEditor({
  service,
  opened,
  recovery,
  restored,
  onRestore,
  onDiscardRecovery,
  onReset,
}: {
  service: AuthoringService
  opened: OpenResult
  recovery?: RecoveryDraft
  restored?: RecoveryDraft
  onRestore: (draft: RecoveryDraft) => void
  onDiscardRecovery: () => void
  onReset: () => void
}) {
  const [state, dispatch] = useReducer(authoringReducer, opened, (result) => {
    const initial = createAuthoringState(result)
    return restored
      ? authoringReducer(initial, { type: 'restore', documents: restored.documents, history: restored.history })
      : initial
  })
  const [inputs, setInputs] = useState<Record<string, string>>(() => restored?.inputs ?? {})
  const [selected, setSelected] = useState<GridPosition>({ row: 0, column: 0 })
  const [explicitHandle, setExplicitHandle] = useState<string>()
  const [view, setView] = useState<'Grid' | 'Source' | 'Split'>('Grid')
  const [inspector, setInspector] = useState('Property')
  const [drawer, setDrawer] = useState('Source changes')
  const [showFormulas, setShowFormulas] = useState(false)
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<string>()
  const inspectorOverlay = useViewport('(max-width: 1279px)')
  const outlineOverlay = useViewport('(max-width: 1023px)')
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [outlineOpen, setOutlineOpen] = useState(false)
  const [composing, setComposing] = useState(false)
  const [notice, setNotice] = useState('')
  const [conflictDialog, setConflictDialog] = useState(false)
  const [exampleText, setExampleText] = useState('')
  const [example, setExample] = useState<ExampleInputResult>()
  const [savedDigest, setSavedDigest] = useState(opened.preview.digest)
  const [range, setRange] = useState<GridPosition[]>([])
  const element = useRef<HTMLElement>(null)
  const propertyField = useRef<HTMLInputElement>(null)
  const mounted = useRef(true)
  const current = useRef(state)
  current.current = state
  const navigation = useRef<
    { sequence: number; position: GridPosition; direction: number; horizontal: number } | undefined
  >(undefined)
  const editedOnce = useRef(!!restored)
  const saveAction = useRef<{ documents: Record<string, string>; baseRevisions: Record<string, string> } | undefined>(
    undefined,
  )
  const owners = useMemo(() => service.owners(state.documents), [service, state.documents])
  const pending = Object.entries(inputs).some(([key, text]) => {
    if (key.startsWith('source:')) return text !== state.documents[key.slice(7)]
    const owner = owners.find((item) => item.handle === key)
    return !!owner && text !== ownerText(owner)
  })
  const exampleEnabled =
    !isDirty(state) && !pending && !state.conflict && sameDocuments(state.baseDocuments, baseDocuments)
  const exampleGeneration = useRef(0)
  const exampleContext = useRef({
    enabled: exampleEnabled,
    text: exampleText,
    sequence: state.draftSequence,
    revisions: state.baseRevisions,
  })
  exampleContext.current = {
    enabled: exampleEnabled,
    text: exampleText,
    sequence: state.draftSequence,
    revisions: state.baseRevisions,
  }
  const preview = currentPreview(state)
  const candidate =
    exampleEnabled && example?.kind === 'valid' ? (example.response as Envelope<PreviewPaper>).data : undefined
  const paper: Paper | undefined = candidate?.paper ?? preview?.responses.paper?.data
  const table = paper?.tables.find((item) => item.id === recording.panel)
  const stale = !candidate && state.preview.status !== 'current'
  function ownerAt(rowIndex: number, column: number): SourceOwner | undefined {
    const row = table?.rows[rowIndex]
    const content = table?.columns[column]?.content
    if (!row) return undefined
    if (row.node) {
      const property = content === 'label' ? 'label' : content === 'main' ? 'formula' : 'condition'
      const matching = owners.find((owner) => owner.nodeId === row.node && owner.property === property)
      return (
        matching ??
        (content !== 'label'
          ? owners.find((owner) => owner.nodeId === row.node && ['condition', 'left-operand'].includes(owner.property))
          : undefined)
      )
    }
    if (row.kind === 'note' && content === 'label') {
      const index = table!.rows.slice(0, rowIndex).filter((item) => item.kind === 'note').length
      return owners.filter((owner) => owner.panelId === recording.panel && owner.property === 'note')[index]
    }
    return undefined
  }
  const selectedOwner =
    owners.find((owner) => owner.handle === explicitHandle) ?? ownerAt(selected.row, selected.column)
  const formulaOwner = owners.find(
    (owner) =>
      owner.nodeId === selectedOwner?.nodeId && ['formula', 'condition', 'left-operand'].includes(owner.property),
  )
  const classOwner = owners.find(
    (owner) =>
      owner.document === selectedOwner?.document &&
      owner.declaration === selectedOwner?.declaration &&
      owner.property === 'classes',
  )
  const textOwner =
    selectedOwner && !['formula', 'classes', 'condition', 'left-operand'].includes(selectedOwner.property)
      ? selectedOwner
      : owners.find((owner) => owner.nodeId === selectedOwner?.nodeId && owner.property === 'label')
  const selectedCell = table?.rows[selected.row]?.cells[selected.column]
  const reason = composing
    ? 'Finish the IME composition before saving'
    : pending
      ? 'Commit the open property or source edit before saving'
      : saveReason(state)
  const diagnosticOwners = state.diagnostics.map((diagnostic) => ownerForDiagnostic(owners, diagnostic))
  const invalidHandles = new Set(
    diagnosticsAreStale(state) ? [] : diagnosticOwners.filter((owner) => !!owner).map((owner) => owner!.handle),
  )
  const technical = state.diagnostics.filter((diagnostic) => ['parsing', 'structural'].includes(diagnostic.category))
  const runtime = state.diagnostics.filter(
    (diagnostic) => diagnostic.category === 'evaluation' && diagnostic.severity === 'error',
  )
  const findings = state.diagnostics.filter((diagnostic) => diagnostic.category === 'business')
  const capsule = saveCapsule(state)
  const schema = preview?.responses.structure?.data
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    let active = true
    void sourceDigest(state.baseDocuments).then((digest) => {
      if (active) setSavedDigest(digest)
    })
    return () => {
      active = false
    }
  }, [state.baseDocuments])
  useEffect(() => {
    if (state.conflict) return
    const request = {
      draftSequence: state.draftSequence,
      baseRevisions: state.baseRevisions,
      documents: state.documents,
    }
    dispatch({ type: 'previewStarted', ...request })
    void service
      .preview(request)
      .then((result) => {
        if (!mounted.current) return
        if (
          result.draftSequence !== current.current.draftSequence ||
          !sameRevisions(result.baseRevisions, current.current.baseRevisions)
        ) {
          setNotice(`Ignored late preview #${result.draftSequence}`)
          return
        }
        dispatch({ type: 'previewReceived', result })
        const move = navigation.current
        if (move?.sequence === result.draftSequence && ['valid', 'runtimeFailure'].includes(result.kind)) {
          navigation.current = undefined
          finishNavigation(move.position, move.direction, move.horizontal)
        }
      })
      .catch((failure: unknown) => {
        if (
          mounted.current &&
          request.draftSequence === current.current.draftSequence &&
          sameRevisions(request.baseRevisions, current.current.baseRevisions)
        ) {
          setNotice(failure instanceof Error ? failure.message : String(failure))
          dispatch({
            type: 'previewReceived',
            result: {
              ...request,
              kind: 'unrecorded',
              responses: { explains: {}, errors: [] },
              diagnostics: [],
              digest: '',
              reason: 'Preview request failed; no engine evidence is available for this draft',
            },
          })
        }
      })
    // The table is display evidence; it does not identify a request or trigger a preview.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [service, state.draftSequence, state.baseRevisions, state.documents, state.conflict])
  const recoveryStatus = useDraftRecovery({ recording, state, inputs, enabled: editedOnce.current && !recovery })
  useEffect(() => {
    function leaving(event: BeforeUnloadEvent) {
      if (isDirty(current.current) || pending) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', leaving)
    return () => window.removeEventListener('beforeunload', leaving)
  }, [pending])
  useEffect(() => {
    if (editing && textOwner?.handle === editing) propertyField.current?.focus()
  }, [editing, textOwner?.handle, inspectorOpen])

  function input(key: string, value: string) {
    editedOnce.current = true
    exampleGeneration.current++
    const source = key.startsWith('source:')
      ? state.documents[key.slice(7)]
      : ownerText(owners.find((owner) => owner.handle === key))
    setInputs((previous) => {
      const next = { ...previous }
      if (source === value) delete next[key]
      else next[key] = value
      return next
    })
  }
  function selectOwner(owner: SourceOwner) {
    setExplicitHandle(owner.handle)
    if (owner.nodeId) {
      const row = table?.rows.findIndex((item) => item.node === owner.nodeId) ?? -1
      if (row >= 0) setSelected({ row, column: owner.property === 'formula' ? 1 : 0 })
    }
  }
  function focusGrid() {
    requestAnimationFrame(() =>
      element.current
        ?.querySelector<HTMLElement>(`[data-row="${selected.row}"][data-column="${selected.column}"]`)
        ?.focus(),
    )
  }
  function finishNavigation(position: GridPosition, direction: number, horizontal: number) {
    let next = {
      ...position,
      row: Math.max(0, Math.min((table?.rows.length ?? 1) - 1, position.row + direction)),
    }
    if (horizontal && table) {
      const width = table.columns.length
      const start = position.row * width + position.column
      for (let index = start + horizontal; index >= 0 && index < table.rows.length * width; index += horizontal) {
        const target = { row: Math.floor(index / width), column: index % width }
        if (ownerAt(target.row, target.column)?.editable) {
          next = target
          break
        }
      }
    }
    setEditing(undefined)
    setInspectorOpen(false)
    setSelected(next)
    setExplicitHandle(undefined)
    requestAnimationFrame(() =>
      element.current?.querySelector<HTMLElement>(`[data-row="${next.row}"][data-column="${next.column}"]`)?.focus(),
    )
  }
  function apply(owner: SourceOwner, value: SemanticOperation['value'], direction = 0, horizontal = 0) {
    if (composing || state.saving) return
    if (
      inputs[`source:${owner.document}`] !== undefined &&
      inputs[`source:${owner.document}`] !== state.documents[owner.document]
    ) {
      setNotice('Finish the current source edit before changing a property in that document.')
      return
    }
    const op: SemanticOperation['op'] =
      owner.property === 'formula'
        ? 'setFormula'
        : owner.property === 'classes'
          ? 'setClasses'
          : ['precision', 'hide-zero', 'title'].includes(owner.property)
            ? 'setLayoutOption'
            : 'setText'
    const operation = { handle: owner.handle, op, value }
    const result = service.apply(operation, state.documents)
    if (!result.ok) {
      setNotice(result.reason)
      return
    }
    exampleGeneration.current++
    setInputs((previous) => {
      const next = { ...previous }
      delete next[owner.handle]
      return next
    })
    editedOnce.current = true
    setExample(undefined)
    if (owner.property === 'formula')
      navigation.current = { sequence: state.draftSequence + 1, position: selected, direction, horizontal }
    else if (['label', 'note', 'section-title', 'title'].includes(owner.property)) {
      navigation.current = undefined
      finishNavigation(selected, direction, horizontal)
    }
    dispatch({
      type: 'transaction',
      label: `${owner.property} · ${owner.nodeId ?? owner.declaration}`,
      patches: result.patches,
      ownerHandles: [owner.handle],
      operation,
    })
    setDrawer('Source changes')
    setNotice('')
  }
  function undo(redo = false) {
    if (redo ? !canRedo(state) : !canUndo(state)) return
    setInputs({})
    setExample(undefined)
    setEditing(undefined)
    dispatch({ type: redo ? 'redo' : 'undo' })
    const transaction = (redo ? state.history.future : state.history.past).at(-1)
    const owner = owners.find((item) => item.handle === transaction?.ownerHandles[0])
    if (owner) selectOwner(owner)
    setNotice(`${redo ? 'Redid' : 'Undid'} ${transaction?.label ?? 'source transaction'}`)
  }
  async function save() {
    if (state.conflict) {
      setConflictDialog(true)
      return
    }
    if (reason || !canSave(state) || saveAction.current) return
    const request = { documents: state.documents, baseRevisions: state.baseRevisions }
    const sequence = state.draftSequence
    saveAction.current = request
    dispatch({ type: 'saveStarted' })
    try {
      const result = await service.commit(request)
      if (
        !mounted.current ||
        saveAction.current !== request ||
        current.current.conflict ||
        current.current.draftSequence !== sequence ||
        !sameRevisions(current.current.baseRevisions, request.baseRevisions)
      )
        return
      if (result.ok) {
        dispatch({
          type: 'saveSucceeded',
          draftSequence: sequence,
          baseRevisions: result.baseRevisions,
          documents: result.documents,
        })
        setNotice(result.message)
      } else if (result.kind === 'conflict') {
        dispatch({
          type: 'saveFailed',
          draftSequence: sequence,
          kind: 'conflict',
          conflict: {
            documents: result.documents,
            baseRevisions: result.currentRevisions,
            changed: result.changed,
          },
        })
        setConflictDialog(true)
      } else {
        dispatch({ type: 'saveFailed', draftSequence: sequence, kind: 'invalid' })
        setNotice(result.reason)
      }
    } catch (failure) {
      if (mounted.current && saveAction.current === request) {
        dispatch({ type: 'saveFailed', draftSequence: sequence, kind: 'offline' })
        setNotice(failure instanceof Error ? failure.message : String(failure))
      }
    } finally {
      if (saveAction.current === request) saveAction.current = undefined
    }
  }
  async function previewExample() {
    if (!exampleEnabled || composing) return
    const generation = ++exampleGeneration.current
    const snapshot = { ...exampleContext.current }
    try {
      const result = await service.previewExampleInput(snapshot.text)
      const context = exampleContext.current
      if (
        mounted.current &&
        generation === exampleGeneration.current &&
        context.enabled &&
        context.text === snapshot.text &&
        context.sequence === snapshot.sequence &&
        sameRevisions(context.revisions, snapshot.revisions)
      )
        setExample(result)
    } catch (failure) {
      if (mounted.current && generation === exampleGeneration.current)
        setNotice(failure instanceof Error ? failure.message : String(failure))
    }
  }
  function nextProblem(backward: boolean) {
    const located = [...technical, ...runtime]
      .map((diagnostic) => ownerForDiagnostic(owners, diagnostic))
      .filter((owner) => !!owner)
    if (!located.length) return
    const index = located.findIndex((owner) => owner!.handle === selectedOwner?.handle)
    const next = located[(index + (backward ? -1 : 1) + located.length) % located.length]!
    selectOwner(next)
    setDrawer('Problems')
  }
  const formulaText = formulaOwner ? (inputs[formulaOwner.handle] ?? ownerText(formulaOwner)) : ''
  const formulaDiagnostics =
    !diagnosticsAreStale(state) && formulaOwner
      ? state.diagnostics.flatMap((diagnostic) => {
          const location = diagnostic.location
          if (ownerForDiagnostic(owners, diagnostic)?.handle !== formulaOwner.handle || !location) return []
          return [
            {
              from: Math.max(0, (location.startOffset ?? formulaOwner.range.start) - formulaOwner.range.start),
              to: Math.min(
                formulaText.length,
                (location.endOffset ?? formulaOwner.range.end) - formulaOwner.range.start,
              ),
              severity: diagnostic.severity,
              message: `${diagnostic.code}: ${diagnostic.message}`,
            },
          ]
        })
      : []
  const nodeCompletions = Object.entries(schema?.nodes ?? {}).map(([id, node]) => ({
    label: id,
    type: 'variable',
    detail: `${node.label} · ${node.type ?? node.kind}`,
  }))
  const fragmentCompletions = owners
    .filter((owner) => owner.kind === 'defn')
    .map((owner) => ({
      label: owner.nodeId ?? owner.declaration,
      type: 'function',
      detail: 'Shared fragment declaration',
    }))
  const grid = table ? (
    <section className="author-card author-grid-panel" data-author-region="grid" aria-label="Author grid">
      <p className="author-reference">Paper · {paper?.title}</p>
      <h2>{table.title}</h2>
      {candidate && <p className="author-stale">Candidate example input preview · recorded, not saved</p>}
      {state.conflict ? (
        <p className="author-stale">Preview based on old source revisions · resolve the conflict to refresh.</p>
      ) : stale && preview ? (
        <p className="author-stale">Previous valid preview (draft #{preview.draftSequence})</p>
      ) : null}
      {state.preview.status === 'unavailable' && <p className="author-stale">{previewStatus(state)}</p>}
      <div className="author-toolbar">
        <label>
          Find in table <input type="search" value={query} onChange={(event) => setQuery(event.target.value)} />
        </label>
        <button
          onClick={() => {
            const matches = paperMatches(table, query)
            const next = matches.find((row) => row > selected.row) ?? matches[0]
            if (next !== undefined) {
              setSelected({ row: next, column: 0 })
              setExplicitHandle(undefined)
            }
          }}
        >
          Next match
        </button>
        <label>
          <input type="checkbox" checked={showFormulas} onChange={(event) => setShowFormulas(event.target.checked)} />
          Show formulas
        </label>
        <label>
          <input type="checkbox" checked disabled /> Show zero rows
        </label>
      </div>
      <p className="author-reference">
        Zero rows are included in these recorded states; no alternate visibility is recorded.
      </p>
      <AuthoringGrid
        table={table}
        selected={selected}
        ownerFor={ownerAt}
        onSelect={(position) => {
          setSelected(position)
          setExplicitHandle(undefined)
        }}
        onEdit={(owner, initialText, composition) => {
          const begin = () => {
            selectOwner(owner)
            setEditing(owner.handle)
            setInspector('Property')
            setInspectorOpen(true)
            if (initialText !== undefined) input(owner.handle, initialText)
          }
          if (composition) {
            flushSync(begin)
            const field = element.current?.querySelector<HTMLElement>(
              `[aria-label="${owner.property === 'formula' ? 'Mantra DSL formula' : 'Property text'}"]`,
            )
            if (field instanceof HTMLInputElement) {
              field.focus()
              field.select()
            } else if (field) {
              const editor = EditorView.findFromDOM(field)
              editor?.dispatch({ selection: { anchor: 0, head: editor.state.doc.length } })
              editor?.focus()
            }
          } else begin()
        }}
        showFormulas={showFormulas}
        formulaFor={(row, column) => {
          const owner = ownerAt(row, column)
          return owner && owner.property === 'formula' ? ownerText(owner) : undefined
        }}
        invalidHandles={invalidHandles}
        errorPreview={
          preview?.kind === 'runtimeFailure'
            ? state.conflict || stale
              ? 'previous-runtime'
              : 'current-runtime'
            : 'previous-valid'
        }
        onRangeChange={setRange}
        onToggleFormulas={() => setShowFormulas((value) => !value)}
        onPaste={(text, owner) => {
          if (!owner) return
          input(owner.handle, text)
          selectOwner(owner)
          setEditing(owner.handle)
          if (owner.property !== 'formula') apply(owner, text)
        }}
      />
    </section>
  ) : (
    <p>No Paper for this panel yet.</p>
  )
  const source = (
    <SourcePane
      state={state}
      owners={owners}
      selectedOwner={selectedOwner}
      inputs={inputs}
      onInput={input}
      onComposition={setComposing}
      onSelect={selectOwner}
      onPatch={(patch) => {
        editedOnce.current = true
        setExample(undefined)
        const before = state.documents[patch.document]
        const after = before.slice(0, patch.start) + patch.text + before.slice(patch.end)
        setInputs((previous) => {
          const next = { ...previous }
          if (next[`source:${patch.document}`] === after) delete next[`source:${patch.document}`]
          return next
        })
        dispatch({ type: 'transaction', label: `Source edit · ${patch.document}`, patches: [patch] })
      }}
    />
  )

  const closeInspector = () => {
    setInspectorOpen(false)
    focusGrid()
  }
  const outlinePanel = (
    <AuthoringOutline
      owners={owners}
      selectedOwner={selectedOwner}
      structure={schema}
      paper={paper}
      run={preview?.responses.run?.data}
      panelId={recording.panel}
      invalidHandles={invalidHandles}
      onSelect={(owner) => {
        selectOwner(owner)
        setOutlineOpen(false)
      }}
    />
  )
  const effectiveView = inspectorOverlay && view === 'Split' ? 'Grid' : view
  const mobileFormula =
    inspectorOverlay && inspectorOpen && selectedOwner?.property === 'formula' && inspector === 'Property'
  const formulaPanel = (
    <section className="author-card author-formula" data-author-region="formula">
      <h2>Mantra DSL formula</h2>
      <p>
        {formulaOwner?.nodeId ?? 'Select a formula cell'} · {formulaOwner?.kind}
      </p>
      <AuthoringCodeEditor
        value={formulaText}
        ariaLabel="Mantra DSL formula"
        editorKey={formulaOwner?.handle ?? 'no-owner'}
        readOnly={!formulaOwner?.editable || !!state.saving}
        onChange={(value) => {
          if (formulaOwner) input(formulaOwner.handle, value)
        }}
        onCommit={(value) => {
          if (formulaOwner) apply(formulaOwner, value)
        }}
        onCancel={() => {
          if (formulaOwner) input(formulaOwner.handle, ownerText(formulaOwner))
          setEditing(undefined)
          closeInspector()
        }}
        autoFocus={!!formulaOwner && editing === formulaOwner.handle}
        selectAll={!!formulaOwner && editing === formulaOwner.handle}
        onCompositionChange={setComposing}
        diagnostics={formulaDiagnostics}
        completions={[...nodeCompletions, ...fragmentCompletions]}
      />
      <button
        disabled={!formulaOwner?.editable || !!state.saving || composing}
        onClick={() => {
          if (formulaOwner) apply(formulaOwner, formulaText)
        }}
      >
        Apply formula
      </button>
      <p className="author-reference">Completions from recorded structure (simulated language service)</p>
      {formulaOwner && !formulaOwner.editable && <p>{formulaOwner.reason}</p>}
    </section>
  )
  const inspectorPanel = (
    <AuthoringInspector
      inspector={inspector}
      setInspector={setInspector}
      state={state}
      selectedOwner={selectedOwner}
      textOwner={textOwner}
      classOwner={classOwner}
      owners={owners}
      inputs={inputs}
      input={input}
      apply={apply}
      composing={composing}
      setComposing={setComposing}
      editing={editing}
      propertyField={propertyField}
      onCancel={() => {
        setEditing(undefined)
        closeInspector()
      }}
      selectOwner={selectOwner}
      onApplyClasses={(classes) => {
        if (!classOwner) return
        const targets = range.map((position) => ownerAt(position.row, position.column)).filter((owner) => !!owner)
        if (targets.length > 1) {
          setNotice('Multi-row class editing is planned; apply to one declaration at a time.')
          return
        }
        apply(classOwner, classes)
      }}
      multipleClasses={range.length > 1}
      selectedCell={selectedCell}
      preview={preview}
      caseId={recording.case}
      exampleInputLabel={config.exampleInput}
      exampleEnabled={exampleEnabled}
      formulaEditor={mobileFormula ? formulaPanel : undefined}
      exampleText={exampleText}
      example={example}
      candidate={candidate}
      onExampleChange={(text) => {
        exampleGeneration.current++
        setExampleText(text)
        setExample(undefined)
      }}
      onPreviewExample={() => void previewExample()}
    />
  )
  return (
    <main
      className="authoring"
      ref={element}
      data-draft-sequence={state.draftSequence}
      data-preview-kind={state.validity}
      data-author-stage="editor"
      onKeyDownCapture={(event) => {
        if ((event.target as HTMLElement).closest('[data-author-region="backup"]') && event.key !== 'F6') return
        if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229 || composing) return
        const modifier = event.ctrlKey || event.metaKey
        if (modifier && event.key.toLowerCase() === 's') {
          event.preventDefault()
          void save()
        }
        if (modifier && event.key === '`') {
          event.preventDefault()
          setShowFormulas((value) => !value)
        }
        if (modifier && !pending && (event.key.toLowerCase() === 'z' || event.key.toLowerCase() === 'y')) {
          event.preventDefault()
          event.stopPropagation()
          undo(event.shiftKey || event.key.toLowerCase() === 'y')
        }
        if (event.key === 'F8') {
          event.preventDefault()
          nextProblem(event.shiftKey)
        }
        if (event.key === 'F6') {
          event.preventDefault()
          const regions = [...(element.current?.querySelectorAll<HTMLElement>('[data-author-region]') ?? [])]
          const index = regions.findIndex((region) => region.contains(document.activeElement))
          const next = regions[(index + (event.shiftKey ? -1 : 1) + regions.length) % regions.length]
          next?.querySelector<HTMLElement>('button, input, [tabindex="0"], .cm-content')?.focus()
        }
      }}
    >
      <a className="skip-link" href="#author-grid">
        Skip to grid
      </a>
      <header className="author-identity" data-author-region="identity">
        <div>
          <span className="author-badge">Prototype · recorded engine data</span>
          <h1>Template · {schema?.schema ?? opened.identity.title}</h1>
          <p>
            v{schema?.schemaVersion} · source{' '}
            <code title={JSON.stringify(state.baseRevisions)}>{savedDigest.slice(0, 8)}</code> · draft #
            {state.draftSequence}
          </p>
          <p>Runtime · Mantra author preview · example inputs: {recording.case}</p>
        </div>
        <div>
          <ThemeControl lang="en" />
          {capsule.label && <p role="status">{capsule.label}</p>}
        </div>
      </header>
      {recovery && (
        <section className="author-recovery" role="status">
          <p>
            A draft from {recoveryTime(recovery.savedAt)} was found in this browser. It has not been validated or saved.
          </p>
          <button onClick={() => onRestore(recovery)}>Restore</button>{' '}
          <button onClick={onDiscardRecovery}>Discard</button>
        </section>
      )}
      <div className="author-toolbar">
        <button
          disabled={!canUndo(state)}
          onClick={() => undo()}
          title={`Undo: ${state.history.past.at(-1)?.label ?? 'none'} (template)`}
        >
          Undo
        </button>
        <button disabled={!canRedo(state)} onClick={() => undo(true)}>
          Redo
        </button>
        <button onClick={() => void save()} disabled={!state.conflict && !!reason} title={reason}>
          Save
        </button>
        {reason && <span className="author-reference">{reason}</span>}
        {outlineOverlay && <button onClick={() => setOutlineOpen(true)}>Open outline</button>}
        {inspectorOverlay && <button onClick={() => setInspectorOpen(true)}>Open inspector</button>}
        {(['Grid', 'Source', ...(inspectorOverlay ? [] : ['Split'])] as Array<'Grid' | 'Source' | 'Split'>).map(
          (mode) => (
            <button key={mode} aria-pressed={effectiveView === mode} onClick={() => setView(mode)}>
              {mode}
            </button>
          ),
        )}
        <button onClick={() => setDrawer('Build')}>View build report</button>
      </div>
      {state.conflict && (
        <p className="author-stale">
          Conflict · changed outside this editor (simulated). Your draft is kept.
          <button onClick={() => setConflictDialog(true)}>Review conflict</button>
        </p>
      )}
      <div className="author-layout">
        {!outlineOverlay && outlinePanel}

        <div id="author-grid" className="author-center">
          {!mobileFormula && formulaPanel}

          <div className={effectiveView === 'Split' ? 'author-work-area author-split' : 'author-work-area'}>
            {effectiveView !== 'Source' && grid}
            {effectiveView !== 'Grid' && source}
          </div>
        </div>
        {!inspectorOverlay && inspectorPanel}
      </div>
      <AuthoringDrawer
        tab={drawer}
        onTab={setDrawer}
        state={state}
        owners={owners}
        onSelect={selectOwner}
        onRevert={() => undo()}
        report={service.exportReport()?.data}
        problemCount={technical.length + runtime.length}
      />
      <footer className="author-status" role="status" aria-live="polite">
        <strong data-testid="preview-status">{previewStatus(state)}</strong>
        <span>Findings: {findings.length}</span>
        <span>Runtime failures: {runtime.length}</span>
        <span>Errors: {technical.length + runtime.length}</span>
        <span>
          Mantra {String(recording.engine.mantra)} · Normein {String(recording.engine.normein)}
        </span>
        {notice && <span>{notice}</span>}
        {recoveryStatus.status !== 'idle' && <span>{recoveryStatusMessage(recoveryStatus)}</span>}
      </footer>
      <details className="author-controls">
        <summary>Prototype controls (simulated)</summary>
        <div className="author-toolbar">
          <button
            onClick={() => {
              service.delayNextPreview(2000)
              setNotice('Next preview delayed by 2 s')
            }}
          >
            Delay next preview by 2 s
          </button>
          <button
            disabled={!!state.saving}
            onClick={() =>
              void service.simulateExternalChange().then((change) => {
                saveAction.current = undefined
                dispatch({ type: 'externalChanged', ...change })
                setExample(undefined)
                setNotice('External edit simulated')
              })
            }
          >
            Simulate external edit to layout.mantra
          </button>
          <button onClick={onReset}>Reset prototype to recorded base</button>
        </div>
        <p>These controls simulate timing and external events. No source file is written.</p>
        <AuthoringBackupPanel
          recording={recording}
          state={state}
          inputs={inputs}
          recoveryStatus={recoveryStatus}
          onImport={onRestore}
        />
      </details>
      {outlineOverlay && outlineOpen && (
        <div className="author-outline-overlay">
          <PrototypeDialog title="Outline" onClose={() => setOutlineOpen(false)}>
            <button aria-label="Close Outline" onClick={() => setOutlineOpen(false)}>
              Close outline
            </button>
            {outlinePanel}
          </PrototypeDialog>
        </div>
      )}
      {inspectorOverlay && inspectorOpen && (
        <div className="author-inspector-overlay">
          <PrototypeDialog title="Inspector" onClose={closeInspector}>
            <button aria-label="Close Inspector" onClick={closeInspector}>
              Return to grid
            </button>
            {inspectorPanel}
          </PrototypeDialog>
        </div>
      )}
      {conflictDialog && state.conflict && (
        <PrototypeDialog title="Source revision conflict (simulated)" onClose={() => setConflictDialog(false)}>
          <p>Your draft and source history have been preserved.</p>
          {state.conflict.changed.map((path) => (
            <section key={path}>
              <h3>{path}</h3>
              <SourceDocumentDiff
                document={path}
                before={state.baseDocuments[path]}
                after={state.conflict!.documents[path]}
                fallbackOperation="External source edit"
              />
            </section>
          ))}
          {state.conflict.overlapping?.length ? (
            <p>Overlapping source edits need explicit owner resolution; discard or keep this draft for review.</p>
          ) : null}
          <button
            onClick={() => {
              dispatch({ ...state.conflict!, type: 'rebase' })
              setConflictDialog(false)
            }}
          >
            Re-preview on the new base
          </button>{' '}
          <button
            onClick={() => {
              dispatch({ type: 'discard' })
              setInputs({})
              setConflictDialog(false)
            }}
          >
            Discard my draft
          </button>{' '}
          <button onClick={() => setConflictDialog(false)}>Keep editing</button>
        </PrototypeDialog>
      )}
    </main>
  )
}

export default AuthoringPrototype
