import { describe, expect, it } from 'vitest'
import type { Paper, Run } from './types'
import { auditForCell, nodeValue, panelValue } from './viewModel'

const run: Run = {
  caseGraph: null,
  usage: null,
  failure: null,
  succeeded: true,
  validationPassed: true,
  members: { member: [{ key: 'A', label: 'A' }] },
  diagnostics: [],
  values: { value: { A: { link: null, value: { n: '10.00' }, display: '10.00', active: true } } },
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
            { text: '10.00', address: { case: null, node: 'value', coord: ['A'] } },
            { text: '20.00', address: { case: null, node: 'value', coord: ['B'] } },
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
    expect(auditForCell(paper, rows, { case: null, node: 'value', coord: ['B'] })?.formula).toBe('B')
    expect(auditForCell(paper, rows, { case: null, node: 'value', coord: ['C'] })).toBeUndefined()
  })
  it('selects a weighted aggregate by its explicit address rather than a member row anchor', () => {
    const aggregateAddress = { case: null, node: 'aggregate.value', coord: ['period=2025'] }
    const aggregateEntry = {
      ...paper.audit[0],
      anchor: 't1-r1-sum',
      address: aggregateAddress,
      formula: 'Weighted total',
    }
    const aggregatePaper = { ...paper, audit: [...paper.audit, aggregateEntry] }
    expect(auditForCell(aggregatePaper, paper.tables[0].rows, aggregateAddress)).toBe(aggregateEntry)
    expect(auditForCell(aggregatePaper, undefined, aggregateAddress)).toBe(aggregateEntry)
    expect(
      auditForCell(aggregatePaper, paper.tables[0].rows, {
        case: null,
        node: 'aggregate.value',
        coord: ['period=2024'],
      }),
    ).toBeUndefined()
  })
  it('reads an explicitly addressed whole aggregate from the engine display and never substitutes it for a member or fixed slice', () => {
    const withAggregate: Run = {
      ...run,
      aggregates: {
        rate: {
          kind: 'ratio',
          numeratorId: 'tax',
          denominatorId: 'profit',
          dimensions: ['entity'],
          fixed: {},
          members: [],
          memberCount: 2,
          activeMemberCount: 2,
          numeratorTotal: { n: '79.8' },
          denominatorTotal: { n: '320' },
          rounding: null,
          result: { n: '0.249375' },
          undefinedReason: null,
          truncated: true,
          display: { numeratorTotal: '79,80', denominatorTotal: '320,00', result: '24,9375 %' },
        },
      },
    }
    expect(nodeValue(withAggregate, 'aggregate.rate')).toBe('24,9375 %')
    expect(panelValue(withAggregate, 'rate')).toBe('24,9375 %')
    expect(nodeValue(withAggregate, 'rate', 'missing')).toBeUndefined()
    expect(nodeValue(withAggregate, 'aggregate.rate', 'entity=A')).toBeUndefined()
    expect(nodeValue(withAggregate, 'rate', 'A')).toBeUndefined()
    expect(nodeValue(withAggregate, 'aggregate.missing')).toBeUndefined()
  })
})
