// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import type { Compare, Value } from '../types'
import type { TemplatePreview } from './client'
import { firstCase, templatePreview, templateRequest } from './fixtures.test.helpers'
import { CandidateComparison } from './CandidateComparison'

afterEach(cleanup)

function preview(): TemplatePreview {
  return structuredClone(templatePreview(firstCase, templateRequest()).data)
}

function change(node: string, base: Value, variant: Value): Compare['changes'][number]['items'][number] {
  return {
    node,
    coord: [],
    base,
    variant,
    delta: null,
    basePresent: true,
    variantPresent: true,
    display: { base: null, variant: null, delta: null },
  }
}

function noChanges(): Compare {
  return { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] }
}

it('requires real comparison evidence before reporting an empty change set', () => {
  const { rerender } = render(<CandidateComparison current={false} />)
  expect(screen.getByText('Preview a draft to compare its values with the saved template.')).toBeTruthy()
  expect(screen.getByText('Saved template preview is not available yet.')).toBeTruthy()
  expect(screen.queryByText('No value changes reported for this draft.')).toBeNull()
  expect(screen.queryByRole('table')).toBeNull()
  const candidate = preview()
  candidate.difference = noChanges()
  rerender(<CandidateComparison candidate={candidate} current />)
  expect(screen.getByText('No value changes reported for this draft.')).toBeTruthy()
  expect(screen.getByText('Current draft comparison')).toBeTruthy()
  expect(screen.queryByRole('table')).toBeNull()
})

it('renders every engine comparison bucket without rounding exact decimal strings or dropping zero and false', () => {
  const candidate = preview()
  candidate.difference = {
    variant: { parameters: [] },
    mainline: [
      {
        ...change('precise-result', { n: '9007199254740993.000000000001' }, { n: '9007199254740993.000000000002' }),
        step: 2,
        panel: 'main',
        delta: { n: '0.000000000001' },
      },
    ],
    changes: [
      {
        step: null,
        panel: null,
        items: [change('zero-input', { n: '0.0000' }, { n: '0.00' }), change('business-status', false, true)],
      },
    ],
    parameterChanges: [
      {
        ...change('rate', { n: '0.19' }, { n: '0.20' }),
        baseSource: 'parameters/base.mantra',
        variantSource: 'case/override.mantra',
        display: { base: '19 %', variant: '20 %', delta: '1 pp' },
      },
    ],
  }
  render(<CandidateComparison candidate={candidate} current />)
  const comparison = within(screen.getByRole('table', { name: 'Changes from saved template' }))
  for (const text of [
    'Mainline results',
    'Other values and inputs',
    'Parameters',
    '9007199254740993.000000000001',
    '9007199254740993.000000000002',
    '0.000000000001',
    '0.0000',
    '0.00',
    'false',
    'true',
    'parameters/base.mantra → case/override.mantra',
    '19 %',
    '20 %',
    '1 pp',
    'Compared',
  ]) {
    expect(comparison.getByText(text)).toBeTruthy()
  }
})

it('labels saved runtime failures and business-check status without hiding the returned Paper', () => {
  const baseline = preview()
  baseline.succeeded = false
  baseline.validationPassed = false
  render(<CandidateComparison baseline={baseline} current={false} />)
  const saved = within(screen.getByRole('region', { name: 'Saved template' }))
  expect(saved.getByText('Saved template preview contains runtime failures.')).toBeTruthy()
  expect(saved.getByText('Saved template business checks have not passed.')).toBeTruthy()
  expect(saved.getAllByRole('table').length).toBe(baseline.paper.tables.length)
  expect(screen.queryByText('No value changes reported for this draft.')).toBeNull()
})

it('distinguishes added and removed values from explicitly present null and false, including member coordinates', () => {
  const candidate = preview()
  candidate.difference = {
    ...noChanges(),
    changes: [
      {
        step: null,
        panel: null,
        items: [
          { ...change('new-member', null, false), basePresent: false, coord: ['north'] },
          { ...change('removed-member', null, null), variantPresent: false, coord: ['south'] },
        ],
      },
    ],
  }
  render(<CandidateComparison candidate={candidate} current />)
  const added = within(screen.getByRole('row', { name: /new-member/ }))
  expect(added.getByText('new-member @ north')).toBeTruthy()
  expect(added.getByText('Absent')).toBeTruthy()
  expect(added.getByText('false')).toBeTruthy()
  expect(added.getByText('Added')).toBeTruthy()
  const removed = within(screen.getByRole('row', { name: /removed-member/ }))
  expect(removed.getByText('null')).toBeTruthy()
  expect(removed.getByText('Absent')).toBeTruthy()
  expect(removed.getByText('Removed')).toBeTruthy()
})

it('keeps previous comparison evidence visibly stale while the saved Paper stays exact and read-only', () => {
  const candidate = preview()
  candidate.difference = {
    ...noChanges(),
    changes: [{ step: null, panel: null, items: [change('old-value', 'A', 'B')] }],
  }
  const baseline = preview()
  const original = structuredClone(baseline.paper)
  render(<CandidateComparison candidate={candidate} baseline={baseline} current={false} />)
  expect(screen.getByText('Previous draft comparison — preview the current draft to update it.')).toBeTruthy()
  expect(screen.getByRole('row', { name: /old-value/ })).toBeTruthy()
  const saved = screen.getByRole('region', { name: 'Saved template' })
  expect(within(saved).getByRole('heading', { name: 'Saved template' })).toBeTruthy()
  expect(within(saved).queryByRole('button')).toBeNull()
  expect(within(saved).queryByRole('checkbox')).toBeNull()
  baseline.paper.tables.forEach((table) => {
    const rendered = within(saved).getByRole('table', { name: 'Saved template: ' + table.title })
    const cells = within(rendered).getAllByRole('cell')
    expect(cells.map((cell) => cell.textContent)).toEqual(
      table.rows.flatMap((row) => row.cells.map((cell) => cell.text)),
    )
    fireEvent.click(cells[0])
  })
  expect(baseline.paper).toEqual(original)
  expect(screen.getByText('Previous draft comparison — preview the current draft to update it.')).toBeTruthy()
})
