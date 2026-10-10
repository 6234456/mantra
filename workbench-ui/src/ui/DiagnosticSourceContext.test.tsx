// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import diagnosticGolden from '../../../mantra-workbench/src/test/resources/golden/ifrs-income-taxes-case-unreconciled-5198903e/diagnostics.json'
import sourceGolden from '../../../mantra-workbench/src/test/resources/golden/ifrs-income-taxes-case-unreconciled-5198903e/source-context.json'
import structureGolden from '../../../mantra-workbench/src/test/resources/golden/ifrs-income-taxes-case-unreconciled-5198903e/structure.json'
import { WorkbenchReadError, type WorkbenchData } from '../data'
import type { Diagnostics, Envelope, SourceContext, Structure } from '../types'
import { DiagnosticSourceContext } from './DiagnosticSourceContext'
import { DiagnosticsPage } from './ReadOnlyPages'

vi.mock('../i18n', async (original) => ({ ...(await original<typeof import('../i18n')>()), language: () => 'en' }))
afterEach(cleanup)

const diagnostics = diagnosticGolden as Envelope<Diagnostics>
const source = sourceGolden as Envelope<SourceContext>
const structure = structureGolden.data as unknown as Structure
const caseId = source.data.case
const finding = diagnostics.data.diagnostics[0]

function sourceProps(data: Partial<WorkbenchData>) {
  return { caseId, finding, data: data as WorkbenchData, diagnosticIndex: 0, expectedRevision: diagnostics.revision }
}

