// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { App } from './App'
import type { Envelope, Explain, Paper, Run, Structure } from '../types'

const caseId = 'sample/case.mantra'
const base = '/cases/sample%2Fcase.mantra'
const wrap = <T,>(data: T): Envelope<T> => ({ contract: 'mantra.workbench/1', revision: '1234567890abcdef', engine: { mantra: 'test', normein: 'test' }, data })

const structure: Structure = {
  schema: 'generic/example', title: 'Sample calculation', mainline: [{ step: 1, panel: 'main', title: 'First result', result: 'total' }],
  panels: [
    { id: 'main', title: 'First result', role: 'mainline', step: 1, dims: [], result: 'total', breadcrumb: [{ kind: 'mainline' }, { panel: 'main', label: 'First result' }], entries: [{ step: 1, panel: 'main', via: 'total', viaLabel: 'First result', path: ['main'] }], fields: [], nodes: ['total'], imports: [], exports: [] },
    { id: 'detail', title: 'Member detail', role: 'branch', dims: ['member'], result: 'total', breadcrumb: [{ kind: 'mainline' }, { panel: 'detail', label: 'Member detail' }], entries: [{ step: 1, panel: 'main', via: 'total', viaLabel: 'First result', path: ['detail', 'main'] }], fields: [], nodes: ['choice', 'value'], imports: [], exports: [] },
  ], generalInputs: [], params: [], nodes: { total: { label: 'First result', kind: 'total' }, value: { label: 'Member value', kind: 'line' }, choice: { label: 'Choice', kind: 'choice' } }, headline: 'total',
}
const run: Run = { succeeded: true, diagnostics: [], members: { member: [{ key: 'A', label: 'A' }, { key: 'B', label: 'B' }] }, values: {
  total: { '': { value: { n: '30.00' }, display: '30.00', active: true } },
  value: { A: { value: { n: '10.00' }, display: '10.00', active: true }, B: { value: { n: '20.00' }, display: '20.00', active: true } },
} }
const paper: Paper = { title: 'Paper', header: [], overview: [], auxiliary: [], legend: [], diagnostics: [], audit: [
  { anchor: 't1-r1-A', citation: '1', label: 'Member value A', formula: 'Formula A', working: 'Calculation A', result: '10.00' },
  { anchor: 't1-r1-B', citation: '1', label: 'Member value B', formula: 'Formula B', working: 'Calculation B', result: '20.00' },
], tables: [{ id: 'detail', ref: '1', title: 'Member detail', columns: [{ id: 'label', header: 'Label' }, { id: 'A', header: 'A' }, { id: 'B', header: 'B' }], rows: [
  { kind: 'VALUE', depth: 0, anchor: 't1-r1', cells: [{ text: 'Member value' }, { text: '10.00', address: { node: 'value', coord: ['A'] } }, { text: '20.00', address: { node: 'value', coord: ['B'] } }] },
  { kind: 'option', depth: 0, node: 'choice', optionKey: 'one', flags: ['selected'], cells: [{ text: 'Option one' }, { text: '18.00', address: { node: 'choice' } }, { text: 'Reference' }] },
  { kind: 'option', depth: 0, node: 'choice', optionKey: 'two', cells: [{ text: 'Option two' }, { text: '20.00', address: { node: 'choice' } }, { text: 'Reference' }] },
] }] }

function docs(extra: Record<string, unknown> = {}) {
  return {
    '/fixtures/index.json': { cases: [{ id: caseId, title: 'Sample case', files: { structure: '/fixtures/sample/structure.json', run: '/fixtures/sample/run.json', paper: '/fixtures/sample/paper.json', explains: Object.fromEntries(Array.from({ length: 7 }, (_, i) => [`n${i}`, `/fixtures/sample/n${i}.json`])) } }] },
    '/fixtures/sample/structure.json': wrap(structure), '/fixtures/sample/run.json': wrap(run), '/fixtures/sample/paper.json': wrap(paper), ...extra,
  }
}
function serve(documents: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (!(url in documents)) return { ok: false, status: 404, statusText: 'Not Found' }
    return { ok: true, json: async () => documents[url] }
  }))
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); if (typeof window.localStorage?.clear === 'function') window.localStorage.clear(); history.replaceState(null, '', '/') })

describe('fixture-backed workbench shell', () => {
  it('shows a loading state while fixture discovery is pending', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
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
    fireEvent.click(within(table).getByRole('button', { name: 'B: 20.00' }))
    expect(await screen.findByText('Formula B')).toBeTruthy()
    expect(screen.queryByText('Formula A')).toBeNull()
    expect(location.search).toContain('cell=value%40B')
    expect(screen.getByText(/Option one/, { selector: '.choice-option span' })).toBeTruthy()
    const chosen = screen.getByText(/Option one/, { selector: '.choice-option span' }).closest('.choice-option')!
    expect(chosen.classList.contains('chosen')).toBe(true)
    expect(within(chosen as HTMLElement).getByText('18.00')).toBeTruthy()
  })

  it('shows a fixture error state without invented values', async () => {
    serve({})
    history.replaceState(null, '', `${base}/overview`)
    render(<App />)
    expect(await screen.findByText(/WP3 muss die Vertragsdateien bereitstellen/)).toBeTruthy()
    expect(screen.queryByText('30.00')).toBeNull()
  })

  it('offers explicit continuation at the provenance depth limit', async () => {
    const explanations: Record<string, unknown> = {}
    for (let i = 0; i < 7; i++) {
      const explain: Explain = { address: { node: `n${i}` }, label: `Node ${i}`, kind: 'line', result: { value: { n: String(i) }, display: String(i) }, status: 'active', steps: [], branches: [], references: i < 6 ? [{ address: { node: `n${i + 1}` }, label: `Node ${i + 1}`, display: String(i + 1) }] : [], parts: [], options: [] }
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
})
