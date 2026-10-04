// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { ExplainDetails } from './ExplainDetails'
import { RatioAggregateEvidence } from './RatioAggregateEvidence'
import type { Explain, RatioAggregate } from '../types'

const aggregate: RatioAggregate = {
  numeratorId: 'tax-expense',
  denominatorId: 'profit',
  dimensions: ['entity'],
  fixed: {},
  members: [
    { coord: ['A'], numerator: { n: '65400' }, denominator: { n: '240000' }, active: true },
    { coord: ['B'], numerator: { n: '14400' }, denominator: { n: '80000' }, active: true },
    { coord: ['C'], numerator: { n: '9007199254740993.001' }, denominator: { n: '1' }, active: false },
  ],
  memberCount: 3,
  activeMemberCount: 2,
  numeratorTotal: { n: '79800' },
  denominatorTotal: { n: '320000' },
  rounding: { scale: 6, mode: 'half-up' },
  result: { n: '0.249375' },
  undefinedReason: null,
  truncated: false,
  display: { numeratorTotal: '79.800,00', denominatorTotal: '320.000,00', result: '24,9375 %' },
}
afterEach(cleanup)

it('shows the engine totals, display formatting and active mask without reducing decimal precision', () => {
  render(<RatioAggregateEvidence aggregate={aggregate} />)
  expect(screen.getByText('79.800,00')).toBeTruthy()
  expect(screen.getByText('24,9375 %')).toBeTruthy()
  expect(screen.getByText('Rundung: 6 · half-up')).toBeTruthy()
  expect(screen.getByText('Mitglieder · 2/3')).toBeTruthy()
  const member = screen.getByRole('row', { name: /entity=C/ })
  expect(within(member).getByText('9007199254740993.001')).toBeTruthy()
  expect(within(member).getByLabelText('Ausgeschlossen')).toBeTruthy()
})

it('keeps an undefined ratio visible with its reason, fixed context and bounded member notice', () => {
  render(
    <RatioAggregateEvidence
      aggregate={{
        ...aggregate,
        fixed: { period: '2025' },
        denominatorTotal: { n: '0' },
        result: null,
        rounding: null,
        undefinedReason: 'zero-denominator',
        truncated: true,
        display: { ...aggregate.display, denominatorTotal: '0,00', result: '—' },
      }}
    />,
  )
  expect(screen.getByText(/Quote ist nicht definiert/)).toBeTruthy()
  expect(screen.getByText('period=2025')).toBeTruthy()
  expect(screen.getByText('Rundung: Exakte Division')).toBeTruthy()
  expect(screen.getByText(/begrenzt/)).toBeTruthy()
  expect(document.querySelector('.ratio-totals > div:last-child dd')?.textContent).toBe('—')
})

it('displays aggregate evidence from a Total part separately from the captured kernel steps', () => {
  const explain: Explain = {
    address: { node: 'group-result' },
    label: 'Group result',
    kind: 'total',
    result: { value: { n: '0.249375' }, display: '24,9375 %' },
    status: 'active',
    aggregate: null,
    steps: [{ text: '(+ alpha beta)', display: '2' }],
    branches: [{ text: 'condition', selected: true }],
    references: [],
    options: [],
    parts: [
      {
        address: { node: 'aggregate.effective-rate' },
        label: 'Effective rate',
        sign: 1,
        value: { n: '0.249375' },
        display: '24,9375 %',
        crossFooted: true,
        aggregate,
      },
    ],
  }
  render(<ExplainDetails explain={explain} />)
  expect(screen.getByText('(+ alpha beta)')).toBeTruthy()
  expect(screen.getByText('condition')).toBeTruthy()
  expect(screen.getByText('+ Effective rate')).toBeTruthy()
  expect(screen.getByRole('region', { name: 'Gewichtete Quote' })).toBeTruthy()
  expect(document.querySelectorAll('.calculation-step')).toHaveLength(2)
})