it('loads captured source only on request and displays the golden line numbers and multiline highlight', async () => {
  let complete!: (value: Envelope<SourceContext>) => void
  const sourceContext = vi.fn(() => new Promise<Envelope<SourceContext>>((resolve) => (complete = resolve)))
  const { container } = render(<DiagnosticSourceContext {...sourceProps({ sourceContext })} />)
  expect(sourceContext).not.toHaveBeenCalled()
  const button = screen.getByRole('button', { name: 'Show source text' })
  fireEvent.click(button)
  expect(sourceContext).toHaveBeenCalledWith(caseId, 0, diagnostics.revision, expect.any(AbortSignal))
  expect(screen.getByRole('status').textContent).toContain('Loading source text')
  expect((button as HTMLButtonElement).disabled).toBe(true)
  await act(async () => complete(source))
  const lines = screen.getAllByRole('listitem')
  expect(lines.map((line) => Number(line.getAttribute('value')))).toEqual(source.data.lines.map((line) => line.number))
  for (const [index, line] of source.data.lines.entries()) {
    expect(lines[index].textContent).toBe(line.text)
    expect(lines[index].getAttribute('aria-label')).toBe(`Line ${line.number}, Column ${line.startColumn}`)
  }
  expect([...container.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(
    source.data.lines
      .filter((line) => line.highlightStart !== null && line.highlightEnd !== null)
      .map((line) => line.text.slice(line.highlightStart!, line.highlightEnd!)),
  )
  expect(screen.getByText(source.data.revision)).toBeTruthy()
  expect(screen.getByText(source.data.case)).toBeTruthy()
  expect(screen.getByLabelText('Source text', { selector: '.diagnostic-source-scroll' }).getAttribute('tabindex')).toBe(
    '0',
  )
})

it('reports absent source capabilities and absent document positions without creating evidence', () => {
  const { rerender } = render(<DiagnosticSourceContext {...sourceProps({})} />)
  expect(screen.getByText('Source text is unavailable in this view.')).toBeTruthy()
  expect(screen.queryByRole('button')).toBeNull()
  rerender(<DiagnosticSourceContext {...sourceProps({})} finding={{ ...finding, location: null }} />)
  expect(screen.getByText('This finding has no document source location.')).toBeTruthy()
  expect(screen.queryByRole('list')).toBeNull()
})

it('shows a revision conflict without displaying or retrying source from the obsolete finding', async () => {
  const sourceContext = vi.fn().mockRejectedValue(new WorkbenchReadError(409, 'Changed', 'new-revision'))
  render(<DiagnosticSourceContext {...sourceProps({ sourceContext })} />)
  fireEvent.click(screen.getByRole('button', { name: 'Show source text' }))
  expect((await screen.findByRole('alert')).textContent).toContain('The documents have changed')
  expect((screen.getByRole('button', { name: 'Try again' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.queryByRole('list')).toBeNull()
  expect(sourceContext).toHaveBeenCalledOnce()
})

it.each(['root revision', 'source revision', 'location'])('rejects mismatched %s evidence', async (mismatch) => {
  const response = structuredClone(source)
  const ownedFinding = { ...finding, caseRevision: source.data.revision }
  if (mismatch === 'root revision') response.revision = 'new-revision'
  if (mismatch === 'source revision') response.data.revision = 'new-revision'
  if (mismatch === 'location') response.data.location.line += 1
  const sourceContext = vi.fn().mockResolvedValue(response)
  render(<DiagnosticSourceContext {...sourceProps({ sourceContext })} finding={ownedFinding} />)
  fireEvent.click(screen.getByRole('button', { name: 'Show source text' }))
  expect((await screen.findByRole('alert')).textContent).toContain('The documents have changed')
  expect(screen.queryByRole('list')).toBeNull()
})

it('permits retry after a transport failure and escapes source text while retaining a clipping notice', async () => {
  const escaped = structuredClone(source)
  escaped.data.lines[0] = {
    ...escaped.data.lines[0],
    text: '<img src=x onerror=alert(1)>',
    startColumn: 20,
  }
  escaped.data.truncated = true
  const sourceContext = vi.fn().mockRejectedValueOnce(new Error('Connection lost')).mockResolvedValueOnce(escaped)
  const { container } = render(<DiagnosticSourceContext {...sourceProps({ sourceContext })} />)
  fireEvent.click(screen.getByRole('button', { name: 'Show source text' }))
  expect((await screen.findByRole('alert')).textContent).toContain('Connection lost')
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
  await screen.findByText('Bounded excerpt; further lines or characters have been omitted.')
  expect(container.querySelector('img')).toBeNull()
  expect(screen.getAllByRole('listitem')[0].textContent).toBe('…<img src=x onerror=alert(1)>')
  expect(sourceContext).toHaveBeenCalledTimes(2)
})

it('retains the original diagnostics index after selection and aborts an obsolete source request', async () => {
  let complete!: (value: Envelope<SourceContext>) => void
  const sourceContext = vi.fn(
    (_id: string, _index: number, _revision: string, _signal?: AbortSignal) =>
      new Promise<Envelope<SourceContext>>((resolve) => (complete = resolve)),
  )
  const data = { diagnostics: vi.fn().mockResolvedValue(diagnostics), sourceContext } as unknown as WorkbenchData
  render(<DiagnosticsPage caseId={caseId} structure={structure} data={data} navigate={vi.fn()} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Show source text' }))
  const firstSignal = sourceContext.mock.calls[0][3] as AbortSignal
  const list = document.querySelector('.findings-list')!
  fireEvent.click(within(list as HTMLElement).getByRole('button', { name: /MANTRA-INPUT-REQUIRED/ }))
  expect(firstSignal.aborted).toBe(true)
  await act(async () => complete(source))
  expect(screen.queryByRole('list')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Show source text' }))
  expect(sourceContext).toHaveBeenLastCalledWith(caseId, 2, diagnostics.revision, expect.any(AbortSignal))
})

it('refreshes findings and removes previously opened source on workspace change', async () => {
  const data = {
    diagnostics: vi.fn().mockResolvedValue(diagnostics),
    sourceContext: vi.fn().mockResolvedValue(source),
  } as unknown as WorkbenchData
  const props = { caseId, structure, data, navigate: vi.fn() }
  const { rerender } = render(<DiagnosticsPage {...props} refresh={0} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Show source text' }))
  await screen.findByRole('list')
  rerender(<DiagnosticsPage {...props} refresh={1} />)
  await screen.findByRole('button', { name: 'Show source text' })
  expect(data.diagnostics).toHaveBeenCalledTimes(2)
  expect(screen.queryByRole('list')).toBeNull()
})

it('uses the server diagnostics index when a severity filter makes a later finding the first visible item', async () => {
  const filteredGolden = structuredClone(diagnostics)
  filteredGolden.data.diagnostics[0].severity = 'warning'
  filteredGolden.data.diagnostics[1].severity = 'warning'
  const sourceContext = vi.fn(() => new Promise<Envelope<SourceContext>>(() => {}))
  const data = { diagnostics: vi.fn().mockResolvedValue(filteredGolden), sourceContext } as unknown as WorkbenchData
  render(<DiagnosticsPage caseId={caseId} structure={structure} data={data} navigate={vi.fn()} />)
  await screen.findByRole('button', { name: 'Show source text' })
  fireEvent.change(screen.getByRole('combobox', { name: 'Severity' }), { target: { value: 'error' } })
  fireEvent.click(screen.getByRole('button', { name: 'Show source text' }))
  expect(sourceContext).toHaveBeenCalledWith(caseId, 2, diagnostics.revision, expect.any(AbortSignal))
})
