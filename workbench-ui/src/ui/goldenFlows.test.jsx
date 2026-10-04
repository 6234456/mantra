// @vitest-environment jsdom
import React from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { App } from './App'
import { addressToPath, casePath } from '../address'

const golden = resolve(process.cwd(), '../mantra-workbench/src/test/resources/golden')
const manifest = JSON.parse(readFileSync(resolve(golden, 'index.json'), 'utf8'))
const files = { '/fixtures/index.json': manifest }
for (const item of manifest.cases) {
  for (const value of Object.values(item.files)) {
    const paths = typeof value === 'string' ? [value] : Object.values(value)
    for (const path of paths) {
      files[path] = JSON.parse(readFileSync(resolve(golden, path.replace('/fixtures/', '')), 'utf8'))
    }
  }
}

const scenarios = [
  {
    id: 'de-est/case-mustermann.mantra',
    heading: 'Einkommensteuer 2025',
    result: '1.462,24',
    panel: 'zve',
    cell: '112.380,00',
    address: { node: 'summe-einkuenfte' },
  },
  {
    id: 'ifrs-impairment/case-demo.mantra',
    heading: 'IAS 36 – Impairment test with corporate assets',
    result: '121',
    panel: 'step-1',
    cell: '120',
    address: { node: 'carrying-amount', coord: ['A'] },
  },
  {
    id: 'cost-accounting/case-demo.mantra',
    heading: 'Product cost by manufacturing order',
    result: '1,140.00',
    panel: 'cost-sources',
    cell: '12,400.00',
    address: { node: 'direct-primary-total' },
  },
  {
    id: 'ifrs-income-taxes/case-demo.mantra',
    heading: 'IAS 12 – Tax-expense reconciliation',
    result: '79,800',
    panel: 'entity-tax',
    cell: '240,000',
    address: { node: 'accounting-profit', coord: ['North'] },
  },
  {
    id: 'fixed-assets/case-demo.mantra',
    heading: 'Fixed assets – cost and depreciation roll-forward',
    result: '60,000.00',
    panel: 'carrying-flow',
    cell: '76,000.00',
    rowLabel: 'Closing carrying amount',
    address: { node: 'carrying-closing', coord: ['Machine', 'P1'] },
  },
  {
    id: 'ifrs-leases/case-demo.mantra',
    heading: 'IFRS 16 – annual lease roll-forward',
    result: '–',
    panel: 'liability-flow',
    cell: '9,523.81',
    rowLabel: 'Closing lease liability',
    address: { node: 'liability-closing', coord: ['Office', 'P2'] },
  },
]

function serveGolden(extraFiles = {}, fixtureManifest = manifest) {
  const available = { ...files, '/fixtures/index.json': fixtureManifest, ...extraFiles }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input) => {
      const document = available[input]
      if (!document) return { ok: false, status: 404, statusText: 'Not Found' }
      return { ok: true, json: async () => document }
    }),
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  if (typeof window.localStorage?.clear === 'function') window.localStorage.clear()
  history.replaceState(null, '', '/')
})

