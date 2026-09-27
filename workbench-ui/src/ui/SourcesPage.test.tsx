// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { SourcesPage } from './SourcesPage'
import type { WorkbenchData } from '../data'
import type { Structure } from '../types'

afterEach(cleanup)

it('inspects a CSV, maps a source column, and applies the source to the case', async () => {
  document.documentElement.lang = 'en'
  const envelope = <T,>(data: T) => ({ contract: 'mantra.workbench/1' as const, revision: '0123456789abcdef',
    engine: { mantra: 'test', normein: 'test' }, data })
  const inspect = vi.fn().mockResolvedValue(envelope({ name: 'pay.csv', format: 'csv', rowCount: 1, delimiter: ';',
    columns: [{ name: 'Person', sample: ['A'] }, { name: 'Wage', sample: ['12,5'] }] }))
  const apply = vi.fn().mockResolvedValue(envelope({}))
  const data = {
    sources: vi.fn().mockResolvedValue(envelope({ sources: [] })),
    importTemplates: vi.fn().mockResolvedValue(envelope({ templates: [] })),
    importInspect: inspect, importApply: apply,
  } as unknown as WorkbenchData
  const structure = { generalInputs: [{ id: 'amount', label: 'Amount', type: 'decimal' }], panels: [], nodes: {} } as unknown as Structure
  const saved = vi.fn()
  render(<SourcesPage caseId="case.mantra" structure={structure} revision="0123456789abcdef" data={data} onSaved={saved} />)
  const file = { name: 'pay.csv', size: 24, arrayBuffer: async () => new TextEncoder().encode('Person;Wage\nA;12,5\n').buffer }
  fireEvent.change(screen.getByLabelText('File'), { target: { files: [file] } })
  await waitFor(() => expect(screen.getByText('pay.csv')).toBeTruthy())
  fireEvent.click(screen.getByRole('button', { name: '2 · Inspect' }))
  await waitFor(() => expect(screen.getByText('1 data rows')).toBeTruthy())
  fireEvent.change(screen.getByLabelText('Target input: Wage'), { target: { value: 'amount' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply source' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(inspect).toHaveBeenCalledWith('case.mantra', 'pay.csv', 'csv', expect.any(String))
  expect(apply).toHaveBeenCalledWith('case.mantra', 'pay.csv', 'csv', expect.any(String), '0123456789abcdef',
    { mode: 'wide', delimiter: ';', decimal: ',', grouping: '.', columns: { Wage: 'amount' } })
})
