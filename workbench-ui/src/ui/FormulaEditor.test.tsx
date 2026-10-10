// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { currentCompletions, startCompletion } from '@codemirror/autocomplete'
import { FormulaEditor } from './FormulaEditor'
import type { WorkbenchData } from '../data'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

const revision = '1234567890abcdef'
const initialFormula = '(* carrying-amount 2)'

function checked(valid = true, responseRevision = revision) {
  return {
    revision: responseRevision,
    data: {
      valid,
      diagnostics: valid
        ? []
        : [{ severity: 'error', code: 'MANTRA-FORMULA', message: 'Old formula error', range: null }],
    },
  }
}

function calculated(display = '2,00', responseRevision = revision) {
  return {
    revision: responseRevision,
    data: {
      preview: true,
      run: { values: { weighting: { A: { link: null, value: { n: '2' }, display, active: true } } } },
      difference: { mainline: [], changes: [], parameterChanges: [], variant: { parameters: [] } },
    },
  }
}

function deferred<T>() {
  let resolve!: (result: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((success, failure) => {
    resolve = success
    reject = failure
  })
  return { promise, resolve, reject }
}

function editorProps(authoring = vi.fn(), formulaEdit = vi.fn()) {
  return {
    caseId: 'case.mantra',
    revision,
    target: { kind: 'formulaSlot' as const, id: 'weighting' },
    initialFormula,
    operation: (formula: string) => ({ op: 'bindFormula' as const, id: 'weighting', formula }),
    data: { authoring, formulaEdit } as unknown as WorkbenchData,
    onSaved: vi.fn(),
  }
}

function currentView() {
  const element = document.querySelector<HTMLElement>('.cm-editor')
  const view = element && EditorView.findFromDOM(element)
  if (!view) throw new Error('CodeMirror is not mounted')
  return view
}

function changeFormula(source: string) {
  act(() => {
    const view = currentView()
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: source } })
  })
}

it('checks a formula before saving and keeps the engine preview separate from the committed edit', async () => {
  const authoring = vi.fn().mockResolvedValue(checked())
  const formulaEdit = vi.fn().mockImplementation(async (_caseId, _revision, _operation, preview: boolean) => ({
    revision,
    data: {
      preview,
      run: { values: { weighting: { A: { link: null, value: { n: '2' }, display: '2,00', active: true } } } },
      difference: { mainline: [], changes: [], parameterChanges: [], variant: { parameters: [] } },
    },
  }))
  const saved = vi.fn()
  render(
    <FormulaEditor
      caseId="case.mantra"
      revision="1234567890abcdef"
      target={{ kind: 'formulaSlot', id: 'weighting' }}
      initialFormula="(* carrying-amount 2)"
      operation={(formula) => ({ op: 'bindFormula', id: 'weighting', formula })}
      data={{ authoring, formulaEdit } as unknown as WorkbenchData}
      onSaved={saved}
    />,
  )
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByText('2,00')).toBeTruthy()
  expect(formulaEdit).toHaveBeenCalledWith(
    'case.mantra',
    '1234567890abcdef',
    { op: 'bindFormula', id: 'weighting', formula: '(* carrying-amount 2)' },
    true,
  )
  expect(saved).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(authoring).toHaveBeenCalledWith(
    'case.mantra',
    'check',
    { kind: 'formulaSlot', id: 'weighting' },
    '(* carrying-amount 2)',
  )
  expect(formulaEdit).toHaveBeenLastCalledWith(
    'case.mantra',
    '1234567890abcdef',
    { op: 'bindFormula', id: 'weighting', formula: '(* carrying-amount 2)' },
    false,
  )
})

it('keeps a rejected formula in the editor and does not submit it', async () => {
  const authoring = vi.fn().mockResolvedValue({
    revision,
    data: {
      valid: false,
      diagnostics: [{ severity: 'error', code: 'MANTRA-FORMULA', message: 'Unknown root', range: null }],
    },
  })
  const formulaEdit = vi.fn()
  render(
    <FormulaEditor
      caseId="case.mantra"
      revision="1234567890abcdef"
      target={{ kind: 'formulaSlot', id: 'weighting' }}
      initialFormula="unknown-root"
      operation={(formula) => ({ op: 'bindFormula', id: 'weighting', formula })}
      data={{ authoring, formulaEdit } as unknown as WorkbenchData}
      onSaved={vi.fn()}
    />,
  )
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  expect(await screen.findByText(/Unknown root/)).toBeTruthy()
  expect(formulaEdit).not.toHaveBeenCalled()
  expect(document.querySelector('.cm-content')?.textContent).toContain('unknown-root')
})

