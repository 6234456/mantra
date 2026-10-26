// @vitest-environment jsdom
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SchemaPropertiesPanel } from './SchemaPropertiesPanel'
import TemplateDraftPreviewPage from './TemplateDraftPreviewPage'
import { TemplatePreviewError, type TemplatePreviewClient } from './client'
import { firstCase, templateSources, templatePreview } from './fixtures.test.helpers'
import type { SchemaProperties, SchemaPropertiesClient, SchemaPropertiesRequest } from './schemaPropertiesClient'
import type { Envelope } from '../types'
import { createBackup, emptyRecoveryPending, parseBackup, type SchemaPropertyDraft } from './recovery'

const snapshot = templateSources()
const source = snapshot.data.documents.find((item) => item.role === 'schema')!
const original = '(schema example/demo {:version "1"}\r\n  ;; keep 😀\r\n  (info answer "Old" 20 {:class :custom}))\r\n'
source.text = original
const declaration = '(info answer "Old" 20 {:class :custom})'
const textHandle = 'c'.repeat(64)
const classHandle = 'd'.repeat(64)

function response(sent: SchemaPropertiesRequest): Envelope<SchemaProperties> {
  let text = sent.text
  for (const operation of sent.operations) {
    if (operation.op === 'setText') text = text.replace('"Old"', JSON.stringify(operation.text))
    if (operation.op === 'setClasses')
      text = text.replace(':custom', `[${operation.classes.map((name) => `:${name}`).join(' ')}]`)
    if (operation.op === 'unset') text = text.replace(' {:class :custom}', '')
  }
  const range = { line: 3, column: 3, startOffset: text.indexOf('(info'), endOffset: text.lastIndexOf(')') }
  const common = {
    document: source.document,
    node: 'answer',
    form: 'info' as const,
    path: [0],
    declared: true,
    editable: true,
    reason: null,
    declarationRange: range,
    range,
  }
  return {
    contract: 'mantra.workbench/4',
    revision: sent.baseRevision,
    engine: snapshot.engine,
    data: {
      document: firstCase,
      draftSequence: sent.draftSequence,
      baseRevisions: sent.baseRevisions,
      scope: 'schema-properties',
      coverage: 'presentation-items',
      schemaId: 'example/demo',
      source: { handle: sent.handle, document: source.document, text },
      patches: [],
      owners: [
        { ...common, handle: textHandle, property: 'label', type: 'text', value: 'Old', canUnset: false },
        {
          ...common,
          handle: classHandle,
          property: 'classes',
          type: 'classes',
          value: [{ kw: 'custom' }],
          canUnset: true,
        },
        {
          ...common,
          handle: 'f'.repeat(64),
          document: 'include.mantra',
          node: 'hidden',
          path: [1],
          editable: false,
          reason: 'Captured include is read-only.',
          property: 'label',
          type: 'text',
          value: 'Hidden label',
          canUnset: false,
        },
      ],
    },
  }
}
function client() {
  return { properties: vi.fn<SchemaPropertiesClient['properties']>(async (_id, sent) => response(sent)) }
}
function props(api = client()) {
  return {
    caseId: firstCase,
    snapshot,
    source,
    text: original,
    conflict: false,
    client: api,
    onApply: vi.fn((_id: string, _request: SchemaPropertiesRequest, _text: string) => true),
    onConflict: vi.fn(),
    onDraftChange: vi.fn(),
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
async function inspect() {
  fireEvent.click(screen.getByRole('button', { name: /Inspect schema properties|Refresh schema properties/ }))
  await screen.findByLabelText('Schema property')
  await waitFor(() => expect(screen.queryByText('Reading schema properties…')).toBeNull())
}
function type(text: string) {
  fireEvent.change(screen.getByLabelText('Declared text'), { target: { value: text } })
}
const draft: SchemaPropertyDraft = {
  document: source.document,
  path: [0],
  form: 'info',
  node: 'answer',
  property: 'label',
  declaration,
  text: 'New 中文😀',
  unset: false,
}

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
})
afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.unstubAllGlobals()
})

