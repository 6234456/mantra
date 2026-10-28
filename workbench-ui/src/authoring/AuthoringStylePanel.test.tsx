// @vitest-environment jsdom
import { useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AuthoringStylePanel, type AuthoringStylePanelProps } from './AuthoringStylePanel'
import type { SourceOwner } from './service'

afterEach(cleanup)

const owner: SourceOwner = {
  handle: 'opaque-class-owner',
  document: 'schema.mantra',
  declaration: '(info sample …)',
  declarationRange: { start: 0, end: 60 },
  property: 'classes',
  kind: 'info',
  range: { start: 20, end: 40 },
  editable: true,
  value: ['subtotal'],
  channel: 'Template definition',
}

function mount(overrides: Partial<AuthoringStylePanelProps> = {}) {
  const onApply = vi.fn()
  const onChange = vi.fn()
  function Harness() {
    const [value, setValue] = useState(overrides.value ?? ['subtotal'])
    return (
      <AuthoringStylePanel
        owner={owner}
        documents={{ 'layout.mantra': '(layout sample {:style-preset [:utilities :working-paper]})' }}
        cell={{ text: '1.00', style: { weight: 'bold', tone: 'accent', fill: 'subtle' } }}
        onApply={onApply}
        {...overrides}
        value={value}
        onChange={(classes) => {
          onChange(classes)
          setValue(classes)
        }}
      />
    )
  }
  return { ...render(<Harness />), onApply, onChange }
}

it('uses ordered chips and a grouped picker to replace a class, then applies only on request', () => {
  const { onChange, onApply } = mount()
  expect(screen.queryByRole('textbox', { name: 'Classes' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Remove class subtotal' }))
  expect(onChange).toHaveBeenLastCalledWith([])
  fireEvent.click(screen.getByRole('button', { name: 'Add class' }))
  expect(screen.getByRole('group', { name: ':style-preset :utilities' })).toBeTruthy()
  expect(screen.getByRole('group', { name: ':style-preset :working-paper' })).toBeTruthy()
  for (const name of ['normal', 'subtle', 'highlight', 'variance']) {
    expect(screen.getByRole('button', { name: `Add class ${name}` })).toBeTruthy()
  }
  expect(screen.getByRole('link', { name: 'dsl-reference §3.4' }).getAttribute('href')).toContain(
    'docs/dsl-reference.md#34-reusable-style-classes',
  )
  fireEvent.click(screen.getByRole('button', { name: 'Add class result' }))
  expect(onChange).toHaveBeenLastCalledWith(['result'])
  expect(screen.getByRole('button', { name: 'Remove class result' })).toBeTruthy()
  expect(onApply).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Apply classes' }))
  expect(onApply).toHaveBeenCalledOnce()
})

it('preserves source class order and unknown tags while appending an existing vocabulary class', () => {
  const { onChange } = mount({ value: ['unknown', 'subtotal'] })
  expect(screen.getByText('No matching rule · no visual effect')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Add class' }))
  expect((screen.getByRole('button', { name: 'Add class subtotal' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Add class strong' }))
  expect(onChange).toHaveBeenLastCalledWith(['unknown', 'subtotal', 'strong'])
  expect(
    screen.getAllByRole('button', { name: /^Remove class/ }).map((button) => button.getAttribute('aria-label')),
  ).toEqual(['Remove class unknown', 'Remove class subtotal', 'Remove class strong'])
})

it('displays only the selected Paper cell style as readable values with unavailable rule provenance', () => {
  mount()
  expect(screen.getByRole('heading', { name: 'Final cell style · Paper' })).toBeTruthy()
  expect(screen.getByText('Weight').nextElementSibling?.textContent).toBe('bold')
  expect(screen.getByText('Tone').nextElementSibling?.textContent).toBe('accent')
  expect(screen.getByText('Fill').nextElementSibling?.textContent).toBe('subtle')
  expect(screen.getByText('Rule provenance unavailable.')).toBeTruthy()
  expect(
    screen.getByText('Styles never change values, rounding, aggregation, applicability or validation.'),
  ).toBeTruthy()
})

it('does not invent cell defaults or mutate a read-only or multiple selection', () => {
  const { onApply, onChange } = mount({ cell: { text: '1.00' }, multiple: true })
  expect(screen.getAllByText('Not supplied by Paper')).toHaveLength(3)
  expect((screen.getByRole('button', { name: 'Remove class subtotal' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Add class' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Apply classes' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Apply classes' }))
  expect(onApply).not.toHaveBeenCalled()
  expect(onChange).not.toHaveBeenCalled()
})
