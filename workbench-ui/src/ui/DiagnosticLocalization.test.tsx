// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import type { WorkbenchData } from '../data'
import type { Diagnostic, Diagnostics, Envelope, Run, Structure } from '../types'
import { diagnosticMessages } from '../diagnosticMessages'
import { InputsPage } from './InputsPage'
import { DiagnosticsPage } from './ReadOnlyPages'

vi.mock('../i18n', async (original) => ({ ...(await original<typeof import('../i18n')>()), language: () => 'de' }))
afterEach(cleanup)

const structure: Structure = {
  schema: 'test',
  title: 'Test',
  mainline: [],
  panels: [],
  params: [],
  nodes: {},
  generalInputs: [{ id: 'fact', label: 'Fact', type: 'decimal', dims: [] }],
}
const finding: Diagnostic = {
  code: 'MANTRA-INPUT-REQUIRED',
  category: 'business',
  severity: 'error',
  message: 'Input fact[A] must be explicitly supplied; default 0 does not count.',
  address: { case: null, node: 'fact' },
  location: null,
  related: [],
  rowIndex: null,
  column: null,
  caseRevision: null,
}
const run: Run = {
  caseGraph: null,
  usage: null,
  failure: null,
  succeeded: true,
  validationPassed: false,
  members: {},
  values: { fact: { '': { value: { n: '0' }, display: '0,00', active: true, origin: 'implicit', link: null } } },
  diagnostics: [finding],
}

it('shows the German explanation and visible untouched English details beside an input without blocking save', () => {
  render(
    <InputsPage
      caseId="case"
      structure={structure}
      run={run}
      revision="revision"
      data={{ edit: vi.fn() } as unknown as WorkbenchData}
      onSaved={vi.fn()}
      navigate={vi.fn()}
    />,
  )
  const input = screen.getByLabelText('Wert')
  const control = within(input.closest('.input-control')!)
  expect(control.getByText(diagnosticMessages[finding.code].de)).toBeTruthy()
  expect(control.getByText(finding.message).getAttribute('lang')).toBe('en')
  expect(control.getByText(/Originaldetails/)).toBeTruthy()
  expect((control.getByRole('button', { name: 'Speichern' }) as HTMLButtonElement).disabled).toBe(false)
})

it('retains complete original cell details next to the translated table-cell finding', () => {
  const cellFinding = {
    ...finding,
    address: { case: null, node: 'records', cell: { row: '0', column: 'fact' } },
    rowIndex: 0,
    column: 'fact',
  }
  const tableStructure: Structure = {
    ...structure,
    generalInputs: [{ id: 'records', type: 'table', columns: [{ name: 'fact', type: 'decimal' }] }],
  }
  const tableRun: Run = {
    ...run,
    diagnostics: [cellFinding],
    values: { records: { '': { value: [{ map: [[{ kw: 'fact' }, null]] }], display: '', active: true, link: null } } },
  }
  render(
    <InputsPage
      caseId="case"
      structure={tableStructure}
      run={tableRun}
      revision="revision"
      data={{ edit: vi.fn() } as unknown as WorkbenchData}
      onSaved={vi.fn()}
      navigate={vi.fn()}
    />,
  )
  const target = screen.getByLabelText('fact · 0')
  const cell = within(target.closest('td')!)
  expect(cell.getByText(diagnosticMessages[finding.code].de)).toBeTruthy()
  expect(cell.getByText(finding.message).getAttribute('lang')).toBe('en')
})

it('shows code-localized descriptions in the findings list and raw causes as visible details including unknown kernel codes', async () => {
  const unknown: Diagnostic = {
    ...finding,
    code: 'DSL-KERNEL-NEW-CAUSE',
    category: 'evaluation',
    message: 'DSL-KERNEL-NEW-CAUSE: <callback> returned 1/0',
  }
  const envelope: Envelope<Diagnostics> = {
    contract: 'mantra.workbench/4',
    revision: 'revision',
    engine: { mantra: 'test', normein: 'pinned' },
    data: { diagnostics: [unknown] },
  }
  render(
    <DiagnosticsPage
      caseId="case"
      structure={structure}
      data={{ diagnostics: vi.fn().mockResolvedValue(envelope) } as unknown as WorkbenchData}
      navigate={vi.fn()}
    />,
  )
  const details = await screen.findByText(unknown.message)
  expect(details.getAttribute('lang')).toBe('en')
  expect(details.closest('.finding-original-detail')?.textContent).toContain('Originaldetails')
  expect(
    screen.getAllByText(
      'Der Rechenkern hat einen Befund gemeldet. Die Originaldetails enthalten die konkrete Ursache.',
    ),
  ).toHaveLength(2)
  expect(details.innerHTML).toContain('&lt;callback&gt;')
})
