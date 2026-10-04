import { describe, expect, it } from 'vitest'
import { addressFromPath, addressKey, addressToPath, casePath } from './address'

describe('contract addresses', () => {
  it('round trips a scalar and a dimensional value', () => {
    const scalar = { node: 'total' }
    const dimensional = { node: 'amount', coord: ['A/B', 'C@D'] }
    expect(addressFromPath(addressToPath(scalar))).toEqual(scalar)
    expect(addressFromPath(addressToPath(dimensional))).toEqual(dimensional)
  })

  it('round trips a table cell containing reserved punctuation', () => {
    const address = { node: 'records', cell: { row: 'a.b#c', column: 'rate@x' } }
    expect(addressFromPath(addressToPath(address))).toEqual(address)
  })

  it('encodes a relative case path as a single route component', () => {
    expect(casePath('project/case.mantra')).toBe('/cases/project%2Fcase%2Emantra')
  })
  it('compares scalar and member addresses independently of property order or an empty coordinate', () => {
    expect(addressKey({ node: 'total' })).toBe(addressKey({ coord: [], node: 'total' }))
    expect(addressKey({ node: 'amount', coord: ['A'] })).toBe(addressKey({ coord: ['A'], node: 'amount' }))
    expect(addressKey({ node: 'aggregate.rate', coord: ['period=2025'] })).not.toBe(
      addressKey({ node: 'rate', coord: ['period=2025'] }),
    )
  })
  it('uses the same projected-node and reserved-punctuation keys as generated fixtures', () => {
    expect(addressToPath({ node: 'aggregate.rate' })).toBe('aggregate%2Erate')
    expect(addressToPath({ node: 'all.amount', coord: ['period=2025'] })).toBe('all%2Eamount@period%3D2025')
    expect(addressToPath({ node: 'amount', coord: ["member /.*'()", 'B/C', 'ä'] })).toBe(
      'amount@member%20%2F%2E%2A%27%28%29/B%2FC/%C3%A4',
    )
  })
})
