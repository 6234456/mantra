// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from './App'
import { addressToPath, casePath } from '../address'
import type { Envelope, Explain, Paper, Run, Structure, Diagnostic, RatioAggregate } from '../types'

const caseId = 'sample/case.mantra'
const base = '/cases/sample%2Fcase.mantra'
const wrap = <T,>(data: T): Envelope<T> => ({
  contract: 'mantra.workbench/3',
  revision: '1234567890abcdef',
  engine: { mantra: 'test', normein: 'test' },
  data,
})

const structure: Structure = {
  schema: 'generic/example',
  title: 'Sample calculation',
  mainline: [{ step: 1, panel: 'main', title: 'First result', result: 'total' }],
  panels: [
    {
      id: 'main',
      title: 'First result',
      role: 'mainline',
      step: 1,
      dims: [],
      result: 'total',
      breadcrumb: [{ kind: 'mainline' }, { panel: 'main', label: 'First result' }],
      entries: [{ step: 1, panel: 'main', via: 'total', viaLabel: 'First result', path: ['main'] }],
      fields: [],
      nodes: ['total'],
      imports: [],
      exports: [],
    },
    {
      id: 'detail',
      title: 'Member detail',
      role: 'branch',
      dims: ['member'],
      result: 'total',
      breadcrumb: [{ kind: 'mainline' }, { panel: 'detail', label: 'Member detail' }],
      entries: [{ step: 1, panel: 'main', via: 'total', viaLabel: 'First result', path: ['detail', 'main'] }],
      fields: [],
      nodes: ['choice', 'value'],
      imports: [],
      exports: [],
    },
  ],
  generalInputs: [],
  params: [],
  nodes: {
    total: { label: 'First result', kind: 'total' },
    value: { label: 'Member value', kind: 'line' },
    choice: { label: 'Choice', kind: 'choice' },
  },
  headline: 'total',
}
const run: Run = {
  succeeded: true,
  validationPassed: true,
  diagnostics: [],
  members: {
    member: [
      { key: 'A', label: 'A' },
      { key: 'B', label: 'B' },
    ],
  },
  values: {
    total: { '': { value: { n: '30.00' }, display: '30.00', active: true } },
    value: {
      A: { value: { n: '10.00' }, display: '10.00', active: true },
      B: { value: { n: '20.00' }, display: '20.00', active: true },
    },
  },
}
const paper: Paper = {
  title: 'Paper',
  header: [],
  overview: [],
  auxiliary: [],
  legend: [],
  diagnostics: [],
  audit: [
    {
      anchor: 't1-r1-A',
      citation: '1',
      label: 'Member value A',
      formula: 'Formula A',
      working: 'Calculation A',
      result: '10.00',
    },
    {
      anchor: 't1-r1-B',
      citation: '1',
      label: 'Member value B',
      formula: 'Formula B',
      working: 'Calculation B',
      result: '20.00',
    },
  ],
  tables: [
    {
      id: 'detail',
      ref: '1',
      title: 'Member detail',
      columns: [
        { id: 'label', header: 'Label' },
        { id: 'A', header: 'A' },
        { id: 'B', header: 'B' },
      ],
      rows: [
        {
          kind: 'VALUE',
          depth: 0,
          anchor: 't1-r1',
          cells: [
            { text: 'Member value' },
            { text: '10.00', address: { node: 'value', coord: ['A'] } },
            { text: '20.00', address: { node: 'value', coord: ['B'] } },
          ],
        },
        {
          kind: 'option',
          depth: 0,
          node: 'choice',
          optionKey: 'one',
          flags: ['selected'],
          cells: [{ text: 'Option one' }, { text: '18.00', address: { node: 'choice' } }, { text: 'Reference' }],
        },
        {
          kind: 'option',
          depth: 0,
          node: 'choice',
          optionKey: 'two',
          cells: [{ text: 'Option two' }, { text: '20.00', address: { node: 'choice' } }, { text: 'Reference' }],
        },
      ],
    },
  ],
}

