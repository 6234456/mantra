// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PaperTable as Table } from '../types'
import { PaperTable, paperMatches } from './PaperTable'

const table: Table = {
  id: 'main',
  ref: '1',
  title: 'Main',
  columns: [
    { id: 'label', header: 'Description' },
    { id: 'amount', header: 'Amount' },
  ],
  rows: [
    {
      kind: 'VALUE',
      depth: 0,
      node: 'opening',
      cells: [{ text: 'Opening balance' }, { text: '1,200.00', address: { case: null, node: 'opening' } }],
    },
    {
      kind: 'VALUE',
      depth: 0,
      node: 'movement',
      cells: [{ text: 'Movement' }, { text: '0.00', address: { case: null, node: 'movement' } }],
    },
    {
      kind: 'TOTAL',
      depth: 0,
      node: 'closing',
      cells: [{ text: 'Closing balance' }, { text: '1,200.00', address: { case: null, node: 'closing' } }],
    },
  ],
}

afterEach(cleanup)

describe('renderer-owned paper browsing', () => {
  it('finds and navigates rows without changing their order, values or cell selection', () => {
    const select = vi.fn()
    const { container } = render(<PaperTable table={table} onIncludeZero={vi.fn()} onSelect={select} />)
    const originalRows = [...container.querySelectorAll('tbody tr')].map((row) => row.textContent)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: ' BALANCE ' } })
    expect(screen.getByRole('status').textContent).toBe('1 / 2')
    expect(container.querySelector('.paper-match-active')?.getAttribute('data-paper-row')).toBe('0')
    fireEvent.click(screen.getByRole('button', { name: 'Nächster Treffer' }))
    expect(container.querySelector('.paper-match-active')?.getAttribute('data-paper-row')).toBe('2')
    expect(screen.getByRole('status').textContent).toBe('2 / 2')
    fireEvent.click(screen.getByRole('button', { name: 'Nächster Treffer' }))
    expect(container.querySelector('.paper-match-active')?.getAttribute('data-paper-row')).toBe('0')
    expect([...container.querySelectorAll('tbody tr')].map((row) => row.textContent)).toEqual(originalRows)
    const cells = screen.getAllByRole('button', { name: 'Amount: 1,200.00' })
    fireEvent.click(cells[0])
    expect(select).toHaveBeenLastCalledWith({ case: null, node: 'opening' })
    fireEvent.keyDown(cells[0], { key: 'ArrowDown' })
    expect(select).toHaveBeenLastCalledWith({ case: null, node: 'movement' })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Amount: 0.00' }))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'absent' } })
    expect(screen.getByRole('status').textContent).toBe('Keine Treffer')
    expect((screen.getByRole('button', { name: 'Nächster Treffer' }) as HTMLButtonElement).disabled).toBe(true)
    expect(container.querySelectorAll('tbody tr')).toHaveLength(3)
  })

  it('requests server expansion and collapses only by requesting the bound layout policy', () => {
    const reveal = vi.fn()
    const { rerender } = render(
      <PaperTable
        table={table}
        browsing={{ includeZero: false, hideZero: true }}
        onIncludeZero={reveal}
        onSelect={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('checkbox', { name: 'Nullzeilen anzeigen' }))
    expect(reveal).toHaveBeenCalledWith(true)
    rerender(
      <PaperTable
        table={table}
        browsing={{ includeZero: true, hideZero: true }}
        onIncludeZero={reveal}
        onSelect={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('checkbox', { name: 'Nullzeilen anzeigen' }))
    expect(reveal).toHaveBeenLastCalledWith(false)
  })

  it('declares static-preview expansion unavailable and omits the toggle when the layout includes zeros', () => {
    const { rerender } = render(<PaperTable table={table} onIncludeZero={vi.fn()} onSelect={vi.fn()} />)
    expect((screen.getByRole('checkbox') as HTMLInputElement).disabled).toBe(true)
    expect(screen.getByText('Live-Server erforderlich')).toBeTruthy()
    rerender(
      <PaperTable
        table={table}
        browsing={{ includeZero: false, hideZero: false }}
        onIncludeZero={vi.fn()}
        onSelect={vi.fn()}
      />,
    )
    expect(screen.queryByRole('checkbox')).toBeNull()
  })

  it('searches displayed text as supplied without numeric conversion or duplicate matches', () => {
    expect(paperMatches(table, '1,200.00')).toEqual([0, 2])
    expect(paperMatches(table, '0.00')).toEqual([0, 1, 2])
    expect(paperMatches(table, ' ')).toEqual([])
  })
})
