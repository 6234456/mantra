// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { InputsPage } from './InputsPage'
import type { WorkbenchData } from '../data'
import type { Run, Structure } from '../types'

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
  members: {},
  diagnostics: [],
  values: {
    amount: { '': { value: { n: '100.00' }, display: '100,00', active: true, origin: 'case' } },
  },
}
afterEach(cleanup)

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
