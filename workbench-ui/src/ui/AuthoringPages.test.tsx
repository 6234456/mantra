// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { EditorView } from '@codemirror/view'
import { ExtensionsPage, FormulaSlotCard } from './AuthoringPages'
import type { WorkbenchData } from '../data'
import type { Structure } from '../types'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function currentEditor() {
  const element = document.querySelector<HTMLElement>('.cm-editor')
  const editor = element && EditorView.findFromDOM(element)
  if (!editor) throw new Error('CodeMirror is not mounted')
  return editor
}

function editFormula(formula: string) {
  act(() => {
    const editor = currentEditor()
    editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: formula } })
  })
}

function editableSlotProps() {
  const revision = '1234567890abcdef'
  const authoring = vi.fn().mockResolvedValue({ revision, data: { valid: true, diagnostics: [] } })
  const formulaEdit = vi.fn().mockImplementation(async (_caseId, requestRevision, _operation, preview) => ({
    revision: requestRevision,
    data: {
      preview,
      run: { values: { weighting: { A: { link: null, value: { n: '4' }, display: '4,00', active: true } } } },
      difference: { mainline: [], changes: [], parameterChanges: [], variant: { parameters: [] } },
    },
  }))
  return {
    slot: { id: 'weighting', title: 'Weighting', defaultFormula: '(* base 2)', binding: '(* base 3)' },
    caseId: 'case.mantra',
    revision,
    data: { authoring, formulaEdit } as unknown as WorkbenchData,
    onSaved: vi.fn(),
    authoring,
    formulaEdit,
  }
}

it('shows non-line extensions without offering a formula editor for them', () => {
  const structure = {
    schema: 'sample',
    title: 'Sample',
    mainline: [],
    panels: [],
    generalInputs: [],
    params: [],
    slots: [{ id: 'custom', title: 'Custom', extensions: ['editable', 'chosen'] }],
    nodes: {
      editable: { id: 'editable', label: 'Editable line', kind: 'extension', formula: { text: '(+ 1 2)' } },
      chosen: { id: 'chosen', label: 'Choice extension', kind: 'extension' },
    },
  } as Structure
  render(
    <ExtensionsPage
      caseId="case.mantra"
      structure={structure}
      revision="1234567890abcdef"
      data={{} as WorkbenchData}
      onSaved={vi.fn()}
    />,
  )
  const readonly = screen.getByText('Choice extension').closest('.extension-readonly') as HTMLElement
  expect(readonly).toBeTruthy()
  expect(within(readonly).queryByRole('button', { name: 'Speichern' })).toBeNull()
  expect(screen.getAllByRole('button', { name: 'Speichern' })).toHaveLength(1)
})

