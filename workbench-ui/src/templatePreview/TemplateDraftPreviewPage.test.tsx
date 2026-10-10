// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Envelope } from '../types'
import TemplateDraftPreviewPage from './TemplateDraftPreviewPage'
import { TemplatePreviewError } from './client'
import type { TemplatePreview, TemplatePreviewClient, TemplateSources } from './client'
import { firstCase, secondCase, formulaDiagnostic, templatePreview, templateSources } from './fixtures.test.helpers'

beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  cleanup()
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  document.querySelector('meta[name="mantra-package-workspace"]')?.remove()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => (resolve = complete))
  return { promise, resolve }
}

function fakeClient() {
  return {
    workspace: vi.fn(async () => ({
      cases: [
        { id: firstCase, title: 'First example' },
        { id: secondCase, title: 'Second example' },
      ],
    })),
    sources: vi.fn(async (caseId: string) => templateSources(caseId)),
    preview: vi.fn<TemplatePreviewClient['preview']>(async (caseId, request) => templatePreview(caseId, request)),
  }
}

const schema = templateSources().data.documents.find((document) => document.role === 'schema')!
const fragment = templateSources().data.documents.find((document) => document.role === 'included')!

async function open(client = fakeClient()) {
  render(<TemplateDraftPreviewPage client={client} />)
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: schema.handle } })
  const source = await screen.findByRole('textbox', { name: 'Draft source ' + schema.document })
  return { client, source: source as HTMLTextAreaElement }
}

async function previewCurrent() {
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await waitFor(() =>
    expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Current engine preview/),
  )
}

it('discards a pending preview after source editing and preserves the exact newer editor buffer', async () => {
  const client = fakeClient()
  const pending = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(pending.promise)
  const { source } = await open(client)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const oldRequest = client.preview.mock.calls[0][1]
  const changed = source.value + '; Newer in-memory source\n'
  fireEvent.change(source, { target: { value: changed } })
  await act(async () => {
    pending.resolve(templatePreview(firstCase, oldRequest))
    await pending.promise
  })
  expect(source.value).toBe(changed)
  expect(screen.getByTestId('template-preview-status').textContent).toContain('Preview a draft')
  expect(screen.queryByRole('table')).toBeNull()
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false)
})

it('rejects late source captures and previews from an earlier case context even after switching back', async () => {
  const client = fakeClient()
  const oldSources = deferred<Envelope<TemplateSources>>()
  const oldPreview = deferred<Envelope<TemplatePreview>>()
  client.sources.mockReturnValueOnce(oldSources.promise)
  client.preview.mockReturnValueOnce(oldPreview.promise)
  render(<TemplateDraftPreviewPage client={client} />)
  await screen.findByRole('option', { name: 'Second example' })
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const oldRequest = client.preview.mock.calls[0][1]
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: firstCase } })
  await waitFor(() => expect(client.sources).toHaveBeenCalledTimes(3))
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: schema.handle } })
  const source = (await screen.findByRole('textbox', {
    name: 'Draft source ' + schema.document,
  })) as HTMLTextAreaElement
  const latest = source.value + '; Current case buffer\n'
  fireEvent.change(source, { target: { value: latest } })
  await previewCurrent()
  const latestSequence = client.preview.mock.lastCall![1].draftSequence
  await act(async () => {
    oldSources.resolve({ ...templateSources(firstCase), revision: 'b'.repeat(64) })
    oldPreview.resolve(templatePreview(secondCase, oldRequest))
    await Promise.all([oldSources.promise, oldPreview.promise])
  })
  expect(source.value).toBe(latest)
  expect((screen.getByLabelText('Example case') as HTMLSelectElement).value).toBe(firstCase)
  expect(screen.getByTestId('template-preview-status').textContent).toContain(
    'Current engine preview · draft #' + latestSequence,
  )
  expect(screen.queryByText('b'.repeat(64))).toBeNull()
})

it('keeps edited source and a pending preview when a case switch is canceled, and discards the reply after an accepted switch', async () => {
  const client = fakeClient()
  const { source } = await open(client)
  const changed = source.value + '; Unsaved source\n'
  fireEvent.change(source, { target: { value: changed } })
  const pending = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(pending.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const request = client.preview.mock.lastCall![1]
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  expect(source.value).toBe(changed)
  expect((screen.getByLabelText('Example case') as HTMLSelectElement).value).toBe(firstCase)
  expect(client.sources).toHaveBeenCalledOnce()
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  await waitFor(() => expect(client.sources).toHaveBeenCalledTimes(2))
  await act(async () => {
    pending.resolve(templatePreview(firstCase, request))
    await pending.promise
  })
  expect(confirm).toHaveBeenCalledTimes(2)
  expect((screen.getByLabelText('Example case') as HTMLSelectElement).value).toBe(secondCase)
  expect(screen.getByTestId('template-preview-status').textContent).toContain('Preview a draft')
  expect(screen.queryByRole('table')).toBeNull()
})

it('discards a pending preview on reload and respects the confirmation before replacing changed source', async () => {
  const client = fakeClient()
  const pending = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(pending.promise)
  const { source } = await open(client)
  const changed = source.value + '; Unsubmitted change\n'
  fireEvent.change(source, { target: { value: changed } })
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const request = client.preview.mock.calls[0][1]
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  fireEvent.click(screen.getByRole('button', { name: 'Reload saved source' }))
  expect(source.value).toBe(changed)
  expect(client.sources).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: 'Reload saved source' }))
  await waitFor(() => expect(client.sources).toHaveBeenCalledTimes(2))
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  await act(async () => {
    pending.resolve(templatePreview(firstCase, request))
    await pending.promise
  })
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(screen.getByTestId('template-preview-status').textContent).toContain('Preview a draft')
  expect(screen.queryByRole('table')).toBeNull()
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: schema.handle } })
  expect((screen.getByRole('textbox', { name: 'Draft source ' + schema.document }) as HTMLTextAreaElement).value).toBe(
    schema.text,
  )
})