it('uses a reloaded formula for both the visible editor and the next engine check', async () => {
  const authoring = vi.fn().mockResolvedValue(checked())
  const data = { authoring } as unknown as WorkbenchData
  const props = {
    caseId: 'case.mantra',
    revision: '1234567890abcdef',
    target: { kind: 'formulaSlot' as const, id: 'weighting' },
    operation: (formula: string) => ({ op: 'bindFormula' as const, id: 'weighting', formula }),
    data,
    onSaved: vi.fn(),
  }
  const editor = render(<FormulaEditor {...props} initialFormula="(* carrying-amount 2)" />)
  editor.rerender(<FormulaEditor {...props} initialFormula="(* carrying-amount 3)" />)
  expect(document.querySelector('.cm-content')?.textContent).toContain('(* carrying-amount 3)')
  fireEvent.click(screen.getByRole('button', { name: 'Prüfen' }))
  await waitFor(() =>
    expect(authoring).toHaveBeenCalledWith('case.mantra', 'check', props.target, '(* carrying-amount 3)'),
  )
})

it('discards check diagnostics after the draft changes, even if the original text is restored', async () => {
  const pending = deferred<ReturnType<typeof checked>>()
  const authoring = vi.fn().mockReturnValue(pending.promise)
  render(<FormulaEditor {...editorProps(authoring)} />)
  fireEvent.click(screen.getByRole('button', { name: 'Prüfen' }))
  changeFormula('(* carrying-amount 3)')
  changeFormula(initialFormula)
  await act(async () => pending.resolve(checked(false)))
  expect(screen.queryByText('Old formula error')).toBeNull()
  expect(currentView().state.doc.toString()).toBe(initialFormula)
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Prüfen' }).disabled).toBe(false)
})

it('does not save a snapshot that changed during its check and checks the new draft on the next save', async () => {
  const pending = deferred<ReturnType<typeof checked>>()
  const authoring = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValue(checked())
  const formulaEdit = vi.fn().mockResolvedValue(calculated())
  const props = editorProps(authoring, formulaEdit)
  render(<FormulaEditor {...props} />)
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  changeFormula('(* carrying-amount 3)')
  await act(async () => pending.resolve(checked()))
  expect(formulaEdit).not.toHaveBeenCalled()
  expect(props.onSaved).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(props.onSaved).toHaveBeenCalledOnce())
  expect(authoring).toHaveBeenLastCalledWith('case.mantra', 'check', props.target, '(* carrying-amount 3)')
  expect(formulaEdit).toHaveBeenCalledWith(
    'case.mantra',
    revision,
    { op: 'bindFormula', id: 'weighting', formula: '(* carrying-amount 3)' },
    false,
  )
})

it('keeps editing enabled during a preview but never shows its stale result', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const formulaEdit = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValue(calculated('3,00'))
  render(<FormulaEditor {...editorProps(vi.fn(), formulaEdit)} />)
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(currentView().state.facet(EditorState.readOnly)).toBe(false)
  changeFormula('(* carrying-amount 3)')
  await act(async () => pending.resolve(calculated()))
  expect(screen.queryByText('2,00')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByText('3,00')).toBeTruthy()
})

it('preserves a dirty draft across a new source revision and discards the old preview', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const formulaEdit = vi
    .fn()
    .mockReturnValueOnce(pending.promise)
    .mockResolvedValue(calculated('4,00', 'fedcba0987654321'))
  const props = editorProps(vi.fn(), formulaEdit)
  const mounted = render(<FormulaEditor {...props} />)
  changeFormula('(* carrying-amount 4)')
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  mounted.rerender(<FormulaEditor {...props} revision="fedcba0987654321" initialFormula="(* carrying-amount 3)" />)
  expect(currentView().state.doc.toString()).toBe('(* carrying-amount 4)')
  await act(async () => pending.resolve(calculated()))
  expect(screen.queryByText('2,00')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByText('4,00')).toBeTruthy()
  expect(formulaEdit).toHaveBeenLastCalledWith(
    'case.mantra',
    'fedcba0987654321',
    { op: 'bindFormula', id: 'weighting', formula: '(* carrying-amount 4)' },
    true,
  )
})

it('clears already displayed results when only the source revision changes', async () => {
  const props = editorProps(vi.fn(), vi.fn().mockResolvedValue(calculated()))
  const mounted = render(<FormulaEditor {...props} />)
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByText('2,00')).toBeTruthy()
  mounted.rerender(<FormulaEditor {...props} revision="fedcba0987654321" />)
  expect(screen.queryByText('2,00')).toBeNull()
})