it('inspects actual owner identities and only applies text through the source callback', async () => {
  const p = props()
  render(<SchemaPropertiesPanel {...p} />)
  await inspect()
  type('New 中文😀')
  expect(p.onApply).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  await waitFor(() => expect(p.onApply).toHaveBeenCalledOnce())
  const sent = p.client.properties.mock.calls[1][1]
  expect(sent.operations).toEqual([{ op: 'setText', owner: textHandle, text: 'New 中文😀' }])
  expect(sent.baseRevisions).toEqual(snapshot.data.baseRevisions)
  expect(p.onApply.mock.calls[0]).toEqual([firstCase, sent, original.replace('"Old"', '"New 中文😀"')])
  expect(p.onDraftChange).toHaveBeenLastCalledWith(null)
})

it('shows captured include declarations but keeps them read-only', async () => {
  const p = props()
  render(<SchemaPropertiesPanel {...p} />)
  await inspect()
  const select = screen.getByLabelText('Schema property') as HTMLSelectElement
  fireEvent.change(select, { target: { value: select.options[2].value } })
  expect((screen.getByLabelText('Declared text') as HTMLTextAreaElement).readOnly).toBe(true)
  expect(screen.getByText(/Captured include is read-only/)).toBeDefined()
  expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(true)
})

