// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ScenariosPage, isComparisonDate, scenariosPath } from './ScenariosPage'
import { FixtureData } from '../data'
import type { WorkbenchData } from '../data'
import type { Compare, Envelope, Structure, Workspace } from '../types'
import compareDocument from '../../../mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/compare-2026.json'
import structureDocument from '../../../mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/structure.json'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const structure = structureDocument.data as unknown as Structure
const parameterId = compareDocument.data.variant.parameters[0]
const workspace: Workspace = { cases: [], parameters: [{ id: parameterId, path: 'parameters.mantra' }] }
const props = {
  caseId: 'sample/case.mantra',
  structure,
  workspace,
  selected: [parameterId],
  revision: 'baseline-revision',
  navigate: vi.fn(),
}

function comparison(id = parameterId): Envelope<Compare> {
  const result = structuredClone(compareDocument) as Envelope<Compare>
  result.data.variant.parameters = [id]
  return result
}

function adapter(compare = vi.fn().mockResolvedValue(comparison())) {
  return { compare } as unknown as WorkbenchData
}

it('renders engine-provided display strings and parameter provenance from a tracked Compare document', async () => {
  const data = adapter()
  render(<ScenariosPage {...props} data={data} />)
  const table = await screen.findByRole('table')
  const change = compareDocument.data.mainline[0]
  expect(within(table).getByText(change.display.base)).toBeTruthy()
  expect(within(table).getByText(change.display.variant)).toBeTruthy()
  expect(within(table).getByText(change.display.delta)).toBeTruthy()
  expect(screen.getByText(compareDocument.revision)).toBeTruthy()
  expect(data.compare).toHaveBeenCalledWith(props.caseId, [parameterId], expect.any(AbortSignal))
  const details = document.querySelector('details')!
  fireEvent.click(details.querySelector('summary')!)
  const parameter = compareDocument.data.parameterChanges[0]
  const parameterRow = details.querySelector('article')!
  expect(within(parameterRow).getByText(`${parameter.baseSource} → ${parameter.variantSource}`)).toBeTruthy()
  expect(within(parameterRow).getByText(parameter.display.variant)).toBeTruthy()
})

it('bounds comparison concurrency and retains errors independently of completed scenarios', async () => {
  const selected = ['first', 'second', 'third', 'fourth']
  const requests: Array<{
    id: string
    resolve: (response: Envelope<Compare>) => void
    reject: (error: Error) => void
  }> = []
  const compare = vi.fn(
    (_caseId: string, sets: string[]) =>
      new Promise<Envelope<Compare>>((resolve, reject) => requests.push({ id: sets[0], resolve, reject })),
  )
  render(
    <ScenariosPage
      {...props}
      data={adapter(compare)}
      selected={selected}
      workspace={{ cases: [], parameters: selected.map((id) => ({ id, path: `${id}.mantra` })) }}
    />,
  )
  expect(compare).toHaveBeenCalledTimes(2)
  await act(async () => requests[0].resolve(comparison('first')))
  expect(compare).toHaveBeenCalledTimes(3)
  await act(async () => requests[1].reject(new Error('Rejected by engine')))
  expect(compare).toHaveBeenCalledTimes(4)
  expect(screen.getByRole('alert').textContent).toBe('Rejected by engine')
  expect(screen.getByRole('table')).toBeTruthy()
  await act(async () => {
    requests[2].resolve(comparison('third'))
    requests[3].resolve(comparison('fourth'))
  })
  expect(compare.mock.calls.map(([, sets]) => sets)).toEqual(selected.map((id) => [id]))
  expect(screen.queryByText('Wird verglichen…')).toBeNull()
  expect((screen.getByRole('button', { name: 'Szenarien neu laden' }) as HTMLButtonElement).disabled).toBe(false)
})

