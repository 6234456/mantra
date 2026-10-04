import { describe, expect, it } from 'vitest'
import { diagnosticPath, diagnosticsForInput } from './diagnostics'
import type { Diagnostic, Structure } from './types'

const finding = (overrides: Partial<Diagnostic> = {}): Diagnostic => ({
  caseRevision: null,
  category: 'business',
  severity: 'error',
  code: 'MANTRA-INPUT-REQUIRED',
  message: 'Required',
  location: null,
  address: { case: null, node: 'items', cell: { row: '0', column: 'amount' } },
  related: [],
  rowIndex: 0,
  column: 'amount',
  ...overrides,
})

describe('input diagnostic addresses', () => {
  it('matches the row position independently of its editable stable key, including row zero', () => {
    const diagnostic = finding()
    const address = { case: null, node: 'items', cell: { row: 'invoice-A', column: 'amount' } }
    expect(diagnosticsForInput([diagnostic], address, 0)).toEqual([diagnostic])
    expect(diagnosticsForInput([diagnostic], address, 1)).toEqual([])
    expect(diagnosticsForInput([diagnostic], { ...address, cell: { row: 'invoice-A', column: 'code' } }, 0)).toEqual([])
    expect(diagnosticsForInput([diagnostic], { case: null, node: 'items' })).toEqual([])
  })

  it('keeps member coordinates separate and accepts a stable-key address without row metadata', () => {
    const member = finding({
      address: { case: null, node: 'items', coord: ['A'], cell: { row: 'invoice-A', column: 'amount' } },
      rowIndex: null,
      column: null,
    })
    expect(
      diagnosticsForInput(
        [member],
        { case: null, node: 'items', coord: ['A'], cell: { row: 'invoice-A', column: 'amount' } },
        2,
      ),
    ).toEqual([member])
    expect(
      diagnosticsForInput(
        [member],
        { case: null, node: 'items', coord: ['B'], cell: { row: 'invoice-A', column: 'amount' } },
        2,
      ),
    ).toEqual([])
  })

  it('opens input findings in their editor and computed findings in their paper panel', () => {
    const structure: Structure = {
      schema: 'test',
      title: 'Test',
      mainline: [],
      params: [],
      generalInputs: ['items'],
      nodes: {},
      panels: [
        {
          id: 'local',
          role: 'branch',
          title: 'Local',
          dims: [],
          fields: ['amount'],
          nodes: ['total'],
          imports: [],
          exports: [],
          entries: [],
          breadcrumb: [],
        },
      ],
    }
    expect(diagnosticPath('folder/case.mantra', structure, finding())).toBe(
      '/cases/folder%2Fcase%2Emantra/inputs/general?cell=items%230.amount',
    )
    expect(diagnosticPath('case', structure, finding({ address: { case: null, node: 'amount' } }))).toBe(
      '/cases/case/inputs/local?cell=amount',
    )
    expect(diagnosticPath('case', structure, finding({ address: { case: null, node: 'total' } }))).toBe(
      '/cases/case/panels/local?cell=total',
    )
    expect(diagnosticPath('case', structure, finding({ address: null }))).toBeUndefined()
  })
})

it('does not attach source findings to a same-named local input and opens the source snapshot', () => {
  const source = finding({
    address: { case: 'source/case.mantra', node: 'items', cell: { row: '0', column: 'amount' } },
    caseRevision: 'source-revision',
  })
  expect(diagnosticsForInput([source], { case: null, node: 'items', cell: { row: '0', column: 'amount' } }, 0)).toEqual(
    [],
  )
  const emptyStructure: Structure = {
    schema: 'test',
    title: 'Test',
    mainline: [],
    panels: [],
    params: [],
    generalInputs: [],
  }
  const path = diagnosticPath('root/case.mantra', emptyStructure, source)
  expect(new URL(path!, 'http://localhost').searchParams.get('case')).toBe('source/case.mantra')
})
