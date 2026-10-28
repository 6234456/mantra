// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import TemplateDraftPreviewPage from './TemplateDraftPreviewPage'
import { TemplatePreviewError, type TemplatePreviewClient } from './client'
import { firstCase, templatePreview, templateSources } from './fixtures.test.helpers'
import { createBackup, emptyRecoveryPending, parseBackup, type RecoveryDraft, type RecoveryPending } from './recovery'
import { recoveryStorageKey } from './recoveryStorage'

const snapshot = templateSources()
const source = snapshot.data.documents.find((entry) => entry.role === 'schema')!
const slot = recoveryStorageKey(snapshot.data.workspaceId!, firstCase)

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function client() {
  return {
    workspace: vi.fn(async () => ({
      workspaceId: snapshot.data.workspaceId,
      cases: [{ id: firstCase, title: 'Example' }],
    })),
    sources: vi.fn(async () => templateSources()),
    preview: vi.fn<TemplatePreviewClient['preview']>(async (id, request) => templatePreview(id, request)),
  }
}

function backup({
  text = source.text + '\n; recovered source',
  inputs = {},
  pending = emptyRecoveryPending(),
}: {
  text?: string
  inputs?: Record<string, string>
  pending?: RecoveryPending
} = {}) {
  const made = createBackup(
    snapshot,
    {
      documents: {
        ...Object.fromEntries(snapshot.data.documents.map((entry) => [entry.handle, entry.text])),
        [source.handle]: text,
      },
      inputs,
      pending,
    },
    '2026-10-25T03:00:00.000Z',
  )
  if (made.status !== 'ready') throw new Error(made.reason)
  return made
}

async function open(host = client()) {
  render(<TemplateDraftPreviewPage client={host} />)
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: source.handle } })
  return {
    host,
    editor: screen.getByRole('textbox', { name: 'Draft source ' + source.document }) as HTMLTextAreaElement,
  }
}

async function saved() {
  await waitFor(() =>
    expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('saved'),
  )
  const raw = localStorage.getItem(slot)!
  const parsed = parseBackup(raw)
  if (parsed.status !== 'ready') throw new Error(parsed.reason)
  return { raw, draft: parsed.draft }
}

function importJson(raw: string) {
  fireEvent.change(screen.getByRole('textbox', { name: 'Draft backup JSON' }), { target: { value: raw } })
  fireEvent.click(screen.getByRole('button', { name: 'Validate backup JSON' }))
}

it('backs up edited source, marks newer changes waiting and synchronously flushes the last edit on pagehide', async () => {
  const { editor } = await open()
  const first = source.text + '\n; first accepted text'
  fireEvent.change(editor, { target: { value: first } })
  expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('waiting')
  expect((await saved()).draft.documents.find((entry) => entry.handle === source.handle)?.text).toBe(first)
  const newest = first + '\n; last committed 中文😀'
  fireEvent.change(editor, { target: { value: newest } })
  expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('waiting')
  fireEvent(window, new Event('pagehide'))
  const parsed = parseBackup(localStorage.getItem(slot)!)
  expect(parsed.status).toBe('ready')
  if (parsed.status === 'ready')
    expect(parsed.draft.documents.find((entry) => entry.handle === source.handle)?.text).toBe(newest)
})

it('offers an existing browser copy without applying or overwriting it while the current page is edited', async () => {
  const stored = backup()
  localStorage.setItem(slot, stored.json)
  const { editor, host } = await open()
  expect(editor.value).toBe(source.text)
  expect(host.preview).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'Restore browser draft' })).toBeDefined()
  fireEvent.change(editor, { target: { value: source.text + '\n; a separate new draft' } })
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 240))
  })
  expect(localStorage.getItem(slot)).toBe(stored.json)
  expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('paused')
})

it('restores raw invalid input before Structure exists and only a matching successful candidate clears the marker', async () => {
  localStorage.setItem(slot, backup({ inputs: { 'requested-units': 'nine', unknown: '' } }).json)
  const { editor, host } = await open()
  fireEvent.click(screen.getByRole('button', { name: 'Restore browser draft' }))
  expect(editor.value).toContain('; recovered source')
  expect(screen.queryByRole('table')).toBeNull()
  expect(screen.getByTestId('template-restored-status').textContent).toContain('not validated')
  const raw = screen.getByRole('textbox', { name: 'Example input requested-units' }) as HTMLInputElement
  expect(raw.value).toBe('nine')
  expect((screen.getByRole('textbox', { name: 'Example input unknown' }) as HTMLInputElement).value).toBe('')
  host.preview.mockRejectedValueOnce(new TemplatePreviewError(422, 'Input text is invalid'))
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await screen.findByText('Input text is invalid')
  expect(screen.getByTestId('template-restored-status')).toBeDefined()
  fireEvent.change(raw, { target: { value: '9007199254740993.00000000001' } })
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await waitFor(() => expect(screen.queryByTestId('template-restored-status')).toBeNull())
  expect(host.preview.mock.lastCall![1].inputs).toContainEqual({
    node: 'requested-units',
    text: '9007199254740993.00000000001',
  })
})

