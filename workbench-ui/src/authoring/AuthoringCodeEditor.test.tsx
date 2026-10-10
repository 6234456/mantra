// @vitest-environment jsdom
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { EditorSelection, EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { completionStatus, currentCompletions, startCompletion } from '@codemirror/autocomplete'
import { forEachDiagnostic } from '@codemirror/lint'
import { afterEach, expect, it, vi } from 'vitest'
import { AuthoringCodeEditor, type AuthoringCodeEditorProps } from './AuthoringCodeEditor'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  document.querySelector('meta[name="mantra-style-nonce"]')?.remove()
})

function editor() {
  const element = document.querySelector<HTMLElement>('.cm-editor')
  const view = element && EditorView.findFromDOM(element)
  if (!view) throw new Error('CodeMirror is not mounted')
  return view
}

function mount(overrides: Partial<AuthoringCodeEditorProps> = {}) {
  const onChange = vi.fn()
  const onCommit = vi.fn()
  const onCancel = vi.fn()
  const props: AuthoringCodeEditorProps = {
    value: '(+ value 1)',
    ariaLabel: 'Template formula',
    onChange,
    onCommit,
    onCancel,
    ...overrides,
  }
  function Harness() {
    const [value, setValue] = useState(props.value)
    return (
      <AuthoringCodeEditor
        {...props}
        value={value}
        onChange={(source) => {
          onChange(source)
          setValue(source)
        }}
      />
    )
  }
  return { ...render(<Harness />), onChange, onCommit, onCancel }
}

it('uses an accessible real CodeMirror input, preserves multiline Enter and commits with Ctrl+Enter', () => {
  const { onChange, onCommit } = mount()
  expect(screen.getByRole('textbox', { name: 'Template formula' })).toBe(editor().contentDOM)
  act(() => editor().dispatch({ selection: { anchor: editor().state.doc.length } }))
  fireEvent.keyDown(editor().contentDOM, { key: 'Enter', keyCode: 13 })
  expect(editor().state.doc.toString()).toBe('(+ value 1)\n')
  expect(onChange).toHaveBeenLastCalledWith('(+ value 1)\n')
  expect(onCommit).not.toHaveBeenCalled()
  fireEvent.keyDown(editor().contentDOM, { key: 'Enter', keyCode: 13, ctrlKey: true })
  expect(onCommit).toHaveBeenCalledWith('(+ value 1)\n')
})

it('commits a formula on Tab and closes it on Escape without changing the source', () => {
  const { onCommit, onCancel, onChange } = mount()
  fireEvent.keyDown(editor().contentDOM, { key: 'Tab', keyCode: 9 })
  expect(onCommit).toHaveBeenCalledWith('(+ value 1)')
  fireEvent.keyDown(editor().contentDOM, { key: 'Escape', keyCode: 27 })
  expect(onCancel).toHaveBeenCalledOnce()
  expect(onChange).not.toHaveBeenCalled()
})

it('lets source-view Tab leave when no commit callback is supplied', () => {
  mount({ onCommit: undefined, onCancel: undefined })
  expect(fireEvent.keyDown(editor().contentDOM, { key: 'Tab', keyCode: 9 })).toBe(true)
})

it('suppresses IME commit and cancellation while allowing the browser to confirm composition', () => {
  const onCompositionChange = vi.fn()
  const { onCommit, onCancel } = mount({ onCompositionChange })
  expect(fireEvent.keyDown(editor().contentDOM, { key: 'Enter', ctrlKey: true, isComposing: true })).toBe(true)
  expect(fireEvent.keyDown(editor().contentDOM, { key: 'Enter', ctrlKey: true, keyCode: 229 })).toBe(true)
  fireEvent.compositionStart(editor().contentDOM)
  expect(onCompositionChange).toHaveBeenLastCalledWith(true)
  fireEvent.keyDown(editor().contentDOM, { key: 'Enter', keyCode: 13, ctrlKey: true })
  fireEvent.keyDown(editor().contentDOM, { key: 'Escape', keyCode: 27 })
  expect(onCommit).not.toHaveBeenCalled()
  expect(onCancel).not.toHaveBeenCalled()
  fireEvent.compositionEnd(editor().contentDOM)
  expect(onCompositionChange).toHaveBeenLastCalledWith(false)
})

it('accepts a recorded completion before Enter can insert a newline or commit', async () => {
  const { onCommit } = mount({ value: 'req', completions: [{ label: 'request', type: 'variable', detail: 'Number' }] })
  act(() => {
    editor().dispatch({ selection: { anchor: 3 } })
    startCompletion(editor())
  })
  await waitFor(() => expect(currentCompletions(editor().state).map((item) => item.label)).toEqual(['request']))
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 100))
  })
  fireEvent.keyDown(editor().contentDOM, { key: 'Enter', keyCode: 13 })
  expect(editor().state.doc.toString()).toBe('request')
  expect(onCommit).not.toHaveBeenCalled()
})

