// @vitest-environment jsdom
import { useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AuthoringGrid, type AuthoringGridProps, type GridPosition } from './AuthoringGrid'
import type { SourceOwner } from './service'
import type { PaperTable } from '../types'

afterEach(cleanup)

const paper: PaperTable = {
  id: 'controls',
  ref: '1',
  title: 'Recorded controls',
  columns: [
    { id: 'description', header: 'Description' },
    { id: 'amount', header: 'Amount' },
  ],
  rows: [
    { kind: 'HEADING', depth: 0, cells: [{ text: 'Controls' }, { text: '' }] },
    {
      kind: 'VALUE',
      depth: 1,
      flags: ['inactive'],
      node: 'unallocated',
      cells: [
        { text: 'Request not allocated' },
        {
          text: '(60.00)',
          address: { case: null, node: 'unallocated', coord: [] },
          style: { tone: 'accent', fill: 'subtle', weight: 'bold' },
          styleOverrides: { tone: 'accent', fill: 'subtle', weight: 'bold' },
        },
      ],
    },
    { kind: 'CHECK', depth: 0, node: 'conserved', cells: [{ text: 'Conserved' }, { text: '✗' }] },
  ],
}

function owner(property: SourceOwner['property'], editable = true): SourceOwner {
  return {
    handle: `opaque-${property}`,
    document: 'schema.mantra',
    declaration: 'unallocated',
    declarationRange: { start: 0, end: 80 },
    property,
    kind: 'info',
    nodeId: 'unallocated',
    range: { start: 20, end: 50 },
    editable,
    reason: editable ? undefined : 'This condition is defined by its schema.',
    value: property === 'formula' ? '(- request allocated-total)' : 'Request not allocated',
    channel: 'Template definition',
  }
}

const labelOwner = owner('label')
const formulaOwner = owner('formula')
const readonlyOwner = owner('condition', false)

function mount(overrides: Partial<AuthoringGridProps> = {}) {
  const onSelect = vi.fn()
  const onEdit = vi.fn()
  const props: AuthoringGridProps = {
    table: paper,
    onSelect,
    onEdit,
    ownerFor: (row, column) => (row === 1 ? (column === 0 ? labelOwner : formulaOwner) : readonlyOwner),
    ...overrides,
  }
  function Harness() {
    const [selected, setSelected] = useState<GridPosition>(overrides.selected ?? { row: 0, column: 0 })
    return (
      <AuthoringGrid
        {...props}
        selected={selected}
        onSelect={(position) => {
          onSelect(position)
          setSelected(position)
        }}
      />
    )
  }
  return { ...render(<Harness />), onSelect, onEdit, props }
}

function cell(row: number, column: number) {
  const element = document.querySelector<HTMLTableCellElement>(`td[data-row="${row}"][data-column="${column}"]`)
  if (!element) throw new Error('Grid cell was not rendered')
  return element
}

function display(row: number, column: number) {
  return cell(row, column).querySelector('[data-authoring-value]')?.textContent
}

it('keeps every renderer cell, with one tab stop, exact values, row flags and resolved styles', () => {
  mount()
  expect(screen.getByRole('grid', { name: 'Recorded controls' })).toBeTruthy()
  expect(screen.getAllByRole('gridcell')).toHaveLength(6)
  expect(screen.getAllByRole('gridcell').filter((item) => item.tabIndex === 0)).toEqual([cell(0, 0)])
  expect(display(1, 1)).toBe('(60.00)')
  expect(cell(1, 1).style.fontWeight).toBe('600')
  expect(cell(1, 1).style.color).toBe('var(--blue-strong)')
  expect(cell(1, 1).style.backgroundColor).toBe('var(--rail)')
  expect(cell(1, 0).closest('tr')?.classList.contains('flag-inactive')).toBe(true)
  expect(cell(1, 0).getAttribute('aria-readonly')).toBe('false')
  expect(cell(0, 0).getAttribute('aria-readonly')).toBe('true')
})

it('navigates to labels and values with arrows, row edges and table edges', () => {
  const { onSelect } = mount()
  fireEvent.keyDown(cell(0, 0), { key: 'ArrowDown' })
  expect(document.activeElement).toBe(cell(1, 0))
  fireEvent.keyDown(cell(1, 0), { key: 'ArrowRight' })
  expect(document.activeElement).toBe(cell(1, 1))
  fireEvent.keyDown(cell(1, 1), { key: 'Home' })
  expect(document.activeElement).toBe(cell(1, 0))
  fireEvent.keyDown(cell(1, 0), { key: 'End' })
  expect(document.activeElement).toBe(cell(1, 1))
  fireEvent.keyDown(cell(1, 1), { key: 'End', ctrlKey: true })
  expect(document.activeElement).toBe(cell(2, 1))
  fireEvent.keyDown(cell(2, 1), { key: 'Home', metaKey: true })
  expect(document.activeElement).toBe(cell(0, 0))
  expect(onSelect).toHaveBeenLastCalledWith({ row: 0, column: 0 })
})

it.each(['Enter', 'F2'])('edits the semantic owner on %s and begins printable input with its replacement', (key) => {
  const { onEdit } = mount({ selected: { row: 1, column: 0 } })
  fireEvent.keyDown(cell(1, 0), { key })
  expect(onEdit).toHaveBeenLastCalledWith(labelOwner, undefined)
  fireEvent.keyDown(cell(1, 1), { key: 'a' })
  expect(onEdit).toHaveBeenLastCalledWith(formulaOwner, 'a')
})