it('aborts a previous batch and discards late results after a baseline revision changes', async () => {
  const requests: Array<{ signal: AbortSignal; resolve: (response: Envelope<Compare>) => void }> = []
  const compare = vi.fn(
    (_case: string, _sets: string[], signal?: AbortSignal) =>
      new Promise<Envelope<Compare>>((resolve) => requests.push({ signal: signal!, resolve })),
  )
  const data = adapter(compare)
  const view = render(<ScenariosPage {...props} data={data} />)
  expect(requests).toHaveLength(1)
  view.rerender(<ScenariosPage {...props} data={data} revision="changed-revision" />)
  expect(requests[0].signal.aborted).toBe(true)
  expect(requests).toHaveLength(2)
  const old = comparison()
  old.revision = 'old-comparison'
  await act(async () => requests[0].resolve(old))
  expect(screen.queryByRole('table')).toBeNull()
  const current = comparison()
  current.revision = 'current-comparison'
  await act(async () => requests[1].resolve(current))
  expect(await screen.findByText('current-comparison')).toBeTruthy()
  expect(screen.queryByText('old-comparison')).toBeNull()
  view.unmount()
  expect(requests[1].signal.aborted).toBe(true)
})

it('does not send comparisons for unknown ids or a URL with more than eight scenarios', async () => {
  const data = adapter()
  const view = render(<ScenariosPage {...props} data={data} selected={['unknown']} />)
  expect(screen.getByRole('alert').textContent).toContain('unknown')
  expect(data.compare).not.toHaveBeenCalled()
  const selected = Array.from({ length: 9 }, (_, index) => `set-${index}`)
  view.rerender(
    <ScenariosPage
      {...props}
      data={data}
      selected={selected}
      workspace={{ cases: [], parameters: selected.map((id) => ({ id, path: id })) }}
    />,
  )
  expect(screen.getByRole('alert').textContent).toContain('höchstens 8')
  expect(data.compare).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Auswahl löschen' }))
  expect(props.navigate).toHaveBeenLastCalledWith(scenariosPath(props.caseId, []))
})

it('rejects a response for a different parameter scenario without displaying its values', async () => {
  render(<ScenariosPage {...props} data={adapter(vi.fn().mockResolvedValue(comparison('other')))} />)
  expect((await screen.findByRole('alert')).textContent).toContain('anderen Szenario')
  expect(screen.queryByRole('table')).toBeNull()
})

it('omits requests when an adapter declares comparison unavailable', () => {
  const data = adapter()
  data.canCompareParameters = () => false
  render(<ScenariosPage {...props} data={data} />)
  expect(screen.getByRole('status').textContent).toContain('nicht verfügbar')
  expect(screen.queryByRole('combobox')).toBeNull()
  expect(data.compare).not.toHaveBeenCalled()
})

it('states fixture capabilities and reports a missing saved comparison', async () => {
  const data = new FixtureData()
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, json: async () => ({ cases: [{ id: props.caseId, files: {} }] }) })),
  )
  render(<ScenariosPage {...props} data={data} />)
  expect(screen.getByRole('note').textContent).toContain('gespeicherte Compare-Ergebnisse')
  expect((await screen.findByRole('alert')).textContent).toContain('Comparison fixture unavailable')
  expect(screen.queryByRole('table')).toBeNull()
})

it('paginates result metadata while keeping every displayed numeric value engine-owned', async () => {
  const response = comparison()
  const row = response.data.mainline[0]
  response.data.mainline = Array.from({ length: 51 }, (_, index) => ({ ...row, coord: [`member-${index}`] }))
  render(<ScenariosPage {...props} data={adapter(vi.fn().mockResolvedValue(response))} />)
  const table = await screen.findByRole('table')
  expect(within(table).getAllByRole('row')).toHaveLength(51)
  expect(within(table).queryByText(/member-50$/)).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Weiter' }))
  await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2))
  expect(within(table).getByText(/member-50$/)).toBeTruthy()
  expect(within(table).getByText(row.display.delta!)).toBeTruthy()
})

it.each([undefined, '', '2026-02-30', '2026-2-03', 'today'])(
  'holds package scenario requests until an explicit valid comparison date is supplied (%s)',
  (effectiveDate) => {
    const data = adapter()
    data.canCompareParameters = () => false
    data.canCompareScenarios = () => true
    data.parameterComparisonDateRequired = () => true
    render(<ScenariosPage {...props} data={data} effectiveDate={effectiveDate} />)
    expect((screen.getByLabelText('Vergleichsdatum') as HTMLInputElement).value).toBe('')
    expect(screen.getByText(/Ein gültiges Vergleichsdatum wählen/)).toBeTruthy()
    expect(screen.queryByText('Wird verglichen…')).toBeNull()
    expect(data.compare).not.toHaveBeenCalled()
    expect(screen.queryByRole('table')).toBeNull()
  },
)

