// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { ExtensionsPage } from './AuthoringPages'
import type { WorkbenchData } from '../data'
import type { Structure } from '../types'

afterEach(cleanup)

it('shows non-line extensions without offering a formula editor for them', () => {
  const structure = {
    schema: 'sample', title: 'Sample', mainline: [], panels: [], generalInputs: [], params: [],
    slots: [{ id: 'custom', title: 'Custom', extensions: ['editable', 'chosen'] }],
    nodes: {
      editable: { id: 'editable', label: 'Editable line', kind: 'extension', formula: { text: '(+ 1 2)' } },
      chosen: { id: 'chosen', label: 'Choice extension', kind: 'extension' },
    },
  } as Structure
  render(<ExtensionsPage caseId="case.mantra" structure={structure} revision="1234567890abcdef"
    data={{} as WorkbenchData} onSaved={vi.fn()} />)
  const readonly = screen.getByText('Choice extension').closest('.extension-readonly') as HTMLElement
  expect(readonly).toBeTruthy()
  expect(within(readonly).queryByRole('button', { name: 'Speichern' })).toBeNull()
  expect(screen.getAllByRole('button', { name: 'Speichern' })).toHaveLength(1)
})
