import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { autocompletion, closeCompletion, completionKeymap } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { lintGutter, setDiagnostics, type Diagnostic as CodeDiagnostic } from '@codemirror/lint'
import type { WorkbenchData } from '../data'
import type { AuthoringFinding, AuthoringHover, AuthoringTarget, FormulaOperation, FormulaEditResult } from '../types'
import { language } from '../i18n'

const lang = language()
const de = (text: string, english: string) => (lang === 'en' ? english : text)

type FormulaSnapshot = {
  editor: EditorView
  epoch: number
  contextVersion: number
  caseId: string
  revision: string
  target: AuthoringTarget
  source: string
  data: WorkbenchData
}

function sameOwner(request: FormulaSnapshot, current: Pick<FormulaSnapshot, 'caseId' | 'target' | 'data'>) {
  return (
    request.caseId === current.caseId &&
    request.data === current.data &&
    request.target.kind === current.target.kind &&
    request.target.id === current.target.id &&
    (request.target.kind !== 'extension' ||
      (current.target.kind === 'extension' && request.target.slot === current.target.slot))
  )
}

export function FormulaEditor({
  caseId,
  revision,
  target,
  initialFormula,
  operation,
  data,
  onSaved,
}: {
  caseId: string
  revision: string
  target: AuthoringTarget
  initialFormula: string
  operation: (formula: string) => FormulaOperation
  data: WorkbenchData
  onSaved: () => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const targetIdentity = JSON.stringify([target.kind, target.id, target.kind === 'extension' ? target.slot : null])
  const targetKey = JSON.stringify([
    target.kind,
    target.id,
    target.kind === 'extension' ? target.slot : null,
    target.kind === 'extension' ? target.title : null,
  ])
  const context = useRef({ caseId, revision, target, targetKey, data, operation, onSaved })
  const contextVersion = useRef(0)
  const epoch = useRef(0)
  const hoverSequence = useRef(0)
  const source = useRef(initialFormula)
  const loadedSource = useRef(initialFormula)
  const loadedIdentity = useRef({ caseId, targetIdentity })
  const editable = useRef(new Compartment())
  const action = useRef<FormulaSnapshot | null>(null)
  const pendingWrite = useRef<FormulaSnapshot | null>(null)
  const composingRef = useRef(false)
  const [composing, setComposing] = useState(false)
  const [findings, setFindings] = useState<AuthoringFinding[]>([])
  const [hover, setHover] = useState<AuthoringHover['hover']>(null)
  const [preview, setPreview] = useState<FormulaEditResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const invalidate = useCallback(() => {
    epoch.current += 1
    hoverSequence.current += 1
    setFindings([])
    setPreview(null)
    setHover(null)
    setError('')
  }, [])

  const snapshot = useCallback((): FormulaSnapshot | null => {
    const editor = view.current
    if (!editor) return null
    const current = context.current
    return {
      editor,
      epoch: epoch.current,
      contextVersion: contextVersion.current,
      caseId: current.caseId,
      revision: current.revision,
      target: { ...current.target },
      source: editor.state.doc.toString(),
      data: current.data,
    }
  }, [])

  const isCurrent = useCallback((request: FormulaSnapshot) => {
    return (
      view.current === request.editor &&
      epoch.current === request.epoch &&
      contextVersion.current === request.contextVersion &&
      request.editor.state.doc.toString() === request.source
    )
  }, [])

  useLayoutEffect(() => {
    const previous = context.current
    context.current = { caseId, revision, target, targetKey, data, operation, onSaved }
    if (
      previous.caseId !== caseId ||
      previous.revision !== revision ||
      previous.targetKey !== targetKey ||
      previous.data !== data
    ) {
      contextVersion.current += 1
      if (view.current) closeCompletion(view.current)
      invalidate()
    }
  }, [caseId, revision, target, targetKey, data, operation, onSaved, invalidate])

  useEffect(() => {
    if (!host.current) return
    const previous = loadedIdentity.current
    const keepDraft =
      previous.caseId === caseId &&
      previous.targetIdentity === targetIdentity &&
      source.current !== loadedSource.current
    const documentSource = keepDraft ? source.current : initialFormula
    source.current = documentSource
    loadedSource.current = initialFormula
    loadedIdentity.current = { caseId, targetIdentity }
    composingRef.current = false
    setComposing(false)
    invalidate()
    const readOnly = !!pendingWrite.current && sameOwner(pendingWrite.current, context.current)
    let hoverTimer: ReturnType<typeof setTimeout> | undefined
    const styleNonce = document.querySelector<HTMLMetaElement>('meta[name="mantra-style-nonce"]')?.content
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: documentSource,
        extensions: [
          ...(styleNonce ? [EditorView.cspNonce.of(styleNonce)] : []),
          history(),
          editable.current.of([EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)]),
          keymap.of([...defaultKeymap, ...historyKeymap, ...completionKeymap]),
          EditorView.lineWrapping,
          lintGutter(),
          autocompletion({
            override: [
              async (context) => {
                const request = snapshot()
                if (
                  !request ||
                  request.source !== context.state.doc.toString() ||
                  composingRef.current ||
                  request.editor.composing
                )
                  return null
                try {
                  const response = await request.data.authoring(
                    request.caseId,
                    'complete',
                    request.target,
                    request.source,
                    context.pos,
                  )
                  if (!isCurrent(request) || context.aborted || response.revision !== request.revision) return null
                  const result = response.data
                  if (!result.items.length) return null
                  return {
                    from: result.replacementRange.startOffset,
                    to: result.replacementRange.endOffset,
                    options: result.items.map((item) => ({
                      label: item.label,
                      apply: item.insertText,
                      type: item.kind,
                      detail: item.detail,
                      info: item.documentation ?? undefined,
                    })),
                  }
                } catch {
                  return null
                }
              },
            ],
          }),
          EditorView.domEventHandlers({
            compositionstart() {
              composingRef.current = true
              setComposing(true)
              invalidate()
            },
            compositionend() {
              composingRef.current = false
              setComposing(false)
            },
          }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              source.current = update.state.doc.toString()
              invalidate()
            }
            if (update.selectionSet || update.docChanged) {
              if (hoverTimer) clearTimeout(hoverTimer)
              const sequence = ++hoverSequence.current
              setHover(null)
              if (composingRef.current || update.view.composing) return
              const request = snapshot()
              if (!request) return
              const cursor = update.state.selection.main.head
              hoverTimer = setTimeout(() => {
                if (!isCurrent(request) || hoverSequence.current !== sequence) return
                void request.data
                  .authoring(request.caseId, 'hover', request.target, request.source, cursor)
                  .then((result) => {
                    if (isCurrent(request) && hoverSequence.current === sequence) {
                      setHover(result.revision === request.revision ? result.data.hover : null)
                    }
                  })
                  .catch(() => {
                    if (isCurrent(request) && hoverSequence.current === sequence) setHover(null)
                  })
              }, 250)
            }
          }),
          EditorView.theme({
            '&': { minHeight: '108px', fontSize: '12px', backgroundColor: 'var(--field)', color: 'var(--ink)' },
            '.cm-content': { fontFamily: 'IBM Plex Mono, Menlo, monospace', padding: '12px' },
            '.cm-gutters': {
              backgroundColor: 'var(--bar)',
              color: 'var(--ink-3)',
              borderRight: '1px solid var(--line-soft)',
            },
            '&.cm-focused': { outline: '2px solid var(--blue)', outlineOffset: '-2px' },
          }),
        ],
      }),
    })
    view.current = editor
    return () => {
      if (hoverTimer) clearTimeout(hoverTimer)
      epoch.current += 1
      hoverSequence.current += 1
      editor.destroy()
      view.current = null
    }
  }, [caseId, data, initialFormula, targetIdentity, invalidate, snapshot, isCurrent])

  useEffect(() => {
    const editor = view.current
    if (!editor) return
    const diagnostics: CodeDiagnostic[] = findings
      .filter((item) => item.range)
      .map((item) => ({
        from: Math.min(item.range!.startOffset, editor.state.doc.length),
        to: Math.min(Math.max(item.range!.endOffset, item.range!.startOffset), editor.state.doc.length),
        severity: item.severity,
        message: `${item.code}: ${item.message}`,
      }))
    editor.dispatch(setDiagnostics(editor.state, diagnostics))
  }, [findings])

  function beginAction() {
    if (action.current || composingRef.current || view.current?.composing) return null
    const request = snapshot()
    if (!request) return null
    action.current = request
    setBusy(true)
    setError('')
    return request
  }

  function finishAction(request: FormulaSnapshot) {
    if (action.current !== request) return
    action.current = null
    if (view.current) setBusy(false)
  }

  function checkRevision(request: FormulaSnapshot, actual: string) {
    if (actual === request.revision) return true
    setError(de('Die Quelldokumente wurden geändert. Bitte neu laden.', 'Source documents changed. Please reload.'))
    return false
  }

  async function checkSnapshot(request: FormulaSnapshot): Promise<boolean> {
    const result = await request.data.authoring(request.caseId, 'check', request.target, request.source)
    if (!isCurrent(request) || composingRef.current || request.editor.composing) return false
    if (!checkRevision(request, result.revision)) return false
    setFindings(result.data.diagnostics)
    return result.data.valid
  }

  async function check(): Promise<void> {
    const request = beginAction()
    if (!request) return
    try {
      await checkSnapshot(request)
    } catch (failure) {
      if (isCurrent(request)) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      finishAction(request)
    }
  }
  async function calculate() {
    const request = beginAction()
    if (!request) return
    setPreview(null)
    try {
      const edit = context.current.operation(request.source)
      const result = await request.data.formulaEdit(request.caseId, request.revision, edit, true)
      if (isCurrent(request) && checkRevision(request, result.revision)) setPreview(result.data)
    } catch (failure) {
      if (isCurrent(request)) setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      finishAction(request)
    }
  }
  async function save() {
    const request = beginAction()
    if (!request) return
    try {
      const edit = context.current.operation(request.source)
      if (!(await checkSnapshot(request))) return
      if (!isCurrent(request)) return
      // A dispatched write cannot be cancelled by ignoring its response. Keep this draft fixed until it settles.
      pendingWrite.current = request
      request.editor.dispatch({
        effects: editable.current.reconfigure([EditorState.readOnly.of(true), EditorView.editable.of(false)]),
      })
      await request.data.formulaEdit(request.caseId, request.revision, edit, false)
      // Metadata or revision changes cannot undo that write. Notify the current callback for the same owner.
      if (view.current && sameOwner(request, context.current)) context.current.onSaved()
    } catch (failure) {
      if (
        isCurrent(request) ||
        (pendingWrite.current === request && view.current && sameOwner(request, context.current))
      )
        setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      if (pendingWrite.current === request) {
        pendingWrite.current = null
        if (view.current && sameOwner(request, context.current)) {
          view.current.dispatch({
            effects: editable.current.reconfigure([EditorState.readOnly.of(false), EditorView.editable.of(true)]),
          })
        }
      }
      finishAction(request)
    }
  }
  const values = preview?.run.values[target.id]
  return (
    <div className="formula-editor">
      <div className="formula-source" ref={host} aria-label={de('Formel', 'Formula')} />
      <div className="formula-actions">
        <button type="button" disabled={busy || composing} onClick={() => void check()}>
          {de('Prüfen', 'Check')}
        </button>
        <button type="button" disabled={busy || composing} onClick={() => void calculate()}>
          {de('Vorschau', 'Preview')}
        </button>
        <button type="button" className="primary-button" disabled={busy || composing} onClick={() => void save()}>
          {de('Speichern', 'Save')}
        </button>
      </div>
      {hover && (
        <div className="formula-hover">
          <strong>{hover.symbol}</strong>
          <span>{hover.detail}</span>
          {hover.current && <code>{hover.current.display}</code>}
          {hover.documentation && <small>{hover.documentation}</small>}
        </div>
      )}
      {findings.length > 0 && (
        <div className="formula-findings" role="status">
          {findings.map((item, index) => (
            <p key={`${item.code}-${index}`} className={item.severity}>
              <code>{item.code}</code> {item.message}
            </p>
          ))}
        </div>
      )}
      {error && (
        <p className="input-error" role="alert">
          {error}
        </p>
      )}
      {preview && (
        <div className="formula-preview">
          <span className="eyebrow">{de('Vorschau · nicht gespeichert', 'Preview · not saved')}</span>
          {values ? (
            Object.entries(values).map(([coord, item]) => (
              <div key={coord}>
                <span>{coord || target.id}</span>
                <strong>{item.display}</strong>
              </div>
            ))
          ) : (
            <p>{de('Keine Ergebniszeile', 'No result row')}</p>
          )}
          {preview.difference.mainline.map((change, index) => (
            <small key={`${change.node}-${index}`}>
              {change.node}: {change.display.base ?? '—'} → {change.display.variant ?? '—'}
            </small>
          ))}
        </div>
      )}
    </div>
  )
}