it('rejects a check from another graph revision before it can authorize a save', async () => {
  const authoring = vi.fn().mockResolvedValue(checked(true, 'fedcba0987654321'))
  const formulaEdit = vi.fn()
  render(<FormulaEditor {...editorProps(authoring, formulaEdit)} />)
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  expect(await screen.findByRole('alert')).toHaveProperty(
    'textContent',
    'Die Quelldokumente wurden geändert. Bitte neu laden.',
  )
  expect(formulaEdit).not.toHaveBeenCalled()
})

it('rejects a preview envelope from another graph revision', async () => {
  render(<FormulaEditor {...editorProps(vi.fn(), vi.fn().mockResolvedValue(calculated('9,00', 'fedcba0987654321')))} />)
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByRole('alert')).toBeTruthy()
  expect(screen.queryByText('9,00')).toBeNull()
})

it('keeps one save in flight and makes its draft read-only only while the actual write is pending', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const authoring = vi.fn().mockResolvedValue(checked())
  const formulaEdit = vi.fn().mockReturnValue(pending.promise)
  const props = editorProps(authoring, formulaEdit)
  render(<FormulaEditor {...props} />)
  const save = screen.getByRole<HTMLButtonElement>('button', { name: 'Speichern' })
  act(() => {
    fireEvent.click(save)
    fireEvent.click(save)
  })
  await waitFor(() => expect(formulaEdit).toHaveBeenCalledOnce())
  expect(authoring).toHaveBeenCalledOnce()
  expect(save.disabled).toBe(true)
  expect(currentView().state.facet(EditorState.readOnly)).toBe(true)
  expect(currentView().contentDOM.getAttribute('contenteditable')).toBe('false')
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(formulaEdit).toHaveBeenCalledOnce()
  await act(async () => pending.resolve(calculated()))
  expect(props.onSaved).toHaveBeenCalledOnce()
  expect(currentView().state.facet(EditorState.readOnly)).toBe(false)
  expect(save.disabled).toBe(false)
})

it('unlocks the editor and retains the draft after a committed-write failure', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const props = editorProps(vi.fn().mockResolvedValue(checked()), vi.fn().mockReturnValue(pending.promise))
  render(<FormulaEditor {...props} />)
  changeFormula('(* carrying-amount 4)')
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(currentView().state.facet(EditorState.readOnly)).toBe(true))
  await act(async () => pending.reject(new Error('Write rejected')))
  expect(screen.getByRole('alert')).toHaveProperty('textContent', 'Write rejected')
  expect(currentView().state.doc.toString()).toBe('(* carrying-amount 4)')
  expect(currentView().state.facet(EditorState.readOnly)).toBe(false)
  expect(props.onSaved).not.toHaveBeenCalled()
})

it('keeps a rebuilt editor locked until its dispatched write settles and notifies the current owner callback', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const props = editorProps(vi.fn().mockResolvedValue(checked()), vi.fn().mockReturnValue(pending.promise))
  const latestSaved = vi.fn()
  const mounted = render(<FormulaEditor {...props} />)
  changeFormula('(* carrying-amount 4)')
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(currentView().state.facet(EditorState.readOnly)).toBe(true))
  const oldView = currentView()
  mounted.rerender(
    <FormulaEditor
      {...props}
      revision="fedcba0987654321"
      initialFormula="(* carrying-amount 3)"
      onSaved={latestSaved}
    />,
  )
  expect(currentView()).not.toBe(oldView)
  expect(currentView().state.doc.toString()).toBe('(* carrying-amount 4)')
  expect(currentView().state.facet(EditorState.readOnly)).toBe(true)
  expect(currentView().contentDOM.getAttribute('contenteditable')).toBe('false')
  await act(async () => pending.resolve(calculated()))
  expect(props.onSaved).not.toHaveBeenCalled()
  expect(latestSaved).toHaveBeenCalledOnce()
  expect(currentView().state.facet(EditorState.readOnly)).toBe(false)
})