it('closes only the completion menu on the first Escape and cancels on the second', async () => {
  const { onCancel } = mount({ value: 'req', completions: [{ label: 'request' }] })
  act(() => {
    editor().dispatch({ selection: { anchor: 3 } })
    startCompletion(editor())
  })
  await waitFor(() => expect(completionStatus(editor().state)).toBe('active'))
  fireEvent.keyDown(editor().contentDOM, { key: 'Escape', keyCode: 27 })
  expect(completionStatus(editor().state)).toBeNull()
  expect(onCancel).not.toHaveBeenCalled()
  fireEvent.keyDown(editor().contentDOM, { key: 'Escape', keyCode: 27 })
  expect(onCancel).toHaveBeenCalledOnce()
})

it('synchronizes controlled source and read-only metadata without remounting or echoing an edit', () => {
  const onChange = vi.fn()
  const onCommit = vi.fn()
  const props = { value: 'original', onChange, onCommit, ariaLabel: 'Source' }
  const { rerender } = render(<AuthoringCodeEditor {...props} />)
  const originalView = editor()
  rerender(<AuthoringCodeEditor {...props} value="changed outside this pane" readOnly />)
  expect(editor()).toBe(originalView)
  expect(editor().state.doc.toString()).toBe('changed outside this pane')
  expect(editor().state.facet(EditorState.readOnly)).toBe(true)
  expect(editor().contentDOM.getAttribute('contenteditable')).toBe('false')
  expect(onChange).not.toHaveBeenCalled()
  fireEvent.keyDown(editor().contentDOM, { key: 'Enter', keyCode: 13, ctrlKey: true })
  expect(onCommit).not.toHaveBeenCalled()
})

it('uses the latest callback and source after a metadata refresh', () => {
  const oldCommit = vi.fn()
  const nextCommit = vi.fn()
  const onChange = vi.fn()
  const { rerender } = render(
    <AuthoringCodeEditor value="old" onChange={onChange} onCommit={oldCommit} ariaLabel="Formula" />,
  )
  const mounted = editor()
  rerender(<AuthoringCodeEditor value="new" onChange={onChange} onCommit={nextCommit} ariaLabel="Changed formula" />)
  fireEvent.keyDown(editor().contentDOM, { key: 'Enter', keyCode: 13, ctrlKey: true })
  expect(editor()).toBe(mounted)
  expect(oldCommit).not.toHaveBeenCalled()
  expect(nextCommit).toHaveBeenCalledWith('new')
  expect(screen.getByRole('textbox', { name: 'Changed formula' })).toBe(editor().contentDOM)
})

it('focuses and selects an existing editor when editing is activated for the same owner', () => {
  const onChange = vi.fn()
  const props = { value: '(+ value 1)', onChange, ariaLabel: 'Formula', editorKey: 'same-owner' }
  const { rerender } = render(<AuthoringCodeEditor {...props} autoFocus={false} selectAll={false} />)
  const mounted = editor()
  expect(document.activeElement).not.toBe(mounted.contentDOM)
  rerender(<AuthoringCodeEditor {...props} autoFocus selectAll />)
  expect(editor()).toBe(mounted)
  expect(document.activeElement).toBe(mounted.contentDOM)
  expect(mounted.state.selection.main.from).toBe(0)
  expect(mounted.state.selection.main.to).toBe(props.value.length)
  act(() => mounted.dispatch({ selection: { anchor: 2 } }))
  rerender(<AuthoringCodeEditor {...props} autoFocus selectAll ariaLabel="Refreshed formula" />)
  expect(mounted.state.selection.main.from).toBe(2)
  expect(mounted.state.selection.main.to).toBe(2)
  expect(onChange).not.toHaveBeenCalled()
})

it('replaces and clears updated diagnostics without remounting the editor', () => {
  const onChange = vi.fn()
  const props = { value: '0123456789', onChange, ariaLabel: 'Source' }
  const { rerender } = render(
    <AuthoringCodeEditor
      {...props}
      diagnostics={[{ from: 1, to: 3, severity: 'error', message: 'Old recorded error' }]}
    />,
  )
  const mounted = editor()
  rerender(
    <AuthoringCodeEditor
      {...props}
      diagnostics={[{ from: 6, to: 9, severity: 'warning', message: 'Current recorded warning' }]}
    />,
  )
  const diagnostics: Array<{ from: number; to: number; message: string }> = []
  forEachDiagnostic(mounted.state, (finding, from, to) => diagnostics.push({ from, to, message: finding.message }))
  expect(diagnostics).toEqual([{ from: 6, to: 9, message: 'Current recorded warning' }])
  rerender(<AuthoringCodeEditor {...props} />)
  const cleared: unknown[] = []
  forEachDiagnostic(mounted.state, (finding) => cleared.push(finding))
  expect(cleared).toEqual([])
  expect(editor()).toBe(mounted)
  expect(onChange).not.toHaveBeenCalled()
})

