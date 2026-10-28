// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { SourcesPage } from './SourcesPage'
import type { WorkbenchData } from '../data'
import type { ImportInspection, ImportTemplate, Structure } from '../types'

afterEach(cleanup)

const envelope = <T,>(data: T) => ({
  contract: 'mantra.workbench/4' as const,
  revision: '0123456789abcdef',
  engine: { mantra: 'test', normein: 'test' },
  data,
})

async function excelPage({
  inspection,
  templates = [],
}: { inspection?: ImportInspection; templates?: ImportTemplate[] } = {}) {
  document.documentElement.lang = 'en'
  const inspected: ImportInspection = inspection ?? {
    name: 'source.xlsx',
    format: 'xlsx',
    rowCount: 2,
    columns: [],
    xlsxSheets: [
      {
        name: 'Data',
        rows: 5,
        columns: 3,
        headers: [
          { column: 1, title: 'Name' },
          { column: 2, title: 'Units' },
        ],
        suggestedRange: 'B3:C5',
      },
      { name: 'Other', rows: 4, columns: 2, headers: [{ column: 0, title: 'Code' }], suggestedRange: 'A2:A4' },
    ],
  }
  const apply = vi.fn().mockResolvedValue(envelope({}))
  const saveTemplate = vi
    .fn()
    .mockImplementation(async (template: ImportTemplate) => envelope({ templates: [template] }))
  const saved = vi.fn()
  const data = {
    sources: vi.fn().mockResolvedValue(envelope({ sources: [] })),
    importTemplates: vi.fn().mockResolvedValue(envelope({ templates })),
    importInspect: vi.fn().mockResolvedValue(envelope(inspected)),
    importApply: apply,
    saveImportTemplate: saveTemplate,
  } as unknown as WorkbenchData
  const structure = {
    generalInputs: [
      { id: 'total', label: 'Total', type: 'decimal' },
      {
        id: 'entries',
        label: 'Entries',
        type: 'table',
        columns: [
          { name: 'id', type: 'keyword' },
          { name: 'quantity', type: 'decimal' },
        ],
      },
    ],
    panels: [],
    nodes: {},
  } as unknown as Structure
  render(
    <SourcesPage caseId="case.mantra" structure={structure} revision="0123456789abcdef" data={data} onSaved={saved} />,
  )
  fireEvent.change(screen.getByLabelText('File'), {
    target: { files: [{ name: 'source.xlsx', size: 3, arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer }] },
  })
  await waitFor(() =>
    expect((screen.getByRole('button', { name: '2 · Inspect' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.click(screen.getByRole('button', { name: '2 · Inspect' }))
  await screen.findByLabelText('Excel import')
  return { apply, saveTemplate, saved }
}

function regionMapping() {
  fireEvent.change(screen.getByLabelText('Excel import'), { target: { value: 'range' } })
  fireEvent.change(screen.getByLabelText('Target column: Name'), { target: { value: 'id' } })
  fireEvent.change(screen.getByLabelText('Target column: Units'), { target: { value: 'quantity' } })
}

it('keeps named XLSX inputs as the default even when worksheet metadata is available', async () => {
  const { apply, saved } = await excelPage()
  expect((screen.getByLabelText('Excel import') as HTMLSelectElement).value).toBe('named')
  expect(screen.queryByLabelText('Cell range')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(apply).toHaveBeenCalledWith('case.mantra', 'source.xlsx', 'xlsx', 'AQID', '0123456789abcdef', {})
})

it('maps an ordinary worksheet rectangle into one typed table input with explicit headers', async () => {
  const { apply, saved } = await excelPage()
  regionMapping()
  expect((screen.getByLabelText('Cell range') as HTMLInputElement).value).toBe('B3:C5')
  expect(screen.getByText('B · Name')).toBeTruthy()
  expect(screen.getByText('C · Units')).toBeTruthy()
  expect(screen.getByText(/Shown headers come from the first stored worksheet row/)).toBeTruthy()
  expect((screen.getByLabelText('Table input') as HTMLSelectElement).options.length).toBe(2)
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(apply).toHaveBeenCalledWith('case.mantra', 'source.xlsx', 'xlsx', 'AQID', '0123456789abcdef', {
    input: 'entries',
    sheet: 'Data',
    range: 'B3:C5',
    columns: { Name: 'id', Units: 'quantity' },
  })
})

it('saves and reloads region options through the existing mapping template flow', async () => {
  const { saveTemplate } = await excelPage()
  regionMapping()
  fireEvent.change(screen.getByLabelText('Cell range'), { target: { value: '$B$3:$C$8' } })
  fireEvent.change(screen.getByLabelText('Template name'), { target: { value: 'source-mapping' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save mapping as template' }))
  await waitFor(() => expect(saveTemplate).toHaveBeenCalledOnce())
  expect(saveTemplate).toHaveBeenCalledWith({
    name: 'source-mapping',
    format: 'xlsx',
    options: {
      input: 'entries',
      sheet: 'Data',
      range: '$B$3:$C$8',
      columns: { Name: 'id', Units: 'quantity' },
    },
  })
  await screen.findByRole('option', { name: 'source-mapping · XLSX' })
  fireEvent.change(screen.getByLabelText('Excel import'), { target: { value: 'named' } })
  fireEvent.change(screen.getByLabelText('Template'), { target: { value: 'source-mapping' } })
  expect((screen.getByLabelText('Cell range') as HTMLInputElement).value).toBe('$B$3:$C$8')
  expect((screen.getByLabelText('Target column: Units') as HTMLSelectElement).value).toBe('quantity')
})

it('retains named import compatibility when an older backend provides no worksheet metadata', async () => {
  const { apply } = await excelPage({ inspection: { name: 'source.xlsx', format: 'xlsx', rowCount: 1, columns: [] } })
  expect((screen.getByRole('option', { name: 'Worksheet range' }) as HTMLOptionElement).disabled).toBe(true)
  expect(screen.getByText(/Worksheet metadata is unavailable/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  await waitFor(() => expect(apply).toHaveBeenCalledOnce())
  expect(apply.mock.calls[0][5]).toEqual({})
})

it('blocks duplicate column targets and malformed ranges without invoking source mutation', async () => {
  const { apply } = await excelPage()
  regionMapping()
  fireEvent.change(screen.getByLabelText('Target column: Units'), { target: { value: 'id' } })
  expect((screen.getByRole('button', { name: 'Apply source' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.change(screen.getByLabelText('Target column: Units'), { target: { value: 'quantity' } })
  fireEvent.change(screen.getByLabelText('Cell range'), { target: { value: 'Data!B3:C5' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  expect(apply).not.toHaveBeenCalled()
})

it('clears old headers and mappings when selecting another worksheet', async () => {
  const { apply } = await excelPage()
  regionMapping()
  fireEvent.change(screen.getByLabelText('Worksheet'), { target: { value: 'Other' } })
  expect((screen.getByLabelText('Cell range') as HTMLInputElement).value).toBe('A2:A4')
  expect(screen.queryByLabelText('Target column: Units')).toBeNull()
  expect((screen.getByRole('button', { name: 'Apply source' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.change(screen.getByLabelText('Target column: Code'), { target: { value: 'id' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  await waitFor(() => expect(apply).toHaveBeenCalledOnce())
  expect(apply.mock.calls[0][5]).toEqual({ input: 'entries', sheet: 'Other', range: 'A2:A4', columns: { Code: 'id' } })
})

it('keeps rejected region options and uploaded bytes available for correction', async () => {
  const { apply, saved } = await excelPage()
  apply.mockRejectedValueOnce(new Error('Selected range does not contain the mapped header'))
  regionMapping()
  fireEvent.change(screen.getByLabelText('Cell range'), { target: { value: 'B4:C5' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  expect((await screen.findByRole('alert')).textContent).toContain('Selected range does not contain')
  expect((screen.getByLabelText('Cell range') as HTMLInputElement).value).toBe('B4:C5')
  expect((screen.getByLabelText('Target column: Units') as HTMLSelectElement).value).toBe('quantity')
  expect(screen.getByText('source.xlsx')).toBeTruthy()
  expect(saved).not.toHaveBeenCalled()
})

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
