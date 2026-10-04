// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { AggregateEvidence } from './AggregateEvidence'
import type { Aggregate } from '../types'

afterEach(cleanup)
const boundary: Aggregate = {
  kind: 'boundary',
  dimension: 'year',
  boundary: 'last',
  periodKeys: ['P1', 'P2'],
  dimensions: ['asset', 'year'],
  fixed: { asset: 'A' },
  memberCount: 2,
  activeMemberCount: 1,
  selectionCount: 1,
  result: { n: '9007199254740993.001' },
  undefinedReason: null,
  truncated: false,
  display: { result: '9.007.199.254.740.993,001' },
  members: [
    { coord: ['A', 'P1'], value: { n: '10' }, active: true, selected: false },
    { coord: ['A', 'P2'], value: { n: '9007199254740993.001' }, active: true, selected: true },
  ],
  selected: [{ coord: ['A', 'P2'], value: { n: '9007199254740993.001' }, active: true, selected: true }],
}

it('shows the engine-selected boundary and exact decimal strings, including unselected periods', () => {
  render(<AggregateEvidence aggregate={boundary} />)
  expect(screen.getByRole('region', { name: 'Bestandsaggregation' })).toBeTruthy()
  expect(screen.getByText(/Letzte Periode/)).toBeTruthy()
  expect(screen.getByText('9.007.199.254.740.993,001')).toBeTruthy()
  expect(screen.getByText('asset=A')).toBeTruthy()
  const selected = screen.getByRole('row', { name: /year=P2/ })
  expect(within(selected).getByText('9007199254740993.001')).toBeTruthy()
  expect(within(selected).getByText('✓')).toBeTruthy()
  expect(within(screen.getByRole('row', { name: /year=P1/ })).getByText('—')).toBeTruthy()
})

it('keeps the engine sum and visible truncation without calculating from the bounded members', () => {
  render(
    <AggregateEvidence
      aggregate={{
        kind: 'sum',
        dimensions: ['asset'],
        fixed: {},
        memberCount: 1000,
        activeMemberCount: 1000,
        members: [{ coord: ['A'], value: { n: '1' }, active: true, selected: true }],
        result: { n: '1000' },
        display: { result: '1.000,00' },
        truncated: true,
        undefinedReason: null,
      }}
    />,
  )
  expect(screen.getByRole('region', { name: 'Summenaggregation' })).toBeTruthy()
  expect(screen.getByText('1.000,00')).toBeTruthy()
  expect(screen.getByText(/1000\/1000/)).toBeTruthy()
  expect(screen.getByText(/begrenzt/)).toBeTruthy()
})

it('retains selected boundary evidence when the bounded scope details stop before the last period', () => {
  render(<AggregateEvidence aggregate={{ ...boundary, members: boundary.members.slice(0, 1), truncated: true }} />)
  expect(screen.getByRole('row', { name: /year=P2/ })).toBeTruthy()
  expect(screen.getByText('9007199254740993.001')).toBeTruthy()
  expect(screen.getByText(/begrenzt/)).toBeTruthy()
})