it('clamps diagnostics and explicit source selections to the current document', () => {
  const onSelection = vi.fn()
  mount({
    value: 'abc',
    diagnostics: [{ from: -10, to: 20, severity: 'error', message: 'Recorded diagnostic' }],
    selection: { from: 1, to: 20 },
    onSelection,
  })
  const ranges: Array<{ from: number; to: number }> = []
  forEachDiagnostic(editor().state, (_finding, from, to) => ranges.push({ from, to }))
  expect(ranges).toEqual([{ from: 0, to: 3 }])
  expect(editor().state.selection.main.from).toBe(1)
  expect(editor().state.selection.main.to).toBe(3)
  expect(onSelection).not.toHaveBeenCalled()
  act(() => editor().dispatch({ selection: { anchor: 0, head: 2 } }))
  expect(onSelection).toHaveBeenCalledWith(0, 2)
})

it('keeps primary and secondary source owner marks visible while focus and cursor stay elsewhere', () => {
  const onChange = vi.fn()
  const onSelection = vi.fn()
  const props = { value: 'abc def ghi', onChange, onSelection, ariaLabel: 'Source' }
  const { rerender } = render(
    <>
      <button>Grid cell</button>
      <AuthoringCodeEditor
        {...props}
        ownerHighlights={[
          { from: 0, to: 3, primary: true },
          { from: 4, to: 7 },
        ]}
      />
    </>,
  )
  const mounted = editor()
  act(() => mounted.dispatch({ selection: { anchor: 5 } }))
  onSelection.mockClear()
  screen.getByRole('button', { name: 'Grid cell' }).focus()
  expect(document.querySelector('.cm-owner-primary')?.textContent).toBe('abc')
  expect(document.querySelector('.cm-owner-secondary')?.textContent).toBe('def')
  expect(mounted.hasFocus).toBe(false)
  rerender(
    <>
      <button>Grid cell</button>
      <AuthoringCodeEditor {...props} ownerHighlights={[{ from: 8, to: 11, primary: true }]} />
    </>,
  )
  expect(document.querySelector('.cm-owner-primary')?.textContent).toBe('ghi')
  expect(document.querySelector('.cm-owner-secondary')).toBeNull()
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Grid cell' }))
  expect(mounted.state.selection.main.from).toBe(5)
  expect(mounted.state.selection.main.to).toBe(5)
  expect(onSelection).not.toHaveBeenCalled()
  expect(onChange).not.toHaveBeenCalled()
})

it('replaces owner ranges against controlled source changes and clears stale or empty marks', () => {
  const onChange = vi.fn()
  const props = { value: 'old', onChange, ariaLabel: 'Source' }
  const { rerender } = render(<AuthoringCodeEditor {...props} ownerHighlights={[{ from: 0, to: 3, primary: true }]} />)
  const mounted = editor()
  rerender(
    <AuthoringCodeEditor
      {...props}
      value="new source"
      ownerHighlights={[
        { from: 4, to: 100, primary: true },
        { from: -2, to: 0 },
        { from: 4, to: 4 },
        { from: Number.NaN, to: 8 },
      ]}
    />,
  )
  expect(editor()).toBe(mounted)
  expect(document.querySelector('.cm-owner-primary')?.textContent).toBe('source')
  expect(document.querySelector('.cm-owner-secondary')).toBeNull()
  rerender(<AuthoringCodeEditor {...props} value="new source" />)
  expect(document.querySelector('[data-authoring-owner]')).toBeNull()
  expect(onChange).not.toHaveBeenCalled()
})

it('handles source navigation inside the editor scroller without scrolling ancestor panes', () => {
  const { container } = mount()
  const mounted = editor()
  const scroller = mounted.scrollDOM
  container.scrollTop = 73
  container.scrollLeft = 9
  scroller.scrollTop = 40
  scroller.scrollLeft = 10
  Object.defineProperties(scroller, { clientHeight: { value: 100 }, clientWidth: { value: 100 } })
  vi.spyOn(scroller, 'getBoundingClientRect').mockReturnValue({
    top: 100,
    bottom: 200,
    left: 20,
    right: 120,
    width: 100,
    height: 100,
    x: 20,
    y: 100,
    toJSON: () => ({}),
  })
  vi.spyOn(mounted, 'coordsAtPos').mockImplementation(() => {
    throw new Error('CodeMirror forbids layout reads during its write phase')
  })
  const cursor = {
    setStart: vi.fn(),
    collapse: vi.fn(),
    getBoundingClientRect: () => ({ top: 245, bottom: 260, left: 140, right: 145 }),
  }
  vi.spyOn(document, 'createRange').mockReturnValue(cursor as unknown as Range)
  const handler = mounted.state.facet(EditorView.scrollHandler)[0]
  const handled = handler(mounted, mounted.state.selection.main, {
    x: 'nearest',
    y: 'nearest',
    xMargin: 5,
    yMargin: 5,
  })
  expect(handled).toBe(true)
  expect(scroller.scrollTop).toBe(105)
  expect(scroller.scrollLeft).toBe(40)
  expect(container.scrollTop).toBe(73)
  expect(container.scrollLeft).toBe(9)
  expect(cursor.setStart).toHaveBeenCalledOnce()
  expect(cursor.collapse).toHaveBeenCalledWith(true)
  expect(document.activeElement).not.toBe(mounted.contentDOM)
})

