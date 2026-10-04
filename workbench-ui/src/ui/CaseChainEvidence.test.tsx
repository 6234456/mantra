// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { CaseChainEvidence } from './CaseChainEvidence'
import type { CaseGraph } from '../types'

afterEach(cleanup)

it('shows distinct source statuses and links the complete source coordinate with its revision', () => {
  const graph: CaseGraph = {
    root: 'root/case.mantra',
    cases: [
      {
        case: 'root/case.mantra',
        caseId: 'root',
        schema: { id: 'neutral', version: '2' },
        revision: 'root-r',
        succeeded: true,
        validationPassed: false,
      },
      {
        case: 'source/case.mantra',
        caseId: 'source',
        schema: { id: 'neutral', version: '1' },
        revision: 'source-r',
        succeeded: true,
        validationPassed: false,
      },
      {
        case: 'failed/case.mantra',
        caseId: 'failed',
        schema: { id: 'neutral', version: null },
        revision: 'failed-r',
        succeeded: false,
        validationPassed: true,
      },
    ],
    edges: [
      {
        from: { case: 'source/case.mantra', node: 'closing', coord: ['A/B', 'P2'] },
        to: { case: 'root/case.mantra', node: 'opening', coord: ['A/B', 'P3'] },
        revision: 'source-r',
      },
    ],
  }
  const navigate = vi.fn()
  render(<CaseChainEvidence graph={graph} caseId="root/case.mantra" navigate={navigate} />)
  expect(screen.getAllByText('Fachliche Befunde')).toHaveLength(2)
  expect(screen.getByText('Berechnung fehlgeschlagen')).toBeTruthy()
  expect(screen.getByRole('link', { name: 'source' }).getAttribute('href')).toBe(
    '/cases/source%2Fcase%2Emantra/overview',
  )
  const mapping = screen.getByRole('link', { name: 'closing @ A/B / P2' })
  const path = new URL(mapping.getAttribute('href')!, 'http://localhost')
  expect(path.searchParams.get('case')).toBe('source/case.mantra')
  expect(path.searchParams.get('expectedRevision')).toBe('source-r')
  fireEvent.click(mapping)
  expect(navigate).toHaveBeenCalledWith(mapping.getAttribute('href'))
})

it('does not add a chain panel to an ordinary single case', () => {
  const graph: CaseGraph = { root: 'case.mantra', cases: [], edges: [] }
  const { container } = render(<CaseChainEvidence graph={graph} caseId="case.mantra" navigate={vi.fn()} />)
  expect(container.textContent).toBe('')
})
