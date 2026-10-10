import { useEffect, useLayoutEffect, useRef } from 'react'
import { Annotation, Compartment, EditorState, Prec } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { autocompletion, completionKeymap, type Completion } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint'

export type AuthoringCompletion = Pick<Completion, 'label' | 'type' | 'detail'> & { apply?: string }
export type AuthoringCodeDiagnostic = Diagnostic

export interface AuthoringCodeEditorProps {
  value: string
  onChange: (value: string) => void
  ariaLabel: string
  onCommit?: (value: string) => void
  onCancel?: () => void
  onSelection?: (from: number, to: number) => void
  onCompositionChange?: (composing: boolean) => void
  completions?: readonly AuthoringCompletion[]
  diagnostics?: readonly AuthoringCodeDiagnostic[]
  readOnly?: boolean
  autoFocus?: boolean
  selectAll?: boolean
  editorKey?: string
  selection?: { from: number; to: number }
}

const externalUpdate = Annotation.define<boolean>()

/** Shared controlled editor for formula drafts and the source pane; values are never evaluated here. */
export function AuthoringCodeEditor(props: AuthoringCodeEditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const current = useRef(props)
  const composing = useRef(false)
  const editable = useRef(new Compartment())
  useLayoutEffect(() => {
    current.current = props
  })

  useEffect(() => {
    if (!host.current) return
    const initial = current.current
    const nonce = document.querySelector<HTMLMetaElement>('meta[name="mantra-style-nonce"]')?.content
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: initial.value,
        selection: initial.autoFocus && initial.selectAll ? { anchor: 0, head: initial.value.length } : undefined,
        extensions: [
          ...(nonce ? [EditorView.cspNonce.of(nonce)] : []),
          editable.current.of([
            EditorState.readOnly.of(initial.readOnly ?? false),
            EditorView.editable.of(!initial.readOnly),
            EditorView.contentAttributes.of({ 'aria-label': initial.ariaLabel }),
          ]),
          history(),
          Prec.highest(
            keymap.of([
              ...completionKeymap,
              {
                key: 'Mod-Enter',
                run: (active) => {
                  if (composing.current || active.composing || current.current.readOnly) return false
                  if (!current.current.onCommit) return false
                  current.current.onCommit(active.state.doc.toString())
                  return true
                },
                stopPropagation: true,
              },
              {
                key: 'Tab',
                run: (active) => {
                  if (composing.current || active.composing || current.current.readOnly) return false
                  if (!current.current.onCommit) return false
                  current.current.onCommit(active.state.doc.toString())
                  return true
                },
                shift: (active) => {
                  if (composing.current || active.composing || current.current.readOnly) return false
                  if (!current.current.onCommit) return false
                  current.current.onCommit(active.state.doc.toString())
                  return true
                },
                stopPropagation: true,
              },
              {
                key: 'Escape',
                run: (active) => {
                  if (composing.current || active.composing || !current.current.onCancel) return false
                  current.current.onCancel()
                  return true
                },
                stopPropagation: true,
              },
            ]),
          ),
          keymap.of([...defaultKeymap, ...historyKeymap]),
          autocompletion({
            override: [
              (context) => {
                if (composing.current || context.view?.composing || current.current.readOnly) return null
                const word = context.matchBefore(/[\w./:+*-]*/)
                if (!word || (!context.explicit && word.from === word.to)) return null
                const options = current.current.completions
                return options?.length ? { from: word.from, options: [...options] } : null
              },
            ],
          }),
          EditorView.lineWrapping,
          lintGutter(),
          EditorView.domEventHandlers({
            compositionstart() {
              composing.current = true
              current.current.onCompositionChange?.(true)
            },
            compositionend() {
              composing.current = false
              current.current.onCompositionChange?.(false)
            },
          }),
          EditorView.updateListener.of((update) => {
            if (update.transactions.some((transaction) => transaction.annotation(externalUpdate))) return
            if (update.docChanged) current.current.onChange(update.state.doc.toString())
            if (update.selectionSet || update.docChanged) {
              const selection = update.state.selection.main
              current.current.onSelection?.(selection.from, selection.to)
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
      editor.destroy()
      view.current = null
      if (composing.current) current.current.onCompositionChange?.(false)
      composing.current = false
    }
  }, [props.editorKey])

  useEffect(() => {
    const editor = view.current
    if (!editor || editor.state.doc.toString() === props.value) return
    editor.dispatch({
      changes: { from: 0, to: editor.state.doc.length, insert: props.value },
      annotations: externalUpdate.of(true),
    })
  }, [props.value, props.editorKey])

  useEffect(() => {
    const editor = view.current
    if (!editor) return
    editor.dispatch({
      effects: editable.current.reconfigure([
        EditorState.readOnly.of(props.readOnly ?? false),
        EditorView.editable.of(!props.readOnly),
        EditorView.contentAttributes.of({ 'aria-label': props.ariaLabel }),
      ]),
    })
  }, [props.readOnly, props.ariaLabel, props.editorKey])

  useEffect(() => {
    const editor = view.current
    if (!editor) return
    const length = editor.state.doc.length
    const diagnostics = (props.diagnostics ?? []).map((item) => {
      const from = Math.max(0, Math.min(item.from, length))
      return { ...item, from, to: Math.max(from, Math.min(item.to, length)) }
    })
    editor.dispatch(setDiagnostics(editor.state, diagnostics))
  }, [props.diagnostics, props.editorKey, props.value])

  useEffect(() => {
    const editor = view.current
    if (!editor || !props.selection) return
    const length = editor.state.doc.length
    editor.dispatch({
      selection: {
        anchor: Math.max(0, Math.min(props.selection.from, length)),
        head: Math.max(0, Math.min(props.selection.to, length)),
      },
      annotations: externalUpdate.of(true),
      scrollIntoView: true,
    })
  }, [props.selection, props.editorKey])

  useEffect(() => {
    const editor = view.current
    if (!editor || !props.autoFocus) return
    if (props.selectAll)
      editor.dispatch({
        selection: { anchor: 0, head: editor.state.doc.length },
        annotations: externalUpdate.of(true),
      })
    editor.focus()
  }, [props.autoFocus, props.selectAll, props.editorKey])

  return (
    <div
      className="authoring-code-editor"
      ref={host}
      onKeyDownCapture={(event) => {
        // Let the IME consume its own keys without CodeMirror commands or parent shortcuts.
        if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229 || composing.current)
          event.stopPropagation()
      }}
    />
  )
}
