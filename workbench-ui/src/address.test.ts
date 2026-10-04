import { describe, expect, it } from 'vitest'
import { addressFromPath, addressKey, addressToPath, casePath, provenancePath } from './address'

describe('contract addresses', () => {
  it('round trips a scalar and a dimensional value', () => {
    const scalar = { case: null, node: 'total' }
    const dimensional = { case: null, node: 'amount', coord: ['A/B', 'C@D'] }
    expect(addressFromPath(addressToPath(scalar))).toEqual(scalar)
    expect(addressFromPath(addressToPath(dimensional))).toEqual(dimensional)
  })

  it('round trips a table cell containing reserved punctuation', () => {
    const address = { case: null, node: 'records', cell: { row: 'a.b#c', column: 'rate@x' } }
    expect(addressFromPath(addressToPath(address))).toEqual(address)
  })

  it('encodes a relative case path as a single route component', () => {
    expect(casePath('project/case.mantra')).toBe('/cases/project%2Fcase%2Emantra')
  })
  it('compares scalar and member addresses independently of property order or an empty coordinate', () => {
    expect(addressKey({ case: null, node: 'total' })).toBe(addressKey({ case: null, coord: [], node: 'total' }))
    expect(addressKey({ case: null, node: 'amount', coord: ['A'] })).toBe(
      addressKey({ case: null, coord: ['A'], node: 'amount' }),
    )
    expect(addressKey({ case: null, node: 'aggregate.rate', coord: ['period=2025'] })).not.toBe(
      addressKey({ case: null, node: 'rate', coord: ['period=2025'] }),
    )
  })
  it('uses the same projected-node and reserved-punctuation keys as generated fixtures', () => {
    expect(addressToPath({ case: null, node: 'aggregate.rate' })).toBe('aggregate%2Erate')
    expect(addressToPath({ case: null, node: 'all.amount', coord: ['period=2025'] })).toBe('all%2Eamount@period%3D2025')
    expect(addressToPath({ case: null, node: 'amount', coord: ["member /.*'()", 'B/C', 'ä'] })).toBe(
      'amount@member%20%2F%2E%2A%27%28%29/B%2FC/%C3%A4',
    )
  })
})

it('separates same node addresses owned by different cases and encodes source context separately', () => {
  const local = { case: null, node: 'closing', coord: ['A'] }
  const source = { case: 'source/case one.mantra', node: 'closing', coord: ['A'] }
  expect(addressKey(local)).not.toBe(addressKey(source))
  expect(addressFromPath(addressToPath(source), source.case)).toEqual(source)
  const path = new URL(provenancePath('root/case.mantra', source, 'source-revision'), 'http://localhost')
  expect(path.pathname).toContain('/cases/root%2Fcase%2Emantra/provenance/')
  expect(path.searchParams.get('case')).toBe(source.case)
  expect(path.searchParams.get('expectedRevision')).toBe('source-revision')
})
