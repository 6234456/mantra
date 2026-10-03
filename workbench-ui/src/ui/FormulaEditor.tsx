import { useEffect, useRef, useState } from 'react'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { autocompletion, completionKeymap } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { lintGutter, setDiagnostics, type Diagnostic as CodeDiagnostic } from '@codemirror/lint'
import type { WorkbenchData } from '../data'
import type { AuthoringFinding, AuthoringHover, AuthoringTarget, FormulaOperation, FormulaEditResult } from '../types'
import { language } from '../i18n'

const lang = language()
const de = (text: string, english: string) => (lang === 'en' ? english : text)

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
  const targetRef = useRef(target)
  targetRef.current = target
  const [formula, setFormula] = useState(initialFormula)
  const [findings, setFindings] = useState<AuthoringFinding[]>([])
  const [hover, setHover] = useState<AuthoringHover['hover']>(null)
  const [preview, setPreview] = useState<FormulaEditResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!host.current) return
    setFormula(initialFormula)
    let hoverTimer: ReturnType<typeof setTimeout> | undefined
    const styleNonce = document.querySelector<HTMLMetaElement>('meta[name="mantra-style-nonce"]')?.content
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: initialFormula,
        extensions: [
          ...(styleNonce ? [EditorView.cspNonce.of(styleNonce)] : []),
          history(),
          keymap.of([...defaultKeymap, ...historyKeymap, ...completionKeymap]),
          EditorView.lineWrapping,
          lintGutter(),
          autocompletion({
            override: [
              async (context) => {
                try {
                  const result = (
                    await data.authoring(
                      caseId,
                      'complete',
                      targetRef.current,
                      context.state.doc.toString(),
                      context.pos,
                    )
                  ).data
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
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              setFormula(update.state.doc.toString())
              setFindings([])
              setPreview(null)
              setError('')
            }
            if (update.selectionSet) {
              if (hoverTimer) clearTimeout(hoverTimer)
              hoverTimer = setTimeout(() => {
                void data
                  .authoring(
                    caseId,
                    'hover',
                    targetRef.current,
                    update.state.doc.toString(),
                    update.state.selection.main.head,
                  )
                  .then((result) => setHover(result.data.hover))
                  .catch(() => setHover(null))
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
      editor.destroy()
      view.current = null
    }
  }, [caseId, data, initialFormula])

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

  async function check(): Promise<boolean> {
    setBusy(true)
    setError('')
    try {
      const result = (await data.authoring(caseId, 'check', targetRef.current, formula)).data
      setFindings(result.diagnostics)
      return result.valid
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
      return false
    } finally {
      setBusy(false)
    }
  }
  async function calculate() {
    setBusy(true)
    setError('')
    setPreview(null)
    try {
      const result = await data.formulaEdit(caseId, revision, operation(formula), true)
      setPreview(result.data)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setBusy(false)
    }
  }
  async function save() {
    if (!(await check())) return
    setBusy(true)
    try {
      await data.formulaEdit(caseId, revision, operation(formula), false)
      onSaved()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setBusy(false)
    }
  }
  const values = preview?.run.values[target.id]
  return (
    <div className="formula-editor">
      <div className="formula-source" ref={host} aria-label={de('Formel', 'Formula')} />
      <div className="formula-actions">
        <button type="button" disabled={busy} onClick={() => void check()}>
          {de('Prüfen', 'Check')}
        </button>
        <button type="button" disabled={busy} onClick={() => void calculate()}>
          {de('Vorschau', 'Preview')}
        </button>
        <button type="button" className="primary-button" disabled={busy} onClick={() => void save()}>
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