it('restores unapplied fields visibly without restoring inspection, and Cancel remains possible before Inspect', async () => {
  const pending: RecoveryPending = {
    layoutSettings: [{ op: 'set', property: 'precision', text: '-' }],
    styleClass: {
      operation: { op: 'upsert', name: '', style: { weight: 'bold', tone: null, fill: null } },
      creating: true,
      selection: '',
    },
  }
  localStorage.setItem(slot, backup({ pending }).json)
  await open()
  fireEvent.click(screen.getByRole('button', { name: 'Restore browser draft' }))
  expect(screen.getByRole('button', { name: 'Inspect layout settings' })).toBeDefined()
  expect(screen.getByRole('button', { name: 'Inspect style classes' })).toBeDefined()
  expect(screen.getByLabelText('Uninspected restored layout changes').textContent).toContain('-')
  expect(screen.getByLabelText('Uninspected restored style change').textContent).toContain('bold')
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel setting changes' }))
  expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Cancel style changes' }))
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
})

it('keeps mismatched and corrupt stored bytes downloadable while refusing to restore or replace them', async () => {
  const altered: RecoveryDraft = { ...backup().draft, baseRevision: 'a'.repeat(64) }
  const raw = JSON.stringify(altered)
  localStorage.setItem(slot, raw)
  const { editor } = await open()
  expect((screen.getByRole('button', { name: 'Restore browser draft' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Download browser draft' }) as HTMLButtonElement).disabled).toBe(false)
  fireEvent.change(editor, { target: { value: source.text + '\n; current kept' } })
  fireEvent(window, new Event('pagehide'))
  expect(localStorage.getItem(slot)).toBe(raw)
  expect(editor.value).toContain('current kept')
})

it('does not apply imported JSON during validation and invalidates that validation when the text changes', async () => {
  const { editor } = await open()
  importJson(backup().json)
  expect(editor.value).toBe(source.text)
  expect((screen.getByRole('button', { name: 'Restore imported draft' }) as HTMLButtonElement).disabled).toBe(false)
  fireEvent.change(screen.getByRole('textbox', { name: 'Draft backup JSON' }), { target: { value: '{ invalid B' } })
  expect(screen.queryByRole('button', { name: 'Restore imported draft' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Validate backup JSON' }))
  expect((screen.getByRole('button', { name: 'Restore imported draft' }) as HTMLButtonElement).disabled).toBe(true)
  expect(editor.value).toBe(source.text)
})

it('allows an explicit matching foreign-workspace import without overwriting an undecided browser copy', async () => {
  const old = backup({ text: source.text + '\n; old browser copy' })
  localStorage.setItem(slot, old.json)
  const { editor } = await open()
  const imported = { ...backup({ text: source.text + '\n; portable source' }).draft, workspaceId: 'f'.repeat(64) }
  importJson(JSON.stringify(imported))
  expect(screen.getByTestId('template-recovery-import-match').textContent).toContain('another workspace')
  fireEvent.click(screen.getByRole('button', { name: 'Restore imported draft' }))
  expect(editor.value).toContain('portable source')
  expect(screen.getByRole('button', { name: 'Restore browser draft' })).toBeDefined()
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 240))
  })
  expect(localStorage.getItem(slot)).toBe(old.json)
  fireEvent.click(screen.getByRole('button', { name: 'Discard browser draft' }))
  const protectedCopy = await saved()
  expect(protectedCopy.draft.workspaceId).toBe(snapshot.data.workspaceId)
  expect(protectedCopy.draft.documents.find((entry) => entry.handle === source.handle)?.text).toContain(
    'portable source',
  )
})

it('requires confirmation before Reload discards an existing copy even when the page source is clean', async () => {
  const old = backup()
  localStorage.setItem(slot, old.json)
  const { host } = await open()
  vi.mocked(window.confirm).mockReturnValueOnce(false)
  fireEvent.click(screen.getByRole('button', { name: 'Reload saved source' }))
  expect(window.confirm).toHaveBeenCalled()
  expect(host.sources).toHaveBeenCalledTimes(1)
  expect(localStorage.getItem(slot)).toBe(old.json)
  fireEvent.click(screen.getByRole('button', { name: 'Reload saved source' }))
  await waitFor(() => expect(host.sources).toHaveBeenCalledTimes(2))
  expect(localStorage.getItem(slot)).toBeNull()
})

it('pauses automatic backup when another page replaces the expected copy and keeps the latest draft editable', async () => {
  const { editor } = await open()
  fireEvent.change(editor, { target: { value: source.text + '\n; first page' } })
  await saved()
  const external = backup({ text: source.text + '\n; different page' }).json
  localStorage.setItem(slot, external)
  fireEvent.change(editor, { target: { value: source.text + '\n; newest local text' } })
  await waitFor(() =>
    expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('paused'),
  )
  expect(localStorage.getItem(slot)).toBe(external)
  expect(editor.value).toContain('newest local text')
  expect((screen.getByRole('button', { name: 'Download draft backup' }) as HTMLButtonElement).disabled).toBe(false)
})

it('reports quota failure without claiming protection and keeps direct backup download available', async () => {
  const storage = {
    getItem: vi.fn(() => null),
    removeItem: vi.fn(),
    setItem: vi.fn(() => {
      throw new DOMException('full', 'QuotaExceededError')
    }),
  }
  const host = client()
  render(<TemplateDraftPreviewPage client={host} recoveryStorage={storage} />)
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: source.handle } })
  const editor = await screen.findByRole('textbox', { name: 'Draft source ' + source.document })
  fireEvent.change(editor, { target: { value: source.text + '\n; not yet protected' } })
  await waitFor(() =>
    expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('error'),
  )
  expect(screen.getByTestId('template-recovery-status').textContent).toContain('storage is full')
  expect((screen.getByRole('button', { name: 'Download draft backup' }) as HTMLButtonElement).disabled).toBe(false)
})

