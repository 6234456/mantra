// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { SchemaPropertyOwner } from './schemaPropertiesClient'
import {
  SchemaPresentationOutline,
  schemaPresentationOutline,
  schemaPropertyIdentity,
} from './SchemaPresentationOutline'
import { textareaSourceRange } from './sourceNavigation'

afterEach(cleanup)

function owner(
  path: number[],
  node: string | null,
  property: 'label' | 'text' | 'classes' = 'label',
  document = 'schema.mantra',
): SchemaPropertyOwner {
  return {
    handle: `${path.join('-')}-${property}-${document}`,
    document,
    node,
    form: node === null ? 'note' : path.length === 1 ? 'section' : 'info',
    path,
    property,
    type: property === 'classes' ? 'classes' : 'text',
    declared: property !== 'classes',
    value: property === 'classes' ? null : node === null ? 'Nested note 中文😀' : `Label ${node}`,
    editable: document === 'schema.mantra',
    reason: document === 'schema.mantra' ? null : 'Captured include is read-only.',
    canUnset: false,
    range: property === 'classes' ? null : { line: 3, column: 1, startOffset: 20, endOffset: 25 },
    declarationRange: { line: 2, column: 1, startOffset: 10, endOffset: 30 },
  }
}
const owners = [
  owner([2, 10], 'zero'),
  owner([2], 'main', 'classes'),
  owner([2, 2], null, 'text'),
  owner([2], 'main'),
  owner([0], 'included', 'label', 'shared.mantra'),
  owner([2, 10], 'zero', 'classes'),
  owner([7, 0], 'no-visible-parent'),
]

it('groups each declaration once by document and uses numeric paths regardless of input ordering', () => {
  const tree = schemaPresentationOutline(owners)
  expect(tree.map((item) => item.document)).toEqual(['schema.mantra', 'shared.mantra'])
  expect(tree[0].declarations.map((item) => item.path)).toEqual([[2], [7, 0]])
  const main = tree[0].declarations[0]
  expect(main.owners.map((item) => item.property)).toEqual(['classes', 'label'])
  expect(main.children.map((item) => item.path)).toEqual([
    [2, 2],
    [2, 10],
  ])
  expect(main.children[1].owners.map((item) => item.property)).toEqual(['label', 'classes'])
  expect(owners[0].path).toEqual([2, 10])
})

it('finds raw declaration text while retaining ancestor context and never invents visibility reasons', () => {
  render(
    <SchemaPresentationOutline owners={owners} selection="" current busy={false} editing={false} onSelect={vi.fn()} />,
  )
  fireEvent.change(screen.getByLabelText('Find a declaration'), { target: { value: '中文😀' } })
  const nav = screen.getByRole('navigation', { name: 'Schema presentation outline' })
  expect(within(nav).getByText('section main')).toBeDefined()
  expect(within(nav).getByText('note')).toBeDefined()
  expect(within(nav).queryByText('info zero')).toBeNull()
  expect(within(nav).queryByRole('region', { name: 'Declarations in shared.mantra' })).toBeNull()
  expect(nav.textContent).not.toMatch(/Zero row hidden|Not active|Hidden \(:hidden\)/)
  fireEvent.change(screen.getByLabelText('Find a declaration'), { target: { value: 'not a declaration' } })
  expect(screen.getByRole('status').textContent).toBe('No declarations match this search.')
})

it('selects the exact owner, marks the current property and allows read-only declarations to be inspected', () => {
  const onSelect = vi.fn()
  render(
    <SchemaPresentationOutline
      owners={owners}
      selection={schemaPropertyIdentity(owners[0])}
      current
      busy={false}
      editing={false}
      onSelect={onSelect}
    />,
  )
  const pressed = screen.getAllByRole('button', { pressed: true })
  expect(pressed).toHaveLength(1)
  expect(pressed[0].textContent).toBe('label')
  const included = screen.getByRole('button', {
    name: 'Select label of section included in shared.mantra at line 2, path 0',
  })
  expect((included as HTMLButtonElement).disabled).toBe(false)
  fireEvent.click(included)
  expect(onSelect).toHaveBeenCalledWith(owners[4])
})

it('disables selection for stale catalogs, in-flight requests and pending fields while retaining search', () => {
  const p = { owners, selection: '', current: true, busy: false, editing: false, onSelect: vi.fn() }
  const view = render(<SchemaPresentationOutline {...p} current={false} />)
  for (const state of [{ current: false }, { busy: true }, { editing: true }]) {
    view.rerender(<SchemaPresentationOutline {...p} {...state} />)
    expect(screen.getAllByRole('button').every((button) => (button as HTMLButtonElement).disabled)).toBe(true)
    fireEvent.click(screen.getAllByRole('button')[0])
  }
  expect(p.onSelect).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Find a declaration'), { target: { value: 'zero' } })
  expect((screen.getByLabelText('Find a declaration') as HTMLInputElement).value).toBe('zero')
})

it('reports an empty directory without asserting that a Paper or schema has no content', () => {
  render(<SchemaPresentationOutline owners={[]} selection="" current busy={false} editing={false} onSelect={vi.fn()} />)
  expect(screen.getByText('No presentation declarations were returned.')).toBeDefined()
})

it('translates raw UTF-16 ranges through CRLF, bare CR and emoji without changing source', () => {
  for (const newline of ['\r\n', '\r', '\n']) {
    const raw = `(schema demo${newline}  ;; 😀${newline}  (note "中文😀"))`
    const token = '"中文😀"'
    const startOffset = raw.indexOf(token)
    const result = textareaSourceRange(raw, { startOffset, endOffset: startOffset + token.length })!
    expect(raw.replace(/\r\n?/g, '\n').slice(result.startOffset, result.endOffset)).toBe(token)
  }
  for (const range of [
    { startOffset: -1, endOffset: 2 },
    { startOffset: 0.5, endOffset: 1 },
    { startOffset: 1, endOffset: 1 },
    { startOffset: 0, endOffset: 20 },
  ]) {
    expect(textareaSourceRange('abc', range)).toBeUndefined()
  }
})
