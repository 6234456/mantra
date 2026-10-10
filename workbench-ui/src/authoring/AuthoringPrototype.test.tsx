// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { EditorView } from '@codemirror/view'
import { AuthoringPrototype } from './AuthoringPrototype'
import recordingJson from './recording/recording.json'
import { createRecordedService } from './simulated/recordedService'
import type {
  ApplyResult,
  AuthoringRecording,
  AuthoringService,
  CommitResult,
  ExampleInputResult,
  PreviewResult,
} from './service'

// jsdom's Crypto lacks subtle; use Node's actual WebCrypto without adding Node type dependencies.
const cryptoModule = 'node:crypto'
const { webcrypto } = await import(cryptoModule)
const recording: AuthoringRecording = recordingJson

beforeEach(() => {
  vi.stubGlobal('crypto', webcrypto)
  // jsdom has no layout geometry; CodeMirror's delayed source measurement still needs Range methods.
  const createRange = document.createRange.bind(document)
  vi.spyOn(document, 'createRange').mockImplementation(() => {
    const range = createRange()
    range.getClientRects = () => Object.assign([], { item: () => null })
    range.getBoundingClientRect = () => new DOMRect()
    return range
  })
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  })
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function previewRoot() {
  const element = document.querySelector<HTMLElement>('[data-preview-kind]')
  if (!element) throw new Error('The authoring preview state is not rendered')
  return element
}

it('keeps backup JSON undo and save shortcuts separate from the template history', async () => {
  const { service } = await open()
  await renameLabel()
  const before = previewRoot().getAttribute('data-draft-sequence')
  const commit = vi.spyOn(service, 'commit')
  document.querySelector<HTMLDetailsElement>('.author-controls')!.open = true
  document.querySelector<HTMLDetailsElement>('[data-author-region="backup"]')!.open = true
  const field = screen.getByRole('textbox', { name: 'Backup JSON' })
  fireEvent.change(field, { target: { value: '{"untrusted":"draft"}' } })
  fireEvent.keyDown(field, { key: 'z', ctrlKey: true })
  fireEvent.keyDown(field, { key: 's', ctrlKey: true })
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe(before)
  expect(screen.getByRole('gridcell', { name: 'Description: Unallocated request' })).toBeTruthy()
  expect(commit).not.toHaveBeenCalled()
})

async function open(service: AuthoringService = createRecordedService(recording, { delays: [0] })) {
  const mounted = render(<AuthoringPrototype service={service} />)
  const fork = await screen.findByRole('button', { name: 'Create editable copy…' })
  await waitFor(() => expect((fork as HTMLButtonElement).disabled).toBe(false))
  fireEvent.click(fork)
  fireEvent.click(await screen.findByRole('button', { name: 'Confirm editable copy' }))
  await screen.findByRole('grid', { name: 'Allocation conservation' })
  await waitFor(() => expect(screen.getByTestId('preview-status').textContent).toContain('Preview: current'))
  return { ...mounted, service }
}

function selectLabel(text: string) {
  const cell = screen.getByRole('gridcell', { name: `Description: ${text}` })
  fireEvent.click(cell)
  return cell.closest('tr') as HTMLTableRowElement
}

async function renameLabel() {
  selectLabel('Request not allocated')
  fireEvent.change(screen.getByRole('textbox', { name: 'Property text' }), {
    target: { value: 'Unallocated request' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Apply property' }))
  await screen.findByRole('gridcell', { name: 'Description: Unallocated request' })
}

function codeEditor(label: string) {
  const element = screen.getByLabelText(label).closest<HTMLElement>('.cm-editor')
  const editor = element && EditorView.findFromDOM(element)
  if (!editor) throw new Error(`CodeMirror ${label} is not mounted`)
  return editor
}

function replaceCode(label: string, text: string) {
  act(() => {
    const editor = codeEditor(label)
    editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: text } })
  })
}

function chooseResultClass() {
  fireEvent.click(screen.getByRole('button', { name: 'Remove class subtotal' }))
  fireEvent.click(screen.getByRole('button', { name: 'Add class' }))
  fireEvent.click(screen.getByRole('button', { name: 'Add class result' }))
}