it('blocks restore after a known graph conflict while retaining the browser bytes', async () => {
  const old = backup()
  localStorage.setItem(slot, old.json)
  const { host, editor } = await open()
  host.preview.mockRejectedValueOnce(new TemplatePreviewError(409, 'External graph changed'))
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await screen.findByText('External graph changed')
  expect((screen.getByRole('button', { name: 'Restore browser draft' }) as HTMLButtonElement).disabled).toBe(true)
  expect(editor.value).toBe(source.text)
  expect(localStorage.getItem(slot)).toBe(old.json)
})

it('invalidates an in-flight candidate during restore and never restores trusted Paper from the backup', async () => {
  const old = backup()
  localStorage.setItem(slot, old.json)
  const host = client()
  let complete!: (value: ReturnType<typeof templatePreview>) => void
  host.preview.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        complete = resolve
      }),
  )
  const { editor } = await open(host)
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  const request = host.preview.mock.calls[0][1]
  fireEvent.click(screen.getByRole('button', { name: 'Restore browser draft' }))
  await act(async () => {
    complete(templatePreview(firstCase, request))
    await Promise.resolve()
  })
  expect(editor.value).toContain('recovered source')
  expect(screen.queryByRole('table')).toBeNull()
  expect(screen.getByTestId('template-restored-status')).toBeDefined()
  expect((screen.getByRole('button', { name: 'Undo source' }) as HTMLButtonElement).disabled).toBe(true)
})

it('rejects invalid UTF-8 file bytes without changing the source or making a replacement-character backup', async () => {
  const { editor } = await open()
  const file = new File(['invalid bytes'], 'invalid.json', { type: 'application/json' })
  Object.defineProperty(file, 'arrayBuffer', { value: async () => new Uint8Array([0xff, 0xfe]).buffer })
  fireEvent.change(screen.getByLabelText('Import draft backup file'), { target: { files: [file] } })
  expect((await screen.findByRole('alert')).textContent).toBe(
    'The backup file could not be read as valid UTF-8. The current draft was not changed.',
  )
  expect(editor.value).toBe(source.text)
  expect((screen.getByRole('button', { name: 'Restore imported draft' }) as HTMLButtonElement).disabled).toBe(true)
})