it('centers an owner clipped by the lower pane edge while preserving grid focus and the source cursor', () => {
  const onChange = vi.fn()
  const onSelection = vi.fn()
  const props = { value: 'abc def ghi', onChange, onSelection, ariaLabel: 'Source' }
  const { container, rerender } = render(
    <>
      <button>Grid cell</button>
      <AuthoringCodeEditor {...props} />
    </>,
  )
  const mounted = editor()
  act(() => mounted.dispatch({ selection: { anchor: 2 } }))
  onSelection.mockClear()
  const grid = screen.getByRole('button', { name: 'Grid cell' })
  grid.focus()
  const navigation = vi.spyOn(EditorView, 'scrollIntoView')
  rerender(
    <>
      <button>Grid cell</button>
      <AuthoringCodeEditor {...props} ownerHighlights={[{ from: 4, to: 7, primary: true }]} />
    </>,
  )
  const [position, options] = navigation.mock.calls.at(-1)!
  const target = typeof position === 'number' ? EditorSelection.cursor(position) : position
  const scroller = mounted.scrollDOM
  container.scrollTop = 73
  scroller.scrollTop = 40
  Object.defineProperties(scroller, { clientHeight: { value: 100 }, clientWidth: { value: 100 } })
  vi.spyOn(scroller, 'getBoundingClientRect').mockReturnValue({
    top: 100,
    bottom: 200,
    left: 0,
    right: 100,
    width: 100,
    height: 100,
    x: 0,
    y: 100,
    toJSON: () => ({}),
  })
  vi.spyOn(mounted, 'coordsAtPos').mockImplementation(() => {
    throw new Error('CodeMirror forbids layout reads during its write phase')
  })
  const cursor = {
    setStart: vi.fn(),
    collapse: vi.fn(),
    getBoundingClientRect: () => ({ top: 192, bottom: 222, left: 20, right: 25 }),
  }
  vi.spyOn(document, 'createRange').mockReturnValue(cursor as unknown as Range)
  const handler = mounted.state.facet(EditorView.scrollHandler)[0]
  expect(
    handler(mounted, target, {
      x: options?.x ?? 'nearest',
      y: options?.y ?? 'nearest',
      xMargin: options?.xMargin ?? 5,
      yMargin: options?.yMargin ?? 5,
    }),
  ).toBe(true)
  // A 57 px inner scroll moves the previously clipped 192–222 px target to 135–165 px.
  expect(scroller.scrollTop).toBe(97)
  expect(scroller.scrollLeft).toBe(0)
  expect(container.scrollTop).toBe(73)
  expect(document.querySelector('.cm-owner-primary')?.textContent).toBe('def')
  expect(document.activeElement).toBe(grid)
  expect(mounted.state.selection.main.from).toBe(2)
  expect(mounted.state.selection.main.to).toBe(2)
  expect(onSelection).not.toHaveBeenCalled()
  expect(onChange).not.toHaveBeenCalled()
})

it('uses the configured CSP nonce and destroys the old view when editor identity changes', () => {
  const meta = document.createElement('meta')
  meta.name = 'mantra-style-nonce'
  meta.content = 'test-style-nonce'
  document.head.append(meta)
  const onChange = vi.fn()
  const { rerender, unmount } = render(
    <AuthoringCodeEditor value="one" onChange={onChange} ariaLabel="Source" editorKey="first" />,
  )
  const oldView = editor()
  const destroyed = vi.spyOn(oldView, 'destroy')
  expect(oldView.state.facet(EditorView.cspNonce)).toBe('test-style-nonce')
  rerender(<AuthoringCodeEditor value="two" onChange={onChange} ariaLabel="Source" editorKey="second" />)
  expect(destroyed).toHaveBeenCalledOnce()
  expect(editor()).not.toBe(oldView)
  expect(editor().state.doc.toString()).toBe('two')
  const newDestroyed = vi.spyOn(editor(), 'destroy')
  unmount()
  expect(newDestroyed).toHaveBeenCalledOnce()
})
