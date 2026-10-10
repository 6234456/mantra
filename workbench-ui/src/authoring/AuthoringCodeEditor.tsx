import { useEffect, useLayoutEffect, useRef } from 'react'
import { Annotation, Compartment, EditorState, Prec, StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, keymap, type DecorationSet } from '@codemirror/view'
import { autocompletion, completionKeymap, type Completion } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint'

export type AuthoringCompletion = Pick<Completion, 'label' | 'type' | 'detail'> & { apply?: string }
export type AuthoringCodeDiagnostic = Diagnostic
export interface AuthoringOwnerHighlight {
  from: number
  to: number
  primary?: boolean
}

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
  ownerHighlights?: readonly AuthoringOwnerHighlight[]
}

const externalUpdate = Annotation.define<boolean>()
const setOwnerHighlights = StateEffect.define<readonly AuthoringOwnerHighlight[]>()
const ownerHighlights = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update: (decorations, transaction) => {
    decorations = decorations.map(transaction.changes)
    for (const effect of transaction.effects) {
      if (!effect.is(setOwnerHighlights)) continue
      const length = transaction.state.doc.length
      const ranges = effect.value.flatMap((item) => {
        if (!Number.isFinite(item.from) || !Number.isFinite(item.to)) return []
        const from = Math.max(0, Math.min(Math.trunc(item.from), length))
        const to = Math.max(from, Math.min(Math.trunc(item.to), length))
        return from === to
          ? []
          : [
              Decoration.mark({
                class: item.primary ? 'cm-owner-primary' : 'cm-owner-secondary',
                attributes: { 'data-authoring-owner': item.primary ? 'primary' : 'secondary' },
              }).range(from, to),
            ]
      })
      decorations = Decoration.set(ranges, true)
    }
    return decorations
  },
  provide: (field) => EditorView.decorations.from(field),
})

function scrollDistance(
  from: number,
  to: number,
  start: number,
  end: number,
  strategy: 'nearest' | 'start' | 'end' | 'center',
  margin: number,
) {
  if (strategy === 'start') return from - start - margin
  if (strategy === 'end') return to - end + margin
  if (strategy === 'center') return (from + to - start - end) / 2
  if (from < start + margin) return from - start - margin
  if (to > end - margin) return to - end + margin
  return 0
}

/** CodeMirror's default scroll traverses ancestors; author panes own only their internal viewport. */
const internalScrolling = EditorView.scrollHandler.of((editor, range, options) => {
  const scroller = editor.scrollDOM
  if (!scroller.clientHeight || !scroller.clientWidth) return true
  // The facet runs in CodeMirror's write phase, where coordsAtPos cannot be called.
  // Read the rendered cursor location directly without mutating the native selection.
  const position = editor.domAtPos(range.head)
  const cursor = scroller.ownerDocument.createRange()
  cursor.setStart(position.node, position.offset)
  cursor.collapse(true)
  const target = cursor.getBoundingClientRect()
  const viewport = scroller.getBoundingClientRect()
  const top = viewport.top + scroller.clientTop
  const left = viewport.left + scroller.clientLeft
  const y = scrollDistance(target.top, target.bottom, top, top + scroller.clientHeight, options.y, options.yMargin)
  const x = scrollDistance(target.left, target.right, left, left + scroller.clientWidth, options.x, options.xMargin)
  if (y) scroller.scrollTop = Math.max(0, scroller.scrollTop + y)
  if (x) scroller.scrollLeft = Math.max(0, scroller.scrollLeft + x)
  return true
})

/** Shared controlled editor for formula drafts and the source pane; values are never evaluated here. */
export function AuthoringCodeEditor(props: AuthoringCodeEditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const current = useRef(props)
  const composing = useRef(false)
  const highlightedPrimary = useRef<string | undefined>(undefined)
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
          ownerHighlights,
          internalScrolling,
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
            '.cm-owner-primary': {
              backgroundColor: 'var(--author-definition-tint)',
              boxShadow: 'inset 0 -2px var(--author-definition)',
            },
            '.cm-owner-secondary': {
              backgroundColor: 'var(--bar)',
              textDecoration: 'underline dotted var(--author-definition)',
              textUnderlineOffset: '3px',
            },
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
    const highlights = props.ownerHighlights ?? []
    const primary = highlights.find((item) => item.primary && Number.isFinite(item.from))
    const primaryKey = primary ? `${props.editorKey ?? ''}:${primary.from}:${primary.to}` : undefined
    const navigate = primary && primaryKey !== highlightedPrimary.current
    highlightedPrimary.current = primaryKey
    editor.dispatch({
      effects: [
        setOwnerHighlights.of(highlights),
        ...(navigate
          ? [EditorView.scrollIntoView(Math.max(0, Math.min(Math.trunc(primary.from), editor.state.doc.length)))]
          : []),
      ],
      annotations: externalUpdate.of(true),
    })
  }, [props.ownerHighlights, props.value, props.editorKey])

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
