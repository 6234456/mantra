// @vitest-environment jsdom
import { cleanup, fireEvent, render } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { Cell, PaperTable as Table } from '../types'
import { PaperTable } from './PaperTable'

const address = { case: null, node: 'result', coord: [] }
const legacyDefaults = { weight: 'normal', tone: 'default', fill: 'none' }
const stylesheet = document.createElement('style')

// Vitest disables CSS imports in this project. Read the real styles without adding Node typings
// or a new test configuration that would alter unrelated suites.
const runtime = globalThis as unknown as {
  process: { getBuiltinModule: (name: 'fs') => { readFileSync: (path: string, encoding: 'utf8') => string } }
}
const fs = runtime.process.getBuiltinModule('fs')

beforeAll(() => {
  // Exercise the actual built-in total, heading, input and button rules beneath overrides.
  stylesheet.textContent =
    fs.readFileSync('src/style.css', 'utf8') + '\n' + fs.readFileSync('src/ui/PaperTable.css', 'utf8')
  document.head.append(stylesheet)
})
afterEach(cleanup)
afterAll(() => stylesheet.remove())

function table(kind: string, cell: Cell): Table {
  return {
    id: 'main',
    ref: '1',
    title: 'Main',
    columns: [
      { id: 'label', header: 'Description' },
      { id: 'amount', header: 'Amount' },
    ],
    rows: [{ kind, depth: 1, flags: ['inactive'], cells: [{ text: 'Result' }, cell] }],
  }
}

function renderCell(kind: string, cell: Cell, select = vi.fn()) {
  const result = render(<PaperTable table={table(kind, cell)} onIncludeZero={vi.fn()} onSelect={select} />)
  const td = result.container.querySelector('tbody tr td:last-child') as HTMLTableCellElement
  return { ...result, td }
}

describe('renderer-owned paper style overrides', () => {
  it.each(['TOTAL', 'HEADING'])('applies explicit resets over built-in %s styling and input highlights', (kind) => {
    const select = vi.fn()
    const { td } = renderCell(
      kind,
      {
        text: '13.2500',
        address,
        editable: true,
        style: legacyDefaults,
        styleOverrides: { weight: 'normal', tone: 'default', fill: 'none' },
      },
      select,
    )
    expect(td.style.fontWeight).toBe('400')
    expect(td.style.color).toBe('var(--ink)')
    expect(td.style.backgroundColor).toBe('transparent')
    const button = td.querySelector('button')!
    expect(getComputedStyle(button).fontWeight).toBe('inherit')
    expect(getComputedStyle(button).color).toBe('inherit')
    expect(button.textContent).toBe('13.2500')
    fireEvent.click(button)
    expect(select).toHaveBeenCalledWith(address)
  })

  it('maps subtle fill to the neutral token while leaving unrelated input properties alone', () => {
    const { td, rerender } = renderCell('VALUE', {
      text: '2.0000',
      editable: true,
      style: { ...legacyDefaults, fill: 'subtle' },
      styleOverrides: { fill: 'subtle' },
    })
    expect(td.style.backgroundColor).toBe('var(--rail)')
    expect(td.style.fontWeight).toBe('')
    expect(td.style.color).toBe('')
    rerender(
      <PaperTable
        table={table('VALUE', { text: '2.0000', style: { ...legacyDefaults, fill: 'subtle' } })}
        onIncludeZero={vi.fn()}
        onSelect={vi.fn()}
      />,
    )
    expect(td.style.backgroundColor).toBe('')
    expect(getComputedStyle(td).background).toBe('var(--rail)')
  })

  it('applies accent and bold independently of row type while preserving displayed values', () => {
    const { td } = renderCell('VALUE', {
      text: '13.2500',
      address,
      style: { weight: 'bold', tone: 'accent', fill: 'accent' },
      styleOverrides: { weight: 'bold', tone: 'accent', fill: 'accent' },
    })
    expect(td.style.fontWeight).toBe('600')
    expect(td.style.color).toBe('var(--blue-strong)')
    expect(td.style.backgroundColor).toBe('var(--blue-tint)')
    expect(td.textContent).toBe('13.2500')
  })

  it('keeps legacy default objects from resetting heading and editable input presentation', () => {
    const { td, rerender } = renderCell('HEADING', { text: '13.2500', style: legacyDefaults })
    expect(td.style.fontWeight).toBe('')
    expect(td.style.color).toBe('')
    expect(td.style.backgroundColor).toBe('')
    expect(getComputedStyle(td).fontWeight).toBe('600')
    expect(getComputedStyle(td).background).toBe('var(--rail)')
    rerender(
      <PaperTable
        table={table('VALUE', { text: '13.2500', editable: true, style: legacyDefaults })}
        onIncludeZero={vi.fn()}
        onSelect={vi.fn()}
      />,
    )
    expect(td.style.backgroundColor).toBe('')
    expect(getComputedStyle(td).background).toBe('var(--blue-field)')
  })

  it('retains legacy positive styling when sparse metadata is unavailable', () => {
    const { td } = renderCell('VALUE', {
      text: '13.2500',
      style: { weight: 'bold', tone: 'accent', fill: 'accent' },
    })
    expect(td.style.fontWeight).toBe('600')
    expect(getComputedStyle(td).color).toBe('var(--blue-strong)')
    expect(getComputedStyle(td).background).toBe('var(--blue-tint)')
    expect(td.textContent).toBe('13.2500')
  })
})