it('distinguishes explicit empty classes from removing their declaration and validates names', async () => {
  const p = props()
  function Harness() {
    const [text, setText] = useState(original)
    return (
      <SchemaPropertiesPanel
        {...p}
        text={text}
        onApply={(id, sent, next) => {
          p.onApply(id, sent, next)
          setText(next)
          return true
        }}
      />
    )
  }
  render(<Harness />)
  await inspect()
  const select = screen.getByLabelText('Schema property') as HTMLSelectElement
  fireEvent.change(select, { target: { value: select.options[1].value } })
  fireEvent.change(screen.getByLabelText('Assigned classes'), { target: { value: 'custom custom' } })
  expect(screen.getByRole('alert').textContent).toContain('unique')
  expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.change(screen.getByLabelText('Assigned classes'), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  await waitFor(() => expect(p.onApply).toHaveBeenCalledOnce())
  expect(p.client.properties.mock.calls[1][1].operations).toEqual([
    { op: 'setClasses', owner: classHandle, classes: [] },
  ])
  fireEvent.click(screen.getByRole('button', { name: 'Remove class declaration' }))
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  await waitFor(() => expect(p.onApply).toHaveBeenCalledTimes(2))
  expect(p.client.properties.mock.calls[2][1].operations).toEqual([{ op: 'unset', owner: classHandle }])
})

it('rejects a late apply after source editing and retains the newer source and fields', async () => {
  const api = client()
  const p = props(api)
  const view = render(<SchemaPropertiesPanel {...p} />)
  await inspect()
  type('First edit')
  const delayed = deferred<Envelope<SchemaProperties>>()
  api.properties.mockReturnValueOnce(delayed.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  const sent = api.properties.mock.calls[1][1]
  view.rerender(<SchemaPropertiesPanel {...p} text={original + '; handwritten\n'} />)
  await act(async () => {
    delayed.resolve(response(sent))
    await delayed.promise
  })
  expect(p.onApply).not.toHaveBeenCalled()
  expect((screen.getByLabelText('Declared text') as HTMLTextAreaElement).value).toBe('First edit')
  expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(true)
})

it('does not accept or clear an apply confirmation after newer field input', async () => {
  const p = props()
  render(<SchemaPropertiesPanel {...p} />)
  await inspect()
  type('First')
  const delayed = deferred<Envelope<SchemaProperties>>()
  p.client.properties.mockReturnValueOnce(delayed.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  const sent = p.client.properties.mock.calls[1][1]
  type('Second')
  await act(async () => {
    delayed.resolve(response(sent))
    await delayed.promise
  })
  expect(p.onApply).not.toHaveBeenCalled()
  expect((screen.getByLabelText('Declared text') as HTMLTextAreaElement).value).toBe('Second')
  expect(screen.getByText(/fields changed while applying/)).toBeDefined()
})

it('refuses to rebind retained fields to a changed declaration after refresh', async () => {
  const p = props()
  const view = render(<SchemaPropertiesPanel {...p} />)
  await inspect()
  type('Retained')
  view.rerender(<SchemaPropertiesPanel {...p} text={original.replace('"Old"', '"External"')} />)
  await inspect()
  expect(screen.getByText(/retained declaration is unavailable or has changed/)).toBeDefined()
  expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel schema property changes' }))
  expect(p.onDraftChange).toHaveBeenLastCalledWith(null)
})

it('retains fields and marks conflicts without accepting source or claiming a preview', async () => {
  const p = props()
  render(<SchemaPropertiesPanel {...p} />)
  await inspect()
  type('Retained')
  p.client.properties.mockRejectedValueOnce(new TemplatePreviewError(409, 'Source changed', []))
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  await waitFor(() => expect(p.onConflict).toHaveBeenCalledOnce())
  expect(p.onApply).not.toHaveBeenCalled()
  expect((screen.getByLabelText('Declared text') as HTMLTextAreaElement).value).toBe('Retained')
})

it('restored raw fields require inspection and remain cancelable before inspection', async () => {
  const p = props()
  render(<SchemaPropertiesPanel {...p} restoredDraft={draft} />)
  expect((screen.getByLabelText('Declared text') as HTMLTextAreaElement).value).toBe(draft.text)
  expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel schema property changes' }))
  expect(p.onDraftChange).toHaveBeenLastCalledWith(null)
})

it('round-trips raw pending schema fields in recovery v2, preserves v1 and rejects forged authority', () => {
  const documents = Object.fromEntries(snapshot.data.documents.map((item) => [item.handle, item.text]))
  const old = createBackup(snapshot, { documents, inputs: {}, pending: emptyRecoveryPending() })
  expect(old.status === 'ready' && old.draft.format).toBe('mantra.template-preview-recovery/1')
  const saved = createBackup(snapshot, {
    documents,
    inputs: {},
    pending: { ...emptyRecoveryPending(), schemaProperty: draft },
  })
  expect(saved.status).toBe('ready')
  if (saved.status !== 'ready') return
  expect(saved.draft.format).toBe('mantra.template-preview-recovery/2')
  expect(parseBackup(saved.json)).toEqual({ status: 'ready', draft: saved.draft })
  const corrupted = structuredClone(saved.draft)
  Object.assign(corrupted.pending.schemaProperty!, { owner: textHandle })
  expect(parseBackup(JSON.stringify(corrupted)).status).toBe('invalid')
  expect(parseBackup(JSON.stringify({ ...saved.draft, format: 'mantra.template-preview-recovery/1' })).status).toBe(
    'invalid',
  )
})

it('coordinates Apply, source Undo/Redo, preview blocking and browser recovery in the real page', async () => {
  const api = client()
  const previewClient: TemplatePreviewClient = {
    workspace: vi.fn(async () => ({
      workspaceId: snapshot.data.workspaceId,
      cases: [{ id: firstCase, title: 'First' }],
    })),
    sources: vi.fn(async () => snapshot),
    preview: vi.fn(async (id, sent) => templatePreview(id, sent)),
  }
  render(<TemplateDraftPreviewPage client={previewClient} schemaPropertiesClient={api} />)
  await screen.findByRole('option', { name: `${source.document} · schema` })
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: source.handle } })
  await screen.findByRole('textbox', { name: `Draft source ${source.document}` })
  fireEvent.click(screen.getByText('Schema presentation properties', { selector: 'summary' }))
  await inspect()
  type('Pending 中文😀')
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true),
  )
  expect((screen.getByRole('button', { name: 'Undo source' }) as HTMLButtonElement).disabled).toBe(true)
  await waitFor(() => expect(Object.values(localStorage).join('')).toContain('Pending 中文😀'))
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  const editor = screen.getByRole('textbox', { name: `Draft source ${source.document}` }) as HTMLTextAreaElement
  await waitFor(() => expect(editor.value).toContain('Pending 中文😀'))
  expect(editor.value).toContain(';; keep 😀\r\n'.replace(/\r\n/g, '\n'))
  fireEvent.click(screen.getByRole('button', { name: 'Undo source' }))
  await waitFor(() => expect(editor.value).toContain('"Old"'))
  fireEvent.click(screen.getByRole('button', { name: 'Redo source' }))
  await waitFor(() => expect(editor.value).toContain('Pending 中文😀'))
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await waitFor(() => expect(previewClient.preview).toHaveBeenCalledOnce())
  const sent = vi.mocked(previewClient.preview).mock.calls[0][1]
  expect(sent.documents.find((item) => item.handle === source.handle)?.text).toBe(
    original.replace('"Old"', '"Pending 中文😀"'),
  )
})

