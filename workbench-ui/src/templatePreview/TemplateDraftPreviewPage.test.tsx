// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Envelope } from '../types'
import TemplateDraftPreviewPage from './TemplateDraftPreviewPage'
import { TemplatePreviewError } from './client'
import type { TemplatePreview, TemplatePreviewClient, TemplateSources } from './client'
import { firstCase, secondCase, formulaDiagnostic, templatePreview, templateSources } from './fixtures.test.helpers'
import type { LayoutSettings, LayoutSettingsClient, LayoutSettingsRequest } from './layoutSettingsClient'
import type { StyleClasses, StyleClassesClient, StyleClassesRequest } from './styleClassesClient'
import * as sourceHistory from './sourceHistory'

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  cleanup()
  localStorage.clear()
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  document.querySelector('meta[name="mantra-package-workspace"]')?.remove()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete
    reject = fail
  })
  return { promise, resolve, reject }
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

async function savedPreviewCurrent() {
  await waitFor(() =>
    expect(screen.getByTestId('template-baseline-status').textContent).toBe(
      'Saved-template preview · captured source and saved example inputs',
    ),
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

it('takes panel choices from the full engine Structure and keeps them after a filtered preview', async () => {
  const { client, source } = await open()
  expect((screen.getByLabelText('Preview panel') as HTMLSelectElement).disabled).toBe(true)
  await previewCurrent()
  const panels = templatePreview(firstCase, client.preview.mock.lastCall![1]).data.structure.panels
  expect(panels.length).toBeGreaterThan(1)
  const chosen = panels[0]
  const allChoices = panels.map((panel) => panel.id)
  const edited = source.value + '; Still the same draft\n'
  fireEvent.change(source, { target: { value: edited } })
  fireEvent.change(screen.getByLabelText('Preview panel'), { target: { value: chosen.id } })
  client.preview.mockImplementationOnce(async (caseId, request) => {
    const next = templatePreview(caseId, request)
    return { ...next, data: { ...next.data, paper: { ...next.data.paper, tables: [] } } }
  })
  await previewCurrent()
  expect(client.preview.mock.lastCall![1]).toMatchObject({ panel: chosen.id, includeZero: false })
  expect(source.value).toBe(edited)
  expect(
    within(screen.getByLabelText('Preview panel'))
      .getAllByRole('option')
      .map((option) => (option as HTMLOptionElement).value),
  ).toEqual(['', ...allChoices])
  expect(within(screen.getByRole('region', { name: 'Draft preview' })).queryByRole('table')).toBeNull()
})

it('clears the old Explain selection and cancels its pending preview when the panel changes', async () => {
  const { client } = await open()
  await previewCurrent()
  fireEvent.click(document.querySelector<HTMLButtonElement>('.cell-button')!)
  await previewCurrent()
  expect(screen.getByRole('heading', { name: 'Draft Explain' })).toBeTruthy()
  const pending = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(pending.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const request = client.preview.mock.lastCall![1]
  const signal = client.preview.mock.lastCall![2]
  const panel = templatePreview(firstCase, request).data.structure.panels[0]
  fireEvent.change(screen.getByLabelText('Preview panel'), { target: { value: panel.id } })
  expect(signal?.aborted).toBe(true)
  expect(screen.queryByRole('heading', { name: /Draft Explain/ })).toBeNull()
  await previewCurrent()
  expect(client.preview.mock.lastCall![1].explain).toBeUndefined()
  expect(client.preview.mock.lastCall![1].panel).toBe(panel.id)
  await act(async () => {
    pending.resolve(templatePreview(firstCase, request))
    await pending.promise
  })
  expect(screen.queryByRole('heading', { name: /Draft Explain/ })).toBeNull()
})

it('previews the captured saved template independently without source edits, input overrides or Explain', async () => {
  const { client, source } = await open()
  await previewCurrent()
  const node = templatePreview(firstCase, client.preview.mock.lastCall![1]).data.structure.nodes!['requested-units']
  const changed = source.value + '; Draft template\n'
  fireEvent.change(source, { target: { value: changed } })
  fireEvent.click(screen.getByRole('checkbox', { name: node.label }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Example input requested-units' }), {
    target: { value: '9' },
  })
  fireEvent.click(
    within(screen.getByRole('group', { name: 'Preview settings' })).getByRole('checkbox', { name: 'Show zero rows' }),
  )
  const panel = templatePreview(firstCase, client.preview.mock.lastCall![1]).data.structure.panels[0]
  fireEvent.change(screen.getByLabelText('Preview panel'), { target: { value: panel.id } })
  await previewCurrent()
  const candidateRequest = client.preview.mock.lastCall![1]
  const status = screen.getByTestId('template-preview-status').textContent
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  await savedPreviewCurrent()
  const baselineRequest = client.preview.mock.lastCall![1]
  expect(baselineRequest).toMatchObject({
    baseRevision: templateSources().revision,
    baseRevisions: templateSources().data.baseRevisions,
    inputs: [],
    panel: panel.id,
    includeZero: true,
  })
  expect(baselineRequest.documents).toEqual(
    templateSources()
      .data.documents.filter((item) => item.editable)
      .map(({ handle, text }) => ({ handle, text })),
  )
  expect(baselineRequest.explain).toBeUndefined()
  expect(candidateRequest.documents.find((item) => item.handle === schema.handle)?.text).toBe(changed)
  expect(candidateRequest.inputs).toEqual([{ node: 'requested-units', text: '9' }])
  expect(source.value).toBe(changed)
  expect((screen.getByRole('textbox', { name: 'Example input requested-units' }) as HTMLInputElement).value).toBe('9')
  expect(screen.getByTestId('template-preview-status').textContent).toBe(status)
})

it('keeps a saved-template request alive while edits cancel only the candidate request', async () => {
  const { client, source } = await open()
  await previewCurrent()
  const saved = deferred<Envelope<TemplatePreview>>()
  const candidate = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(saved.promise)
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  const savedRequest = client.preview.mock.lastCall![1]
  const savedSignal = client.preview.mock.lastCall![2]
  client.preview.mockReturnValueOnce(candidate.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const candidateRequest = client.preview.mock.lastCall![1]
  const candidateSignal = client.preview.mock.lastCall![2]
  fireEvent.change(source, { target: { value: source.value + '; Newer draft\n' } })
  expect(candidateSignal?.aborted).toBe(true)
  expect(savedSignal?.aborted).toBe(false)
  await act(async () => {
    saved.resolve(templatePreview(firstCase, savedRequest))
    candidate.resolve(templatePreview(firstCase, candidateRequest))
    await Promise.all([saved.promise, candidate.promise])
  })
  await savedPreviewCurrent()
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
})

it('replaces saved-template context for panel and zero-row changes and ignores a late baseline', async () => {
  const { client } = await open()
  await previewCurrent()
  const old = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(old.promise)
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  const oldRequest = client.preview.mock.lastCall![1]
  const oldSignal = client.preview.mock.lastCall![2]
  const panel = templatePreview(firstCase, oldRequest).data.structure.panels[0]
  fireEvent.change(screen.getByLabelText('Preview panel'), { target: { value: panel.id } })
  await savedPreviewCurrent()
  expect(oldSignal?.aborted).toBe(true)
  expect(client.preview.mock.lastCall![1].panel).toBe(panel.id)
  const latest = client.preview.mock.calls.length
  await act(async () => {
    const stale = templatePreview(firstCase, oldRequest)
    old.resolve({ ...stale, data: { ...stale.data, paper: { ...stale.data.paper, title: 'Stale saved result' } } })
    await old.promise
  })
  expect(screen.queryByText('Stale saved result')).toBeNull()
  fireEvent.click(
    within(screen.getByRole('group', { name: 'Preview settings' })).getByRole('checkbox', { name: 'Show zero rows' }),
  )
  await waitFor(() => expect(client.preview.mock.calls.length).toBe(latest + 1))
  expect(client.preview.mock.lastCall![1]).toMatchObject({ panel: panel.id, includeZero: true, inputs: [] })
})

it('aborts a saved-template request when comparison is closed and cannot revive it after a case switch', async () => {
  const { client } = await open()
  const old = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(old.promise)
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  const oldRequest = client.preview.mock.lastCall![1]
  const signal = client.preview.mock.lastCall![2]
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  expect(signal?.aborted).toBe(true)
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  await waitFor(() => expect(client.sources).toHaveBeenCalledTimes(2))
  await act(async () => {
    old.resolve(templatePreview(firstCase, oldRequest))
    await old.promise
  })
  expect(screen.queryByRole('region', { name: 'Saved-template comparison' })).toBeNull()
  expect(screen.getByTestId('template-preview-status').textContent).toContain('Preview a draft')
})

it('captures the new case before comparing it and ignores the old case baseline while comparison stays enabled', async () => {
  const { client } = await open()
  const old = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(old.promise)
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  const oldRequest = client.preview.mock.lastCall![1]
  const signal = client.preview.mock.lastCall![2]
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  await savedPreviewCurrent()
  expect(signal?.aborted).toBe(true)
  expect(client.preview.mock.calls.map(([caseId]) => caseId)).toEqual([firstCase, secondCase])
  await act(async () => {
    const stale = templatePreview(firstCase, oldRequest)
    old.resolve({ ...stale, data: { ...stale.data, paper: { ...stale.data.paper, title: 'Old case saved result' } } })
    await old.promise
  })
  expect(screen.queryByText('Old case saved result')).toBeNull()
  expect((screen.getByLabelText('Example case') as HTMLSelectElement).value).toBe(secondCase)
  expect(screen.getByTestId('template-preview-status').textContent).toContain('Preview a draft')
})

it('does not reintroduce saved panel choices when the current candidate has no panels', async () => {
  const { client } = await open()
  const saved = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(saved.promise)
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  const request = client.preview.mock.lastCall![1]
  client.preview.mockImplementationOnce(async (caseId, candidate) => {
    const next = templatePreview(caseId, candidate)
    return { ...next, data: { ...next.data, structure: { ...next.data.structure, panels: [] } } }
  })
  await previewCurrent()
  await act(async () => {
    saved.resolve(templatePreview(firstCase, request))
    await saved.promise
  })
  await savedPreviewCurrent()
  expect(within(screen.getByLabelText('Preview panel')).getAllByRole('option')).toHaveLength(1)
  expect((screen.getByLabelText('Preview panel') as HTMLSelectElement).disabled).toBe(true)
})

it('keeps baseline lookup failures separate from a valid candidate and retries the captured source', async () => {
  const { client, source } = await open()
  const changed = source.value + '; New panel in draft only\n'
  fireEvent.change(source, { target: { value: changed } })
  await previewCurrent()
  const status = screen.getByTestId('template-preview-status').textContent
  client.preview.mockRejectedValueOnce(new TemplatePreviewError(404, 'Panel is not in the saved template'))
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  await screen.findByText('Panel is not in the saved template')
  expect(within(screen.getByRole('region', { name: 'Saved template' })).queryByRole('table')).toBeNull()
  expect(screen.getByTestId('template-preview-status').textContent).toBe(status)
  expect(source.value).toBe(changed)
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Retry saved-template preview' }))
  await savedPreviewCurrent()
  expect(screen.queryByText('Panel is not in the saved template')).toBeNull()
  expect(client.preview.mock.lastCall![1].documents.find((item) => item.handle === schema.handle)?.text).toBe(
    schema.text,
  )
})

it('turns a saved-template revision conflict into a graph conflict without discarding the draft', async () => {
  const { client, source } = await open()
  const changed = source.value + '; Retain this draft\n'
  fireEvent.change(source, { target: { value: changed } })
  await previewCurrent()
  const candidate = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(candidate.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const candidateRequest = client.preview.mock.lastCall![1]
  const signal = client.preview.mock.lastCall![2]
  client.preview.mockRejectedValueOnce(new TemplatePreviewError(409, 'Saved source changed externally'))
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  await screen.findByText('Sources changed outside this page. Your draft is retained; reload to capture a new base.')
  expect(signal?.aborted).toBe(true)
  expect(screen.getByTestId('template-baseline-status').textContent).not.toContain('Calculating')
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
  await act(async () => {
    candidate.resolve(templatePreview(firstCase, candidateRequest))
    await candidate.promise
  })
  expect(source.value).toBe(changed)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
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

async function openLayoutSettings(styleClassesClient?: StyleClassesClient) {
  const client = fakeClient()
  const layout = templateSources().data.documents.find((item) => item.role === 'layout')!
  const settingsClient = {
    settings: vi.fn<LayoutSettingsClient['settings']>(async (_case, request) => {
      const suffix = request.operations.length ? '\n; layout settings applied' : ''
      return {
        contract: 'mantra.workbench/4',
        revision: request.baseRevision,
        engine: templateSources().engine,
        data: {
          document: firstCase,
          draftSequence: request.draftSequence,
          baseRevisions: request.baseRevisions,
          scope: 'layout',
          layoutId: 'example/layout',
          preset: 'de-staffel-4',
          source: { handle: layout.handle, document: layout.document, text: request.text + suffix },
          properties: [
            {
              id: 'precision',
              label: 'Precision',
              type: 'integer',
              group: 'numbers',
              options: [],
              minimum: 0,
              maximum: 18,
              declared: true,
              explicitValue: { n: '2' },
              inheritedValue: { n: '2' },
              effectiveValue: { n: '2' },
              editable: true,
              reason: null,
              canUnset: true,
            },
          ],
          patches: suffix
            ? [
                {
                  startOffset: request.text.length,
                  endOffset: request.text.length,
                  text: suffix,
                  removedText: '',
                },
              ]
            : [],
        },
      } as Envelope<LayoutSettings>
    }),
  }
  render(
    <TemplateDraftPreviewPage
      client={client}
      settingsClient={settingsClient}
      styleClassesClient={styleClassesClient}
    />,
  )
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  await previewCurrent()
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: layout.handle } })
  fireEvent.click(screen.getByText('Advanced layout settings'))
  fireEvent.click(screen.getByRole('button', { name: 'Inspect layout settings' }))
  const precision = (await screen.findByLabelText('Layout setting precision')) as HTMLInputElement
  return { client, layout, settingsClient, precision }
}

it('treats unapplied layout fields as a guarded draft and never previews the old source as current', async () => {
  const { client, precision } = await openLayoutSettings()
  fireEvent.change(precision, { target: { value: '0' } })
  const preview = screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement
  expect(preview.disabled).toBe(true)
  expect(screen.getByText('Apply or cancel the layout setting changes before previewing.').id).toBe(
    preview.getAttribute('aria-describedby'),
  )
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
  fireEvent.click(preview)
  expect(client.preview).toHaveBeenCalledOnce()
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  expect(confirm).toHaveBeenCalledOnce()
  expect(client.sources).toHaveBeenCalledOnce()
  expect(precision.value).toBe('0')
  fireEvent.click(screen.getByRole('button', { name: 'Cancel setting changes' }))
  expect(preview.disabled).toBe(false)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Current engine preview/)
})

it('applies a settings source patch to the real draft context while keeping the saved-template baseline independent', async () => {
  const { client, layout, settingsClient, precision } = await openLayoutSettings()
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  await savedPreviewCurrent()
  const before = client.preview.mock.calls.length
  fireEvent.change(precision, { target: { value: '0' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply layout settings' }))
  await screen.findByText('Settings applied to the source draft. Preview the draft to update its results.')
  const request: LayoutSettingsRequest = settingsClient.settings.mock.lastCall![1]
  expect(request.operations).toEqual([{ op: 'set', property: 'precision', text: '0' }])
  expect(client.preview).toHaveBeenCalledTimes(before)
  expect(screen.getByTestId('template-baseline-status').textContent).toMatch(/^Saved-template preview/)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
  expect((screen.getByRole('textbox', { name: 'Draft source ' + layout.document }) as HTMLTextAreaElement).value).toBe(
    layout.text + '\n; layout settings applied',
  )
  await previewCurrent()
  expect(client.preview.mock.lastCall![1].documents.find((item) => item.handle === layout.handle)?.text).toBe(
    layout.text + '\n; layout settings applied',
  )
})

function styleResponse(request: StyleClassesRequest): Envelope<StyleClasses> {
  const layout = templateSources().data.documents.find((item) => item.role === 'layout')!
  const suffix = request.operations.length ? '\n; style class applied' : ''
  return {
    contract: 'mantra.workbench/4',
    revision: request.baseRevision,
    engine: templateSources().engine,
    data: {
      document: firstCase,
      draftSequence: request.draftSequence,
      baseRevisions: request.baseRevisions,
      scope: 'layout-style-classes',
      layoutId: 'example/layout',
      stylePresets: [],
      usageScope: 'submitted-layout',
      source: { handle: layout.handle, document: layout.document, text: request.text + suffix },
      classes: [
        {
          name: 'custom',
          editable: true,
          reason: null,
          presets: [],
          local: { weight: 'bold', tone: null, fill: null },
          inherited: { weight: null, tone: null, fill: null },
          effective: { weight: 'bold', tone: null, fill: null },
          references: [],
          canRemove: true,
          removeReason: null,
        },
      ],
      patches: suffix
        ? [{ startOffset: request.text.length, endOffset: request.text.length, text: suffix, removedText: '' }]
        : [],
    },
  }
}

function fakeStyleClient() {
  return { classes: vi.fn<StyleClassesClient['classes']>(async (_case, request) => styleResponse(request)) }
}

async function openStyleClasses() {
  fireEvent.click(screen.getByText('Style classes', { selector: 'summary' }))
  fireEvent.click(screen.getByRole('button', { name: 'Inspect style classes' }))
  await screen.findByLabelText('Style class weight')
}

it('keeps the draft guarded until both layout-setting and style-class field buffers are applied or canceled', async () => {
  const styles = fakeStyleClient()
  const { client, precision } = await openLayoutSettings(styles)
  await openStyleClasses()
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  fireEvent.change(precision, { target: { value: '4' } })
  const preview = screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement
  expect(preview.disabled).toBe(true)
  expect(screen.getByText('Apply or cancel the layout setting and style class changes before previewing.')).toBeTruthy()
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  expect(confirm).toHaveBeenCalledOnce()
  expect(client.sources).toHaveBeenCalledOnce()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel style changes' }))
  expect(preview.disabled).toBe(true)
  expect(precision.value).toBe('4')
  expect(screen.getByText('Apply or cancel the layout setting changes before previewing.')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel setting changes' }))
  expect(preview.disabled).toBe(false)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Current engine preview/)
})

it('applies a style source patch without recalculating or changing the captured saved-template baseline', async () => {
  const styles = fakeStyleClient()
  const { client, layout } = await openLayoutSettings(styles)
  await openStyleClasses()
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  await savedPreviewCurrent()
  const before = client.preview.mock.calls.length
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply style changes' }))
  await screen.findByText('Style changes applied to the source draft. Preview the draft to check its results.')
  expect(styles.classes.mock.lastCall![1].operations).toEqual([
    { op: 'upsert', name: 'custom', style: { weight: 'normal', tone: null, fill: null } },
  ])
  expect(client.preview).toHaveBeenCalledTimes(before)
  expect(screen.getByTestId('template-baseline-status').textContent).toMatch(/^Saved-template preview/)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
  expect((screen.getByRole('textbox', { name: 'Draft source ' + layout.document }) as HTMLTextAreaElement).value).toBe(
    layout.text + '\n; style class applied',
  )
  await previewCurrent()
  expect(client.preview.mock.lastCall![1].documents.find((item) => item.handle === layout.handle)?.text).toBe(
    layout.text + '\n; style class applied',
  )
})

it('requires a style refresh after another settings panel applies source changes and retains the unapplied style fields', async () => {
  const styles = fakeStyleClient()
  const { precision } = await openLayoutSettings(styles)
  await openStyleClasses()
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  fireEvent.change(precision, { target: { value: '4' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply layout settings' }))
  await screen.findByText('Settings applied to the source draft. Preview the draft to update its results.')
  expect((screen.getByRole('button', { name: 'Apply style changes' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByLabelText('Style class weight') as HTMLSelectElement).value).toBe('normal')
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Refresh style classes' }))
  await screen.findByText('Style classes refreshed. Review retained changes before applying.')
  fireEvent.click(screen.getByRole('button', { name: 'Apply style changes' }))
  await screen.findByText('Style changes applied to the source draft. Preview the draft to check its results.')
  expect(styles.classes.mock.lastCall![1].text).toContain('; layout settings applied')
  expect(styles.classes.mock.lastCall![1].operations).toEqual([
    { op: 'upsert', name: 'custom', style: { weight: 'normal', tone: null, fill: null } },
  ])
})

it('rejects a late style patch after manual source editing and keeps both source and style buffers', async () => {
  const styles = fakeStyleClient()
  const { layout } = await openLayoutSettings(styles)
  await openStyleClasses()
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  const late = deferred<Envelope<StyleClasses>>()
  styles.classes.mockReturnValueOnce(late.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Apply style changes' }))
  const request = styles.classes.mock.lastCall![1]
  const signal = styles.classes.mock.lastCall![2]
  const source = screen.getByRole('textbox', { name: 'Draft source ' + layout.document }) as HTMLTextAreaElement
  const newer = source.value + '\n; Manual edit after style request'
  fireEvent.change(source, { target: { value: newer } })
  await act(async () => {
    late.resolve(styleResponse(request))
    await late.promise
  })
  expect(signal?.aborted).toBe(true)
  expect(source.value).toBe(newer)
  expect((screen.getByLabelText('Style class weight') as HTMLSelectElement).value).toBe('normal')
  expect((screen.getByRole('button', { name: 'Apply style changes' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
})

it('accepts only one source patch when settings and style responses arrive together for the same old text', async () => {
  const styles = fakeStyleClient()
  const { layout, precision, settingsClient } = await openLayoutSettings(styles)
  await openStyleClasses()
  const inspected = await settingsClient.settings.mock.results[0].value
  const settingReply = deferred<Envelope<LayoutSettings>>()
  const styleReply = deferred<Envelope<StyleClasses>>()
  settingsClient.settings.mockReturnValueOnce(settingReply.promise)
  styles.classes.mockReturnValueOnce(styleReply.promise)
  fireEvent.change(precision, { target: { value: '4' } })
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply layout settings' }))
  fireEvent.click(screen.getByRole('button', { name: 'Apply style changes' }))
  const request = settingsClient.settings.mock.lastCall![1]
  const styleRequest = styles.classes.mock.lastCall![1]
  const suffix = '\n; layout settings applied'
  await act(async () => {
    settingReply.resolve({
      ...inspected,
      data: {
        ...inspected.data,
        draftSequence: request.draftSequence,
        source: { ...inspected.data.source, text: request.text + suffix },
        patches: [{ startOffset: request.text.length, endOffset: request.text.length, text: suffix, removedText: '' }],
      },
    })
    styleReply.resolve(styleResponse(styleRequest))
    await Promise.all([settingReply.promise, styleReply.promise])
  })
  const source = screen.getByRole('textbox', { name: 'Draft source ' + layout.document }) as HTMLTextAreaElement
  expect(source.value).toBe(request.text + suffix)
  expect(source.value).not.toContain('; style class applied')
  expect((screen.getByLabelText('Style class weight') as HTMLSelectElement).value).toBe('normal')
  expect((screen.getByRole('button', { name: 'Apply style changes' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel style changes' }))
  fireEvent.click(screen.getByRole('button', { name: 'Undo source' }))
  expect(source.value).toBe(request.text)
  expect((screen.getByRole('button', { name: 'Undo source' }) as HTMLButtonElement).disabled).toBe(true)
})

const undoSource = () => screen.getByRole('button', { name: 'Undo source' }) as HTMLButtonElement
const redoSource = () => screen.getByRole('button', { name: 'Redo source' }) as HTMLButtonElement
const sourceText = (document: string) =>
  screen.getByRole('textbox', { name: 'Draft source ' + document }) as HTMLTextAreaElement

it('uses one exact source history for manual schema edits, root settings and style class application', async () => {
  const styles = fakeStyleClient()
  const { layout, precision } = await openLayoutSettings(styles)
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: schema.handle } })
  const schemaDraft = schema.text + '\n; 中文😀 manual edit\n'
  fireEvent.change(sourceText(schema.document), { target: { value: schemaDraft } })
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: layout.handle } })
  fireEvent.change(precision, { target: { value: '4' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply layout settings' }))
  await screen.findByText('Settings applied to the source draft. Preview the draft to update its results.')
  await openStyleClasses()
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply style changes' }))
  await screen.findByText('Style changes applied to the source draft. Preview the draft to check its results.')
  const settingsDraft = layout.text + '\n; layout settings applied'
  const styledDraft = settingsDraft + '\n; style class applied'
  expect(sourceText(layout.document).value).toBe(styledDraft)
  fireEvent.click(undoSource())
  expect(sourceText(layout.document).value).toBe(settingsDraft)
  fireEvent.click(undoSource())
  expect(sourceText(layout.document).value).toBe(layout.text)
  fireEvent.click(undoSource())
  expect(sourceText(schema.document).value).toBe(schema.text)
  expect(undoSource().disabled).toBe(true)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
  fireEvent.click(redoSource())
  expect(sourceText(schema.document).value).toBe(schemaDraft)
  fireEvent.click(redoSource())
  expect(sourceText(layout.document).value).toBe(settingsDraft)
  fireEvent.click(redoSource())
  expect(sourceText(layout.document).value).toBe(styledDraft)
  expect(redoSource().disabled).toBe(true)
})

it('links blocked history actions to unapplied fields and requires explicit Apply or Cancel', async () => {
  const styles = fakeStyleClient()
  const { layout, precision } = await openLayoutSettings(styles)
  fireEvent.change(sourceText(layout.document), { target: { value: layout.text + '\n; manual edit' } })
  fireEvent.click(undoSource())
  expect(redoSource().disabled).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Refresh layout settings' }))
  await screen.findByLabelText('Layout setting precision')
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Refresh layout settings' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.change(precision, { target: { value: '4' } })
  expect(undoSource().disabled).toBe(true)
  expect(redoSource().disabled).toBe(true)
  expect(document.getElementById(redoSource().getAttribute('aria-describedby')!)?.textContent).toContain(
    'Apply or cancel',
  )
  fireEvent.click(redoSource())
  expect(sourceText(layout.document).value).toBe(layout.text)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel setting changes' }))
  expect(redoSource().disabled).toBe(false)
  await openStyleClasses()
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  expect(redoSource().disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel style changes' }))
  fireEvent.click(redoSource())
  expect(sourceText(layout.document).value).toBe(layout.text + '\n; manual edit')
})

it('undoes during a candidate request, discards its late response and retains raw inputs and the independent saved baseline', async () => {
  const { client, source } = await open()
  await previewCurrent()
  fireEvent.click(screen.getByRole('checkbox', { name: 'Compare with saved template' }))
  await savedPreviewCurrent()
  const node = templatePreview(firstCase, client.preview.mock.lastCall![1]).data.structure.nodes!['requested-units']
  fireEvent.click(screen.getByRole('checkbox', { name: node.label || 'requested-units' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Example input requested-units' }), { target: { value: ' 9 ' } })
  fireEvent.change(source, { target: { value: schema.text + '\n; in-memory source edit' } })
  const late = deferred<Envelope<TemplatePreview>>()
  client.preview.mockReturnValueOnce(late.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const request = client.preview.mock.lastCall![1]
  const signal = client.preview.mock.lastCall![2]
  const before = client.preview.mock.calls.length
  expect(undoSource().disabled).toBe(false)
  fireEvent.click(undoSource())
  expect(signal?.aborted).toBe(true)
  await act(async () => {
    late.resolve(templatePreview(firstCase, request))
    await late.promise
  })
  expect(source.value).toBe(schema.text)
  expect((screen.getByRole('textbox', { name: 'Example input requested-units' }) as HTMLInputElement).value).toBe(' 9 ')
  expect(screen.getByTestId('template-baseline-status').textContent).toMatch(/^Saved-template preview/)
  expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Previous engine preview/)
  expect(client.preview).toHaveBeenCalledTimes(before)
  await previewCurrent()
  expect(client.preview.mock.lastCall![1].inputs).toEqual([{ node: 'requested-units', text: ' 9 ' }])
})

it('clears a redo branch on new editing and clears all source history while recapturing or switching cases', async () => {
  const { client, source } = await open()
  fireEvent.change(source, { target: { value: schema.text + '\n; first edit' } })
  fireEvent.click(undoSource())
  expect(redoSource().disabled).toBe(false)
  const branch = schema.text + '\n; new branch'
  fireEvent.change(source, { target: { value: branch } })
  expect(redoSource().disabled).toBe(true)
  const recapture = deferred<Envelope<TemplateSources>>()
  client.sources.mockReturnValueOnce(recapture.promise)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: 'Reload saved source' }))
  expect(undoSource().disabled).toBe(true)
  expect(redoSource().disabled).toBe(true)
  expect(document.getElementById(undoSource().getAttribute('aria-describedby')!)?.textContent).toContain(
    'Capture template sources',
  )
  await act(async () => {
    recapture.resolve(templateSources())
    await recapture.promise
  })
  expect(undoSource().disabled).toBe(true)
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: schema.handle } })
  fireEvent.change(sourceText(schema.document), { target: { value: schema.text + '\n; next edit' } })
  expect(undoSource().disabled).toBe(false)
  fireEvent.change(screen.getByLabelText('Example case'), { target: { value: secondCase } })
  await waitFor(() => expect(client.sources).toHaveBeenCalledTimes(3))
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  expect(undoSource().disabled).toBe(true)
  expect(redoSource().disabled).toBe(true)
})

it('retains the source but disables its history after a graph conflict', async () => {
  const { client, source } = await open()
  const draft = source.value + '\n; keep on conflict'
  fireEvent.change(source, { target: { value: draft } })
  client.preview.mockRejectedValueOnce(new TemplatePreviewError(409, 'Source changed externally'))
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await screen.findByText('Source changed externally')
  expect(source.value).toBe(draft)
  expect(undoSource().disabled).toBe(true)
  expect(document.getElementById(undoSource().getAttribute('aria-describedby')!)?.textContent).toContain(
    'Resolve the source revision conflict',
  )
})

it('does not intercept native editor undo, redo or IME confirmation keys', async () => {
  const { source } = await open()
  const draft = source.value + '\n; native editor text'
  fireEvent.change(source, { target: { value: draft } })
  expect(fireEvent.keyDown(source, { key: 'z', ctrlKey: true })).toBe(true)
  expect(fireEvent.keyDown(source, { key: 'z', metaKey: true, shiftKey: true })).toBe(true)
  expect(fireEvent.keyDown(source, { key: 'y', ctrlKey: true })).toBe(true)
  fireEvent.compositionStart(source)
  expect(fireEvent.keyDown(source, { key: 'Enter', keyCode: 229, isComposing: true })).toBe(true)
  fireEvent.compositionEnd(source)
  expect(source.value).toBe(draft)
  expect(undoSource().disabled).toBe(false)
  expect(redoSource().disabled).toBe(true)
})

it('does not record an accepted source no-op as an undoable transaction', async () => {
  const { precision, settingsClient } = await openLayoutSettings()
  settingsClient.settings.mockImplementationOnce(async (_case, request) => {
    const previous = await settingsClient.settings.mock.results[0].value
    return {
      ...previous,
      data: {
        ...previous.data,
        draftSequence: request.draftSequence,
        source: { ...previous.data.source, text: request.text },
        patches: [],
      },
    }
  })
  fireEvent.change(precision, { target: { value: '4' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply layout settings' }))
  await screen.findByText('Settings applied to the source draft. Preview the draft to update its results.')
  expect(undoSource().disabled).toBe(true)
  expect(redoSource().disabled).toBe(true)
  await waitFor(() =>
    expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Current engine preview/),
  )
})

it('reserves a graph conflict before a simultaneous settings success can accept source or history', async () => {
  const record = vi.spyOn(sourceHistory, 'recordSourceChange')
  const styles = fakeStyleClient()
  const { layout, precision, settingsClient } = await openLayoutSettings(styles)
  await openStyleClasses()
  const inspected = await settingsClient.settings.mock.results[0].value
  const settingReply = deferred<Envelope<LayoutSettings>>()
  const styleReply = deferred<Envelope<StyleClasses>>()
  settingsClient.settings.mockReturnValueOnce(settingReply.promise)
  styles.classes.mockReturnValueOnce(styleReply.promise)
  fireEvent.change(precision, { target: { value: '4' } })
  fireEvent.change(screen.getByLabelText('Style class weight'), { target: { value: 'normal' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply layout settings' }))
  fireEvent.click(screen.getByRole('button', { name: 'Apply style changes' }))
  const request = settingsClient.settings.mock.lastCall![1]
  const suffix = '\n; stale success after another source conflict'
  await act(async () => {
    styleReply.reject(new TemplatePreviewError(409, 'Style baseline changed externally'))
    settingReply.resolve({
      ...inspected,
      data: {
        ...inspected.data,
        draftSequence: request.draftSequence,
        source: { ...inspected.data.source, text: request.text + suffix },
        patches: [{ startOffset: request.text.length, endOffset: request.text.length, text: suffix, removedText: '' }],
      },
    })
    await Promise.allSettled([styleReply.promise, settingReply.promise])
  })
  expect(sourceText(layout.document).value).toBe(layout.text)
  expect((screen.getByLabelText('Layout setting precision') as HTMLInputElement).value).toBe('4')
  expect((screen.getByLabelText('Style class weight') as HTMLSelectElement).value).toBe('normal')
  expect(record).not.toHaveBeenCalled()
  expect(undoSource().disabled).toBe(true)
  expect(redoSource().disabled).toBe(true)
  expect(document.getElementById(undoSource().getAttribute('aria-describedby')!)?.textContent).toContain(
    'Resolve the source revision conflict',
  )
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
})
