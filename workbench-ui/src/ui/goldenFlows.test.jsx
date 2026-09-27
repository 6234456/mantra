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
  for (const path of Object.values(item.files)) {
    files[path] = JSON.parse(readFileSync(resolve(golden, path.replace('/fixtures/', '')), 'utf8'))
  }
}

const scenarios = [
  { id: 'de-est-2025/case-mustermann.mantra', heading: 'Einkommensteuer 2025', result: '1.462,24', panel: 'zve', cell: '112.380,00', address: { node: 'summe-einkuenfte' } },
  { id: 'ifrs-ias36-corporate-assets/case-ie8.mantra', heading: 'IAS 36 – Impairment test with corporate assets', result: '46', panel: 'step-1', cell: '100', address: { node: 'carrying-amount', coord: ['A'] } },
  { id: 'sap-co-product-cost/case-demo.mantra', heading: 'Product cost by manufacturing order', result: '1,140.00', panel: 'cost-sources', cell: '12,400.00', address: { node: 'direct-primary-total' } },
]

function serveGolden() {
  vi.stubGlobal('fetch', vi.fn(async input => {
    const document = files[input]
    if (!document) return { ok: false, status: 404, statusText: 'Not Found' }
    return { ok: true, json: async () => document }
  }))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  if (typeof window.localStorage?.clear === 'function') window.localStorage.clear()
  history.replaceState(null, '', '/')
})

describe('the three tracked golden cases, from overview to a selected Paper cell', () => {
  it.each(scenarios)('$id', async scenario => {
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
    const cell = within(table).getAllByRole('button').find(button => button.textContent === scenario.cell)
    expect(cell).toBeTruthy()
    fireEvent.click(cell)
    expect(location.pathname).toBe(panelUrl)
    expect(new URLSearchParams(location.search).get('cell')).toBe(addressToPath(scenario.address))
    expect(cell?.classList.contains('selected')).toBe(true)
  })

  it.each(scenarios)('$id export uses workbook sheets and fidelity reported by Excel', async scenario => {
    serveGolden()
    history.replaceState(null, '', `${casePath(scenario.id)}/export`)
    render(<App />)
    const entry = manifest.cases.find(item => item.id === scenario.id)
    const first = files[entry.files['export-preview']].data
    expect(await screen.findByRole('heading', { name: `${first.sheets.length} Blätter` })).toBeTruthy()
    expect(screen.getByText(first.report.formulaCells.toString(), { selector: '.export-metrics strong' })).toBeTruthy()
    expect(screen.getByText(first.report.names.toString(), { selector: '.export-metrics strong' })).toBeTruthy()
    const second = first.sheets[1]
    fireEvent.click(screen.getByRole('button', { name: new RegExp(second.name) }))
    expect(await screen.findByRole('heading', { name: second.name, level: 2 })).toBeTruthy()
    const selected = files[entry.files[`export-preview:${second.name}`]].data
    expect(document.querySelector('.export-formula .mono')?.textContent).toBe(selected.preview.cells.find(cell => cell.formula)?.address ?? selected.preview.cells[0]?.address ?? '—')
    expect(screen.getByRole('button', { name: 'Herunterladen' }).hasAttribute('disabled')).toBe(true)
  })
})