it('disables old import validation during file reading and ignores the file after a later explicit JSON choice', async () => {
  const { editor } = await open()
  importJson(backup({ text: source.text + '\n; initially inspected' }).json)
  let complete!: (bytes: ArrayBuffer) => void
  const bytes = new Promise<ArrayBuffer>((resolve) => {
    complete = resolve
  })
  const file = new File(['pending'], 'later.json', { type: 'application/json' })
  Object.defineProperty(file, 'arrayBuffer', { value: () => bytes })
  fireEvent.change(screen.getByLabelText('Import draft backup file'), { target: { files: [file] } })
  expect((screen.getByRole('button', { name: 'Validate backup JSON' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.queryByRole('button', { name: 'Restore imported draft' })).toBeNull()
  const selected = backup({ text: source.text + '\n; latest explicit choice' }).json
  importJson(selected)
  await act(async () => {
    complete(new TextEncoder().encode(backup({ text: source.text + '\n; stale file' }).json).buffer)
    await bytes
  })
  expect((screen.getByRole('textbox', { name: 'Draft backup JSON' }) as HTMLTextAreaElement).value).toBe(selected)
  fireEvent.click(screen.getByRole('button', { name: 'Restore imported draft' }))
  expect(editor.value).toContain('latest explicit choice')
  expect(editor.value).not.toContain('stale file')
})

it('downloads the complete current source directly even when browser storage cannot write', async () => {
  let downloaded: Blob | MediaSource | undefined
  class DownloadURL extends URL {
    static createObjectURL = vi.fn((blob: Blob | MediaSource) => {
      downloaded = blob
      return 'blob:recovery-download'
    })
    static revokeObjectURL = vi.fn()
  }
  vi.stubGlobal('URL', DownloadURL)
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  const storage = {
    getItem: () => null,
    removeItem: () => undefined,
    setItem: () => {
      throw new DOMException('full', 'QuotaExceededError')
    },
  }
  render(<TemplateDraftPreviewPage client={client()} recoveryStorage={storage} />)
  await screen.findByRole('button', { name: 'Download draft backup' })
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.change(screen.getByLabelText('Participating document'), { target: { value: source.handle } })
  const exact = source.text + '\n; current downloadable 中文😀'
  fireEvent.change(screen.getByRole('textbox', { name: 'Draft source ' + source.document }), {
    target: { value: exact },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Download draft backup' }))
  expect(downloaded).toBeInstanceOf(Blob)
  const raw = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = reject
    reader.readAsText(downloaded as Blob)
  })
  const parsed = parseBackup(raw)
  expect(parsed.status).toBe('ready')
  if (parsed.status === 'ready')
    expect(parsed.draft.documents.find((entry) => entry.handle === source.handle)?.text).toBe(exact)
  expect(JSON.parse(raw)).not.toHaveProperty('paper')
  // The download revokes its blob URL on the next task. Finish that lifecycle before
  // afterEach restores URL; otherwise the callback can escape into a later test.
  await waitFor(() => expect(DownloadURL.revokeObjectURL).toHaveBeenCalledWith('blob:recovery-download'))
  expect(DownloadURL.revokeObjectURL).toHaveBeenCalledOnce()
})

it('backs up an oversized invalid paste without imposing the preview source limit or truncating it', async () => {
  const { editor, host } = await open()
  const oversized = 'x'.repeat(70_000)
  fireEvent.change(editor, { target: { value: oversized } })
  expect((await saved()).draft.documents.find((entry) => entry.handle === source.handle)?.text).toBe(oversized)
  host.preview.mockRejectedValueOnce(new TemplatePreviewError(413, 'Preview source budget exceeded'))
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await screen.findByText('Preview source budget exceeded')
  expect(editor.value).toBe(oversized)
  expect((screen.getByRole('button', { name: 'Download draft backup' }) as HTMLButtonElement).disabled).toBe(false)
})

it('keeps the latest snapshot workspace identity if a later reload cannot capture sources', async () => {
  const original = backup()
  localStorage.setItem(slot, original.json)
  const host = client()
  host.sources.mockResolvedValueOnce({ ...snapshot, data: { ...snapshot.data, workspaceId: 'b'.repeat(64) } })
  host.sources.mockRejectedValueOnce(new Error('Source capture failed'))
  await open(host)
  expect(screen.queryByRole('button', { name: 'Restore browser draft' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Reload saved source' }))
  await screen.findByText('Source capture failed')
  expect(screen.queryByRole('button', { name: 'Restore browser draft' })).toBeNull()
  expect(localStorage.getItem(slot)).toBe(original.json)
})

it('removes only its own confirmed browser copy when Undo returns the edited source to clean', async () => {
  const { editor } = await open()
  fireEvent.change(editor, { target: { value: source.text + '\n; temporary source change' } })
  await saved()
  fireEvent.click(screen.getByRole('button', { name: 'Undo source' }))
  await waitFor(() => expect(localStorage.getItem(slot)).toBeNull())
  expect(editor.value).toBe(source.text)
  expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('idle')
})

it('stops pending automatic replacement when a storage event reports a changed copy', async () => {
  const { editor } = await open()
  fireEvent.change(editor, { target: { value: source.text + '\n; first page saved' } })
  const previous = await saved()
  const external = backup({ text: source.text + '\n; other page source' }).json
  localStorage.setItem(slot, external)
  fireEvent(window, new StorageEvent('storage', { key: slot, oldValue: previous.raw, newValue: external }))
  expect(screen.getByTestId('template-recovery-status').getAttribute('data-recovery-state')).toBe('paused')
  fireEvent.change(editor, { target: { value: source.text + '\n; newer current source' } })
  fireEvent(window, new Event('pagehide'))
  expect(localStorage.getItem(slot)).toBe(external)
  expect(editor.value).toContain('newer current source')
})

it('disables browser storage safely when a supplied workspace identity is malformed', async () => {
  const host = client()
  host.workspace.mockResolvedValueOnce({ workspaceId: 'invalid', cases: [{ id: firstCase, title: 'Example' }] })
  host.sources.mockResolvedValueOnce({ ...snapshot, data: { ...snapshot.data, workspaceId: 'invalid' } })
  await open(host)
  expect(screen.getByTestId('template-recovery-status').textContent).toContain('workspace identity')
  expect((screen.getByRole('button', { name: 'Download draft backup' }) as HTMLButtonElement).disabled).toBe(true)
  expect(localStorage.length).toBe(0)
})

it('keeps the stored JSON available for download when the known workspace cannot capture a valid baseline', async () => {
  const old = backup()
  localStorage.setItem(slot, old.json)
  const host = client()
  host.sources.mockRejectedValueOnce(new Error('The saved baseline cannot be read'))
  render(<TemplateDraftPreviewPage client={host} />)
  await screen.findByText('The saved baseline cannot be read')
  expect((screen.getByRole('button', { name: 'Restore browser draft' }) as HTMLButtonElement).disabled).toBe(true)
  expect((screen.getByRole('button', { name: 'Download browser draft' }) as HTMLButtonElement).disabled).toBe(false)
  expect(localStorage.getItem(slot)).toBe(old.json)
})

it('checks the import file byte budget before reading its contents', async () => {
  const { editor } = await open()
  const file = new File([], 'too-large.json', { type: 'application/json' })
  const read = vi.fn(async () => new ArrayBuffer(0))
  Object.defineProperty(file, 'size', { value: 2 * 1024 * 1024 + 1 })
  Object.defineProperty(file, 'arrayBuffer', { value: read })
  fireEvent.change(screen.getByLabelText('Import draft backup file'), { target: { files: [file] } })
  expect(read).not.toHaveBeenCalled()
  expect(screen.getByRole('alert').textContent).toContain('exceeds the 2 MiB limit')
  expect(editor.value).toBe(source.text)
})

it('keeps ordinary preview working on a legacy host without a recovery context and never claims browser protection', async () => {
  const legacySnapshot = structuredClone(snapshot)
  Reflect.deleteProperty(legacySnapshot.data, 'workspaceId')
  const storage = { getItem: vi.fn(() => null), setItem: vi.fn(), removeItem: vi.fn() }
  const host: TemplatePreviewClient = {
    workspace: vi.fn(async () => ({ cases: [{ id: firstCase, title: 'Legacy example' }] })),
    sources: vi.fn(async () => legacySnapshot),
    preview: vi.fn(async (id, request) => templatePreview(id, request)),
  }
  render(<TemplateDraftPreviewPage client={host} recoveryStorage={storage} />)
  await waitFor(() =>
    expect((screen.getByRole('button', { name: 'Preview draft' }) as HTMLButtonElement).disabled).toBe(false),
  )
  fireEvent.click(screen.getByRole('button', { name: 'Preview draft' }))
  await waitFor(() =>
    expect(screen.getByTestId('template-preview-status').textContent).toMatch(/^Current engine preview/),
  )
  expect(screen.getByTestId('template-recovery-status').textContent).toContain('Browser recovery is unavailable')
  expect((screen.getByRole('button', { name: 'Download draft backup' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.queryByRole('button', { name: 'Restore browser draft' })).toBeNull()
  importJson(backup().json)
  expect((screen.getByRole('button', { name: 'Restore imported draft' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent(window, new Event('pagehide'))
  expect(storage.getItem).not.toHaveBeenCalled()
  expect(storage.setItem).not.toHaveBeenCalled()
  expect(storage.removeItem).not.toHaveBeenCalled()
})