it.each([
  { editableCase: false, authoring: true, message: 'Dieser Fall ist schreibgeschützt.' },
  { editableCase: true, authoring: false, message: 'Formelbearbeitung ist in dieser Ansicht nicht verfügbar.' },
])('preserves formula evidence when editing is unavailable: $message', ({ editableCase, authoring, message }) => {
  const data = {
    canEditCase: vi.fn(() => editableCase),
    canAuthorCase: vi.fn(() => authoring),
    authoring: vi.fn(() => {
      throw new Error('Unsupported authoring')
    }),
    formulaEdit: vi.fn(() => {
      throw new Error('Unsupported edit')
    }),
  } as unknown as WorkbenchData
  const structure = {
    schema: 'sample',
    title: 'Sample',
    mainline: [],
    panels: [],
    generalInputs: [],
    params: [],
    slots: [{ id: 'custom', title: 'Custom', extensions: ['editable', 'chosen'] }],
    formulaSlots: [
      {
        id: 'weighting',
        title: 'Weighting',
        defaultFormula: '(* base 2)',
        binding: '(* base 3)',
        uses: ['base'],
      },
    ],
    nodes: {
      editable: { id: 'editable', label: 'Editable line', kind: 'extension', formula: { text: '(+ 1 2)' } },
      chosen: { id: 'chosen', label: 'Choice extension', kind: 'extension' },
    },
  } as Structure
  const saved = vi.fn()
  const confirm = vi.spyOn(window, 'confirm')
  const { container } = render(
    <ExtensionsPage
      caseId="case.mantra"
      structure={structure}
      revision="1234567890abcdef"
      data={data}
      onSaved={saved}
    />,
  )
  expect(screen.getByRole('status').textContent).toBe(message)
  expect(screen.getByText('Choice extension')).toBeTruthy()
  expect(screen.getByText('(+ 1 2)')).toBeTruthy()
  expect(screen.getByText('(* base 2)')).toBeTruthy()
  expect(screen.getByText('(* base 3)')).toBeTruthy()
  expect(screen.getByLabelText('Titel').getAttribute('disabled')).not.toBeNull()
  expect(container.querySelector('.cm-editor')).toBeNull()
  expect(container.querySelector('.extension-new')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Speichern' })).toBeNull()
  for (const name of ['Zeile entfernen', 'Auf Standard zurücksetzen']) {
    const button = screen.getByRole('button', { name })
    expect((button as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(button)
  }
  expect(data.authoring).not.toHaveBeenCalled()
  expect(data.formulaEdit).not.toHaveBeenCalled()
  expect(confirm).not.toHaveBeenCalled()
  expect(saved).not.toHaveBeenCalled()
})

it('rechecks the current authoring capability before resetting an already rendered slot', () => {
  let editable = true
  const formulaEdit = vi.fn()
  const saved = vi.fn()
  const data = { canEditCase: () => editable, formulaEdit } as unknown as WorkbenchData
  render(
    <FormulaSlotCard
      slot={{ id: 'weighting', title: 'Weighting', defaultFormula: '(* base 2)', binding: '(* base 3)' }}
      caseId="case.mantra"
      revision="1234567890abcdef"
      data={data}
      onSaved={saved}
    />,
  )
  editable = false
  fireEvent.click(screen.getByRole('button', { name: 'Auf Standard zurücksetzen' }))
  expect(formulaEdit).not.toHaveBeenCalled()
  expect(saved).not.toHaveBeenCalled()
})

it('preserves a dirty formula slot draft across an external binding update and clears its prior feedback', async () => {
  const props = editableSlotProps()
  props.authoring.mockResolvedValueOnce({
    revision: props.revision,
    data: {
      valid: false,
      diagnostics: [{ severity: 'error', code: 'MANTRA-FORMULA', message: 'Prior formula finding', range: null }],
    },
  })
  const mounted = render(<FormulaSlotCard {...props} />)
  editFormula('(* base 4)')
  fireEvent.click(screen.getByRole('button', { name: 'Prüfen' }))
  expect(await screen.findByText(/Prior formula finding/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByText('4,00')).toBeTruthy()
  mounted.rerender(
    <FormulaSlotCard {...props} revision="fedcba0987654321" slot={{ ...props.slot, binding: '(* base 5)' }} />,
  )
  expect(currentEditor().state.doc.toString()).toBe('(* base 4)')
  expect(screen.queryByText('4,00')).toBeNull()
  expect(screen.queryByText(/Prior formula finding/)).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByText('4,00')).toBeTruthy()
  expect(props.formulaEdit).toHaveBeenLastCalledWith(
    'case.mantra',
    'fedcba0987654321',
    { op: 'bindFormula', id: 'weighting', formula: '(* base 4)' },
    true,
  )
})

it('updates an untouched formula slot editor to an external binding', async () => {
  const props = editableSlotProps()
  const mounted = render(<FormulaSlotCard {...props} />)
  mounted.rerender(
    <FormulaSlotCard {...props} revision="fedcba0987654321" slot={{ ...props.slot, binding: '(* base 5)' }} />,
  )
  expect(currentEditor().state.doc.toString()).toBe('(* base 5)')
  fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }))
  expect(await screen.findByText('4,00')).toBeTruthy()
  expect(props.formulaEdit).toHaveBeenCalledWith(
    'case.mantra',
    'fedcba0987654321',
    { op: 'bindFormula', id: 'weighting', formula: '(* base 5)' },
    true,
  )
})

it.each(['case', 'slot'] as const)('resets a dirty formula slot draft when its %s changes', (changed) => {
  const props = editableSlotProps()
  const mounted = render(<FormulaSlotCard {...props} />)
  editFormula('(* base 4)')
  mounted.rerender(
    <FormulaSlotCard
      {...props}
      caseId={changed === 'case' ? 'another.mantra' : props.caseId}
      slot={changed === 'slot' ? { ...props.slot, id: 'another' } : props.slot}
    />,
  )
  expect(currentEditor().state.doc.toString()).toBe('(* base 3)')
})