it('edits labels and classes through semantic properties and displays the recorded Paper', async () => {
  await open()
  await renameLabel()
  expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid')
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1')
  fireEvent.click(screen.getByRole('tab', { name: 'Source changes' }))
  expect(screen.getByText(/\+.*Unallocated request/)).toBeTruthy()
  const before = selectLabel('Capacity not consumed').textContent
  fireEvent.click(screen.getByRole('tab', { name: 'Style' }))
  chooseResultClass()
  fireEvent.click(screen.getByRole('button', { name: 'Apply classes' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2'))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid'))
  const after = screen.getByRole('gridcell', { name: 'Description: Capacity not consumed' }).closest('tr')!
  expect(after.textContent).toBe(before)
  expect(after.querySelectorAll('.tone-accent').length).toBeGreaterThan(0)
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false)
})

it('retains the previous valid Paper for an invalid formula and allows saving a business finding', async () => {
  await open()
  selectLabel('Request not allocated')
  const previous = screen
    .getByRole('gridcell', { name: 'Description: Request not allocated' })
    .closest('tr')!.textContent
  replaceCode('Mantra DSL formula', '(- request allocated-totl)')
  fireEvent.click(screen.getByRole('button', { name: 'Apply formula' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('invalid'))
  expect(screen.getByTestId('preview-status').textContent).toBe('Previous valid preview (draft #0)')
  const staleRow = screen.getByRole('gridcell', { name: 'Description: Request not allocated' }).closest('tr')!
  expect(staleRow.textContent?.replaceAll('!', '')).toBe(previous)
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('tab', { name: /^Problems/ }))
  expect(screen.getByText('Source range: allocated-totl')).toBeTruthy()
  expect(screen.getAllByText('MANTRA-FORMULA')).toHaveLength(2)

  replaceCode('Mantra DSL formula', '(- request total-capacity)')
  fireEvent.click(screen.getByRole('button', { name: 'Apply formula' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid'))
  const findingRow = screen.getByRole('gridcell', { name: 'Description: Request not allocated' }).closest('tr')!
  expect(within(findingRow).getByText('(60.00)')).toBeTruthy()
  fireEvent.click(screen.getByRole('tab', { name: /^Problems/ }))
  expect(screen.getByText('Finding ✗')).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  expect((await screen.findAllByText('Saved in this prototype session — no file was written')).length).toBeGreaterThan(
    0,
  )
})

it('discards a late recorded preview after a newer undo and keeps the current grid unchanged', async () => {
  const service = createRecordedService(recording, { delays: [0] })
  const original = service.preview.bind(service)
  let release: (() => void) | undefined
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let first: PreviewResult | undefined
  await open(service)
  const preview = vi.spyOn(service, 'preview').mockImplementationOnce(async (request) => {
    const result = await original(request)
    first = result
    await gate
    return result
  })
  selectLabel('Request not allocated')
  fireEvent.change(screen.getByRole('textbox', { name: 'Property text' }), { target: { value: 'Unallocated request' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply property' }))
  await waitFor(() => expect(first?.stateId).toBe('label'))
  fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2'))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid'))
  await act(async () => {
    release?.()
    await gate
  })
  await waitFor(() =>
    expect(screen.getByTestId('preview-status').parentElement?.textContent).toMatch(/ignored|discarded/i),
  )
  expect(screen.getByRole('gridcell', { name: 'Description: Request not allocated' })).toBeTruthy()
  expect(screen.queryByRole('gridcell', { name: 'Description: Unallocated request' })).toBeNull()
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2')
  expect(preview).toHaveBeenCalledTimes(2)
})

it('blocks saving immediately when an idle source edit has not become a validated source transaction', async () => {
  await open()
  await renameLabel()
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Source' }))
  fireEvent.click(screen.getByRole('tab', { name: 'schema' }))
  const editor = codeEditor('Source schema')
  act(() => {
    editor.dispatch({ changes: { from: 0, insert: '; Pending source edit\n' } })
  })
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1')
})

it('keeps a later semantic class edit after committing a raw source edit in Split view', async () => {
  await open()
  selectLabel('Request not allocated')
  fireEvent.click(screen.getByRole('button', { name: 'Split' }))
  fireEvent.click(screen.getByRole('tab', { name: 'schema' }))
  const label = recording.edits.label
  const original = codeEditor('Source schema').state.doc.toString()
  replaceCode('Source schema', original.replace(label.find, label.replace))
  await screen.findByRole('gridcell', { name: 'Description: Unallocated request' })
  await waitFor(() => expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false))

  selectLabel('Capacity not consumed')
  fireEvent.click(screen.getByRole('tab', { name: 'Style' }))
  chooseResultClass()
  fireEvent.click(screen.getByRole('button', { name: 'Apply classes' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2'))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid'))
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 650))
  })

  const source = codeEditor('Source schema').state.doc.toString()
  expect(source).toContain(label.replace)
  expect(source).toContain(recording.edits.class.replace)
  expect(source).not.toContain(recording.edits.class.find)
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2')
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false)
  const row = screen.getByRole('gridcell', { name: 'Description: Capacity not consumed' }).closest('tr')!
  expect(row.querySelectorAll('.tone-accent').length).toBeGreaterThan(0)
})