function docs(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    '/fixtures/index.json': {
      cases: [
        {
          id: caseId,
          title: 'Sample case',
          files: {
            structure: '/fixtures/sample/structure.json',
            run: '/fixtures/sample/run.json',
            paper: '/fixtures/sample/paper.json',
            explains: Object.fromEntries(Array.from({ length: 7 }, (_, i) => [`n${i}`, `/fixtures/sample/n${i}.json`])),
          },
        },
      ],
    },
    '/fixtures/sample/structure.json': wrap(structure),
    '/fixtures/sample/run.json': wrap(run),
    '/fixtures/sample/paper.json': wrap(paper),
    ...extra,
  }
}
function serve(documents: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (!(url in documents)) return { ok: false, status: 404, statusText: 'Not Found' }
      return { ok: true, json: async () => documents[url] }
    }),
  )
}
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  if (typeof window.localStorage?.clear === 'function') window.localStorage.clear()
  history.replaceState(null, '', '/')
})

describe('fixture-backed workbench shell', () => {
  it('shows a loading state while fixture discovery is pending', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {})),
    )
    history.replaceState(null, '', `${base}/overview`)
    render(<App />)
    expect(screen.getByRole('status').textContent).toContain('Mantra')
  })

  it('renders overview values and branch links from contract documents', async () => {
    serve(docs())
    history.replaceState(null, '', `${base}/overview`)
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Sample calculation' })).toBeTruthy()
    expect(screen.getByText('Member detail', { selector: '.branch-card span:nth-child(2)' })).toBeTruthy()
    expect(screen.getByText('30.00', { selector: '.station-value' })).toBeTruthy()
  })

  it('renders matrix cells, member audit fallback and Paper choice options', async () => {
    serve(docs())
    history.replaceState(null, '', `${base}/panels/detail`)
    render(<App />)
    const table = await screen.findByRole('table')
    expect(within(table).getByRole('columnheader', { name: 'B' })).toBeTruthy()
    const selectedCell = within(table).getByRole('button', { name: 'B: 20.00' })
    fireEvent.click(selectedCell)
    expect(selectedCell.classList.contains('selected')).toBe(true)
    expect(new URLSearchParams(location.search).get('cell')).toBe('value@B')
    expect(await screen.findByText('Formula B')).toBeTruthy()
    expect(screen.queryByText('Formula A')).toBeNull()
    expect(location.search).toContain('cell=value%40B')
    expect(screen.getByText(/Option one/, { selector: '.choice-option span' })).toBeTruthy()
    const chosen = screen.getByText(/Option one/, { selector: '.choice-option span' }).closest('.choice-option')!
    expect(chosen.classList.contains('chosen')).toBe(true)
    expect(within(chosen as HTMLElement).getByText('18.00')).toBeTruthy()
  })

  it('keeps an immediately selected matrix member after the server refresh remounts the Paper', async () => {
    vi.stubEnv('VITE_WORKBENCH_MODE', 'live')
    let documentChanged: ((event: MessageEvent) => void) | undefined
    vi.stubGlobal(
      'EventSource',
      class {
        addEventListener(name: string, listener: (event: MessageEvent) => void) {
          if (name === 'documentChanged') documentChanged = listener
        }
        close() {}
      },
    )
    const api = `/api/v1/cases/${encodeURIComponent(caseId)}`
    serve({
      '/api/v1/workspace': wrap({ cases: [{ id: caseId, title: 'Sample case' }], parameters: [] }),
      [`${api}/structure`]: wrap(structure),
      [`${api}/run`]: wrap(run),
      [`${api}/paper?panel=detail`]: wrap(paper),
    })
    history.replaceState(null, '', `${base}/panels/detail`)
    render(<App />)
    const originalTable = await screen.findByRole('table')
    fireEvent.click(within(originalTable).getByRole('button', { name: 'B: 20.00' }))
    expect(await screen.findByText('Formula B')).toBeTruthy()
    expect(documentChanged).toBeTypeOf('function')
    act(() => documentChanged!(new MessageEvent('documentChanged', { data: '{"revision":"next"}' })))
    await waitFor(() => expect(screen.getByRole('table')).not.toBe(originalTable))
    expect(screen.getByRole('button', { name: 'B: 20.00' }).classList.contains('selected')).toBe(true)
    expect(screen.getByText('Formula B')).toBeTruthy()
    expect(screen.queryByText('Formula A')).toBeNull()
    expect(new URLSearchParams(location.search).get('cell')).toBe('value@B')
  })

  it('restores and clears matrix selection when popstate changes the cell query', async () => {
    serve(docs())
    history.replaceState(null, '', `${base}/panels/detail?cell=value%40A`)
    render(<App />)
    expect(await screen.findByText('Formula A')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'A: 10.00' }).classList.contains('selected')).toBe(true)

    history.replaceState(null, '', `${base}/panels/detail?cell=value%40B`)
    fireEvent.popState(window)
    expect(await screen.findByText('Formula B')).toBeTruthy()
    expect(screen.queryByText('Formula A')).toBeNull()
    expect(screen.getByRole('button', { name: 'B: 20.00' }).classList.contains('selected')).toBe(true)

    history.replaceState(null, '', `${base}/panels/detail`)
    fireEvent.popState(window)
    expect(document.querySelector('.cell-button.selected')).toBeNull()
    expect(screen.queryByText('Formula B')).toBeNull()

    history.replaceState(null, '', `${base}/panels/detail?cell=value%40A`)
    fireEvent.popState(window)
    expect(await screen.findByText('Formula A')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'A: 10.00' }).classList.contains('selected')).toBe(true)
  })

  it('shows a fixture error state without invented values', async () => {
    serve({})
    history.replaceState(null, '', `${base}/overview`)
    render(<App />)
    expect(await screen.findByText(/WP3 muss die Vertragsdateien bereitstellen/)).toBeTruthy()
    expect(screen.queryByText('30.00')).toBeNull()
  })

  it('keeps the source recovery page visible when calculation cannot load', async () => {
    const documents = docs()
    delete documents['/fixtures/sample/run.json']
    serve(documents)
    history.replaceState(null, '', `${base}/sources`)
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Datenquellen' })).toBeTruthy()
    expect(await screen.findByText('Noch keine Datei gebunden.')).toBeTruthy()
  })

  it('offers explicit continuation at the provenance depth limit', async () => {
    const explanations: Record<string, unknown> = {}
    for (let i = 0; i < 7; i++) {
      const explain: Explain = {
        address: { node: `n${i}` },
        label: `Node ${i}`,
        kind: 'line',
        result: { value: { n: String(i) }, display: String(i) },
        status: 'active',
        steps: [],
        branches: [],
        references: i < 5 ? [{ address: { node: `n${i + 1}` }, label: `Node ${i + 1}`, display: String(i + 1) }] : [],
        parts:
          i === 5
            ? [
                {
                  address: { node: 'n6' },
                  label: 'Node 6',
                  sign: 1,
                  value: { n: '6' },
                  display: '6',
                  crossFooted: false,
                },
              ]
            : [],
        options: [],
        aggregate: null,
      }
      explanations[`/fixtures/sample/n${i}.json`] = wrap(explain)
    }
    serve(docs(explanations))
    history.replaceState(null, '', `${base}/provenance/n0`)
    render(<App />)
    for (let i = 1; i <= 5; i++) {
      const button = await screen.findByRole('button', { name: new RegExp(`n${i}|Node ${i}`) })
      fireEvent.click(button)
    }
    const continueButton = await screen.findByRole('button', { name: 'Weitere Quellen laden' })
    expect(screen.queryByRole('button', { name: /n6|Node 6/ })).toBeNull()
    fireEvent.click(continueButton)
    expect(await screen.findByRole('button', { name: /n6|Node 6/ })).toBeTruthy()
  })

  it('expands a previous-period total through its exact part addresses without duplicating references', async () => {
    const currentAddress = { node: 'carrying-opening', coord: ['Machine', 'P2'] }
    const priorAddress = { node: 'carrying-closing', coord: ['Machine', 'P1'] }
    const openingAddress = { node: 'carrying-opening', coord: ['Machine', 'P1'] }
    const movementAddress = { node: 'depreciation', coord: ['Machine', 'P1'] }
    const explanation = (address: Explain['address'], label: string, value: string): Explain => ({
      address,
      label,
      kind: 'line',
      result: { value: { n: value }, display: value },
      status: 'active',
      steps: [],
      branches: [],
      references: [],
      parts: [],
      options: [],
      aggregate: null,
    })
    const current = explanation(currentAddress, 'Current opening', '76000.00')
    current.references = [{ address: priorAddress, label: 'Prior closing', display: '76000.00', kind: 'PREVIOUS' }]
    const prior = explanation(priorAddress, 'Prior closing', '76000.00')
    prior.kind = 'total'
    prior.references = [{ address: openingAddress, label: 'Prior opening', display: '80000.00' }]
    prior.parts = [
      {
        address: openingAddress,
        label: 'Prior opening',
        sign: 1,
        value: { n: '80000.00' },
        display: '80000.00',
        crossFooted: false,
      },
      {
        address: movementAddress,
        label: 'Prior depreciation',
        sign: -1,
        value: { n: '4000.00' },
        display: '4000.00',
        crossFooted: false,
      },
    ]
    const fixture = docs({
      '/fixtures/sample/current.json': wrap(current),
      '/fixtures/sample/prior.json': wrap(prior),
      '/fixtures/sample/opening-p1.json': wrap(explanation(openingAddress, 'Prior opening', '80000.00')),
      '/fixtures/sample/depreciation-p1.json': wrap(explanation(movementAddress, 'Prior depreciation', '4000.00')),
    })
    const manifest = fixture['/fixtures/index.json'] as {
      cases: Array<{ files: { explains: Record<string, string> } }>
    }
    manifest.cases[0].files.explains = {
      [addressToPath(currentAddress)]: '/fixtures/sample/current.json',
      [addressToPath(priorAddress)]: '/fixtures/sample/prior.json',
      [addressToPath(openingAddress)]: '/fixtures/sample/opening-p1.json',
      [addressToPath(movementAddress)]: '/fixtures/sample/depreciation-p1.json',
    }
    serve(fixture)
    history.replaceState(null, '', `${base}/provenance/${addressToPath(currentAddress)}`)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /carrying-closing/ }))
    await screen.findByRole('button', { name: /Prior closing/ })
    const openingButtons = await screen.findAllByRole('button', { name: /carrying-opening/ })
    expect(openingButtons).toHaveLength(1)
    fireEvent.click(openingButtons[0])
    expect(await screen.findByRole('button', { name: /Prior opening.*80000\.00/ })).toBeTruthy()
    fireEvent.click(await screen.findByRole('button', { name: /depreciation/ }))
    expect(await screen.findByRole('button', { name: /Prior depreciation.*4000\.00/ })).toBeTruthy()
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => url === '/fixtures/sample/opening-p1.json')).toHaveLength(1)
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => url === '/fixtures/sample/depreciation-p1.json')).toHaveLength(
      1,
    )
  })

  it('shows actual parameter layers and a server-shaped comparison fixture', async () => {
    const fixture = docs()
    const manifest = fixture['/fixtures/index.json'] as {
      cases: Array<{ files: Record<string, unknown> }>
      parameters?: Array<{ id: string; path: string }>
    }
    manifest.parameters = [{ id: 'sample/next', path: 'sample/next.mantra' }]
    manifest.cases[0].files.parameters = '/fixtures/sample/parameters.json'
    manifest.cases[0].files.compares = { '["sample/next"]': '/fixtures/sample/compare.json' }
    fixture['/fixtures/sample/parameters.json'] = wrap({
      parameters: [
        {
          id: 'rate',
          label: 'Example rate',
          reference: 'Section 1',
          layers: [
            { layer: 'schema', value: { n: '10.00' }, declared: true },
            { layer: 'parameters', set: 'sample/current', value: { n: '12.00' }, declared: true },
            { layer: 'case', value: null, declared: false },
          ],
          effective: { value: { n: '12.00' }, layer: 'parameters', set: 'sample/current' },
        },
      ],
    })
    fixture['/fixtures/sample/compare.json'] = wrap({
      variant: { parameters: ['sample/next'] },
      mainline: [
        {
          step: 1,
          panel: 'main',
          node: 'total',
          coord: [],
          base: { n: '30.00' },
          variant: { n: '31.00' },
          delta: { n: '1.00' },
          basePresent: true,
          variantPresent: true,
          display: { base: '30.00', variant: '31.00', delta: '+1.00' },
        },
      ],
      changes: [],
      parameterChanges: [
        {
          node: 'rate',
          coord: [],
          base: { n: '12.00' },
          variant: { n: '13.00' },
          delta: { n: '1.00' },
          basePresent: true,
          variantPresent: true,
          display: { base: '12.00', variant: '13.00', delta: '+1.00' },
          baseSource: 'sample/current',
          variantSource: 'sample/next',
        },
      ],
    })
    serve(fixture)
    history.replaceState(null, '', `${base}/parameters`)
    render(<App />)
    expect(await screen.findByText('Example rate')).toBeTruthy()
    expect(screen.getByText('Section 1')).toBeTruthy()
    expect(document.querySelector('.parameter-layer.is-effective')?.textContent).toContain('12.00')
    fireEvent.change(screen.getByLabelText('Vergleichen mit'), { target: { value: 'sample/next' } })
    expect(location.search).toBe('?compare=sample%2Fnext')
    expect(await screen.findByText('30.00 → 31.00')).toBeTruthy()
    expect(screen.getByText('sample/current → sample/next')).toBeTruthy()
    history.back()
    await waitFor(() => expect((screen.getByLabelText('Vergleichen mit') as HTMLSelectElement).value).toBe(''))
    expect(location.search).toBe('')
    history.forward()
    await waitFor(() =>
      expect((screen.getByLabelText('Vergleichen mit') as HTMLSelectElement).value).toBe('sample/next'),
    )
    expect(await screen.findByText('30.00 → 31.00')).toBeTruthy()
    cleanup()
    render(<App />)
    expect(((await screen.findByLabelText('Vergleichen mit')) as HTMLSelectElement).value).toBe('sample/next')
    expect(await screen.findByText('30.00 → 31.00')).toBeTruthy()
  })

  it('filters diagnostics, shows locations, and links an addressed finding to its panel', async () => {
    const fixture = docs()
    const manifest = fixture['/fixtures/index.json'] as { cases: Array<{ files: Record<string, unknown> }> }
    manifest.cases[0].files.diagnostics = '/fixtures/sample/diagnostics.json'
    const findings: Diagnostic[] = [
      {
        severity: 'error',
        category: 'structural',
        rowIndex: null,
        column: null,
        code: 'MANTRA-INPUT-TYPE',
        message: 'Wrong type',
        location: null,
        address: { node: 'value', coord: ['B'] },
        related: [],
      },
      {
        severity: 'warning',
        category: 'evaluation',
        rowIndex: null,
        column: null,
        code: 'DSL-EXAMPLE',
        message: 'Check source',
        location: { document: 'case.mantra', line: 7, column: 4, startOffset: 42, endOffset: 47 },
        address: { node: 'value', coord: ['B'] },
        related: [{ document: 'schema.mantra', line: 2, column: 8 }],
      },
    ]
    fixture['/fixtures/sample/diagnostics.json'] = wrap({ diagnostics: findings })
    serve(fixture)
    history.replaceState(null, '', `${base}/diagnostics`)
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'MANTRA-INPUT-TYPE' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Schweregrad'), { target: { value: 'warning' } })
    expect(screen.queryByRole('heading', { name: 'MANTRA-INPUT-TYPE' })).toBeNull()
    expect(screen.getByText('case.mantra:7:4', { selector: '.finding-location code' })).toBeTruthy()
    expect(screen.getByText('schema.mantra:2:8')).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: /Zum betroffenen Wert/ }))
    expect(location.pathname).toBe(`${casePath(caseId)}/panels/detail`)
    expect(new URLSearchParams(location.search).get('cell')).toBe('value@B')
  })

  it('filters business findings separately and opens their table cell in the input editor', async () => {
    const finding: Diagnostic = {
      category: 'business',
      severity: 'error',
      code: 'MANTRA-INPUT-REQUIRED',
      message: 'Provide an amount',
      location: null,
      address: { node: 'items', cell: { row: '0', column: 'amount' } },
      related: [],
      rowIndex: 0,
      column: 'amount',
    }
    const findings: Diagnostic[] = [
      finding,
      {
        ...finding,
        category: 'evaluation',
        code: 'DSL-TYPE',
        message: 'A technical error',
        rowIndex: null,
        column: null,
      },
      { ...finding, severity: 'warning', code: 'MANTRA-RATIO-ZERO', message: 'Zero denominator' },
    ]
    const fixture = docs()
    const manifest = fixture['/fixtures/index.json'] as { cases: Array<{ files: Record<string, unknown> }> }
    manifest.cases[0].files.diagnostics = '/fixtures/sample/diagnostics.json'
    fixture['/fixtures/sample/diagnostics.json'] = wrap({ diagnostics: findings })
    fixture['/fixtures/sample/structure.json'] = wrap({
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
    })
    fixture['/fixtures/sample/run.json'] = wrap({
      ...run,
      validationPassed: false,
      diagnostics: [finding],
      values: {
        ...run.values,
        items: {
          '': {
            active: true,
            display: '',
            value: [
              {
                map: [
                  [{ kw: 'code' }, { kw: 'invoice-A' }],
                  [{ kw: 'amount' }, null],
                ],
              },
            ],
          },
        },
      },
    })
    serve(fixture)
    history.replaceState(null, '', `${base}/diagnostics`)
    render(<App />)
    await screen.findByRole('heading', { name: 'MANTRA-INPUT-REQUIRED' })
    fireEvent.change(screen.getByLabelText('Kategorie'), { target: { value: 'business' } })
    expect(screen.queryByText('A technical error')).toBeNull()
    fireEvent.change(screen.getByLabelText('Schweregrad'), { target: { value: 'error' } })
    expect(screen.queryByText('Zero denominator')).toBeNull()
    expect(screen.getByText('Zeile 1 · amount')).toBeTruthy()
    expect(screen.getByText(/verhindern weder die Berechnung noch das Speichern/)).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: /Zum betroffenen Wert/ }))
    expect(location.pathname).toBe(`${casePath(caseId)}/inputs/general`)
    expect(new URLSearchParams(location.search).get('cell')).toBe('items#0.amount')
    const input = await screen.findByLabelText('amount · invoice-A')
    expect(document.activeElement).toBe(input)
    expect(input.closest('td')?.textContent).toContain('Provide an amount')
  })

  it('selects multidimensional and transposed cells using their own addresses and keyboard navigation', async () => {
    const stock = { node: 'closing', coord: ['A', 'P2'] }
    const flow = { node: 'aggregate.movement', coord: ['asset=A'] }
    const fixture = docs()
    const manifest = fixture['/fixtures/index.json'] as { cases: Array<{ files: Record<string, unknown> }> }
    manifest.cases[0].files.explains = {
      [addressToPath(stock)]: '/fixtures/sample/stock.json',
      [addressToPath(flow)]: '/fixtures/sample/flow.json',
    }
    const explanation = (address: typeof stock, label: string, display: string): Explain => ({
      address,
      label,
      kind: 'line',
      status: 'active',
      result: { value: { n: display }, display },
      steps: [],
      branches: [],
      references: [],
      parts: [],
      options: [],
    })
    fixture['/fixtures/sample/stock.json'] = wrap(explanation(stock, 'Period closing', '140.00'))
    fixture['/fixtures/sample/flow.json'] = wrap(explanation(flow, 'Total flow', '60.00'))
    fixture['/fixtures/sample/paper.json'] = wrap({
      ...paper,
      audit: [],
      tables: [
        {
          ...paper.tables[0],
          style: 'transpose',
          columns: [
            { id: 'label', header: 'Asset' },
            { id: 'closing', header: 'Closing' },
            { id: 'flow', header: 'Flow' },
          ],
          rows: [
            {
              kind: 'member',
              depth: 0,
              node: null,
              cells: [{ text: 'A' }, { text: '140.00', address: stock }, { text: '60.00', address: flow }],
            },
          ],
        },
      ],
    })
    serve(fixture)
    history.replaceState(null, '', `${base}/panels/detail`)
    render(<App />)
    const closing = await screen.findByRole('button', { name: 'Closing: 140.00' })
    fireEvent.click(closing)
    expect(await screen.findByRole('heading', { name: 'Period closing' })).toBeTruthy()
    expect(new URLSearchParams(location.search).get('cell')).toBe(addressToPath(stock))
    expect(closing.className).toContain('selected')
    closing.focus()
    fireEvent.keyDown(closing, { key: 'ArrowRight' })
    const total = screen.getByRole('button', { name: 'Flow: 60.00' })
    expect(await screen.findByRole('heading', { name: 'Total flow' })).toBeTruthy()
    expect(document.activeElement).toBe(total)
    expect(new URLSearchParams(location.search).get('cell')).toBe(addressToPath(flow))
    expect(total.className).toContain('selected')
  })

  it('opens a weighted aggregate from the paper and preserves its engine evidence in provenance', async () => {
    const aggregate: RatioAggregate = {
      kind: 'ratio',
      numeratorId: 'tax',
      denominatorId: 'profit',
      dimensions: ['member'],
      fixed: {},
      members: [
        { coord: ['A'], numerator: { n: '65.4' }, denominator: { n: '240' }, active: true },
        { coord: ['B'], numerator: { n: '14.4' }, denominator: { n: '80' }, active: true },
      ],
      memberCount: 2,
      activeMemberCount: 2,
      numeratorTotal: { n: '79.8' },
      denominatorTotal: { n: '320' },
      rounding: { scale: 6, mode: 'half-up' },
      result: { n: '0.249375' },
      undefinedReason: null,
      truncated: false,
      display: { numeratorTotal: '79,80', denominatorTotal: '320,00', result: '24,9375 %' },
    }
    const address = { node: 'aggregate.value' }
    const explanation: Explain = {
      address,
      label: 'Weighted total',
      kind: 'aggregate',
      status: 'active',
      result: { value: { n: '0.249375' }, display: '24,9375 %' },
      aggregate,
      steps: [],
      branches: [],
      references: [],
      parts: [],
      options: [],
    }
    const fixture = docs()
    const manifest = fixture['/fixtures/index.json'] as { cases: Array<{ files: Record<string, unknown> }> }
    manifest.cases[0].files.explains = { [addressToPath(address)]: '/fixtures/sample/aggregate.json' }
    fixture['/fixtures/sample/aggregate.json'] = wrap(explanation)
    fixture['/fixtures/sample/paper.json'] = wrap({
      ...paper,
      audit: [
        ...paper.audit,
        {
          anchor: 't1-r1-sum',
          citation: '1',
          label: 'Weighted total',
          formula: '',
          working: '',
          result: '24,9375 %',
          address,
          aggregate,
        },
      ],
      tables: [
        {
          ...paper.tables[0],
          rows: [
            {
              kind: 'VALUE',
              depth: 0,
              anchor: 't1-r1',
              cells: [{ text: 'Rate' }, { text: '24,9375 %', address }, { text: '' }],
            },
          ],
        },
      ],
    })
    serve(fixture)
    history.replaceState(null, '', `${base}/panels/detail`)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'A: 24,9375 %' }))
    expect(await screen.findByRole('heading', { name: 'Weighted total' })).toBeTruthy()
    expect(screen.getByText('79,80')).toBeTruthy()
    expect(screen.queryByText('Formula A')).toBeNull()
    expect(document.querySelectorAll('.inspector .calculation-step')).toHaveLength(0)
    fireEvent.click(screen.getByRole('link', { name: /Herkunft/ }))
    expect(await screen.findByRole('region', { name: 'Gewichtete Quote' })).toBeTruthy()
    expect(screen.getByText('Rundung: 6 · half-up')).toBeTruthy()
    expect(document.querySelectorAll('.provenance-layout .calculation-step')).toHaveLength(0)
  })

  it('shows the addressed reconciliation verdict and amounts from Run in the inspector and provenance', async () => {
    const address = { node: 'reconciliation', coord: ['B'] }
    const validation = {
      active: true,
      passed: false,
      severity: 'error',
      reconciliation: {
        left: { n: '100.02' },
        right: { n: '100.00' },
        difference: { n: '0.02' },
        tolerance: { n: '0.01' },
      },
    }
    const explanation: Explain = {
      address,
      label: 'Reconciliation',
      kind: 'reconcile',
      status: 'active',
      result: { value: { n: '0.02' }, display: '0.02' },
      aggregate: null,
      steps: [],
      branches: [],
      references: [],
      parts: [],
      options: [],
    }
    const fixture = docs()
    const manifest = fixture['/fixtures/index.json'] as { cases: Array<{ files: Record<string, unknown> }> }
    manifest.cases[0].files.explains = { [addressToPath(address)]: '/fixtures/sample/reconciliation.json' }
    fixture['/fixtures/sample/reconciliation.json'] = wrap(explanation)
    fixture['/fixtures/sample/run.json'] = wrap({
      ...run,
      validationPassed: false,
      values: {
        ...run.values,
        reconciliation: { B: { active: true, value: { n: '0.02' }, display: '0.02', validation } },
      },
    })
    fixture['/fixtures/sample/paper.json'] = wrap({
      ...paper,
      tables: [
        {
          ...paper.tables[0],
          rows: [
            { kind: 'VALUE', depth: 0, cells: [{ text: 'Reconciliation' }, { text: '' }, { text: '0.02', address }] },
          ],
        },
      ],
    })
    serve(fixture)
    history.replaceState(null, '', `${base}/panels/detail`)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'B: 0.02' }))
    expect(await screen.findByText('Nicht bestanden')).toBeTruthy()
    expect(screen.getByText('100.02')).toBeTruthy()
    expect(screen.getByText('0.01')).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: /Herkunft/ }))
    expect(await screen.findByText('Nicht bestanden')).toBeTruthy()
    expect(screen.getByText('100.00')).toBeTruthy()
  })
})