it('still reports a dispatched extension write after its local title changes without submitting the new title', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const formulaEdit = vi.fn().mockReturnValue(pending.promise)
  const props = editorProps(vi.fn().mockResolvedValue(checked()), formulaEdit)
  const target = { kind: 'extension' as const, slot: 'adjustments', id: 'extra', title: 'Old title' }
  const operation = (formula: string) => ({
    op: 'updateExtension' as const,
    slot: target.slot,
    id: target.id,
    title: target.title,
    formula,
  })
  const mounted = render(<FormulaEditor {...props} target={target} operation={operation} />)
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(formulaEdit).toHaveBeenCalledOnce())
  const latestSaved = vi.fn()
  mounted.rerender(
    <FormulaEditor
      {...props}
      target={{ ...target, title: 'New local title' }}
      operation={operation}
      onSaved={latestSaved}
    />,
  )
  await act(async () => pending.resolve(calculated()))
  expect(latestSaved).toHaveBeenCalledOnce()
  expect(props.onSaved).not.toHaveBeenCalled()
  expect(formulaEdit).toHaveBeenCalledOnce()
  expect(formulaEdit).toHaveBeenCalledWith('case.mantra', revision, operation(initialFormula), false)
})

it('ignores an old preview failure after further editing', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  render(<FormulaEditor {...editorProps(vi.fn(), vi.fn().mockReturnValue(pending.promise))} />)
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  changeFormula('(* carrying-amount 3)')
  await act(async () => pending.reject(new Error('Old preview failure')))
  expect(screen.queryByRole('alert')).toBeNull()
})

it('uses semantic target identity and the current saved callback across equivalent rerenders', async () => {
  const pending = deferred<ReturnType<typeof checked>>()
  const props = editorProps(vi.fn().mockReturnValue(pending.promise), vi.fn().mockResolvedValue(calculated()))
  const latestSaved = vi.fn()
  const mounted = render(<FormulaEditor {...props} />)
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  mounted.rerender(<FormulaEditor {...props} target={{ id: 'weighting', kind: 'formulaSlot' }} onSaved={latestSaved} />)
  await act(async () => pending.resolve(checked()))
  expect(latestSaved).toHaveBeenCalledOnce()
  expect(props.onSaved).not.toHaveBeenCalled()
})

it('invalidates a pending save when its extension title changes and checks the new matching operation', async () => {
  const pending = deferred<ReturnType<typeof checked>>()
  const authoring = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValue(checked())
  const formulaEdit = vi.fn().mockResolvedValue(calculated())
  const props = editorProps(authoring, formulaEdit)
  const target = { kind: 'extension' as const, slot: 'adjustments', id: 'extra', title: 'Old title' }
  const operation = (formula: string) => ({
    op: 'updateExtension' as const,
    slot: target.slot,
    id: target.id,
    title: target.title,
    formula,
  })
  const mounted = render(<FormulaEditor {...props} target={target} operation={operation} />)
  changeFormula('(* carrying-amount 4)')
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  const nextTarget = { ...target, title: 'New title' }
  mounted.rerender(
    <FormulaEditor
      {...props}
      target={nextTarget}
      operation={(formula) => ({
        op: 'updateExtension',
        slot: nextTarget.slot,
        id: nextTarget.id,
        title: nextTarget.title,
        formula,
      })}
    />,
  )
  await act(async () => pending.resolve(checked()))
  expect(formulaEdit).not.toHaveBeenCalled()
  expect(currentView().state.doc.toString()).toBe('(* carrying-amount 4)')
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(props.onSaved).toHaveBeenCalledOnce())
  expect(authoring).toHaveBeenLastCalledWith('case.mantra', 'check', nextTarget, '(* carrying-amount 4)')
  expect(formulaEdit).toHaveBeenCalledWith(
    'case.mantra',
    revision,
    {
      op: 'updateExtension',
      slot: nextTarget.slot,
      id: nextTarget.id,
      title: nextTarget.title,
      formula: '(* carrying-amount 4)',
    },
    false,
  )
})

it('loads a new target instead of carrying over the previous target draft or late preview', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const props = editorProps(vi.fn(), vi.fn().mockReturnValue(pending.promise))
  const mounted = render(<FormulaEditor {...props} />)
  changeFormula('(* carrying-amount 4)')
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  mounted.rerender(<FormulaEditor {...props} target={{ kind: 'formulaSlot', id: 'another' }} />)
  expect(currentView().state.doc.toString()).toBe(initialFormula)
  await act(async () => pending.resolve(calculated()))
  expect(screen.queryByText('2,00')).toBeNull()
})

it('does not submit a pending checked save after unmount', async () => {
  const pending = deferred<ReturnType<typeof checked>>()
  const formulaEdit = vi.fn()
  const props = editorProps(vi.fn().mockReturnValue(pending.promise), formulaEdit)
  const mounted = render(<FormulaEditor {...props} />)
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  mounted.unmount()
  await act(async () => pending.resolve(checked()))
  expect(formulaEdit).not.toHaveBeenCalled()
  expect(props.onSaved).not.toHaveBeenCalled()
})