it('ignores an old example-input candidate after a newer template edit has a current Paper', async () => {
  const service = createRecordedService(recording, { delays: [0] })
  const original = service.previewExampleInput.bind(service)
  let release: (() => void) | undefined
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let candidate: ExampleInputResult | undefined
  const example = vi.spyOn(service, 'previewExampleInput').mockImplementationOnce(async (text) => {
    const result = await original(text)
    candidate = result
    await gate
    return result
  })
  await open(service)
  fireEvent.click(screen.getByRole('tab', { name: 'Example input' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Example input' }), { target: { value: '9' } })
  fireEvent.click(screen.getByRole('button', { name: 'Preview example input' }))
  await waitFor(() => expect(candidate?.kind).toBe('valid'))
  const outstanding = example.mock.results[0].value as Promise<ExampleInputResult>
  fireEvent.click(screen.getByRole('tab', { name: 'Property' }))
  await renameLabel()
  const status = screen.getByTestId('preview-status').textContent
  await act(async () => {
    release?.()
    await outstanding
  })

  expect(screen.getByRole('gridcell', { name: 'Description: Unallocated request' })).toBeTruthy()
  expect(screen.queryByRole('gridcell', { name: 'Description: Request not allocated' })).toBeNull()
  expect(screen.queryByRole('gridcell', { name: /90\.00/ })).toBeNull()
  expect(screen.getByTestId('preview-status').textContent).toBe(status)
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1')
  expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid')
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false)
  expect(example).toHaveBeenCalledOnce()
  expect(example).toHaveBeenCalledWith('9')
})

it('rejects a semantic change while the same document has a pending source edit without losing that buffer', async () => {
  const { service } = await open()
  const apply = vi.spyOn(service, 'apply')
  selectLabel('Capacity not consumed')
  fireEvent.click(screen.getByRole('button', { name: 'Split' }))
  fireEvent.click(screen.getByRole('tab', { name: 'schema' }))
  const label = recording.edits.label
  const original = codeEditor('Source schema').state.doc.toString()
  const buffered = original.replace(label.find, label.replace)
  replaceCode('Source schema', buffered)
  fireEvent.click(screen.getByRole('tab', { name: 'Style' }))
  chooseResultClass()
  fireEvent.click(screen.getByRole('button', { name: 'Apply classes' }))

  expect(apply).not.toHaveBeenCalled()
  expect(screen.getByText('Finish the current source edit before changing a property in that document.')).toBeTruthy()
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('0')
  expect(codeEditor('Source schema').state.doc.toString()).toBe(buffered)
  await screen.findByRole('gridcell', { name: 'Description: Unallocated request' })
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1')
  fireEvent.click(screen.getByRole('button', { name: 'Apply classes' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2'))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid'))
  expect(codeEditor('Source schema').state.doc.toString()).toBe(
    buffered.replace(recording.edits.class.find, recording.edits.class.replace),
  )
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false)
})

it('uses the same finite layout validation for Enter and Apply and preserves empty or invalid property input', async () => {
  const { service } = await open()
  const apply = vi.spyOn(service, 'apply')
  fireEvent.click(screen.getByRole('button', { name: 'Split' }))
  fireEvent.click(screen.getByRole('button', { name: 'precision · whole Paper' }))
  await screen.findByLabelText('Source layout')
  const initial = codeEditor('Source layout').state.doc.toString()
  const submit = (text: string, enter = false) => {
    const field = screen.getByRole('textbox', { name: 'Property text' })
    fireEvent.change(field, { target: { value: text } })
    if (enter) fireEvent.keyDown(field, { key: 'Enter', ctrlKey: true })
    else fireEvent.click(screen.getByRole('button', { name: 'Apply property' }))
    return field as HTMLInputElement
  }
  for (const [text, enter] of [
    ['', true],
    ['three', false],
  ] as const) {
    expect(submit(text, enter).value).toBe(text)
    expect(screen.getByText('Layout option value has the wrong type')).toBeTruthy()
    expect(previewRoot().getAttribute('data-draft-sequence')).toBe('0')
    expect(codeEditor('Source layout').state.doc.toString()).toBe(initial)
  }
  submit('2')
  expect(apply.mock.lastCall?.[0]).toMatchObject({ op: 'setLayoutOption', value: 2 })
  expect(screen.queryByText('Layout option value has the wrong type')).toBeNull()
  submit('3', true)
  expect(apply.mock.lastCall?.[0]).toMatchObject({ op: 'setLayoutOption', value: 3 })
  await waitFor(() => expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1'))
  await waitFor(() => expect(screen.getByTestId('preview-status').textContent).toMatch(/No engine preview/))
  expect(codeEditor('Source layout').state.doc.toString()).toContain(':precision 3')
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)

  fireEvent.click(screen.getByRole('button', { name: 'hide-zero · whole Paper' }))
  const precisionOnly = codeEditor('Source layout').state.doc.toString()
  for (const [text, enter] of [
    ['', false],
    ['yes', true],
  ] as const) {
    expect(submit(text, enter).value).toBe(text)
    expect(screen.getByText('Layout option value has the wrong type')).toBeTruthy()
    expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1')
    expect(codeEditor('Source layout').state.doc.toString()).toBe(precisionOnly)
  }
  submit('true', true)
  expect(apply.mock.lastCall?.[0]).toMatchObject({ op: 'setLayoutOption', value: true })
  expect(codeEditor('Source layout').state.doc.toString()).toContain(':hide-zero true')
  submit('false')
  expect(apply.mock.lastCall?.[0]).toMatchObject({ op: 'setLayoutOption', value: false })
  expect(codeEditor('Source layout').state.doc.toString()).toContain(':hide-zero false')
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
})

it('keeps an external source conflict when an older successful save acknowledgment arrives late', async () => {
  const service = createRecordedService(recording, { delays: [0] })
  const originalCommit = service.commit.bind(service)
  const originalExternal = service.simulateExternalChange.bind(service)
  let releaseCommit: (() => void) | undefined
  let releaseExternal: (() => void) | undefined
  const commitGate = new Promise<void>((resolve) => {
    releaseCommit = resolve
  })
  const externalGate = new Promise<void>((resolve) => {
    releaseExternal = resolve
  })
  let acknowledgment: CommitResult | undefined
  const commit = vi.spyOn(service, 'commit').mockImplementationOnce(async (request) => {
    const result = await originalCommit(request)
    acknowledgment = result
    await commitGate
    return result
  })
  const external = vi.spyOn(service, 'simulateExternalChange').mockImplementationOnce(async () => {
    await externalGate
    return originalExternal()
  })
  await open(service)
  await renameLabel()
  // Start the external request before Saving disables its control; deliver its event while the ACK is pending.
  fireEvent.click(screen.getByRole('button', { name: 'Simulate external edit to layout.mantra' }))
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  await waitFor(() => expect(acknowledgment?.ok).toBe(true))
  expect(screen.getByText('Saving…')).toBeTruthy()
  await act(async () => {
    releaseExternal?.()
    await external.mock.results[0].value
  })
  expect(screen.getByRole('button', { name: 'Review conflict' })).toBeTruthy()
  await act(async () => {
    releaseCommit?.()
    await commit.mock.results[0].value
  })
  expect(screen.getByRole('button', { name: 'Review conflict' })).toBeTruthy()
  expect(screen.queryByText('Saved in this prototype session — no file was written')).toBeNull()
  expect(screen.getByRole('gridcell', { name: 'Description: Unallocated request' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  const dialog = screen.getByRole('dialog', { name: 'Source revision conflict (simulated)' })
  expect(within(dialog).getByText(/Capacity, allocation and residue/)).toBeTruthy()
  expect(commit).toHaveBeenCalledOnce()
})

it('commits a recorded label with Tab and moves to the next editable value owner, reversing with Shift+Tab', async () => {
  await open()
  fireEvent.click(screen.getByRole('button', { name: 'Request not allocated ƒ · explains zero' }))
  const field = screen.getByRole('textbox', { name: 'Property text' })
  fireEvent.change(field, { target: { value: 'Unallocated request' } })
  fireEvent.keyDown(field, { key: 'Tab', keyCode: 9 })
  const renamed = await screen.findByRole('gridcell', { name: 'Description: Unallocated request' })
  const amount = renamed.closest('tr')!.querySelectorAll<HTMLTableCellElement>('[role="gridcell"]')[1]
  await waitFor(() => expect(document.activeElement).toBe(amount))
  expect(amount.getAttribute('aria-readonly')).toBe('false')
  expect(amount.getAttribute('aria-selected')).toBe('true')
  const inspector = screen.getByRole('complementary', { name: 'Inspector' })
  expect(within(inspector).getByText(/\(info unallocated …\) › formula$/)).toBeTruthy()
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1')

  fireEvent.click(screen.getByRole('button', { name: 'Unallocated request ƒ · explains zero' }))
  const reverse = screen.getByRole('textbox', { name: 'Property text' })
  fireEvent.change(reverse, { target: { value: 'Request not allocated' } })
  fireEvent.keyDown(reverse, { key: 'Tab', keyCode: 9, shiftKey: true })
  const restored = await screen.findByRole('gridcell', { name: 'Description: Request not allocated' })
  const previous = restored
    .closest('tr')!
    .previousElementSibling!.querySelectorAll<HTMLTableCellElement>('[role="gridcell"]')[1]
  await waitFor(() => expect(document.activeElement).toBe(previous))
  expect(previous.getAttribute('aria-readonly')).toBe('false')
  expect(within(inspector).getByText(/\(info allocated-total …\) › formula$/)).toBeTruthy()
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2')
})

it('inserts a multiline note without committing until Ctrl+Enter and restores the exact source with Undo', async () => {
  const { service } = await open()
  const documents = Object.fromEntries(Object.entries(recording.base).map(([path, source]) => [path, source.text]))
  const noteOwner = service
    .owners(documents)
    .find((owner) => owner.property === 'note' && owner.panelId === recording.panel)!
  const originalNote = String(noteOwner.value)
  const source = documents[noteOwner.document]
  selectLabel(originalNote)
  fireEvent.click(screen.getByRole('button', { name: 'Split' }))
  const apply = vi.spyOn(service, 'apply')
  const editor = codeEditor('Property text')
  act(() => editor.dispatch({ selection: { anchor: editor.state.doc.length } }))
  fireEvent.keyDown(editor.contentDOM, { key: 'Enter', keyCode: 13 })
  expect(editor.state.doc.toString()).toBe(`${originalNote}\n`)
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('0')
  expect(apply).not.toHaveBeenCalled()
  expect(codeEditor('Source schema').state.doc.toString()).toBe(source)
  const suffix = 'Second line with "quote" and \\ path'
  act(() => editor.dispatch({ changes: { from: editor.state.doc.length, insert: suffix } }))
  fireEvent.keyDown(editor.contentDOM, { key: 'Enter', keyCode: 13, ctrlKey: true })
  await waitFor(() => expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1'))
  await waitFor(() => expect(screen.getByTestId('preview-status').textContent).toMatch(/No engine preview/))
  expect(apply).toHaveBeenCalledOnce()
  expect(apply.mock.lastCall?.[0]).toMatchObject({ op: 'setText', value: `${originalNote}\n${suffix}` })
  const result = apply.mock.results[0].value as ApplyResult
  if (!result.ok) throw new Error(result.reason)
  const patch = result.patches[0]
  expect(JSON.parse(patch.text)).toBe(`${originalNote}\n${suffix}`)
  expect(patch.text).toContain('\\n')
  expect(patch.text).toContain('\\"quote\\"')
  expect(patch.inverse).toBe(source.slice(patch.start, patch.end))
  expect(codeEditor('Source schema').state.doc.toString()).toContain(patch.text)
  expect(screen.getByRole('gridcell', { name: `Description: ${originalNote}` })).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('valid'))
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('2')
  expect(codeEditor('Source schema').state.doc.toString()).toBe(source)
  expect(codeEditor('Property text').state.doc.toString()).toBe(originalNote)
})

it('shows the recorded export report while build, publication and Template Engine actions stay unavailable', async () => {
  await open()
  expect(screen.getByText('Prototype · recorded engine data')).toBeTruthy()
  fireEvent.click(screen.getByRole('tab', { name: 'Build' }))
  expect(screen.getByRole('heading', { name: 'ExcelExport report for the recorded base state' })).toBeTruthy()
  for (const name of ['Build', 'Publish', 'Open in Template Engine']) {
    const button = screen.getByRole('button', { name }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
  }
  expect(screen.queryByText(/Successfully (built|published|exported)|Publication complete|Export complete/i)).toBeNull()
  expect(screen.getByText('Template Engine import and recalculation: not verified.')).toBeTruthy()
})

it('keeps an unrecorded label edit and explains beside the field why no engine preview is available', async () => {
  const { service } = await open()
  const preview = vi.spyOn(service, 'preview')
  selectLabel('Request not allocated')
  const field = screen.getByRole('textbox', { name: 'Property text' }) as HTMLInputElement
  act(() => field.focus())
  fireEvent.change(field, { target: { value: 'Outstanding request' } })
  fireEvent.keyDown(field, { key: 'Enter', keyCode: 13 })
  await screen.findByText('Edit retained · no engine preview is recorded for this draft.')
  await waitFor(() =>
    expect(document.activeElement).toBe(screen.getByRole('gridcell', { name: 'Description: Capacity not consumed' })),
  )
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('1')
  expect(screen.getByTestId('preview-status').textContent).toMatch(/No engine preview/)
  expect(screen.getByRole('gridcell', { name: 'Description: Request not allocated' })).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
  expect(preview.mock.lastCall?.[0].documents[recording.edits.label.document]).toContain(
    '(info unallocated "Outstanding request"',
  )
})

it('counts the real engine runtime diagnostics in Problems and Errors while describing the current Paper', async () => {
  const { service } = await open()
  const preview = vi.spyOn(service, 'preview')
  selectLabel('Request not allocated')
  replaceCode('Mantra DSL formula', '(/ request 0)')
  fireEvent.click(screen.getByRole('button', { name: 'Apply formula' }))
  await waitFor(() => expect(previewRoot().getAttribute('data-preview-kind')).toBe('runtimeFailure'))
  const result = await (preview.mock.results.at(-1)!.value as Promise<PreviewResult>)
  expect(result.diagnostics.map(({ category, severity, code }) => ({ category, severity, code }))).toEqual([
    { category: 'evaluation', severity: 'error', code: 'MANTRA-EVALUATION' },
    { category: 'evaluation', severity: 'error', code: 'MANTRA-EVALUATION' },
  ])
  expect(screen.getByText('Runtime failures: 2')).toBeTruthy()
  expect(screen.getByText('Errors: 2')).toBeTruthy()
  fireEvent.click(screen.getByRole('tab', { name: 'Problems (2)' }))
  expect(screen.getAllByText('MANTRA-EVALUATION')).toHaveLength(2)
  expect(
    screen.getAllByLabelText('Definition has a runtime failure; displayed values come from the current preview.')
      .length,
  ).toBeGreaterThan(0)
  expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false)
})

it('changes the Inspector channel for a read-only engine definition and the separate example-input tab', async () => {
  const { service } = await open()
  const documents = Object.fromEntries(Object.entries(recording.base).map(([path, source]) => [path, source.text]))
  const readOnly = service
    .owners(documents)
    .find((owner) => owner.nodeId === 'conserved' && owner.property === 'left-operand')!
  expect(readOnly.editable).toBe(false)
  const label = screen.getByRole('gridcell', { name: 'Description: Allocated plus unallocated equals the request' })
  fireEvent.click(label.closest('tr')!.querySelectorAll<HTMLElement>('[role="gridcell"]')[1])
  expect(screen.getByTestId('authoring-channel').textContent).toBe(`Read-only · ${readOnly.reason}`)
  fireEvent.click(screen.getByRole('tab', { name: 'Example input' }))
  expect(screen.getByTestId('authoring-channel').textContent).toBe('Example input')
  expect((screen.getByRole('textbox', { name: 'Example input' }) as HTMLInputElement).disabled).toBe(false)
  fireEvent.click(screen.getByRole('tab', { name: 'Property' }))
  selectLabel('Request not allocated')
  expect(screen.getByTestId('authoring-channel').textContent).toBe('ƒ Template definition · simulated owner')
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('0')
})

it('keeps the current drawer open when Escape is pressed in a single selected grid cell', async () => {
  await open()
  const selected = screen.getByRole('gridcell', { name: 'Description: Request not allocated' })
  fireEvent.click(selected)
  fireEvent.click(screen.getByRole('tab', { name: 'Build' }))
  act(() => selected.focus())
  expect(fireEvent.keyDown(selected, { key: 'Escape', keyCode: 27 })).toBe(true)
  expect(screen.getByRole('tab', { name: 'Build' }).getAttribute('aria-selected')).toBe('true')
  expect(screen.getByRole('heading', { name: 'ExcelExport report for the recorded base state' })).toBeTruthy()
  expect(document.activeElement).toBe(selected)
})

it('synchronously transfers a synthetic IME start to the Property input and leaves composition unsubmitted', async () => {
  const { service } = await open()
  const apply = vi.spyOn(service, 'apply')
  const selected = screen.getByRole('gridcell', { name: 'Description: Request not allocated' })
  fireEvent.click(selected)
  act(() => selected.focus())
  expect(fireEvent.keyDown(selected, { key: 'Process', keyCode: 229 })).toBe(true)
  const field = screen.getByRole('textbox', { name: 'Property text' }) as HTMLInputElement
  expect(document.activeElement).toBe(field)
  expect(field.value).toBe('Request not allocated')
  fireEvent.compositionStart(field)
  fireEvent.change(field, { target: { value: '未分配' } })
  expect(fireEvent.keyDown(field, { key: 'Enter', keyCode: 229, isComposing: true })).toBe(true)
  expect(field.value).toBe('未分配')
  expect(apply).not.toHaveBeenCalled()
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('0')
  expect(screen.getByText('Finish the IME composition before saving')).toBeTruthy()
  fireEvent.compositionEnd(field, { data: '未分配' })
})

it('synchronously transfers a synthetic IME start in a formula cell to its CodeMirror editor', async () => {
  const { service } = await open()
  const apply = vi.spyOn(service, 'apply')
  const label = screen.getByRole('gridcell', { name: 'Description: Request not allocated' })
  const amount = label.closest('tr')!.querySelectorAll<HTMLElement>('[role="gridcell"]')[1]
  fireEvent.click(amount)
  act(() => amount.focus())
  expect(fireEvent.keyDown(amount, { key: 'Process', keyCode: 229 })).toBe(true)
  const editor = codeEditor('Mantra DSL formula')
  expect(document.activeElement).toBe(editor.contentDOM)
  expect(editor.state.doc.toString()).toBe('(- request allocated-total)')
  expect(apply).not.toHaveBeenCalled()
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('0')
})

it('uses dismissible Outline and Inspector overlays with Grid or Source views on a narrow viewport', async () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.startsWith('(max-width:'),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
  await open()
  expect(screen.queryByRole('navigation', { name: 'Outline' })).toBeNull()
  expect(screen.queryByRole('complementary', { name: 'Inspector' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Split' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Open outline' }))
  const outline = screen.getByRole('dialog', { name: 'Outline' })
  expect(within(outline).getByRole('navigation', { name: 'Outline' })).toBeTruthy()
  fireEvent.click(within(outline).getByRole('button', { name: 'Request not allocated ƒ · explains zero' }))
  expect(screen.queryByRole('dialog', { name: 'Outline' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Open inspector' }))
  const inspector = screen.getByRole('dialog', { name: 'Inspector' })
  expect((within(inspector).getByRole('textbox', { name: 'Property text' }) as HTMLInputElement).value).toBe(
    'Request not allocated',
  )
  fireEvent.click(within(inspector).getByRole('button', { name: 'Close Inspector' }))
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Inspector' })).toBeNull())
  await waitFor(() =>
    expect(document.activeElement).toBe(screen.getByRole('gridcell', { name: 'Description: Request not allocated' })),
  )
  fireEvent.click(screen.getByRole('button', { name: 'Source' }))
  await screen.findByLabelText('Source schema')
  expect(screen.queryByRole('grid')).toBeNull()
})

it('moves the one formula editor and its pending buffer into the narrow Inspector and returns to its cell on Escape', async () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.startsWith('(max-width:'),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
  const { service } = await open()
  const apply = vi.spyOn(service, 'apply')
  const label = screen.getByRole('gridcell', { name: 'Description: Request not allocated' })
  const amount = label.closest('tr')!.querySelectorAll<HTMLElement>('[role="gridcell"]')[1]
  fireEvent.click(amount)
  const original = codeEditor('Mantra DSL formula').state.doc.toString()
  const pending = `${original}\n; Pending formula edit`
  replaceCode('Mantra DSL formula', pending)
  act(() => amount.focus())
  fireEvent.keyDown(amount, { key: 'F2', keyCode: 113 })
  const dialog = screen.getByRole('dialog', { name: 'Inspector' })
  expect(screen.getAllByRole('textbox', { name: 'Mantra DSL formula' })).toHaveLength(1)
  const field = within(dialog).getByRole('textbox', { name: 'Mantra DSL formula' })
  const editor = codeEditor('Mantra DSL formula')
  expect(field).toBe(editor.contentDOM)
  expect(editor.state.doc.toString()).toBe(pending)
  expect(dialog.contains(document.activeElement)).toBe(true)
  act(() => {
    editor.dispatch({ selection: { anchor: editor.state.doc.length } })
    editor.focus()
  })
  fireEvent.keyDown(editor.contentDOM, { key: 'Enter', keyCode: 13 })
  expect(editor.state.doc.toString()).toBe(`${pending}\n`)
  expect(apply).not.toHaveBeenCalled()
  expect(previewRoot().getAttribute('data-draft-sequence')).toBe('0')
  fireEvent.keyDown(editor.contentDOM, { key: 'Escape', keyCode: 27 })
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Inspector' })).toBeNull())
  await waitFor(() => expect(document.activeElement).toBe(amount))
  expect(screen.getAllByRole('textbox', { name: 'Mantra DSL formula' })).toHaveLength(1)
  expect(codeEditor('Mantra DSL formula').state.doc.toString()).toBe(original)
  expect(apply).not.toHaveBeenCalled()
})
