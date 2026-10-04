// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { FormulaEditor } from './FormulaEditor'
import type { WorkbenchData } from '../data'

afterEach(cleanup)

it('checks a formula before saving and keeps the engine preview separate from the committed edit', async () => {
  const authoring = vi.fn().mockResolvedValue({ data: { valid: true, diagnostics: [] } })
  const formulaEdit = vi.fn().mockImplementation(async (_caseId, _revision, _operation, preview: boolean) => ({
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
  const authoring = vi.fn().mockResolvedValue({ data: { valid: true, diagnostics: [] } })
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
