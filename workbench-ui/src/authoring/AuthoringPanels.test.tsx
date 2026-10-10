// @vitest-environment jsdom
import { StrictMode, useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { BuildPanel, PrototypeDialog, SourceDocumentDiff } from './AuthoringPanels'

afterEach(cleanup)

function DialogHarness({ autoFocusInput = false }: { autoFocusInput?: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)}>Launch dialog</button>
      {open && (
        <PrototypeDialog title="Test dialog" onClose={() => setOpen(false)}>
          <button onClick={() => setOpen(false)}>Close dialog</button>
          {autoFocusInput && <input aria-label="Editor input" autoFocus />}
          <button>Last action</button>
        </PrototypeDialog>
      )}
    </>
  )
}

it('keeps an auto-focused child input focused instead of moving focus to the dialog close button', () => {
  render(
    <StrictMode>
      <DialogHarness autoFocusInput />
    </StrictMode>,
  )
  const launcher = screen.getByRole('button', { name: 'Launch dialog' })
  launcher.focus()
  fireEvent.click(launcher)
  const input = screen.getByRole('textbox', { name: 'Editor input' }) as HTMLInputElement
  expect(document.activeElement).toBe(input)
  fireEvent.change(input, { target: { value: 'draft text' } })
  expect(input.value).toBe('draft text')
  expect(document.activeElement).toBe(input)
  fireEvent.click(screen.getByRole('button', { name: 'Close dialog' }))
  expect(document.activeElement).toBe(launcher)
})

it('focuses an ordinary dialog first button, traps Tab at its boundaries, and returns focus to its launcher', () => {
  render(<DialogHarness />)
  const launcher = screen.getByRole('button', { name: 'Launch dialog' })
  launcher.focus()
  fireEvent.click(launcher)
  const first = screen.getByRole('button', { name: 'Close dialog' })
  const last = screen.getByRole('button', { name: 'Last action' })
  expect(document.activeElement).toBe(first)
  fireEvent.keyDown(first, { key: 'Tab', shiftKey: true })
  expect(document.activeElement).toBe(last)
  fireEvent.keyDown(last, { key: 'Tab' })
  expect(document.activeElement).toBe(first)
  fireEvent.keyDown(first, { key: 'Escape' })
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(document.activeElement).toBe(launcher)
})

it('shows separate source hunks without displaying unchanged lines as external differences', () => {
  const { container } = render(
    <SourceDocumentDiff
      document="layout.mantra"
      before={'(layout\n  :title "Old title"\n  :unchanged true\n  :class :subtotal)\n'}
      after={'(layout\n  :title "New title"\n  :unchanged true\n  :class :result)\n'}
      fallbackOperation="External source edit (simulated)"
    />,
  )
  expect(container.querySelectorAll('[data-source-hunk]')).toHaveLength(2)
  expect(container.querySelectorAll('[data-source-change]')).toHaveLength(4)
  expect(container.textContent).not.toContain(':unchanged true')
  expect(container.textContent).not.toContain('(layout')
  expect(screen.getAllByText('External source edit (simulated)')).toHaveLength(2)
})

it('identifies the recorded base report and associates distinct unavailable reasons with each disabled action', () => {
  render(<BuildPanel />)
  expect(screen.getByRole('heading', { name: 'ExcelExport report for the recorded base state' })).toBeTruthy()
  expect(screen.getByText(/not the current simulated saved source revision/)).toBeTruthy()
  const actions = [
    ['Build', 'contract G-A10'],
    ['Publish', 'contract G-A11'],
    ['Open in Template Engine', 'not verified'],
  ]
  const descriptions: string[] = []
  for (const [name, expectedReason] of actions) {
    const button = screen.getByRole('button', { name }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    const id = button.getAttribute('aria-describedby')
    expect(id).toBeTruthy()
    descriptions.push(id!)
    expect(document.getElementById(id!)?.textContent).toContain(expectedReason)
  }
  expect(new Set(descriptions).size).toBe(3)
})