it('handles same-source Apply with a controlled parent and keeps the next inspection current', async () => {
  const p = props()
  function Harness() {
    const [text, setText] = useState(original)
    return (
      <SchemaPropertiesPanel
        {...p}
        text={text}
        onApply={(_case, _sent, next) => {
          setText(next)
          return true
        }}
      />
    )
  }
  render(<Harness />)
  await inspect()
  type('New')
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  await screen.findByText(/Schema property applied/)
  expect((screen.getByLabelText('Schema property') as HTMLSelectElement).disabled).toBe(false)
})

it('restores pending schema fields in the page without owner permissions and requires a fresh inspection', async () => {
  const documents = Object.fromEntries(snapshot.data.documents.map((item) => [item.handle, item.text]))
  const backup = createBackup(snapshot, {
    documents,
    inputs: {},
    pending: { ...emptyRecoveryPending(), schemaProperty: draft },
  })
  expect(backup.status).toBe('ready')
  if (backup.status !== 'ready') return
  localStorage.setItem(
    `mantra.template-preview.recovery.v1.${snapshot.data.workspaceId}.${encodeURIComponent(firstCase)}`,
    backup.json,
  )
  const api = client()
  const previewClient: TemplatePreviewClient = {
    workspace: vi.fn(async () => ({
      workspaceId: snapshot.data.workspaceId,
      cases: [{ id: firstCase, title: 'First' }],
    })),
    sources: vi.fn(async () => snapshot),
    preview: vi.fn(async (id, sent) => templatePreview(id, sent)),
  }
  render(<TemplateDraftPreviewPage client={previewClient} schemaPropertiesClient={api} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Restore browser draft' }))
  await waitFor(() => expect((screen.getByLabelText('Declared text') as HTMLTextAreaElement).value).toBe(draft.text))
  expect(api.properties).not.toHaveBeenCalled()
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Inspect schema properties' }))
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  await waitFor(() => expect(api.properties).toHaveBeenCalledTimes(2))
  expect(api.properties.mock.calls[1][1].operations).toEqual([{ op: 'setText', owner: textHandle, text: draft.text }])
  expect(previewClient.preview).not.toHaveBeenCalled()
})

it('edits note source text without requiring a node ID and guards composition confirmation', async () => {
  const p = props()
  p.client.properties.mockImplementation(async (_case, sent) => {
    const reply = response(sent)
    Object.assign(reply.data.owners[0], { form: 'note', node: null, property: 'text' })
    return reply
  })
  render(<SchemaPropertiesPanel {...p} />)
  await inspect()
  type('Note 中文😀')
  const input = screen.getByLabelText('Declared text')
  fireEvent.compositionStart(input)
  fireEvent.keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true })
  expect((screen.getByRole('button', { name: 'Apply schema property' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.compositionEnd(input)
  fireEvent.keyDown(screen.getByRole('button', { name: 'Apply schema property' }), { key: 'Enter', keyCode: 13 })
  fireEvent.click(screen.getByRole('button', { name: 'Apply schema property' }))
  await waitFor(() => expect(p.onApply).toHaveBeenCalledOnce())
  expect(p.client.properties.mock.calls[1][1].operations).toEqual([
    { op: 'setText', owner: textHandle, text: 'Note 中文😀' },
  ])
})
