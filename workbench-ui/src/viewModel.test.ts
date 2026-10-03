import { describe, expect, it } from 'vitest'
import type { Paper, Run } from './types'
import { auditForCell, nodeValue } from './viewModel'

const run: Run = {
  succeeded: true,
  members: { member: [{ key: 'A', label: 'A' }] },
  diagnostics: [],
  values: { value: { A: { value: { n: '10.00' }, display: '10.00', active: true } } },
}
const paper: Paper = {
  title: 'Paper',
  header: [],
  overview: [],
  auxiliary: [],
  legend: [],
  diagnostics: [],
  audit: [
    { anchor: 't1-r1-A', citation: '1', label: 'Value A', formula: 'A', working: 'from A', result: '10.00' },
    { anchor: 't1-r1-B', citation: '1', label: 'Value B', formula: 'B', working: 'from B', result: '20.00' },
  ],
  tables: [
    {
      id: 'panel',
      ref: '1',
      title: 'Panel',
      columns: [],
      rows: [
        {
          kind: 'VALUE',
          depth: 0,
          anchor: 't1-r1',
          cells: [
            { text: '10.00', address: { node: 'value', coord: ['A'] } },
            { text: '20.00', address: { node: 'value', coord: ['B'] } },
          ],
        },
      ],
    },
  ],
}

describe('read-only value selection', () => {
  it('never substitutes another member for a missing coordinate', () => {
    expect(nodeValue(run, 'value', 'A')).toBe('10.00')
    expect(nodeValue(run, 'value', 'B')).toBeUndefined()
    expect(nodeValue(run, 'value')).toBeUndefined()
  })
  it('selects the audit entry for the addressed member', () => {
    const rows = paper.tables[0].rows
    expect(auditForCell(paper, rows, { node: 'value', coord: ['B'] })?.formula).toBe('B')
    expect(auditForCell(paper, rows, { node: 'value', coord: ['C'] })).toBeUndefined()
  })
})
