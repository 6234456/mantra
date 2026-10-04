// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { SourcesPage } from './SourcesPage'
import type { WorkbenchData } from '../data'
import type { Structure } from '../types'

afterEach(cleanup)

it('inspects a CSV, maps a source column, and applies the source to the case', async () => {
  document.documentElement.lang = 'en'
  const envelope = <T,>(data: T) => ({
    contract: 'mantra.workbench/4' as const,
    revision: '0123456789abcdef',
    engine: { mantra: 'test', normein: 'test' },
    data,
  })
  const inspect = vi.fn().mockResolvedValue(
    envelope({
      name: 'pay.csv',
      format: 'csv',
      rowCount: 1,
      delimiter: ';',
      columns: [
        { name: 'Person', sample: ['A'] },
        { name: 'Wage', sample: ['12,5'] },
      ],
    }),
  )
  const apply = vi.fn().mockResolvedValue(envelope({}))
  const data = {
    sources: vi.fn().mockResolvedValue(envelope({ sources: [] })),
    importTemplates: vi.fn().mockResolvedValue(envelope({ templates: [] })),
    importInspect: inspect,
    importApply: apply,
  } as unknown as WorkbenchData
  const structure = {
    generalInputs: [{ id: 'amount', label: 'Amount', type: 'decimal' }],
    panels: [],
    nodes: {},
  } as unknown as Structure
  const saved = vi.fn()
  render(
    <SourcesPage caseId="case.mantra" structure={structure} revision="0123456789abcdef" data={data} onSaved={saved} />,
  )
  const file = {
    name: 'pay.csv',
    size: 24,
    arrayBuffer: async () => new TextEncoder().encode('Person;Wage\nA;12,5\n').buffer,
  }
  fireEvent.change(screen.getByLabelText('File'), { target: { files: [file] } })
  await waitFor(() => expect(screen.getByText('pay.csv')).toBeTruthy())
  fireEvent.click(screen.getByRole('button', { name: '2 · Inspect' }))
  await waitFor(() => expect(screen.getByText('1 data rows')).toBeTruthy())
  fireEvent.change(screen.getByLabelText('Target input: Wage'), { target: { value: 'amount' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(inspect).toHaveBeenCalledWith('case.mantra', 'pay.csv', 'csv', expect.any(String))
  expect(apply).toHaveBeenCalledWith('case.mantra', 'pay.csv', 'csv', expect.any(String), '0123456789abcdef', {
    mode: 'wide',
    delimiter: ';',
    decimal: ',',
    grouping: '.',
    columns: { Wage: 'amount' },
  })
})

it.each([
  { editableCase: false, sourcesEditable: true, message: 'This case is read-only.' },
  { editableCase: true, sourcesEditable: false, message: 'Data sources are read-only in this view.' },
])(
  'keeps captured sources visible without invoking unsupported writes: $message',
  async ({ editableCase, sourcesEditable, message }) => {
    document.documentElement.lang = 'en'
    const sources = vi.fn().mockResolvedValue({
      revision: '0123456789abcdef',
      data: {
        sources: [
          { index: 0, kind: 'csv', path: 'data/captured.csv', options: { decimal: '.' }, overridden: ['amount'] },
        ],
      },
    })
    const unsupported = () => {
      throw new Error('Unsupported source mutation')
    }
    const data = {
      canEditCase: vi.fn(() => editableCase),
      canManageSources: vi.fn(() => sourcesEditable),
      sources,
      importTemplates: vi.fn(unsupported),
      importInspect: vi.fn(unsupported),
      importApply: vi.fn(unsupported),
      saveImportTemplate: vi.fn(unsupported),
      removeSource: vi.fn(unsupported),
    } as unknown as WorkbenchData
    const saved = vi.fn()
    render(<SourcesPage caseId="case.mantra" revision="0123456789abcdef" data={data} onSaved={saved} />)
    expect(await screen.findByText('data/captured.csv')).toBeTruthy()
    expect(screen.getByRole('status').textContent).toBe(message)
    expect(screen.getByText('Overridden by manual input: amount')).toBeTruthy()
    expect(sources).toHaveBeenCalledWith('case.mantra', expect.any(AbortSignal))
    const file = { name: 'new.csv', size: 10, arrayBuffer: vi.fn().mockResolvedValue(new ArrayBuffer(0)) }
    const picker = screen.getByLabelText('File') as HTMLInputElement
    expect(picker.disabled).toBe(true)
    fireEvent.change(picker, { target: { files: [file] } })
    for (const name of ['Remove binding', '2 · Inspect']) {
      const button = screen.getByRole('button', { name }) as HTMLButtonElement
      expect(button.disabled).toBe(true)
      fireEvent.click(button)
    }
    expect((screen.getByLabelText('Template') as HTMLSelectElement).disabled).toBe(true)
    expect((screen.getByLabelText('Format') as HTMLSelectElement).disabled).toBe(true)
    expect(file.arrayBuffer).not.toHaveBeenCalled()
    for (const method of [
      'importTemplates',
      'importInspect',
      'importApply',
      'saveImportTemplate',
      'removeSource',
    ] as const)
      expect(data[method]).not.toHaveBeenCalled()
    expect(saved).not.toHaveBeenCalled()
  },
)
