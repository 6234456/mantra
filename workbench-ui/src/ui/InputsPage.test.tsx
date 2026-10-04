// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { InputsPage } from './InputsPage'
import type { WorkbenchData } from '../data'
import type { Diagnostic, Run, Structure } from '../types'

const structure: Structure = {
  schema: 'test',
  title: 'Test',
  mainline: [],
  panels: [],
  params: [],
  nodes: {},
  generalInputs: [{ id: 'amount', label: 'Amount', type: 'decimal', dims: [] }],
}
const run: Run = {
  succeeded: true,
  validationPassed: true,
  members: {},
  diagnostics: [],
  values: {
    amount: { '': { value: { n: '100.00' }, display: '100,00', active: true, origin: 'case' } },
  },
}
afterEach(cleanup)

const businessFinding: Diagnostic = {
  category: 'business',
  severity: 'error',
  code: 'MANTRA-INPUT-REQUIRED',
  message: 'Enter an explicit amount',
  location: null,
  address: { node: 'amount' },
  related: [],
  rowIndex: null,
  column: null,
}

it('shows a business error beside the input and still saves a valid edit with failed business checks', async () => {
  const businessRun: Run = { ...run, validationPassed: false, diagnostics: [businessFinding] }
  const difference = { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] }
  const edit = vi.fn().mockResolvedValue({ data: { difference, run: businessRun } })
  const saved = vi.fn()
  render(
    <InputsPage
      caseId="case.mantra"
      structure={structure}
      run={businessRun}
      revision="base"
      data={{ edit } as unknown as WorkbenchData}
      onSaved={saved}
      navigate={vi.fn()}
    />,
  )
  expect(screen.getByRole('status').textContent).toContain('weiter gespeichert')
  const input = screen.getByLabelText('Wert')
  expect(input.getAttribute('aria-invalid')).toBe('true')
  expect(input.closest('.input-control')?.textContent).toContain('Enter an explicit amount')
  fireEvent.change(input, { target: { value: '0' } })
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(saved).toHaveBeenCalledWith(difference))
  expect(edit).toHaveBeenCalledWith('case.mantra', 'base', [{ op: 'setInput', address: { node: 'amount' }, text: '0' }])
  expect(screen.queryByRole('alert')).toBeNull()
})

it.each([
  { type: 'decimal', value: { n: '0' }, text: '0' },
  { type: 'boolean', value: false, text: 'false' },
])('allows an unchanged implicit $text to be saved as an explicit required fact', async ({ type, value, text }) => {
  const edit = vi.fn().mockResolvedValue({
    data: { difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] } },
  })
  const saved = vi.fn()
  const requiredRun: Run = {
    ...run,
    validationPassed: false,
    diagnostics: [businessFinding],
    values: { amount: { '': { value, display: text, active: true, origin: 'implicit' } } },
  }
  render(
    <InputsPage
      caseId="case.mantra"
      structure={{ ...structure, generalInputs: [{ id: 'amount', label: 'Amount', type }] }}
      run={requiredRun}
      revision="base"
      data={{ edit } as unknown as WorkbenchData}
      onSaved={saved}
      navigate={vi.fn()}
    />,
  )
  const save = screen.getByRole('button', { name: 'Speichern' }) as HTMLButtonElement
  expect(save.disabled).toBe(false)
  fireEvent.click(save)
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(edit).toHaveBeenCalledWith('case.mantra', 'base', [{ op: 'setInput', address: { node: 'amount' }, text }])
})

it('focuses a diagnostic row position, displays the exact cell finding, and edits using its stable key', async () => {
  const edit = vi.fn().mockResolvedValue({
    data: { difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] } },
  })
  const saved = vi.fn()
  const tableStructure: Structure = {
    ...structure,
    generalInputs: [
      {
        id: 'items',
        label: 'Items',
        type: 'table',
        keyColumn: 'code',
        columns: [
          { name: 'code', type: 'keyword' },
          { name: 'amount', type: 'decimal' },
        ],
      },
    ],
  }
  const tableRun: Run = {
    ...run,
    validationPassed: false,
    diagnostics: [
      {
        ...businessFinding,
        address: { node: 'items', cell: { row: '0', column: 'amount' } },
        rowIndex: 0,
        column: 'amount',
      },
    ],
    values: {
      items: {
        '': {
          value: [
            {
              map: [
                [{ kw: 'code' }, { kw: 'first' }],
                [{ kw: 'amount' }, null],
              ],
            },
            {
              map: [
                [{ kw: 'code' }, { kw: '0' }],
                [{ kw: 'amount' }, { n: '5' }],
              ],
            },
          ],
          display: '',
          active: true,
        },
      },
    },
  }
  render(
    <InputsPage
      caseId="case.mantra"
      structure={tableStructure}
      run={tableRun}
      revision="base"
      data={{ edit } as unknown as WorkbenchData}
      onSaved={saved}
      navigate={vi.fn()}
      selectedAddress={{ node: 'items', cell: { row: '0', column: 'amount' } }}
    />,
  )
  const target = screen.getByLabelText('amount · first')
  expect(document.activeElement).toBe(target)
  expect(target.getAttribute('aria-invalid')).toBe('true')
  expect(within(target.closest('td')!).getByText('Enter an explicit amount')).toBeTruthy()
  const other = screen.getByLabelText('amount · 0')
  expect(other.getAttribute('aria-invalid')).toBe('false')
  expect(within(other.closest('td')!).queryByText('Enter an explicit amount')).toBeNull()
  fireEvent.change(target, { target: { value: '0' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save amount · first' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(edit).toHaveBeenCalledWith('case.mantra', 'base', [
    { op: 'setInput', address: { node: 'items', cell: { row: 'first', column: 'amount' } }, text: '0' },
  ])
})

