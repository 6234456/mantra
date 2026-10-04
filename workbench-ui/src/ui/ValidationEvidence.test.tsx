// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { ValidationEvidence } from './ValidationEvidence'
import type { Validation } from './ValidationEvidence'

afterEach(cleanup)
const validation: Validation = {
  active: true,
  passed: false,
  severity: 'error',
  reconciliation: {
    left: { n: '9007199254740993.02' },
    right: { n: '9007199254740993.00' },
    difference: { n: '0.02' },
    tolerance: { n: '0.01' },
  },
}

it('displays the reported outcome and exact reconciliation evidence without floating-point arithmetic', () => {
  render(<ValidationEvidence validation={validation} />)
  expect(screen.getByText('Nicht bestanden')).toBeTruthy()
  expect(screen.getByText('9007199254740993.02')).toBeTruthy()
  expect(screen.getByText('9007199254740993.00')).toBeTruthy()
  expect(screen.getByText('0.02')).toBeTruthy()
  expect(screen.getByText('0.01')).toBeTruthy()
  expect(screen.getByText('Schweregrad: error')).toBeTruthy()
})

it('uses the server verdict even when the displayed evidence alone would suggest another result', () => {
  const { rerender } = render(<ValidationEvidence validation={{ ...validation, passed: true }} />)
  expect(screen.getByText('Bestanden')).toBeTruthy()
  expect(screen.queryByText('Nicht bestanden')).toBeNull()
  rerender(<ValidationEvidence validation={{ ...validation, active: false, passed: null, reconciliation: null }} />)
  expect(screen.getByText('Nicht geprüft')).toBeTruthy()
  expect(screen.queryByText('0.02')).toBeNull()
})