it('announces a concrete read-only reason and never clears definitions', () => {
  const { onEdit } = mount()
  fireEvent.keyDown(cell(0, 0), { key: 'Enter' })
  expect(screen.getByRole('status').textContent).toBe('This condition is defined by its schema.')
  fireEvent.keyDown(cell(1, 0), { key: 'Delete' })
  expect(screen.getByRole('status').textContent).toContain('Labels and formulas cannot be cleared')
  expect(onEdit).not.toHaveBeenCalled()
  expect(display(1, 0)).toBe('Request not allocated')
})

it('lets browsing Tab leave the grid and ignores IME Enter, printable keys and arrows', () => {
  const { onSelect, onEdit } = mount({ selected: { row: 1, column: 0 } })
  expect(fireEvent.keyDown(cell(1, 0), { key: 'Tab' })).toBe(true)
  expect(fireEvent.keyDown(cell(1, 0), { key: 'Tab', shiftKey: true })).toBe(true)
  fireEvent.keyDown(cell(1, 0), { key: 'Enter', isComposing: true })
  fireEvent.keyDown(cell(1, 0), { key: 'ArrowDown', keyCode: 229 })
  fireEvent.keyDown(cell(1, 0), { key: 'a', keyCode: 229 })
  expect(onEdit).not.toHaveBeenCalled()
  expect(onSelect).not.toHaveBeenCalled()
})

it('extends a rectangular range and makes Escape collapse it before invoking an outer dismissal', () => {
  const onEscape = vi.fn()
  mount({ selected: { row: 1, column: 0 }, onEscape })
  fireEvent.keyDown(cell(1, 0), { key: 'ArrowRight', shiftKey: true })
  fireEvent.keyDown(cell(1, 1), { key: 'ArrowDown', shiftKey: true })
  expect(screen.getAllByRole('gridcell').filter((item) => item.getAttribute('aria-selected') === 'true')).toHaveLength(
    4,
  )
  fireEvent.keyDown(cell(2, 1), { key: 'Escape' })
  expect(onEscape).not.toHaveBeenCalled()
  expect(screen.getAllByRole('gridcell').filter((item) => item.getAttribute('aria-selected') === 'true')).toEqual([
    cell(2, 1),
  ])
  fireEvent.keyDown(cell(2, 1), { key: 'Escape' })
  expect(onEscape).toHaveBeenCalledOnce()
})

it('copies the renderer display as TSV and escaped HTML without exposing source handles', () => {
  const onCopy = vi.fn()
  const clipboardData = { setData: vi.fn() }
  mount({ selected: { row: 1, column: 0 }, onCopy })
  fireEvent.keyDown(cell(1, 0), { key: 'ArrowRight', shiftKey: true })
  fireEvent.copy(cell(1, 1), { clipboardData })
  expect(clipboardData.setData).toHaveBeenCalledWith('text/plain', 'Request not allocated\t(60.00)')
  expect(clipboardData.setData).toHaveBeenCalledWith(
    'text/html',
    '<table><tr><td>Request not allocated</td><td>(60.00)</td></tr></table>',
  )
  expect(JSON.stringify(clipboardData.setData.mock.calls)).not.toContain('opaque-')
  expect(onCopy).toHaveBeenCalledWith([
    { row: 1, column: 0 },
    { row: 1, column: 1 },
  ])
  fireEvent.cut(cell(1, 1), { clipboardData })
  expect(screen.getByRole('status').textContent).toContain('Cut is not available')
})

it('delegates single-property paste and rejects a multi-cell paste without editing', () => {
  const onPaste = vi.fn()
  const clipboardData = { getData: vi.fn(() => 'Changed label') }
  mount({ selected: { row: 1, column: 0 }, onPaste })
  fireEvent.paste(cell(1, 0), { clipboardData })
  expect(onPaste).toHaveBeenCalledWith('Changed label', labelOwner)
  onPaste.mockClear()
  fireEvent.keyDown(cell(1, 0), { key: 'ArrowRight', shiftKey: true })
  fireEvent.paste(cell(1, 1), { clipboardData })
  expect(onPaste).not.toHaveBeenCalled()
  expect(screen.getByRole('status').textContent).toContain('Paste into one property')
})

it('marks an invalid definition by owner while preserving its last valid displayed number', () => {
  mount({ invalidHandles: new Set([formulaOwner.handle]) })
  expect(cell(1, 1).getAttribute('aria-invalid')).toBe('true')
  expect(cell(1, 0).getAttribute('aria-invalid')).toBeNull()
  expect(cell(1, 1).textContent).toContain('(60.00)')
  expect(
    screen.getByLabelText('Definition has an error; displayed values come from the last valid preview.'),
  ).toBeTruthy()
})

it('displays passed DSL formulas without computing values and delegates the formula shortcut', () => {
  const onToggleFormulas = vi.fn()
  mount({
    showFormulas: true,
    formulaFor: (row, column) => (row === 1 && column === 1 ? '(+ value 1)' : undefined),
    onToggleFormulas,
  })
  expect(display(1, 1)).toBe('(+ value 1)')
  expect(display(1, 0)).toBe('Request not allocated')
  fireEvent.keyDown(cell(1, 1), { key: '`', ctrlKey: true })
  expect(onToggleFormulas).toHaveBeenCalledOnce()
})

it('distinguishes editable and read-only definition marks without changing renderer display text', () => {
  mount()
  const editable = cell(1, 1).querySelector('.author-definition-mark')
  expect(editable?.getAttribute('aria-label')).toBe('Template definition; press Enter to edit.')
  expect(editable?.textContent).toBe('ƒ')
  expect(display(1, 1)).toBe('(60.00)')
  const readonly = cell(2, 1).querySelector('.author-definition-mark')
  expect(readonly?.getAttribute('aria-label')).toBe('Read-only definition: This condition is defined by its schema.')
  expect(readonly?.textContent).toBe('🔒')
  expect(display(2, 1)).toBe('✗')
})