describe('the six tracked applications, from overview to a selected Paper cell', () => {
  it('shows real Explain steps from the ESt golden on the provenance route', async () => {
    const id = 'de-est/case-mustermann.mantra'
    const address = { node: 'ermaessigung-35a' }
    const entry = manifest.cases.find((item) => item.id === id)
    const goldenExplain = files[entry.files.explains[addressToPath(address)]].data
    serveGolden()
    history.replaceState(null, '', `${casePath(id)}/provenance/${encodeURIComponent(addressToPath(address))}`)
    render(<App />)
    expect(await screen.findByRole('heading', { name: goldenExplain.label, level: 1 })).toBeTruthy()
    expect(goldenExplain.steps.length).toBeGreaterThan(0)
    expect((await screen.findAllByText(goldenExplain.steps[0].text)).length).toBeGreaterThan(0)
    const missing = goldenExplain.references.find((ref) => !entry.files.explains[addressToPath(ref.address)])
    expect(missing).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: new RegExp(missing.address.node) }))
    expect(await screen.findByText('Für diese Ansicht liegen noch keine Daten vor.')).toBeTruthy()
  })

  it.each(scenarios)('$id', async (scenario) => {
    serveGolden()
    history.replaceState(null, '', `${casePath(scenario.id)}/overview`)
    render(<App />)

    expect(await screen.findByRole('heading', { name: scenario.heading, level: 1 })).toBeTruthy()
    expect(document.querySelector('.result-card strong')?.textContent).toBe(scenario.result)

    const panelUrl = `${casePath(scenario.id)}/panels/${scenario.panel}`
    const link = document.querySelector(`.mainline-map a[href="${panelUrl}"]`)
    expect(link).toBeTruthy()
    fireEvent.click(link)
    const table = await screen.findByRole('table')
    const cell = within(table)
      .getAllByRole('button')
      .find(
        (button) =>
          button.textContent === scenario.cell &&
          (!scenario.rowLabel || button.closest('tr')?.textContent.includes(scenario.rowLabel)),
      )
    expect(cell).toBeTruthy()
    fireEvent.click(cell)
    expect(location.pathname).toBe(panelUrl)
    expect(new URLSearchParams(location.search).get('cell')).toBe(addressToPath(scenario.address))
    expect(cell?.classList.contains('selected')).toBe(true)
  })

  it.each(scenarios)('$id export uses workbook sheets and fidelity reported by Excel', async (scenario) => {
    serveGolden()
    history.replaceState(null, '', `${casePath(scenario.id)}/export`)
    render(<App />)
    const entry = manifest.cases.find((item) => item.id === scenario.id)
    const first = files[entry.files['export-preview']].data
    expect(await screen.findByRole('heading', { name: `${first.sheets.length} Blätter` })).toBeTruthy()
    expect(screen.getByText(first.report.formulaCells.toString(), { selector: '.export-metrics strong' })).toBeTruthy()
    expect(screen.getByText(first.report.names.toString(), { selector: '.export-metrics strong' })).toBeTruthy()
    const second = first.sheets[1]
    fireEvent.click(screen.getByRole('button', { name: new RegExp(second.name) }))
    expect(await screen.findByRole('heading', { name: second.name, level: 2 })).toBeTruthy()
    const selected = files[entry.files[`export-preview:${second.name}`]].data
    expect(document.querySelector('.export-formula .mono')?.textContent).toBe(
      selected.preview.cells.find((cell) => cell.formula)?.address ?? selected.preview.cells[0]?.address ?? '—',
    )
    expect(screen.getByRole('button', { name: 'Herunterladen' }).hasAttribute('disabled')).toBe(true)
  })

  it('renders every panel and node change from the tracked 2026 Compare golden on a deep link', async () => {
    const id = 'de-est/case-mustermann.mantra'
    const path = '/fixtures/de-est-case-mustermann-552b3ca5/compare-2026.json'
    const compare = JSON.parse(readFileSync(resolve(golden, path.replace('/fixtures/', '')), 'utf8'))
    expect(compare.data.changes.length).toBeGreaterThan(0)
    const indexed = JSON.parse(JSON.stringify(manifest))
    indexed.parameters = [{ id: 'de.est/params-2026', path: '' }]
    indexed.cases.find((item) => item.id === id).files.compares = { '["de.est/params-2026"]': path }
    serveGolden({ [path]: compare }, indexed)
    history.replaceState(null, '', `${casePath(id)}/parameters?compare=de.est%2Fparams-2026`)
    render(<App />)

    expect((await screen.findAllByText('83.217,90 → 83.061,90')).length).toBeGreaterThan(0)
    expect(screen.getByLabelText('Vergleichen mit').value).toBe('de.est/params-2026')
    expect(document.querySelectorAll('.comparison-group').length).toBe(compare.data.changes.length)
    expect(document.querySelectorAll('.comparison-group .comparison-row').length).toBe(
      compare.data.changes.reduce((count, group) => count + group.items.length, 0),
    )
    for (const group of compare.data.changes) {
      for (const change of group.items) {
        expect(
          [...document.querySelectorAll('.comparison-group .comparison-row small')].some((label) =>
            label.textContent?.startsWith(change.node),
          ),
        ).toBe(true)
      }
    }
  })

  it('opens the IAS 12 weighted rate from its aggregate fixture without synthesizing formula steps', async () => {
    const id = 'ifrs-income-taxes/case-demo.mantra'
    const address = { node: 'aggregate.effective-tax-rate' }
    const entry = manifest.cases.find((item) => item.id === id)
    const explanation = files[entry.files.explains[addressToPath(address)]].data
    expect(explanation.aggregate.result).toEqual({ n: '0.249375' })
    expect(explanation.steps).toEqual([])
    serveGolden()
    history.replaceState(null, '', `${casePath(id)}/provenance/${encodeURIComponent(addressToPath(address))}`)
    render(<App />)
    const evidence = await screen.findByRole('region', { name: 'Gewichtete Quote' })
    expect(within(evidence).getByText(explanation.aggregate.display.numeratorTotal)).toBeTruthy()
    expect(within(evidence).getByText('Mitglieder · 2/2')).toBeTruthy()
    expect(document.querySelectorAll('.provenance-layout .calculation-step')).toHaveLength(0)
  })

  it('keeps the unreconciled IAS 12 calculation open and links its missing table fact to the input', async () => {
    const id = 'ifrs-income-taxes/case-unreconciled.mantra'
    const entry = manifest.cases.find((item) => item.id === id)
    const run = files[entry.files.run].data
    expect(run.succeeded).toBe(true)
    expect(run.validationPassed).toBe(false)
    const finding = run.diagnostics.find((item) => item.code === 'MANTRA-INPUT-REQUIRED')
    expect(finding.rowIndex).toBe(0)
    expect(finding.column).toBe('reason')
    serveGolden()
    history.replaceState(null, '', `${casePath(id)}/diagnostics`)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /MANTRA-INPUT-REQUIRED/ }))
    expect(screen.getByText('Zeile 1 · reason')).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: /Zum betroffenen Wert/ }))
    expect(new URLSearchParams(location.search).get('cell')).toBe('tax-adjustments#0.reason')
    const [input] = await screen.findAllByRole('textbox', { name: /^reason ·/ })
    expect(document.activeElement).toBe(input)
    expect(input.closest('td').textContent).toContain(finding.message)
  })
})