it('discards a pending reply when a different Explain address is selected and requests evidence for that selection', async () => {
  const client = fakeClient()
  await open(client)
  await previewCurrent()
  const pending = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(pending.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const previousRequest = client.preview.mock.lastCall![1]
  const cell = document.querySelector<HTMLButtonElement>('.cell-button')!
  fireEvent.click(cell)
  await previewCurrent()
  const currentRequest = client.preview.mock.lastCall![1]
  expect(currentRequest.explain?.node).toBeTruthy()
  const currentStatus = screen.getByTestId('template-preview-status').textContent
  expect(screen.getByRole('heading', { name: 'Draft Explain' })).toBeTruthy()
  await act(async () => {
    pending.resolve(templatePreview(firstCase, previousRequest))
    await pending.promise
  })
  expect(screen.getByTestId('template-preview-status').textContent).toBe(currentStatus)
  expect(screen.getByRole('heading', { name: 'Draft Explain' })).toBeTruthy()
})

it('retains the last real Paper as previous evidence after exact source diagnostics reject a draft', async () => {
  const client = fakeClient()
  const { source } = await open(client)
  await previewCurrent()
  const paper = screen.getByRole('table').textContent
  const oldSequence = client.preview.mock.lastCall![1].draftSequence
  fireEvent.change(source, { target: { value: source.value + '; Invalid candidate text\n' } })
  client.preview.mockRejectedValueOnce(new TemplatePreviewError(422, 'Invalid draft source', [formulaDiagnostic]))
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  expect((await screen.findByRole('alert')).textContent).toBe('Invalid draft source')
  expect(screen.getByRole('table').textContent).toBe(paper)
  expect(screen.getByTestId('template-preview-status').textContent).toContain(
    'Previous engine preview · draft #' + oldSequence,
  )
  expect(screen.getByText(formulaDiagnostic.code)).toBeTruthy()
  expect(screen.getByText(formulaDiagnostic.message)).toBeTruthy()
  expect(source.value).toContain('Invalid candidate text')
})

it('keeps the draft after a 409 conflict and forbids another preview on the stale baseline until explicit reload', async () => {
  const client = fakeClient()
  const { source } = await open(client)
  await previewCurrent()
  const changed = source.value + '; Draft kept on conflict\n'
  fireEvent.change(source, { target: { value: changed } })
  client.preview.mockRejectedValueOnce(new TemplatePreviewError(409, 'Source revision conflict', [], 'b'.repeat(64)))
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await screen.findByText('Sources changed outside this page. Your draft is retained; reload to capture a new base.')
  expect(source.value).toBe(changed)
  const button = screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement
  expect(button.disabled).toBe(true)
  fireEvent.click(button)
  expect(client.preview).toHaveBeenCalledTimes(2)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: 'Reload saved source' }))
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  expect(screen.queryByText(/Your draft is retained/)).toBeNull()
})

it('submits raw input text for candidate parsing and omits read-only source dependencies from the request', async () => {
  const client = fakeClient()
  await open(client)
  await previewCurrent()
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: fragment.handle } })
  const source = screen.getByRole('textbox', { name: 'Draft source ' + fragment.document }) as HTMLTextAreaElement
  expect(source.readOnly).toBe(true)
  const node = templatePreview(firstCase, client.preview.mock.lastCall![1]).data.structure.nodes!['requested-units']
  fireEvent.click(screen.getByRole('checkbox', { name: node.label || 'requested-units' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Example input requested-units' }), {
    target: { value: ' 1.234,56 ' },
  })
  await previewCurrent()
  const request = client.preview.mock.lastCall![1]
  expect(request.inputs).toEqual([{ node: 'requested-units', text: ' 1.234,56 ' }])
  expect(
    request.documents.every(
      (document) => templateSources().data.documents.find((item) => item.handle === document.handle)?.editable,
    ),
  ).toBe(true)
  expect(request.documents.some((document) => document.handle === fragment.handle)).toBe(false)
  expect(source.value).toBe(fragment.text)
})

it('shows unsupported fixture and package modes without reaching a file-workspace endpoint', () => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  render(<TemplateDraftPreviewPage />)
  expect(screen.getByRole('alert').textContent).toContain('Start the live Mantra workbench')
  expect(fetch).not.toHaveBeenCalled()
  cleanup()
  const marker = document.createElement('meta')
  marker.name = 'mantra-package-workspace'
  marker.content = 'on'
  document.head.append(marker)
  const client = fakeClient()
  render(<TemplateDraftPreviewPage client={client} />)
  expect(screen.getByRole('alert').textContent).toContain('Package workspaces do not provide this endpoint')
  expect(client.workspace).not.toHaveBeenCalled()
  expect(client.sources).not.toHaveBeenCalled()
})