it('uses the scenario capability and passes the authored date with exact scoped parameter ids', async () => {
  const id = 'captured/rates/current'
  const data = adapter(vi.fn().mockResolvedValue(comparison(id)))
  data.canCompareParameters = () => false
  data.canCompareScenarios = () => true
  data.parameterComparisonDateRequired = () => true
  const view = render(
    <ScenariosPage {...props} data={data} selected={[id]} workspace={{ cases: [], parameters: [{ id, path: '' }] }} />,
  )
  fireEvent.change(screen.getByLabelText('Vergleichsdatum'), { target: { value: '2026-07-01' } })
  expect(props.navigate).toHaveBeenLastCalledWith(scenariosPath(props.caseId, [id], '2026-07-01'))
  expect(data.compare).not.toHaveBeenCalled()
  view.rerender(
    <ScenariosPage
      {...props}
      data={data}
      selected={[id]}
      workspace={{ cases: [], parameters: [{ id, path: '' }] }}
      effectiveDate="2026-07-01"
    />,
  )
  await screen.findByRole('table')
  expect(data.compare).toHaveBeenCalledWith(props.caseId, [id], expect.any(AbortSignal), '2026-07-01')
  fireEvent.click(screen.getByRole('button', { name: 'Auswahl löschen' }))
  expect(props.navigate).toHaveBeenLastCalledWith(scenariosPath(props.caseId, [], '2026-07-01'))
  fireEvent.click(screen.getByRole('button', { name: /captured\/rates\/current.*entfernen/ }))
  expect(props.navigate).toHaveBeenLastCalledWith(scenariosPath(props.caseId, [], '2026-07-01'))
})

it('preserves the explicit date when adding a scenario and encodes scoped ids independently', () => {
  const selected = ['old/rates']
  const data = adapter()
  data.parameterComparisonDateRequired = () => true
  render(
    <ScenariosPage
      {...props}
      data={data}
      selected={selected}
      workspace={{ cases: [], parameters: selected.concat('new/rates plus').map((id) => ({ id, path: '' })) }}
      effectiveDate="2026-02-28"
    />,
  )
  fireEvent.change(screen.getByLabelText('Parametersatz hinzufügen'), { target: { value: 'new/rates plus' } })
  const path = props.navigate.mock.calls.at(-1)![0] as string
  const query = new URL(path, 'http://localhost').searchParams
  expect(query.getAll('scenario')).toEqual(['old/rates', 'new/rates plus'])
  expect(query.get('effectiveDate')).toBe('2026-02-28')
  expect(isComparisonDate('2024-02-29')).toBe(true)
  expect(isComparisonDate('2026-02-29')).toBe(false)
})

it('cancels the prior date batch and discards its late response when the explicit date changes', async () => {
  const requests: Array<{ date?: string; signal: AbortSignal; resolve: (response: Envelope<Compare>) => void }> = []
  const compare = vi.fn(
    (_case: string, _sets: string[], signal?: AbortSignal, date?: string) =>
      new Promise<Envelope<Compare>>((resolve) => requests.push({ date, signal: signal!, resolve })),
  )
  const data = adapter(compare)
  data.canCompareScenarios = () => true
  data.parameterComparisonDateRequired = () => true
  const view = render(<ScenariosPage {...props} data={data} effectiveDate="2026-06-30" />)
  view.rerender(<ScenariosPage {...props} data={data} effectiveDate="2026-07-01" />)
  expect(requests[0].signal.aborted).toBe(true)
  expect(requests.map((request) => request.date)).toEqual(['2026-06-30', '2026-07-01'])
  const old = comparison()
  old.revision = 'prior-date-result'
  await act(async () => requests[0].resolve(old))
  expect(screen.queryByRole('table')).toBeNull()
  const current = comparison()
  current.revision = 'chosen-date-result'
  await act(async () => requests[1].resolve(current))
  expect(await screen.findByText('chosen-date-result')).toBeTruthy()
  expect(screen.queryByText('prior-date-result')).toBeNull()
})
