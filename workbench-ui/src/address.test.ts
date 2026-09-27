import { describe, expect, it } from 'vitest'
import { addressFromPath, addressToPath, casePath } from './address'

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
})
