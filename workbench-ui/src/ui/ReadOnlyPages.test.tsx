// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ParametersPage } from './ReadOnlyPages'
import type { WorkbenchData } from '../data'
import type { Parameters, Structure, Workspace } from '../types'

afterEach(cleanup)

const structure = { schema: 'sample', title: 'Sample', nodes: {} } as Structure
const workspace = { parameters: [{ id: 'next', path: 'next.mantra' }] } as Workspace
const parameters: Parameters = {
  parameters: [
    {
      id: 'rate',
      label: 'Example rate',
      reference: 'Independent source',
      layers: [
        { layer: 'schema', value: { n: '10' }, declared: true },
        { layer: 'case', value: { n: '12' }, declared: true },
      ],
      effective: { layer: 'case', value: { n: '12' } },
    },
  ],
}

function parameterData(capabilities: Partial<WorkbenchData> = {}) {
  return {
    parameters: vi.fn().mockResolvedValue({ data: parameters }),
    edit: vi.fn().mockResolvedValue({ data: {} }),
    compare: vi.fn(() => {
      throw new Error('Unsupported comparison')
    }),
    ...capabilities,
  } as unknown as WorkbenchData
}

it('shows parameter provenance but disables save and reset for a read-only case', async () => {
  const data = parameterData({ canEditCase: () => false })
  const saved = vi.fn()
  render(
    <ParametersPage
      caseId="case.mantra"
      structure={structure}
      workspace={workspace}
      data={data}
      revision="revision"
      onSaved={saved}
      navigate={vi.fn()}
    />,
  )
  expect(await screen.findByText('Independent source')).toBeTruthy()
  expect(screen.getByRole('status').textContent).toBe('Dieser Fall ist schreibgeschützt.')
  expect(document.querySelector('.effective-value')?.textContent).toContain('12')
  const input = screen.getByLabelText('Fallüberschreibung bearbeiten') as HTMLInputElement
  expect(input.disabled).toBe(true)
  fireEvent.change(input, { target: { value: '14' } })
  fireEvent.keyDown(input, { key: 'Enter' })
  for (const name of ['Speichern', 'Zurücksetzen']) {
    const button = screen.getByRole('button', { name }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
  }
  expect(data.edit).not.toHaveBeenCalled()
  expect(saved).not.toHaveBeenCalled()
})

it('allows host parameter edits while omitting an unsupported comparison request', async () => {
  const data = parameterData({ canEditCase: () => true, canCompareParameters: () => false })
  const saved = vi.fn()
  const navigate = vi.fn()
  render(
    <ParametersPage
      caseId="case.mantra"
      structure={structure}
      workspace={workspace}
      data={data}
      compareSet="next"
      revision="revision"
      onSaved={saved}
      navigate={navigate}
    />,
  )
  expect(await screen.findByText('Example rate')).toBeTruthy()
  expect(screen.getByRole('status').textContent).toBe('Der Parametervergleich ist in dieser Ansicht nicht verfügbar.')
  const select = screen.getByLabelText('Vergleichen mit') as HTMLSelectElement
  expect(select.disabled).toBe(true)
  fireEvent.change(select, { target: { value: '' } })
  expect(navigate).not.toHaveBeenCalled()
  expect(data.compare).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Fallüberschreibung bearbeiten'), { target: { value: '14' } })
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(data.edit).toHaveBeenCalledWith('case.mantra', 'revision', [{ op: 'setParam', id: 'rate', text: '14' }])
})

it('retains parameter editing for legacy adapters without explicit capabilities and rechecks permission before saving', async () => {
  const data = parameterData()
  const saved = vi.fn()
  render(
    <ParametersPage
      caseId="case.mantra"
      structure={structure}
      workspace={workspace}
      data={data}
      revision="revision"
      onSaved={saved}
      navigate={vi.fn()}
    />,
  )
  await screen.findByText('Example rate')
  fireEvent.click(screen.getByRole('button', { name: 'Zurücksetzen' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(data.edit).toHaveBeenCalledWith('case.mantra', 'revision', [{ op: 'resetParam', id: 'rate' }])
  data.canEditCase = () => false
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  fireEvent.keyDown(screen.getByLabelText('Fallüberschreibung bearbeiten'), { key: 'Enter' })
  expect(data.edit).toHaveBeenCalledOnce()
})