it('retains a nil whole-row placeholder so subsequent cell findings and delete operations keep their source index', async () => {
  const edit = vi.fn().mockResolvedValue({
    data: { difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] } },
  })
  const saved = vi.fn()
  const tableStructure: Structure = {
    ...structure,
    generalInputs: [
      {
        id: 'items',
        label: 'Items',
        type: 'table',
        keyColumn: 'code',
        columns: [
          { name: 'code', type: 'keyword' },
          { name: 'amount', type: 'decimal' },
        ],
      },
    ],
  }
  const tableRun: Run = {
    ...run,
    validationPassed: false,
    diagnostics: [
      {
        ...businessFinding,
        address: { node: 'items', cell: { row: '2', column: 'amount' } },
        rowIndex: 2,
        column: 'amount',
      },
    ],
    values: {
      items: {
        '': {
          value: [
            {
              map: [
                [{ kw: 'code' }, { kw: 'first' }],
                [{ kw: 'amount' }, { n: '1' }],
              ],
            },
            null,
            {
              map: [
                [{ kw: 'code' }, { kw: 'third' }],
                [{ kw: 'amount' }, null],
              ],
            },
          ],
          display: '',
          active: true,
        },
      },
    },
  }
  render(
    <InputsPage
      caseId="case.mantra"
      structure={tableStructure}
      run={tableRun}
      revision="base"
      data={{ edit } as unknown as WorkbenchData}
      onSaved={saved}
      navigate={vi.fn()}
      selectedAddress={{ node: 'items', cell: { row: '2', column: 'amount' } }}
    />,
  )
  const target = screen.getByLabelText('amount · third')
  expect(document.activeElement).toBe(target)
  expect(target.getAttribute('aria-invalid')).toBe('true')
  expect(target.closest('td')?.textContent).toContain('Enter an explicit amount')
  const placeholder = screen.getAllByRole('row')[2]
  expect((within(placeholder).getByRole('textbox', { name: /^amount ·/ }) as HTMLInputElement).value).toBe('')
  expect((target as HTMLInputElement).value).toBe('')
  expect(screen.getAllByRole('row')).toHaveLength(4)
  fireEvent.click(screen.getByRole('button', { name: 'Delete row 2' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(edit).toHaveBeenCalledWith('case.mantra', 'base', [{ op: 'deleteRow', table: 'items', index: 1 }])
})

it('submits raw text, preserves rejected input, and reports the server difference after a successful save', async () => {
  const edit = vi
    .fn()
    .mockRejectedValueOnce(new Error('Invalid decimal text'))
    .mockResolvedValueOnce({
      data: {
        difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] },
      },
    })
  const saved = vi.fn()
  render(
    <InputsPage
      caseId="case.mantra"
      structure={structure}
      run={run}
      revision="1234567890abcdef"
      data={{ edit } as unknown as WorkbenchData}
      onSaved={saved}
      navigate={vi.fn()}
    />,
  )
  const input = screen.getByLabelText('Wert') as HTMLInputElement
  fireEvent.change(input, { target: { value: '1.234,xx' } })
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Invalid decimal text')
  expect(input.value).toBe('1.234,xx')
  expect(edit).toHaveBeenCalledWith('case.mantra', '1234567890abcdef', [
    { op: 'setInput', address: { node: 'amount' }, text: '1.234,xx' },
  ])
  fireEvent.change(input, { target: { value: '1.234,56' } })
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(edit).toHaveBeenLastCalledWith('case.mantra', '1234567890abcdef', [
    { op: 'setInput', address: { node: 'amount' }, text: '1.234,56' },
  ])
})

it('uses the declared table key and sends new row text without parsing decimal input', async () => {
  const edit = vi.fn().mockResolvedValue({
    data: { difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] } },
  })
  const tableStructure: Structure = {
    ...structure,
    generalInputs: [
      {
        id: 'items',
        label: 'Items',
        type: 'table',
        keyColumn: 'code',
        columns: [
          { name: 'code', type: 'keyword' },
          { name: 'amount', type: 'decimal' },
        ],
      },
    ],
  }
  const tableRun: Run = {
    ...run,
    values: {
      items: {
        '': {
          value: [
            {
              map: [
                [{ kw: 'code' }, { kw: 'first' }],
                [{ kw: 'amount' }, { n: '1.00' }],
              ],
            },
          ],
          display: '',
          active: true,
        },
      },
    },
  }
  render(
    <InputsPage
      caseId="case.mantra"
      structure={tableStructure}
      run={tableRun}
      revision="1234567890abcdef"
      data={{ edit } as unknown as WorkbenchData}
      onSaved={vi.fn()}
      navigate={vi.fn()}
    />,
  )
  fireEvent.change(screen.getByLabelText('amount · first'), { target: { value: '2,50' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save amount · first' }))
  await waitFor(() =>
    expect(edit).toHaveBeenCalledWith('case.mantra', '1234567890abcdef', [
      { op: 'setInput', address: { node: 'items', cell: { row: 'first', column: 'amount' } }, text: '2,50' },
    ]),
  )
  const code = screen.getAllByLabelText('code').at(-1) as HTMLInputElement
  const amount = screen.getAllByLabelText('amount').at(-1) as HTMLInputElement
  fireEvent.change(code, { target: { value: 'second' } })
  fireEvent.change(amount, { target: { value: '1.234,56' } })
  fireEvent.click(screen.getByRole('button', { name: 'Zeile hinzufügen' }))
  await waitFor(() =>
    expect(edit).toHaveBeenLastCalledWith('case.mantra', '1234567890abcdef', [
      { op: 'insertRow', table: 'items', rowText: { code: 'second', amount: '1.234,56' } },
    ]),
  )
})