it.each(['case', 'target'] as const)(
  'does not notify a different %s when an already dispatched write finishes',
  async (changed) => {
    const pending = deferred<ReturnType<typeof calculated>>()
    const props = editorProps(vi.fn().mockResolvedValue(checked()), vi.fn().mockReturnValue(pending.promise))
    const latestSaved = vi.fn()
    const mounted = render(<FormulaEditor {...props} />)
    fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(currentView().state.facet(EditorState.readOnly)).toBe(true))
    mounted.rerender(
      <FormulaEditor
        {...props}
        caseId={changed === 'case' ? 'another.mantra' : props.caseId}
        target={changed === 'target' ? { kind: 'formulaSlot', id: 'another' } : props.target}
        onSaved={latestSaved}
      />,
    )
    await act(async () => pending.resolve(calculated()))
    expect(props.onSaved).not.toHaveBeenCalled()
    expect(latestSaved).not.toHaveBeenCalled()
    expect(currentView().state.facet(EditorState.readOnly)).toBe(false)
  },
)

it('does not notify an unmounted editor when an already dispatched write finishes', async () => {
  const pending = deferred<ReturnType<typeof calculated>>()
  const props = editorProps(vi.fn().mockResolvedValue(checked()), vi.fn().mockReturnValue(pending.promise))
  const mounted = render(<FormulaEditor {...props} />)
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(currentView().state.facet(EditorState.readOnly)).toBe(true))
  mounted.unmount()
  await act(async () => pending.resolve(calculated()))
  expect(props.onSaved).not.toHaveBeenCalled()
})

it('does not check, preview or save IME preedit and cancels a save check when composition begins', async () => {
  const pending = deferred<ReturnType<typeof checked>>()
  const authoring = vi.fn().mockReturnValue(pending.promise)
  const formulaEdit = vi.fn()
  render(<FormulaEditor {...editorProps(authoring, formulaEdit)} />)
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  fireEvent.compositionStart(currentView().contentDOM)
  await act(async () => pending.resolve(checked()))
  for (const name of ['Prüfen', 'Vorschau', 'Speichern']) {
    const button = screen.getByRole<HTMLButtonElement>('button', { name })
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
  }
  expect(authoring).toHaveBeenCalledOnce()
  expect(formulaEdit).not.toHaveBeenCalled()
  fireEvent.compositionEnd(currentView().contentDOM)
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Speichern' }).disabled).toBe(false)
})

it('keeps only the hover response for the current cursor', async () => {
  vi.useFakeTimers()
  const first = deferred<{ revision: string; data: { hover: { symbol: string; detail: string } } }>()
  const second = deferred<{ revision: string; data: { hover: { symbol: string; detail: string } } }>()
  const authoring = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
  render(<FormulaEditor {...editorProps(authoring)} />)
  act(() => currentView().dispatch({ selection: { anchor: 1 } }))
  await act(async () => vi.advanceTimersByTimeAsync(250))
  act(() => currentView().dispatch({ selection: { anchor: 2 } }))
  await act(async () => vi.advanceTimersByTimeAsync(250))
  expect(authoring).toHaveBeenCalledTimes(2)
  await act(async () => second.resolve({ revision, data: { hover: { symbol: 'current', detail: 'Current hover' } } }))
  expect(screen.getByText('Current hover')).toBeTruthy()
  await act(async () => first.resolve({ revision, data: { hover: { symbol: 'old', detail: 'Old hover' } } }))
  expect(screen.queryByText('Old hover')).toBeNull()
  expect(screen.getByText('Current hover')).toBeTruthy()
})

it('closes already available completions when the source revision changes', async () => {
  vi.useFakeTimers()
  vi.spyOn(EditorView.prototype, 'requestMeasure').mockImplementation(() => {})
  const authoring = vi.fn().mockResolvedValue({
    revision,
    data: {
      replacementRange: { startOffset: 0, endOffset: 0 },
      items: [{ label: 'carrying-amount', insertText: 'carrying-amount', kind: 'variable', detail: 'Old graph' }],
    },
  })
  const props = editorProps(authoring)
  const mounted = render(<FormulaEditor {...props} />)
  act(() => {
    startCompletion(currentView())
  })
  await act(async () => vi.advanceTimersByTimeAsync(100))
  expect(currentCompletions(currentView().state).map((item) => item.label)).toEqual(['carrying-amount'])
  mounted.rerender(<FormulaEditor {...props} revision="fedcba0987654321" />)
  expect(currentCompletions(currentView().state)).toEqual([])
})
